import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { assertNativePackages, nativeMethods as methods, nativeVersion as version, nativeSchema as schemaOf, type NativeSchema as Schema } from './native-contract.js'
import { fileURLToPath } from 'node:url'
import { checkTypertContracts } from './typert-contract-check.js'
import { createHash } from 'node:crypto'
import { z } from 'zod'
import { buildNativeVectors } from './native-golden-vectors.js'
import { buildWireVectors } from './native-wire-vectors.js'
import { checkNativeCapture, type NativeCapture } from './capture-native-golden.js'
import { productAccessOutputs } from './product-access-contract.js'
assertNativePackages()
const captured = JSON.parse(await readFile(new URL('../contracts/typert/golden/native-captured.json', import.meta.url), 'utf8')) as NativeCapture
checkNativeCapture(captured)
const modelHash = createHash('sha256').update(await readFile(new URL('../' + captured.execution.model.fixture, import.meta.url))).digest('hex')
if (captured.execution.model.sha256 !== modelHash) throw new Error('Native execution fixture changed: recapture installed-native golden calls')

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
function bind(schema: Schema, hint: string, root: Schema, visiting = new Set<string>()): Binding {
  if (schema.not && Object.keys(schema.not).length === 0) return { type: 'JsonValue', codec: 'DshCodecs.undefined' }
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
      if (schema.additionalProperties === false) return { type: 'Map<String, JsonValue>', codec: `DshCodecs.emptyObject(${schema['x-unknownKeys'] === 'strip'})` }
      const value = typeof schema.additionalProperties === 'object' ? bind(schema.additionalProperties, `${name}Value`, root, visiting) : { type: 'JsonValue', codec: 'DshCodecs.json' }
      return { type: `Map<String, ${value.type}>`, codec: `DshCodecs.map(${value.codec})` }
    }
    const binding = { type: name, codec: `${name}.codec` }; cached.set(signature, binding)
    const fields = props.map(([key, value]) => ({ key, binding: bind(value, `${name}${pascal(key)}`, root, visiting), optional: !(schema.required ?? []).includes(key) }))
    const extra = schema.additionalProperties && typeof schema.additionalProperties === 'object' ? bind(schema.additionalProperties, `${name}Extra`, root, visiting) : schema.additionalProperties === true ? { type: 'JsonValue', codec: 'DshCodecs.json' } : undefined
    const names = fields.map(field => quote(field.key)).join(', ')
    declarations.push(`data class ${name}(
${fields.map(field => `    val ${ident(field.key)}: ${field.optional ? `DshOptional<${field.binding.type}> = DshOptional.Missing` : field.binding.type},`).join('\n')}${extra ? `\n    val additional: Map<String, ${extra.type}> = emptyMap(),` : ''}
) {
    companion object {
        val codec: DshCodec<${name}> = DshCodec(
            { value ->
                val obj = DshCodecs.obj(value)
${schema.additionalProperties === false && schema['x-unknownKeys'] !== 'strip' ? `                DshCodecs.keys(obj, setOf(${names}))\n` : ''}                ${name}(
${fields.map(field => `                    ${ident(field.key)} = ${field.optional ? `DshCodecs.optional(obj, ${quote(field.key)}, ${field.binding.codec})` : `${field.binding.codec}.decode(obj[${quote(field.key)}])`},`).join('\n')}${extra ? `\n                    additional = obj.fields.filterKeys { it !in setOf(${names}) }.mapValues { ${extra.codec}.decode(it.value) },` : ''}
                )
            },
            { value ->
                val fields = linkedMapOf<String, JsonValue>()
${extra ? `                value.additional.forEach { (key, item) -> fields[key] = ${extra.codec}.encode(item) }\n` : ''}${fields.map(field => field.optional ? `                DshCodecs.put(fields, ${quote(field.key)}, value.${ident(field.key)}, ${field.binding.codec})` : `                fields[${quote(field.key)}] = ${field.binding.codec}.encode(value.${ident(field.key)})`).join('\n')}
                JsonObject(fields)
            },
        )
    }
}`)
    return binding
  }
  if (schema.type === 'string' || typeof schema.const === 'string') {
    const values = typeof schema.const === 'string' ? [schema.const] : schema.enum
    return { type: 'String', codec: values ? `DshCodecs.strings(setOf(${values.map(value => quote(String(value))).join(', ')}))` : 'DshCodecs.string' }
  }
  if (schema.type === 'number' || schema.type === 'integer' || typeof schema.const === 'number') return { type: 'Double', codec: schema.type === 'integer' ? 'DshCodecs.integer' : 'DshCodecs.number' }
  if (schema.type === 'boolean' || typeof schema.const === 'boolean') return { type: 'Boolean', codec: typeof schema.const === 'boolean' ? `DshCodecs.booleanLiteral(${schema.const})` : 'DshCodecs.boolean' }
  if (schema.type === 'null' || (Object.hasOwn(schema, 'const') && schema.const === null)) return { type: 'JsonNull', codec: 'DshCodecs.nil' }
  if (schema.type) throw new Error(`Unsupported native schema ${hint}: ${schema.type}`)
  return { type: 'JsonValue', codec: 'DshCodecs.json' }
}

