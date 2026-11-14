import { z } from 'zod'
import { carrier, nativeCodec, nativeMethods, nativeSchema, nativeVersion, type Carrier, type NativeSchema, type NativeSide } from './native-contract.js'

export type NativeVector = {
  id: string; endpoint: string; side: NativeSide; source: 'schema-generated'
  value: Carrier; accepted: boolean; expected?: Carrier
}
const unique = <T>(values: T[]): T[] => [...new Map(values.map(value => [JSON.stringify(carrier(value)), value])).values()]
function resolve(schema: NativeSchema, root: NativeSchema): NativeSchema {
  if (!schema.$ref) return schema
  return schema.$ref.split('/').slice(1).reduce<unknown>((value, key) => (value as Record<string, unknown>)[key.replaceAll('~1', '/').replaceAll('~0', '~')], root) as NativeSchema
}

// One-field-at-a-time structural coverage, not a Cartesian product. Values are
// synthetic public markers; no profile, network, user account or body is read.
function samples(schema: NativeSchema, root: NativeSchema, refs = new Set<string>()): unknown[] {
  if (schema.$ref) {
    if (refs.has(schema.$ref)) return [null, {}, []]
    return samples(resolve(schema, root), root, new Set([...refs, schema.$ref]))
  }
  if (schema.not) return [undefined]
  if (Object.hasOwn(schema, 'const')) return [schema.const]
  if (schema.enum) return schema.enum
  if (schema.anyOf || schema.oneOf) return unique((schema.anyOf ?? schema.oneOf)!.flatMap(part => samples(part, root, refs)))
  if (schema.allOf) {
    const parts = schema.allOf.map(part => samples(part, root, refs))
    const baseline = Object.assign({}, ...parts.map(values => values[0]))
    return unique([baseline, ...parts.flatMap(values => values.map(value => ({ ...baseline, ...value as object })))])
  }
  switch (schema.type) {
    case 'string': return ['fixture-原样🙂']
    case 'number': case 'integer': return [1, 0, -0, 0.5, -0.5, Number.MIN_VALUE, -Number.MIN_VALUE, Number.MAX_VALUE, -Number.MAX_VALUE, Number.MAX_SAFE_INTEGER, Number.MAX_SAFE_INTEGER + 1, 1e-7, 1e21]
    case 'boolean': return [true, false]
    case 'null': return [null]
    case 'array': return [[], ...samples(schema.items ?? {}, root, refs).map(value => [value])]
    case 'object': {
      const props = Object.entries(schema.properties ?? {})
      if (!props.length) return typeof schema.additionalProperties === 'object'
        ? [{}, ...samples(schema.additionalProperties, root, refs).map(value => ({ fixture: value }))] : [{}]
      const values = new Map(props.map(([name, part]) => [name, samples(part, root, refs)]))
      const required = new Set(schema.required ?? [])
      const baseline = Object.fromEntries(props.filter(([name]) => required.has(name)).map(([name]) => [name, values.get(name)![0]]))
      return unique([baseline, ...props.flatMap(([name]) => values.get(name)!.map(value => ({ ...baseline, [name]: value })))])
    }
    default: return [null, 'fixture-原样🙂', 1, true, [], { fixture: null }]
  }
}

function mutations(value: unknown): unknown[] {
  const primitives: unknown[] = [null, 'unknown-native-union', false, 0.5, Number.MIN_VALUE, Number.MAX_VALUE, Number.MAX_SAFE_INTEGER + 1, [], {}]
  if (Array.isArray(value)) return [...primitives, ...value.flatMap((item, index) => mutations(item).map(changed => value.map((old, at) => at === index ? changed : old)))]
  if (value && typeof value === 'object') {
    const object = value as Record<string, unknown>
    return [...primitives, { ...object, unknownFixtureField: true }, ...Object.keys(object).flatMap(key => {
      const missing = { ...object }; delete missing[key]
      return [missing, ...mutations(object[key]).map(changed => ({ ...object, [key]: changed }))]
    })]
  }
  return primitives
}

export function buildNativeVectors(): { dshVersion: string; source: string; vectors: NativeVector[] } {
  const vectors: NativeVector[] = []
  for (const [endpoint, method] of nativeMethods) for (const side of (method.uplink ? ['input', 'result', 'uplink'] : ['input', 'result']) as NativeSide[]) {
    const codec = nativeCodec(endpoint, side)
    const schema = nativeSchema({ create: () => codec })
    const generated = samples(schema, schema)
    const accepted = generated.filter(value => codec.safeParse(value).success)
    if (!accepted.length) throw new Error(`No valid schema sample: ${endpoint} ${side}`)
    const candidates = unique([undefined, null, ...generated, ...mutations(accepted[0])])
    let index = 0
    for (const candidate of candidates) {
      // Round-trip through JSON before testing: undefined nested object fields
      // must disappear, while explicit null remains a distinct wire value.
      const value = JSON.parse(JSON.stringify(carrier(candidate))) as Carrier
      const raw = value.present ? value.value : undefined
      const decoded = codec.safeParse(raw)
      const vector: NativeVector = { id: `${endpoint}:${side}:${index++}`, endpoint, side, source: 'schema-generated', value, accepted: decoded.success }
      if (decoded.success) vector.expected = carrier(z.encode(codec, z.decode(codec, raw)))
      vectors.push(vector)
    }
  }
  return { dshVersion: nativeVersion, source: 'schema-generated; not recorded method execution', vectors }
}
