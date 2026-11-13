import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { createHash } from 'node:crypto'
import { z } from 'zod'
import type { TypertRemoteMethod } from '@deepseek-ai/dsh-typert-protocol'
import { TYPERT_REMOTE as session } from '@deepseek-ai/dsh-api-session-controller/remote'
import { TYPERT_REMOTE as workspace } from '@deepseek-ai/dsh-api-workspace-controller/remote'
import { TYPERT_REMOTE as uploads } from '@deepseek-ai/dsh-client-file-upload/remote'
import { TYPERT_REMOTE as questions } from '@deepseek-ai/dsh-user-questions/remote'
import { TYPERT_REMOTE as access } from '@deepseek-ai/dsh-api-remote-access/remote'
import { TYPERT_REMOTE as terminal } from '@deepseek-ai/dsh-api-terminal-controller/remote'

const require = createRequire(import.meta.url)
const version = '0.2.1-rc.5'
const packages = ['dsh-api-session-controller', 'dsh-api-workspace-controller', 'dsh-client-file-upload', 'dsh-user-questions', 'dsh-api-remote-access', 'dsh-api-gateway', 'dsh-typert-protocol', 'dsh-api-terminal-controller']
for (const name of packages) {
  const manifest = require(`@deepseek-ai/${name}/package.json`) as { version: string }
  if (manifest.version !== version) throw new Error(`${name}: expected ${version}, found ${manifest.version}`)
}
const selected = new Set('list search create follow page prompt cancel updateQueue modelCatalog selectModel fork rename attachment projections control'.split(' ').map(name => `session/${name}`))
const methods = Object.entries({ ...session.methods, ...workspace.methods, ...uploads.methods, ...questions.methods, ...access.methods, ...terminal.methods })
  .filter(([key]) => selected.has(key) || /^(workspace|fileUploads|userQuestions|remoteAccess|terminal)\//.test(key))
  .sort(([a], [b]) => a.localeCompare(b, 'en'))
for (const key of selected) if (!methods.some(([name]) => name === key)) throw new Error(`Native endpoint missing: ${key}`)

type Schema = { [key: string]: unknown; type?: string; properties?: Record<string, Schema>; required?: string[]; additionalProperties?: boolean | Schema; items?: Schema; anyOf?: Schema[]; oneOf?: Schema[]; allOf?: Schema[]; $ref?: string; $defs?: Record<string, Schema>; const?: unknown; enum?: unknown[] }
type Binding = { type: string; codec: string }
const declarations: string[] = []
const cached = new Map<string, Binding>()
const quote = (value: string) => JSON.stringify(value).replaceAll('$', '\\$')
const pascal = (name: string) => name.replace(/(^|[^a-zA-Z0-9])([a-zA-Z0-9])/g, (_, _a: string, b: string) => b.toUpperCase())
const ident = (name: string) => `\`${name}\``
function clean(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(clean)
  if (!value || typeof value !== 'object') return value
  return Object.fromEntries(Object.entries(value).filter(([key]) => !['description', '$schema', 'title'].includes(key)).sort(([a], [b]) => a.localeCompare(b, 'en')).map(([key, item]) => [key, clean(item)]))
}
function schemaOf(value: { create(): unknown }): Schema {
  return z.toJSONSchema(value.create() as z.ZodType, { unrepresentable: 'any' }) as Schema
}
function bind(schema: Schema, hint: string, root: Schema, visiting = new Set<string>()): Binding {
  if (schema.$ref) {
    if (visiting.has(schema.$ref)) return { type: 'JsonValue', codec: 'DshCodecs.json' }
    const target = schema.$ref.split('/').slice(1).reduce<unknown>((value, key) => (value as Record<string, unknown>)[key.replaceAll('~1', '/').replaceAll('~0', '~')], root)
    if (!target) throw new Error(`Unresolved schema: ${schema.$ref}`)
    const next = new Set(visiting); next.add(schema.$ref)
    // Recursive JSON unions are the native arbitrary JSON domain.
    if (JSON.stringify(target).includes(JSON.stringify(schema.$ref))) return { type: 'JsonValue', codec: 'DshCodecs.json' }
    return bind(target as Schema, hint, root, next)
  }
  if (schema.allOf) {
    const combined: Schema = { type: 'object', properties: {}, required: [] }
    for (const part of schema.allOf) {
      if (part.type !== 'object') throw new Error(`Unsupported native intersection: ${hint}`)
      Object.assign(combined.properties!, part.properties)
      combined.required!.push(...part.required ?? [])
      if (part.additionalProperties && typeof part.additionalProperties === 'object') combined.additionalProperties = part.additionalProperties
    }
    return bind(combined, hint, root, visiting)
  }
  const variants = schema.anyOf ?? schema.oneOf
  if (variants) {
    const nonNull = variants.filter(item => item.type !== 'null' && !(Object.hasOwn(item, 'const') && item.const === null))
    if (nonNull.length === 1 && nonNull.length < variants.length) {
      const inner = bind(nonNull[0]!, hint, root, visiting)
      return { type: `${inner.type}?`, codec: `DshCodecs.nullable(${inner.codec})` }
    }
    if (variants.every(item => item.type === 'string' || typeof item.const === 'string')) {
      const allowed = variants.flatMap(item => typeof item.const === 'string' ? [item.const] : item.enum ?? [])
      return { type: 'String', codec: allowed.length === variants.length ? `DshCodecs.strings(setOf(${allowed.map(item => quote(String(item))).join(', ')}))` : 'DshCodecs.string' }
    }
  }
  const signature = hint + '\u0000' + JSON.stringify(clean({ schema, defs: root.$defs }))
  const old = cached.get(signature)
  if (old) return old
  let name = hint.length > 100 ? hint.slice(0, 80) + createHash('sha256').update(hint).digest('hex').slice(0, 12) : hint
  if (variants) {
    const binding = { type: name, codec: `${name}.codec` }; cached.set(signature, binding)
    const members = variants.map((item, index) => bind(item, `${name}Case${index + 1}`, root, visiting))
    declarations.push(`sealed interface ${name} {\n${members.map((member, index) => `    data class Case${index + 1}(val value: ${member.type}) : ${name}`).join('\n')}\n    companion object {\n        val codec: DshCodec<${name}> = DshCodec(\n            { value ->\n${members.map((member, index) => `                try { return@DshCodec Case${index + 1}(${member.codec}.decode(value)) } catch (_: ProtocolException) { }`).join('\n')}\n                throw ProtocolException(\"native: unsupported union\")\n            },\n            { value -> when (value) {\n${members.map((member, index) => `                is Case${index + 1} -> ${member.codec}.encode(value.value)`).join('\n')}\n            } },\n        )\n    }\n}`)
    return binding
  }
  if (schema.type === 'array') {
    const item = bind(schema.items ?? {}, `${name}Item`, root, visiting)
    return { type: `List<${item.type}>`, codec: `DshCodecs.list(${item.codec})` }
  }
  if (schema.type === 'object') {
    const props = Object.entries(schema.properties ?? {})
    if (!props.length) {
      const value = typeof schema.additionalProperties === 'object' ? bind(schema.additionalProperties, `${name}Value`, root, visiting) : { type: 'JsonValue', codec: 'DshCodecs.json' }
      return { type: `Map<String, ${value.type}>`, codec: `DshCodecs.map(${value.codec})` }
    }
    const binding = { type: name, codec: `${name}.codec` }; cached.set(signature, binding)
    const fields = props.map(([key, value]) => ({ key, binding: bind(value, `${name}${pascal(key)}`, root, visiting), optional: !(schema.required ?? []).includes(key) }))
    const extra = schema.additionalProperties && typeof schema.additionalProperties === 'object' ? bind(schema.additionalProperties, `${name}Extra`, root, visiting) : schema.additionalProperties === true ? { type: 'JsonValue', codec: 'DshCodecs.json' } : undefined
    const names = fields.map(field => quote(field.key)).join(', ')
    declarations.push(`data class ${name}(\n${fields.map(field => `    val ${ident(field.key)}: ${field.optional ? `DshOptional<${field.binding.type}> = DshOptional.Missing` : field.binding.type},`).join('\n')}${extra ? `\n    val additional: Map<String, ${extra.type}> = emptyMap(),` : ''}\n) {\n    companion object {\n        val codec: DshCodec<${name}> = DshCodec(\n            { value ->\n                val obj = DshCodecs.obj(value)\n                ${name}(\n${fields.map(field => `                    ${ident(field.key)} = ${field.optional ? `DshCodecs.optional(obj, ${quote(field.key)}, ${field.binding.codec})` : `${field.binding.codec}.decode(obj[${quote(field.key)}])`},`).join('\n')}${extra ? `\n                    additional = obj.fields.filterKeys { it !in setOf(${names}) }.mapValues { ${extra.codec}.decode(it.value) },` : ''}\n                )\n            },\n            { value ->\n                val fields = linkedMapOf<String, JsonValue>()\n${extra ? `                value.additional.forEach { (key, item) -> fields[key] = ${extra.codec}.encode(item) }\n` : ''}${fields.map(field => field.optional ? `                DshCodecs.put(fields, ${quote(field.key)}, value.${ident(field.key)}, ${field.binding.codec})` : `                fields[${quote(field.key)}] = ${field.binding.codec}.encode(value.${ident(field.key)})`).join('\n')}\n                JsonObject(fields)\n            },\n        )\n    }\n}`)
    return binding
  }
  if (schema.type === 'string' || typeof schema.const === 'string') {
    const values = typeof schema.const === 'string' ? [schema.const] : schema.enum
    return { type: 'String', codec: values ? `DshCodecs.strings(setOf(${values.map(value => quote(String(value))).join(', ')}))` : 'DshCodecs.string' }
  }
  if (schema.type === 'number' || schema.type === 'integer' || typeof schema.const === 'number') return { type: 'Double', codec: 'DshCodecs.number' }
  if (schema.type === 'boolean' || typeof schema.const === 'boolean') return { type: 'Boolean', codec: typeof schema.const === 'boolean' ? `DshCodecs.booleanLiteral(${schema.const})` : 'DshCodecs.boolean' }
  if (schema.type === 'null' || (Object.hasOwn(schema, 'const') && schema.const === null)) return { type: 'JsonNull', codec: 'DshCodecs.nil' }
  if (schema.type) throw new Error(`Unsupported native schema ${hint}: ${schema.type}`)
  return { type: 'JsonValue', codec: 'DshCodecs.json' }
}

const snapshot: Record<string, unknown> = {}
const entries: string[] = []
for (const [endpoint, descriptor] of methods) {
  const method = descriptor as TypertRemoteMethod<object, unknown> | TypertRemoteMethod<object, unknown, 'stream', unknown>
  const input = schemaOf(method.input()), result = schemaOf(method.result)
  const name = `Dsh${pascal(endpoint)}`
  const args = bind(input, `${name}Input`, input), value = bind(result, `${name}Result`, result)
  snapshot[endpoint] = { mode: method.mode ?? 'unary', cancellation: method.cancellation ?? null, parameters: method.parameters.map(parameter => ({ name: parameter.name, wire: parameter.wire, source: parameter.source })), input, result, acceptsUndefinedResult: (method.result.create() as z.ZodType).safeParse(undefined).success }
  entries.push(`    val ${pascal(endpoint)} = DshMethod(${quote(endpoint)}, ${method.mode === 'stream'}, ${args.codec}, ${value.codec})`)
}
const prelude = `// Generated by tools/generate-typert-contract.ts; do not edit.\npackage cn.edu.ustc.tokenworks\n\nsealed interface DshOptional<out T> {\n    data object Missing : DshOptional<Nothing>\n    data class Value<T>(val value: T) : DshOptional<T>\n}\n\nclass DshCodec<T>(val decode: (JsonValue) -> T, val encode: (T) -> JsonValue)\ndata class DshMethod<I, O>(val endpoint: String, val streaming: Boolean, val input: DshCodec<I>, val output: DshCodec<O>)\n\nobject DshCodecs {\n    val json = DshCodec<JsonValue>({ it }, { it })\n    val string = DshCodec<String>({ (it as? JsonString)?.value ?: throw ProtocolException() }, { JsonString(it) })\n    val number = DshCodec<Double>({ (it as? JsonNumber)?.value?.takeIf { n -> n.isFinite() } ?: throw ProtocolException() }, { require(it.isFinite()); JsonNumber(it) })\n    val boolean = DshCodec<Boolean>({ (it as? JsonBoolean)?.value ?: throw ProtocolException() }, { JsonBoolean(it) })\n    val nil = DshCodec<JsonNull>({ if (it !== JsonNull) throw ProtocolException(); JsonNull }, { JsonNull })\n    fun obj(value: JsonValue): JsonObject = value as? JsonObject ?: throw ProtocolException()\n    fun strings(values: Set<String>) = DshCodec<String>({ string.decode(it).also { text -> if (text !in values) throw ProtocolException() } }, { if (it !in values) throw ProtocolException(); JsonString(it) })\n    fun booleanLiteral(value: Boolean) = DshCodec<Boolean>({ boolean.decode(it).also { actual -> if (actual != value) throw ProtocolException() } }, { if (it != value) throw ProtocolException(); JsonBoolean(it) })\n    fun <T> nullable(codec: DshCodec<T>) = DshCodec<T?>({ if (it === JsonNull) null else codec.decode(it) }, { if (it == null) JsonNull else codec.encode(it) })\n    fun <T> list(codec: DshCodec<T>) = DshCodec<List<T>>({ (it as? JsonArray)?.values?.map(codec.decode) ?: throw ProtocolException() }, { JsonArray(it.map(codec.encode)) })\n    fun <T> map(codec: DshCodec<T>) = DshCodec<Map<String, T>>({ obj(it).fields.mapValues { entry -> codec.decode(entry.value) } }, { JsonObject(it.mapValues { entry -> codec.encode(entry.value) }) })\n    fun <T> optional(obj: JsonObject, key: String, codec: DshCodec<T>): DshOptional<T> = if (obj.fields.containsKey(key)) DshOptional.Value(codec.decode(obj[key])) else DshOptional.Missing\n    fun <T> put(fields: MutableMap<String, JsonValue>, key: String, value: DshOptional<T>, codec: DshCodec<T>) { if (value is DshOptional.Value) fields[key] = codec.encode(value.value) }\n}\n\nobject DshMethods {\n    const val VERSION = ${quote(version)}\n${entries.join('\n')}\n}\n\n`
const outputs = new Map([
  ['contracts/typert/native-api.json', JSON.stringify({ dshVersion: version, methods: snapshot }, null, 2) + '\n'],
  ['android/core/protocol/src/main/kotlin/cn/edu/ustc/tokenworks/DshNativeTypes.kt', prelude + declarations.join('\n\n') + '\n'],
])
for (const [path, content] of outputs) {
  if (process.argv.includes('--check')) {
    if (await readFile(path, 'utf8').catch(() => '') !== content) throw new Error(`${path} is stale; run npm run contracts:typert`)
  } else {
    await mkdir(new URL('../' + path.substring(0, path.lastIndexOf('/')) + '/', import.meta.url), { recursive: true })
    await writeFile(new URL('../' + path, import.meta.url), content)
  }
}
console.log(`${process.argv.includes('--check') ? 'Checked' : 'Generated'} ${methods.length} native dsh methods (${version})`)
