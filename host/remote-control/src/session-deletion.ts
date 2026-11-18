import type { Context } from '@deepseek-ai/cordis'
import { SessionId } from '@deepseek-ai/dsh-session/types'
import type SessionPersistence from '@deepseek-ai/dsh-session-persistence'
import type {} from '@deepseek-ai/dsh-typert-registry'
import { RemoteError, TypertRemoteService, type TypertRemoteCodecSpec } from '@deepseek-ai/dsh-typert-protocol'
import { createTypertRemoteInfo } from '@deepseek-ai/dsh-typert-protocol/remote-info'
import { z } from 'zod'
import { PRODUCT_NATIVE_VERSION } from './product-access.generated.js'
import { SessionDeletionStorageError, SessionDeletionStore } from './session-deletion-store.js'

const service = 'sessionDeletion'
const unavailable = 'session-deletion/unavailable'
const uncertain = 'session-deletion/outcome-unknown'
const sessionKey = z.string().min(1).max(2048)

declare module '@deepseek-ai/dsh-typert-protocol' {
  interface RemoteErrorDetailsMap {
    'session-deletion/unavailable': Record<string, never>
    'session-deletion/outcome-unknown': Record<string, never>
  }
}

function codec(typeSymbol: string, schema: z.ZodType): TypertRemoteCodecSpec {
  return { mode: 'strict', typeSymbol, create: (() => schema) as unknown as TypertRemoteCodecSpec['create'] }
}
const sessionCodec = codec('tokenworks-mobile#SessionDeletionId', sessionKey)
const parameters = [{ name: 'sessionId', source: 'json', codec: sessionCodec, strictCodec: sessionCodec }] as const
const deletionRemote = createTypertRemoteInfo({
  package: 'tokenworks-mobile-session-deletion',
  methods: {
    'sessionDeletion/status': {
      service, invocation: { kind: 'direct' }, cancellation: { parameter: 'signal' }, parameters,
      result: codec('tokenworks-mobile#SessionDeletionAvailability', z.strictObject({ available: z.boolean() })),
    },
    'sessionDeletion/delete': {
      service, invocation: { kind: 'direct' }, cancellation: { parameter: 'signal' }, parameters,
      result: codec('tokenworks-mobile#SessionDeletionAcknowledgement', z.strictObject({ deleted: z.literal(true) })),
    },
  },
}, z as unknown as Parameters<typeof createTypertRemoteInfo>[1])

function refuse(code: typeof unavailable | typeof uncertain): never {
  throw new RemoteError(code, code === unavailable ? 'This session cannot be deleted.' : 'Deletion was not confirmed.', {})
}

/** The extension is never enabled by a mobile argument or for a scoped non-owner Peer. */
export function sessionDeletionPlugin(root: string, dshVersion: string) {
  return {
    name: 'tokenworks-mobile-session-deletion',
    inject: ['sessionPersistence', 'sessions', 'workspaceRegistry', 'typert'],
    apply(ctx: Context) {
      if (process.platform !== 'linux' || dshVersion !== PRODUCT_NATIVE_VERSION) return
      const deletion = new SessionDeletion(ctx, root)
      ctx.effect(() => async () => { await deletion.close() }, service)
      ctx.effect(() => ctx.typert.register({ package: deletionRemote.package, face: 'host', schemas: [],
        model: { services: [], events: [], objects: [] }, invocations: deletionRemote.descriptors }), `${service}.contract`)
    },
  }
}

class SessionDeletion extends TypertRemoteService {
  private readonly state = {
    closing: new AbortController(), calls: new Set<Promise<unknown>>(),
    started: new Set<SessionId>(), store: undefined as Promise<SessionDeletionStore> | undefined,
  }
  constructor(ctx: Context, readonly root: string) { super(ctx, service) }

  private storage(): Promise<SessionDeletionStore> {
    // A fresh installation may not have materialized its first transcript yet.
    this.state.store ??= SessionDeletionStore.open(this.root).catch((error: unknown) => {
      this.state.store = undefined
      throw error
    })
    return this.state.store
  }

  private request(signal: AbortSignal) {
    const peer = this.ctx.invocation?.peer
    // Relay admission reserves the native owner Peer for the authenticated
    // installation owner with complete grants; LAN pairing binds the same role.
    if (peer?.principal.kind !== 'owner' || peer.signal.aborted || this.state.closing.signal.aborted) refuse(unavailable)
    return AbortSignal.any([signal, peer.signal, this.state.closing.signal])
  }

  private available(persistence: SessionPersistence, id: SessionId): boolean {
    return this.ctx.sessionPersistence.identity === persistence.identity &&
      !this.ctx.sessions.get(id) && this.ctx.workspaceRegistry.archivedSessionIds.includes(id)
  }

  private track<T>(promise: Promise<T>): Promise<T> {
    this.state.calls.add(promise)
    return promise.finally(() => { this.state.calls.delete(promise) })
  }

  status(sessionId: string, callerSignal: AbortSignal): Promise<{ available: boolean }> {
    const signal = this.request(callerSignal)
    const persistence = this.ctx.sessionPersistence
    const id = SessionId(sessionId)
    return this.track((async () => {
      try {
        if (signal.aborted || this.state.started.has(id) || !this.available(persistence, id)) return { available: false }
        const snapshot = await persistence.stat(id, { signal })
        const available = snapshot?.header.id === id && snapshot.header.origin !== 'subagent' &&
          await (await this.storage()).canDelete(snapshot) && !signal.aborted && this.available(persistence, id)
        return { available }
      } catch { return { available: false } }
    })())
  }

  delete(sessionId: string, callerSignal: AbortSignal): Promise<{ deleted: true }> {
    const signal = this.request(callerSignal)
    const persistence = this.ctx.sessionPersistence
    const id = SessionId(sessionId)
    const assertCurrent = () => {
      if (signal.aborted || !this.available(persistence, id)) refuse(unavailable)
    }
    assertCurrent()
    if (this.state.started.has(id)) refuse(unavailable)
    this.state.started.add(id)
    return this.track((async (): Promise<{ deleted: true }> => {
      try {
        const snapshot = await persistence.stat(id, { signal })
        if (snapshot?.header.id !== id || snapshot.header.origin === 'subagent') refuse(unavailable)
        await (await this.storage()).remove(persistence, id, assertCurrent, signal)
        return { deleted: true }
      } catch (error) {
        if (error instanceof SessionDeletionStorageError && error.code === 'outcome-unknown') refuse(uncertain)
        refuse(unavailable)
      } finally { this.state.started.delete(id) }
    })())
  }

  async close(): Promise<void> {
    this.state.closing.abort()
    await Promise.allSettled(this.state.calls)
    const store = await this.state.store?.catch(() => undefined)
    await store?.close()
  }
}