const snapshot: Record<string, unknown> = {}
const entries: string[] = []
for (const [endpoint, descriptor] of methods) {
  const method = descriptor
  const input = schemaOf(method.input()), result = schemaOf(method.result)
  const name = `Dsh${pascal(endpoint)}`
  const args = bind(input, `${name}Input`, input), value = bind(result, `${name}Result`, result)
  if (method.uplink && method.uplink.codec.mode !== 'strict') throw new Error(`No strict native uplink: ${endpoint}`)
  const uplinkSchema = method.uplink?.codec.mode === 'strict' ? schemaOf(method.uplink.codec) : undefined
  const uplink = uplinkSchema ? bind(uplinkSchema, `${name}Uplink`, uplinkSchema) : undefined
  snapshot[endpoint] = { mode: method.mode ?? 'unary', cancellation: method.cancellation ?? null, parameters: method.parameters.map(parameter => ({ name: parameter.name, wire: parameter.wire, source: parameter.source })), input, result, ...(uplinkSchema ? { uplink: uplinkSchema } : {}), acceptsUndefinedResult: (method.result.create() as z.ZodType).safeParse(undefined).success }
  entries.push(`    val ${pascal(endpoint)} = DshMethod(${quote(endpoint)}, ${method.mode === 'stream'}, ${args.codec}, ${value.codec}${uplink ? `, { value -> ${uplink.codec}.encode(${uplink.codec}.decode(value)) }` : ''})`)
}
const prelude = `// Generated by tools/generate-typert-contract.ts; do not edit.
package cn.edu.ustc.tokenworks

sealed interface DshOptional<out T> {
    data object Missing : DshOptional<Nothing>
    data class Value<T>(val value: T) : DshOptional<T>
}

class DshCodec<T>(val decode: (JsonValue) -> T, val encode: (T) -> JsonValue)
data class DshMethod<I, O>(val endpoint: String, val streaming: Boolean, val input: DshCodec<I>, val output: DshCodec<O>, val uplink: ((JsonValue) -> JsonValue)? = null)

object DshCodecs {
    val json = DshCodec<JsonValue>({ it }, { it })
    val undefined = DshCodec<JsonValue>({ throw ProtocolException() }, { throw ProtocolException() })
    val string = DshCodec<String>({ (it as? JsonString)?.value ?: throw ProtocolException() }, { JsonString(it) })
    val number = DshCodec<Double>({ (it as? JsonNumber)?.value?.takeIf { n -> n.isFinite() } ?: throw ProtocolException() }, { if (!it.isFinite()) throw ProtocolException(); JsonNumber(it) })
    val integer = DshCodec<Double>({ number.decode(it).also { n -> if (n % 1.0 != 0.0) throw ProtocolException() } }, { if (it % 1.0 != 0.0) throw ProtocolException(); number.encode(it) })
    val boolean = DshCodec<Boolean>({ (it as? JsonBoolean)?.value ?: throw ProtocolException() }, { JsonBoolean(it) })
    val nil = DshCodec<JsonNull>({ if (it !== JsonNull) throw ProtocolException(); JsonNull }, { JsonNull })
    fun obj(value: JsonValue): JsonObject = value as? JsonObject ?: throw ProtocolException()
    fun keys(value: JsonObject, allowed: Set<String>) { if (value.fields.keys.any { it !in allowed }) throw ProtocolException() }
    fun emptyObject(strip: Boolean) = DshCodec<Map<String, JsonValue>>({ if (!strip) keys(obj(it), emptySet()) else obj(it); emptyMap() }, { if (!strip && it.isNotEmpty()) throw ProtocolException(); JsonObject(emptyMap()) })
    fun strings(values: Set<String>) = DshCodec<String>({ string.decode(it).also { text -> if (text !in values) throw ProtocolException() } }, { if (it !in values) throw ProtocolException(); JsonString(it) })
    fun booleanLiteral(value: Boolean) = DshCodec<Boolean>({ boolean.decode(it).also { actual -> if (actual != value) throw ProtocolException() } }, { if (it != value) throw ProtocolException(); JsonBoolean(it) })
    fun <T> nullable(codec: DshCodec<T>) = DshCodec<T?>({ if (it === JsonNull) null else codec.decode(it) }, { if (it == null) JsonNull else codec.encode(it) })
    fun <T> list(codec: DshCodec<T>) = DshCodec<List<T>>({ (it as? JsonArray)?.values?.map(codec.decode) ?: throw ProtocolException() }, { JsonArray(it.map(codec.encode)) })
    fun <T> map(codec: DshCodec<T>) = DshCodec<Map<String, T>>({ obj(it).fields.mapValues { entry -> codec.decode(entry.value) } }, { JsonObject(it.mapValues { entry -> codec.encode(entry.value) }) })
    fun <T> optional(obj: JsonObject, key: String, codec: DshCodec<T>): DshOptional<T> = if (obj.fields.containsKey(key)) DshOptional.Value(codec.decode(obj[key])) else DshOptional.Missing
    fun <T> put(fields: MutableMap<String, JsonValue>, key: String, value: DshOptional<T>, codec: DshCodec<T>) { if (value is DshOptional.Value) fields[key] = codec.encode(value.value) }
}

object DshMethods {
    const val VERSION = ${quote(version)}
${entries.join('\n')}
}

`
const registry = `// Generated by tools/generate-typert-contract.ts; do not edit.
package cn.edu.ustc.tokenworks

class DshNativeEntry<I, O>(val method: DshMethod<I, O>, val acceptsUndefinedResult: Boolean) {
    fun roundTrip(side: String, carrier: JsonObject): JsonObject {
        val present = (carrier["present"] as? JsonBoolean)?.value ?: throw ProtocolException()
        carrier.exactKeys(*if (present) arrayOf("present", "value") else arrayOf("present"))
        if (!present) {
            if (side != "result" || !acceptsUndefinedResult) throw ProtocolException()
            return carrier
        }
        val value = when (side) {
            "input" -> method.input.encode(method.input.decode(carrier["value"]))
            "result" -> method.output.encode(method.output.decode(carrier["value"]))
            "uplink" -> method.uplink?.invoke(carrier["value"]) ?: throw ProtocolException()
            else -> throw ProtocolException()
        }
        return jsonObject("present" to JsonBoolean(true), "value" to value)
    }
}

object DshNativeRegistry {
    val methods: Map<String, DshNativeEntry<*, *>> = linkedMapOf(
${methods.map(([endpoint, method]) => `        ${quote(endpoint)} to DshNativeEntry(DshMethods.${pascal(endpoint)}, ${(method.result.create() as z.ZodType).safeParse(undefined).success}),`).join('\n')}
    )
    fun compatibility(advertisedVersion: JsonValue): String =
        if (advertisedVersion is JsonString && advertisedVersion.value == DshMethods.VERSION) "compatible" else "unsupported-version"
}
`
const outputs = new Map([
  ['contracts/typert/native-api.json', JSON.stringify({ dshVersion: version, source: 'installed-native-reflection; not recorded method execution', methods: snapshot }, null, 2) + '\n'],
  ['android/core/dsh-protocol/src/main/kotlin/cn/edu/ustc/tokenworks/DshNativeTypes.kt', prelude + declarations.join('\n\n') + '\n'],
  ['android/core/dsh-protocol/src/main/kotlin/cn/edu/ustc/tokenworks/DshNativeRegistry.kt', registry],
  ['contracts/typert/golden/compatibility.json', JSON.stringify({ policy: 'exact-native-version', dshVersion: version, endpoints: methods.map(([endpoint]) => endpoint), cases: [version, '0.2.1', '0.2.1-rc.4', '0.2.1-rc.6', '0.3.0', '', null, 1].map(advertisedVersion => ({ advertisedVersion, decision: advertisedVersion === version ? 'compatible' : 'unsupported-version' })) }, null, 2) + '\n'],
  ['contracts/typert/golden/native-vectors.jsonl', buildNativeVectors().vectors.map(vector => JSON.stringify(vector)).join('\n') + '\n'],
  ['contracts/typert/golden/wire-vectors.json', JSON.stringify(buildWireVectors(), null, 2) + '\n'],
  ...(await productAccessOutputs()).map(({ path, content }) => [path, content] as [string, string]),
])
if (process.argv.includes('--check')) {
  await checkTypertContracts([...outputs].map(([path, content]) => ({ path, content })), fileURLToPath(new URL('../', import.meta.url)))
} else {
  for (const [path, content] of outputs) {
    await mkdir(new URL('../' + path.substring(0, path.lastIndexOf('/')) + '/', import.meta.url), { recursive: true })
    await writeFile(new URL('../' + path, import.meta.url), content)
  }
}
console.log(`${process.argv.includes('--check') ? 'Checked' : 'Generated'} ${methods.length} native dsh methods (${version})`)
