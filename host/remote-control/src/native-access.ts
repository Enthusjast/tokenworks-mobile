import { z } from 'zod'
import type { HostConnectionHandle } from '@deepseek-ai/dsh-client-connection'
import type { RemoteAccessService, RemoteAccessBinding, RemoteAccessAction } from '@deepseek-ai/dsh-api-remote-access'
import type { PeerScope } from '@deepseek-ai/dsh-typert-protocol'
import type {} from '@deepseek-ai/dsh-session/types'
import type {} from '@deepseek-ai/dsh-workspace/types'

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
interface Entry {
  readonly lease: NativeLease
  readonly secret: string
  readonly peer: PeerScope
  readonly timer: NodeJS.Timeout
  connections: number
}
export interface NativePeerAdmission {
  readonly peer: PeerScope
  release(): void
}

/** A policy reads only authenticated, immutable lease generations, never request-body identity. */
export class NativeAccess {
  readonly #entries = new Map<string, Entry>()
  readonly #flights = new Map<string, Promise<Entry>>()
  readonly #lifetime = new AbortController()
  readonly #unregister: () => void
  readonly #interval: NodeJS.Timeout
  #generation = 0
  constructor(readonly connection: HostConnectionHandle, readonly access: RemoteAccessService,
    readonly runtimeId: string, readonly installationId: string, readonly inspect: InspectLease,
    readonly resources: () => NativeResourceIds) {
    this.#unregister = access.registerPolicy('tokenworks-device', { bindings: principal => {
      const current = this.#entries.get(principal.leaseId)
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
    return value
  }

  async #refresh(id: string, entry: Entry): Promise<void> {
    try {
      const value = await this.#read(id, entry.secret)
      if (value.grant_version !== entry.lease.grant_version || value.device_id !== entry.lease.device_id || value.sub !== entry.lease.sub || JSON.stringify(value.bindings) !== JSON.stringify(entry.lease.bindings)) this.#remove(id, entry)
    } catch { this.#remove(id, entry) }
  }

  async admit(id: string, secret: string): Promise<NativePeerAdmission> {
    if (this.#lifetime.signal.aborted || !z.string().uuid().safeParse(id).success || !/^[A-Za-z0-9_-]{43}$/.test(secret)) throw new Error('native lease refused')
    let entry = this.#entries.get(id)
    if (entry && entry.secret !== secret) throw new Error('native lease refused')
    if (!entry) {
      let flight = this.#flights.get(id)
      if (!flight) {
        flight = (async () => {
          const lease = await this.#read(id, secret)
          this.#lifetime.signal.throwIfAborted()
          const principal = Object.freeze({ kind: 'tokenworks-device' as const, leaseId: id, generation: ++this.#generation })
          const peer = this.connection.createPeer({ principal, interactions: 'none' })
          const timer = setTimeout(() => this.#remove(id, created), Math.max(1, lease.exp * 1000 - Date.now()))
          timer.unref()
          const created: Entry = { lease, secret, peer, timer, connections: 0 }
          this.#entries.set(id, created)
          this.access.invalidate(principal)
          return created
        })()
        this.#flights.set(id, flight)
        void flight.finally(() => { if (this.#flights.get(id) === flight) this.#flights.delete(id) }).catch(() => {})
      }
      entry = await flight
    }
    if (entry.secret !== secret || entry.peer.signal.aborted || entry.lease.exp * 1000 <= Date.now() || entry.connections >= 8) throw new Error('native lease admission refused')
    entry.connections++
    let released = false
    const admitted = entry
    return { peer: entry.peer, release: () => {
      if (released) return
      released = true
      admitted.connections--
      if (admitted.connections === 0) this.#remove(id, admitted)
    } }
  }

  #remove(id: string, entry: Entry): void {
    if (this.#entries.get(id) !== entry) return
    this.#entries.delete(id)
    clearTimeout(entry.timer)
    this.access.invalidate(entry.peer.principal)
    void entry.peer.dispose().catch(() => {})
  }

  close(): void {
    this.#lifetime.abort()
    clearInterval(this.#interval)
    for (const [id, entry] of this.#entries) this.#remove(id, entry)
    this.#unregister()
  }
}

/** One Relay grant as the dsh policy bindings it stands for. */
function expand(item: NativeLease['bindings'][number], ids: NativeResourceIds): RemoteAccessBinding[] {
  const actions = item.actions as RemoteAccessAction[]
  const binding = (resource: { kind: string; id?: string }) => ({ resource, role: { name: 'device-lease', resource: resource.kind, actions } }) as unknown as RemoteAccessBinding
  if (item.resource.kind === 'global') return [binding({ kind: 'global' })]
  const kind = item.resource.kind
  // A kind-wide grant covers every identity of the kind, and the calls that
  // name none: the Gateway decides those on `global` alone.
  const identities = item.resource.id === undefined ? (kind === 'session' ? ids.sessions : ids.workspaces) : [item.resource.id]
  return [...(item.resource.id === undefined ? [binding({ kind: 'global' })] : []), ...identities.map(id => binding({ kind, id }))]
}
