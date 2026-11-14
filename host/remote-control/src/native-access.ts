import { isDeepStrictEqual } from 'node:util'
import { z } from 'zod'
import type { HostConnectionHandle } from '@deepseek-ai/dsh-client-connection'
import type { RemoteAccessService, RemoteAccessBinding, RemoteAccessAction } from '@deepseek-ai/dsh-api-remote-access'
import type { PeerScope } from '@deepseek-ai/dsh-typert-protocol'
import type {} from '@deepseek-ai/dsh-session/types'
import type {} from '@deepseek-ai/dsh-workspace/types'
import { isProductBinding, isProductOwnerEligible, productActionAllowsGlobal } from './product-access.generated.js'

declare module '@deepseek-ai/dsh-typert-protocol' {
  interface PrincipalMap {
    'tokenworks-device': { readonly kind: 'tokenworks-device'; readonly leaseId: string; readonly generation: number }
  }
}
const identity = z.string().min(1).max(2048)
// A Relay grant names one identity, or omits it to grant every resource of
// that kind in the runtime.
const resource = z.discriminatedUnion('kind', [
  z.strictObject({ kind: z.literal('global') }),
  z.strictObject({ kind: z.literal('session'), id: identity.optional() }),
  z.strictObject({ kind: z.literal('workspace'), id: identity.optional() }),
])
const binding = z.strictObject({ resource, actions: z.array(z.string().regex(/^(?:[^/:\s]+\/[^/:\s]+|event:.+)$/)).max(256) })
const introspection = z.object({
  active: z.literal(true), sub: z.string().min(1), aud: z.string().min(1), exp: z.number().int(), client_id: z.string().min(1),
  device_id: z.string().uuid(), runtime_id: z.string().uuid(), installation_id: z.string().uuid(), grant_version: z.number().int().positive(),
  organization_id: z.string().uuid(),
  role: z.enum(['owner', 'operator', 'viewer']), is_installation_owner: z.boolean(),
  bindings: z.array(binding).max(512),
})
const envelope = z.object({ ok: z.literal(true), data: z.unknown() })
export type NativeLease = z.infer<typeof introspection>
/** Identities the runtime holds now; a kind-wide grant expands over these. */
export interface NativeResourceIds {
  readonly sessions: readonly string[]
  readonly workspaces: readonly string[]
}
export type InspectLease = (leaseId: string, secret: string, signal: AbortSignal) => Promise<unknown>
/**
 * How an admitted device maps onto a dsh Peer.
 * - `owner`: only an authenticated owner grant belonging to the actual
 *   installation owner becomes an owner Peer and may answer waterfalls.
 * - `device`: always restricted. Non-owner peers in either mode receive only
 *   policy-authorized broadcasts, never scoped waterfalls or answer authority.
 */
export type NativePeerMode = 'owner' | 'device'
interface Entry {
  readonly lease: NativeLease
  readonly secret: string
  readonly peer: PeerScope
  readonly timer: NodeJS.Timeout
  readonly ownsPeer: boolean
  readonly cancel: AbortController
  readonly peerGeneration: string
  connections: number
}
interface InspectionGeneration { revision: number; pending: number }
export interface NativePeerAdmission {
  readonly peer: PeerScope
  readonly lease: NativeLease
  readonly signal: AbortSignal
  readonly ownerEligible: boolean
  readonly peerGeneration: string
  release(): void
}

