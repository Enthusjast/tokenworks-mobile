import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { createRequire } from 'node:module'
import { z } from 'zod'
import { API_REMOTE_FORWARDED_EVENTS } from '@deepseek-ai/dsh-api-remotes'
import { assertNativePackages, installedNativeMethods, nativeMethods, nativeSchema, nativeVersion, nativeVersionRange, nativeVersionPattern, type NativeMethod } from './native-contract.js'
import type { TypertContractCandidate } from './typert-contract-check.js'

const resourceKind = z.enum(['global', 'session', 'workspace'])
const actionSchema = z.strictObject({
  capability: z.string().regex(/^[a-z]+\.[a-z]+$/),
  resources: z.array(resourceKind).min(1),
  globalCall: z.boolean(),
  selected: z.boolean(),
  descriptorSha256: z.string().regex(/^[a-f0-9]{64}$/).optional(),
  eventDelivery: z.enum(['emit', 'owner-waterfall', 'not-forwarded']).optional(),
})
const contractSchema = z.strictObject({
  dshVersion: z.string(),
  dshVersionRange: z.literal(nativeVersionRange),
  nativeForwardedEventsSha256: z.string().regex(/^[a-f0-9]{64}$/),
  nativeResourceSources: z.record(z.string(), z.string().regex(/^[a-f0-9]{64}$/)),
  actions: z.record(z.string().regex(/^(?:[^/:\s]+\/[^/:\s]+|event:[^\s]+)$/), actionSchema),
})
export type ProductAccessContract = z.infer<typeof contractSchema>
const require = createRequire(import.meta.url)
const root = new URL('../', import.meta.url)
const sha256 = (text: string) => createHash('sha256').update(text).digest('hex')

/** Pins the installed descriptor, including codecs, lookup aliases and scope. */
export function productDescriptorHash(method: NativeMethod): string {
  return sha256(JSON.stringify({
    mode: method.mode ?? 'unary', invocation: method.invocation, scope: method.scope,
    parameters: method.parameters.map(({ name, wire, source, lookup }) => ({ name, wire, source, lookup })),
    cancellation: method.cancellation ?? null,
    input: nativeSchema(method.input()), result: nativeSchema(method.result),
    uplink: method.uplink?.codec.mode === 'strict' ? nativeSchema(method.uplink.codec) : null,
  }))
}

/** Includes the complete exported native allowlist, modes and actual resource reader bodies. */
export function productForwardedEventsHash(): string {
  return sha256(JSON.stringify(API_REMOTE_FORWARDED_EVENTS.map(entry => ({
    event: entry.event, mode: entry.mode,
    resources: 'resources' in entry ? Object.entries(entry.resources).map(([kind, reader]) => [kind, String(reader)]) : [],
  }))))
}

export async function readProductAccessContract(): Promise<ProductAccessContract> {
  return contractSchema.parse(JSON.parse(await readFile(new URL('contracts/typert/product-access.json', root), 'utf8')))
}

/** Unknown versions, added/removed methods, codec and resource-reader drift fail closed. */
export async function assertProductAccessContract(value: unknown): Promise<ProductAccessContract> {
  assertNativePackages()
  const contract = contractSchema.parse(value)
  if (contract.dshVersion !== nativeVersion) throw new Error('Unsupported product access native version')
  assertNativePackages(['dsh-api-remotes', 'dsh-user-approval'])
  if (contract.nativeForwardedEventsSha256 !== productForwardedEventsHash()) throw new Error('Product access native forwarded event inventory/readers drift')
  const installed = new Map(installedNativeMethods)
  const endpoints = Object.keys(contract.actions).filter(action => !action.startsWith('event:')).sort()
  if (JSON.stringify(endpoints) !== JSON.stringify([...installed.keys()].sort())) throw new Error('Product access native method inventory drift')
  const selected = Object.entries(contract.actions).filter(([, policy]) => policy.selected).map(([action]) => action).sort()
  if (selected.length !== 40 || JSON.stringify(selected) !== JSON.stringify(nativeMethods.map(([action]) => action).sort())) throw new Error('Product access selected native methods drift')
  for (const [action, policy] of Object.entries(contract.actions)) {
    if (new Set(policy.resources).size !== policy.resources.length || (policy.globalCall && !policy.resources.includes('global'))) throw new Error(`Invalid product access resources: ${action}`)
    const descriptor = installed.get(action)
    if (descriptor ? productDescriptorHash(descriptor) !== policy.descriptorSha256 : policy.selected || policy.descriptorSha256 !== undefined) throw new Error(`Product access native descriptor drift: ${action}`)
    if (descriptor) {
      if (policy.eventDelivery !== undefined) throw new Error(`Invalid native method event delivery: ${action}`)
      continue
    }
    const event = API_REMOTE_FORWARDED_EVENTS.find(entry => `event:${entry.event}` === action)
    if (!event) {
      if (policy.eventDelivery !== 'not-forwarded') throw new Error(`Product access non-forwarded event drift: ${action}`)
      continue
    }
    const delivery = event.mode === 'emit' ? 'emit' : 'owner-waterfall'
    const resources = event.mode === 'emit' && 'resources' in event ? Object.keys(event.resources) : ['global']
    if (policy.eventDelivery !== delivery || policy.globalCall || JSON.stringify([...policy.resources].sort()) !== JSON.stringify(resources.sort())) throw new Error(`Product access native event resource/delivery drift: ${action}`)
  }
  if (Object.keys(contract.nativeResourceSources).length === 0) throw new Error('Product access native resource sources missing')
  for (const [path, hash] of Object.entries(contract.nativeResourceSources)) {
    const segments = path.split('/')
    const packageRoot = dirname(require.resolve(segments.slice(0, 2).join('/') + '/package.json'))
    if (!path.startsWith('@deepseek-ai/') || sha256(await readFile(join(packageRoot, ...segments.slice(2)), 'utf8')) !== hash) throw new Error(`Product access native resource source drift: ${path}`)
  }
  return contract
}


