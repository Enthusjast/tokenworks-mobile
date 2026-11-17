import { createRequire } from 'node:module'
import { z } from 'zod'
import type { TypertRemoteMethod } from '@deepseek-ai/dsh-typert-protocol'
import { TYPERT_REMOTE as session } from '@deepseek-ai/dsh-api-session-controller/remote'
import { TYPERT_REMOTE as workspace } from '@deepseek-ai/dsh-api-workspace-controller/remote'
import { TYPERT_REMOTE as uploads } from '@deepseek-ai/dsh-client-file-upload/remote'
import { TYPERT_REMOTE as questions } from '@deepseek-ai/dsh-user-questions/remote'
import { TYPERT_REMOTE as access } from '@deepseek-ai/dsh-api-remote-access/remote'
import { TYPERT_REMOTE as terminal } from '@deepseek-ai/dsh-api-terminal-controller/remote'

export const nativeVersion = '0.2.2-rc.1'
export const nativeVersionRange = '>=0.2.2-0 <0.2.3-0'
// SemVer 2.0: numeric prerelease identifiers cannot have leading zeroes;
// build identifiers may. Matching the fixed core avoids numeric coercion.
export const nativeVersionPattern = '0[.]2[.]2(-(0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)([.](0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*))*)?([+][0-9A-Za-z-]+([.][0-9A-Za-z-]+)*)?'
const supportedNativeVersion = new RegExp('^' + nativeVersionPattern + '$')
export function isSupportedDshVersion(value: unknown): value is string {
  return typeof value === 'string' && supportedNativeVersion.exec(value)?.[0] === value
}
export const nativePackages = ['dsh-api-session-controller', 'dsh-api-workspace-controller', 'dsh-client-file-upload', 'dsh-user-questions', 'dsh-api-remote-access', 'dsh-api-gateway', 'dsh-typert-protocol', 'dsh-api-terminal-controller']
const require = createRequire(import.meta.url)
export function assertNativePackages(packages: readonly string[] = nativePackages): void {
  for (const name of packages) {
    const { version } = require(`@deepseek-ai/${name}/package.json`) as { version: string }
    if (version !== nativeVersion) throw new Error(`${name}: expected ${nativeVersion}, found ${version}`)
  }
}

// Admission accepts the series; installed schema/capture checks still pin the baseline.
export function nativeCompatibility(advertisedVersion: unknown): 'compatible' | 'unsupported-version' {
  return isSupportedDshVersion(advertisedVersion) ? 'compatible' : 'unsupported-version'
}

const productAccess = require('../contracts/typert/product-access.json') as { actions: Record<string, { selected: boolean }> }
const selected = new Set(Object.entries(productAccess.actions).filter(([, policy]) => policy.selected).map(([action]) => action))
export type NativeMethod = TypertRemoteMethod<object, unknown> | TypertRemoteMethod<object, unknown, 'stream', unknown>
export const installedNativeMethods = Object.entries({ ...session.methods, ...workspace.methods, ...uploads.methods, ...questions.methods, ...access.methods, ...terminal.methods })
  .sort(([a], [b]) => a.localeCompare(b, 'en')) as [string, NativeMethod][]
export const nativeMethods = installedNativeMethods
  .filter(([key]) => selected.has(key))
for (const endpoint of selected) if (!nativeMethods.some(([name]) => name === endpoint)) throw new Error(`Native endpoint missing: ${endpoint}`)

export type NativeSchema = {
  [key: string]: unknown
  type?: string; properties?: Record<string, NativeSchema>; required?: string[]
  additionalProperties?: boolean | NativeSchema; items?: NativeSchema
  anyOf?: NativeSchema[]; oneOf?: NativeSchema[]; allOf?: NativeSchema[]
  $ref?: string; $defs?: Record<string, NativeSchema>; const?: unknown; enum?: unknown[]
  not?: NativeSchema; 'x-unknownKeys'?: 'strip'
}
export function nativeSchema(value: { create(): unknown }): NativeSchema {
  return z.toJSONSchema(value.create() as z.ZodType, {
    unrepresentable: 'any',
    override(ctx) {
      const schema = ctx.zodSchema
      // JSON has no undefined value. Never project it into an unconstrained {}.
      if (schema._zod.def.type === 'undefined' || schema._zod.def.type === 'void') ctx.jsonSchema.not = {}
      // Zod's default objects strip (not reject) extra keys on decode AND encode.
      if (schema instanceof z.ZodObject && schema._zod.def.catchall === undefined) ctx.jsonSchema['x-unknownKeys'] = 'strip'
    },
  }) as NativeSchema
}

export type Carrier = { present: false } | { present: true; value: unknown }
export type NativeSide = 'input' | 'result' | 'uplink'
export function carrier(value: unknown): Carrier { return value === undefined ? { present: false } : { present: true, value } }
export function uncarry(value: Carrier): unknown { return value.present ? value.value : undefined }
export function nativeCodec(endpoint: string, side: NativeSide): z.ZodType {
  const method = nativeMethods.find(([name]) => name === endpoint)?.[1]
  if (!method) throw new Error(`Unknown native endpoint: ${endpoint}`)
  if (side === 'uplink') {
    if (!method.uplink || method.uplink.codec.mode !== 'strict') throw new Error(`No strict native uplink: ${endpoint}`)
    return method.uplink.codec.create() as z.ZodType
  }
  return (side === 'input' ? method.input() : method.result).create() as z.ZodType
}
export function nativeRoundTrip(endpoint: string, side: NativeSide, value: Carrier): Carrier {
  const schema = nativeCodec(endpoint, side)
  return carrier(z.encode(schema, z.decode(schema, uncarry(value))))
}