/** A policy reads only authenticated, immutable lease generations, never request-body identity. */
export class NativeAccess {
  readonly #entries = new Map<string, Entry>()
  readonly #inspections = new Map<string, InspectionGeneration>()
  readonly #lifetime = new AbortController()
  readonly #unregister: () => void
  readonly #interval: NodeJS.Timeout
  #generation = 0
  readonly #mode: NativePeerMode
  constructor(readonly connection: HostConnectionHandle, readonly access: RemoteAccessService,
    readonly runtimeId: string, readonly installationId: string, readonly inspect: InspectLease,
    readonly resources: () => NativeResourceIds, mode: NativePeerMode = 'owner') {
    this.#mode = mode
    // Owner-mode installations may admit mixed roles. Native owner peers bypass
    // policies, so every other authenticated principal must have this policy.
    this.#unregister = access.registerPolicy('tokenworks-device', { bindings: principal => {
      const current = principal.leaseId ? this.#entries.get(principal.leaseId) : undefined
      if (!(current && current.peer.principal.kind === 'tokenworks-device' && current.peer.principal.generation === principal.generation && current.lease.exp * 1000 > Date.now())) return []
      const ids = this.resources()
      return current.lease.bindings.flatMap(item => expand(item, ids))
    } })
    this.#interval = setInterval(() => {
      for (const [id, entry] of this.#entries) void this.#refresh(id, entry)
    }, 20_000)
    this.#interval.unref()
  }

  async #read(id: string, secret: string): Promise<NativeLease> {
    const value = introspection.parse(envelope.parse(await this.inspect(id, secret, this.#lifetime.signal)).data)
    if (value.runtime_id !== this.runtimeId || value.installation_id !== this.installationId || value.aud !== this.runtimeId || value.exp * 1000 <= Date.now() || value.exp * 1000 > Date.now() + 301_000) throw new Error('native lease refused')
    if (!value.bindings.every(isProductBinding)) throw new Error('native lease refused')
    for (const item of value.bindings) { Object.freeze(item.resource); Object.freeze(item.actions); Object.freeze(item) }
    Object.freeze(value.bindings)
    return Object.freeze(value)
  }

  async #refresh(id: string, entry: Entry): Promise<void> {
    try {
      const value = await this.#read(id, entry.secret)
      if (!isDeepStrictEqual(value, entry.lease)) this.#remove(id, entry)
    } catch { this.#remove(id, entry) }
  }

  /** Every carrier and retained RTC handoff independently contacts the authority. */
  async admitFresh(id: string, secret: string, pairedOwner?: () => PeerScope): Promise<NativePeerAdmission> {
    if (this.#lifetime.signal.aborted || !z.string().uuid().safeParse(id).success || !/^[A-Za-z0-9_-]{43}$/.test(secret)) throw new Error('native lease refused')
    const existing = this.#entries.get(id)
    if (existing && existing.secret !== secret) throw new Error('native lease refused')
    let inspection = this.#inspections.get(id)
    if (!inspection) { inspection = { revision: 0, pending: 0 }; this.#inspections.set(id, inspection) }
    inspection.pending++
    const revision = inspection.revision
    try {
      let lease: NativeLease
      try { lease = await this.#read(id, secret) }
      catch (error) {
        // An old failed lookup cannot retire a replacement generation.
        const current = this.#entries.get(id)
        if (inspection.revision === revision) {
          if (current) this.#remove(id, current)
          else inspection.revision++
        }
        throw error
      }
      this.#lifetime.signal.throwIfAborted()
      // Revocation, expiry, release, or a contradictory concurrent admission
      // fences all inspections that started against that older generation.
      if (inspection.revision !== revision) throw new Error('native lease admission refused')
      let entry = this.#entries.get(id)
      if (entry && (entry.secret !== secret || entry.peer.signal.aborted || !isDeepStrictEqual(entry.lease, lease))) {
        this.#remove(id, entry)
        throw new Error('native lease admission refused')
      }
      if (!entry) {
        this.#generation++
        // Owner eligibility is the generated policy's full canonical owner
        // grant AND authenticated installation ownership, never a role alone.
        const principal = this.#mode === 'owner' && isProductOwnerEligible(lease)
          ? Object.freeze({ kind: 'owner' as const })
          : Object.freeze({ kind: 'tokenworks-device' as const, leaseId: id, generation: this.#generation })
        // Native rc.5 needs answer participation for restricted broadcasts;
        // the native Gateway still gates waterfalls on the owner principal.
        const ownsPeer = principal.kind !== 'owner' || !pairedOwner
        const peer = ownsPeer ? this.connection.createPeer({ principal, interactions: 'answer' }) : pairedOwner!()
        const timer = setTimeout(() => this.#remove(id, created), Math.max(1, lease.exp * 1000 - Date.now()))
        timer.unref()
        const created: Entry = { lease, secret, peer, timer, connections: 0, ownsPeer, cancel: new AbortController(), peerGeneration: crypto.randomUUID() }
        entry = created
        this.#entries.set(id, created)
        this.access.invalidate(principal)
      }
      if (this.#entries.get(id) !== entry || entry.cancel.signal.aborted || entry.peer.signal.aborted || entry.lease.exp * 1000 <= Date.now()) throw new Error('native lease admission refused')
      // References include RTC handoff pins as well as native carriers. Physical
      // connection budgets belong to NoiseCarrier/RtcCarrierHost, not this count.
      entry.connections++
      let released = false
      const admitted = entry
      return { peer: entry.peer, lease: entry.lease, signal: entry.cancel.signal, ownerEligible: this.#mode === 'owner' && isProductOwnerEligible(entry.lease), peerGeneration: entry.peerGeneration, release: () => {
        if (released) return
        released = true
        admitted.connections--
        if (admitted.connections === 0) this.#remove(id, admitted)
      } }
    } finally {
      inspection.pending--
      if (inspection.pending === 0) this.#inspections.delete(id)
    }
  }

  #remove(id: string, entry: Entry): void {
    if (this.#entries.get(id) !== entry) return
    this.#entries.delete(id)
    const inspection = this.#inspections.get(id)
    if (inspection) inspection.revision++
    clearTimeout(entry.timer)
    entry.cancel.abort()
    this.access.invalidate(entry.peer.principal)
    if (entry.ownsPeer) void entry.peer.dispose().catch(() => {})
  }

  close(): void {
    if (this.#lifetime.signal.aborted) return
    this.#lifetime.abort()
    clearInterval(this.#interval)
    for (const [id, entry] of this.#entries) this.#remove(id, entry)
    this.#unregister()
  }
}

/** One Relay grant as the dsh policy bindings it stands for. */
function expand(item: NativeLease['bindings'][number], ids: NativeResourceIds): RemoteAccessBinding[] {
  const actions = item.actions as RemoteAccessAction[]
  const binding = (resource: { kind: string; id?: string }, granted = actions) => ({ resource, role: { name: 'device-lease', resource: resource.kind, actions: granted } }) as unknown as RemoteAccessBinding
  if (item.resource.kind === 'global') return [binding({ kind: 'global' })]
  const kind = item.resource.kind
  // Kind-wide identities do not imply unscoped authority. Only actions whose
  // installed native descriptor admits a global call receive that projection.
  const identities = item.resource.id === undefined ? (kind === 'session' ? ids.sessions : ids.workspaces) : [item.resource.id]
  const globalActions = item.resource.id === undefined ? actions.filter(action => productActionAllowsGlobal(action)) : []
  return [...(globalActions.length ? [binding({ kind: 'global' }, globalActions)] : []), ...identities.map(id => binding({ kind, id }))]
}