export function renderProductAccessContracts(contract: ProductAccessContract): TypertContractCandidate[] {
  const owners = (['global', 'session', 'workspace'] as const).map(kind => ({ resource: { kind }, actions: Object.entries(contract.actions).filter(([, policy]) => policy.resources.includes(kind)).map(([action]) => action) }))
  const header = '// Generated by tools/generate-product-access.ts from contracts/typert/product-access.json; do not edit.\n'
  const ts = header + `export const PRODUCT_NATIVE_VERSION = ${JSON.stringify(contract.dshVersion)} as const
export const PRODUCT_NATIVE_VERSION_RANGE = ${JSON.stringify(contract.dshVersionRange)} as const
const supportedDshVersion = new RegExp(${JSON.stringify('^' + nativeVersionPattern + '$')})
export function isSupportedDshVersion(value: unknown): value is string {
  return typeof value === 'string' && supportedDshVersion.exec(value)?.[0] === value
}
export const productActions = ${JSON.stringify(Object.fromEntries(Object.entries(contract.actions).map(([action, { descriptorSha256: _descriptor, ...policy }]) => [action, policy])), null, 2)} as const
export type ProductAction = keyof typeof productActions
export type ProductCapability = (typeof productActions)[ProductAction]['capability']
export type ProductResourceKind = 'global' | 'session' | 'workspace'
export type ProductResource = { readonly kind: 'global' } | { readonly kind: 'session' | 'workspace'; readonly id?: string }
export type ProductBinding = { readonly resource: ProductResource; readonly actions: readonly ProductAction[] }
export const PRODUCT_OWNER_BINDINGS: readonly ProductBinding[] = ${JSON.stringify(owners, null, 2)}
const record = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value)
export function isProductAction(value: unknown): value is ProductAction { return typeof value === 'string' && Object.hasOwn(productActions, value) }
export function isProductResource(value: unknown): value is ProductResource {
  if (!record(value)) return false
  if (value.kind === 'global') return Object.keys(value).length === 1
  return (value.kind === 'session' || value.kind === 'workspace') && Object.keys(value).every(key => key === 'kind' || key === 'id') && (!Object.hasOwn(value, 'id') || (typeof value.id === 'string' && value.id.length > 0 && value.id.length <= 2048))
}
export function productActionAllowsResource(action: unknown, kind: unknown): boolean {
  return isProductAction(action) && (productActions[action].resources as readonly string[]).includes(typeof kind === 'string' ? kind : '')
}
export function productActionAllowsGlobal(action: unknown): boolean { return isProductAction(action) && productActions[action].globalCall }
export function isProductBinding(value: unknown): value is ProductBinding {
  return record(value) && Object.keys(value).every(key => key === 'resource' || key === 'actions') && isProductResource(value.resource) && Array.isArray(value.actions) && value.actions.length <= 256 && value.actions.every(action => productActionAllowsResource(action, (value.resource as ProductResource).kind))
}
export function isProductOwnerEligible(facts: { readonly role?: unknown; readonly is_installation_owner?: unknown; readonly bindings?: unknown }): boolean {
  if (facts.role !== 'owner' || facts.is_installation_owner !== true || !Array.isArray(facts.bindings) || !facts.bindings.every(isProductBinding)) return false
  const bindings: ProductBinding[] = facts.bindings
  return PRODUCT_OWNER_BINDINGS.every(required => required.actions.every(action => bindings.some(binding => binding.resource.kind === required.resource.kind && !('id' in binding.resource) && binding.actions.includes(action))))
}
/** Native transport participation opens broadcasts; only an eligible owner answers waterfalls/results. */
export function productInteractionPolicy(facts: Parameters<typeof isProductOwnerEligible>[0], mode: 'owner' | 'device') {
  return { participation: 'answer' as const, answerAuthority: mode === 'owner' && isProductOwnerEligible(facts) }
}
`
  const kotlinQuote = (text: string) => JSON.stringify(text).replaceAll('$', '\\$')
  const kt = header + `package cn.edu.ustc.tokenworks

data class DshProductResource(val kind: String, val id: String? = null)
data class DshProductBinding(val resource: DshProductResource, val actions: Set<String>)
data class DshProductAction(val capability: String, val resources: Set<String>, val globalCall: Boolean, val selected: Boolean, val eventDelivery: String? = null)
data class DshProductInteractions(val participation: String, val answerAuthority: Boolean)

object DshProductAccess {
    const val VERSION = ${kotlinQuote(contract.dshVersion)}
    const val VERSION_RANGE = ${kotlinQuote(contract.dshVersionRange)}
    private val supportedVersion = Regex(${kotlinQuote(nativeVersionPattern)})
    fun isSupportedDshVersion(value: String): Boolean = supportedVersion.matches(value)
    val actions: Map<String, DshProductAction> = mapOf(
${Object.entries(contract.actions).map(([action, policy]) => `        ${kotlinQuote(action)} to DshProductAction(${kotlinQuote(policy.capability)}, setOf(${policy.resources.map(kotlinQuote).join(', ')}), ${policy.globalCall}, ${policy.selected}, ${policy.eventDelivery === undefined ? 'null' : kotlinQuote(policy.eventDelivery)}),`).join('\n')}
    )
    val ownerBindings: List<DshProductBinding> = listOf(
${owners.map(binding => `        DshProductBinding(DshProductResource(${kotlinQuote(binding.resource.kind)}), setOf(${binding.actions.map(kotlinQuote).join(', ')})),`).join('\n')}
    )
    fun isResource(resource: DshProductResource): Boolean = when (resource.kind) {
        "global" -> resource.id == null
        "session", "workspace" -> resource.id == null || resource.id.isNotEmpty() && resource.id.length <= 2048
        else -> false
    }
    fun allowsResource(action: String, kind: String): Boolean = actions[action]?.resources?.contains(kind) == true
    fun allowsGlobal(action: String): Boolean = actions[action]?.globalCall == true
    fun isBinding(binding: DshProductBinding): Boolean = isResource(binding.resource) && binding.actions.size <= 256 && binding.actions.all { allowsResource(it, binding.resource.kind) }
    fun ownerEligible(role: String, isInstallationOwner: Boolean, bindings: List<DshProductBinding>): Boolean =
        role == "owner" && isInstallationOwner && bindings.all(::isBinding) && ownerBindings.all { required ->
            required.actions.all { action -> bindings.any { it.resource.kind == required.resource.kind && it.resource.id == null && action in it.actions } }
        }
    // Participation is a native transport requirement, not waterfall/result answer authority.
    fun interactions(role: String, isInstallationOwner: Boolean, bindings: List<DshProductBinding>, ownerMode: Boolean): DshProductInteractions =
        DshProductInteractions("answer", ownerMode && ownerEligible(role, isInstallationOwner, bindings))
}
`
  const go = header + `package store

import "regexp"

const ProductNativeVersion = ${JSON.stringify(contract.dshVersion)}
const ProductNativeVersionRange = ${JSON.stringify(contract.dshVersionRange)}

var supportedDshVersion = regexp.MustCompile(${JSON.stringify('^' + nativeVersionPattern + '$')})

// IsSupportedDshVersion admits valid SemVer in the native 0.2.2 family only.
func IsSupportedDshVersion(value string) bool {
\treturn supportedDshVersion.MatchString(value)
}

// OwnerBindings returns fresh mutable bindings for the complete supported native policy.
// Resource kinds follow native lookup/readers and result visibility, not endpoint namespaces.
// A not-forwarded event entry is a retained capability, not an emitted native event.
func OwnerBindings() []GrantBinding {
\treturn []GrantBinding{
${owners.map(binding => `\t\t{Resource: GrantResource{Kind: ${JSON.stringify(binding.resource.kind)}}, Actions: []string{\n${binding.actions.map(action => `\t\t\t${JSON.stringify(action)},`).join('\n')}\n\t\t}},`).join('\n')}
\t}
}
`
  return [
    { path: 'host/remote-control/src/product-access.generated.ts', content: ts },
    { path: 'android/core/dsh-protocol/src/main/kotlin/cn/edu/ustc/tokenworks/DshProductAccess.kt', content: kt },
    { path: 'services/remote/internal/store/owner_bindings.go', content: go },
  ]
}

export async function productAccessOutputs(): Promise<TypertContractCandidate[]> {
  return renderProductAccessContracts(await assertProductAccessContract(await readProductAccessContract()))
}
