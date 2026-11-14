import { randomUUID } from 'node:crypto'
import type { Context } from '@deepseek-ai/cordis'
import type {} from '@deepseek-ai/dsh-session'
import type {} from '@deepseek-ai/dsh-user-approval/types'
import type {} from '@deepseek-ai/dsh-user-questions/types'
import { z } from 'zod'
import type { HostCredentialProvider } from './host-connection-credential.js'
import { installationDPoP } from './relay-tunnel.js'
import { normalizeRestEnvelope, restEnvelopeHeader, restEnvelopeVersion } from './rest-envelope.js'

export type NotificationReferenceStatus = 'disabled' | 'idle' | 'accepted' | 'auth_required' | 'unsupported' | 'expired' | 'unavailable'
export interface NotificationReferenceOptions {
  enabled?: boolean
  origin: string
  runtimeId: string
  credentials: HostCredentialProvider
  ttlSeconds?: number
  /** Isolated loopback fixtures only; the plugin never enables cleartext transport. */
  allowLoopbackHttp?: boolean
}
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const accepted = z.strictObject({ ok: z.literal(true), data: z.strictObject({ accepted: z.literal(true) }) })
const failure = z.strictObject({ ok: z.literal(false), error: z.strictObject({ code: z.string().max(128) }) })
const noncePattern = /^[A-Za-z0-9_-]{43}$/

/** Optional, best-effort reference wakeups. Provider tokens, owner scope and
 * message content never enter this adapter; the authenticated service resolves
 * current registered devices and authorization before sending to FCM. */
export class NotificationReferences {
  readonly #options: NotificationReferenceOptions
  readonly #endpoint: URL
  readonly #ttl: number
  readonly #closed = new AbortController()
  readonly #pending = new Set<Promise<void>>()
  readonly #dispose: (() => boolean)[] = []
  #status: NotificationReferenceStatus

  constructor(ctx: Context, options: NotificationReferenceOptions) {
    this.#options = options
    this.#endpoint = new URL('/v1/push/references', options.origin)
    this.#ttl = (options.ttlSeconds ?? 300) * 1000
    this.#status = options.enabled === true ? 'idle' : 'disabled'
    if (this.#status === 'disabled') return
    const origin = new URL(options.origin)
    const loopback = options.allowLoopbackHttp && ['127.0.0.1', '[::1]'].includes(origin.hostname)
    if (origin.origin !== options.origin || origin.username || origin.password || origin.protocol !== 'https:' && !(loopback && origin.protocol === 'http:') ||
        options.credentials.origin && options.credentials.origin !== options.origin || !uuid.test(options.runtimeId) ||
        !Number.isSafeInteger(this.#ttl) || this.#ttl < 1000 || this.#ttl > 900_000) throw new Error('notification references configuration invalid')
    this.#dispose.push(ctx.on('session/event', (session, event) => {
      if (event.type === 'approval/asked') this.emit(session.id, 'interaction', event.time)
      else if (event.type === 'turn/end' && event.data.reason.kind === 'completed') this.emit(session.id, 'completed', event.time)
    }))
    // Observe the installed answerer waterfall without claiming or delaying it.
    // Prepend also observes requests already handled by a connected native UI.
    this.#dispose.push(ctx.on('user-questions/request', (request, next) => {
      if (request.agent && !request.signal?.aborted) this.emit(request.agent.id, 'interaction')
      return next()
    }, { prepend: true }))
  }

  status(): NotificationReferenceStatus { return this.#status }

  emit(sessionId: string, kind: 'interaction' | 'completed', occurredAt = Date.now()): void {
    if (this.#status === 'disabled' || this.#closed.signal.aborted) return
    if (typeof sessionId !== 'string' || sessionId.length < 1 || sessionId.length > 256 || /[\x00-\x1f\x7f]/.test(sessionId) ||
        kind !== 'interaction' && kind !== 'completed' || !Number.isSafeInteger(occurredAt) || occurredAt > Date.now()) {
      this.#status = 'unavailable'; return
    }
    const expiresAt = occurredAt + this.#ttl
    if (expiresAt <= Date.now()) { this.#status = 'expired'; return }
    if (this.#pending.size >= 16) { this.#status = 'unavailable'; return }
    const body = JSON.stringify({ notification_id: randomUUID(), runtime_id: this.#options.runtimeId,
      session_id: sessionId, kind, expires_at: new Date(expiresAt).toISOString() })
    const task = this.#send(body, expiresAt).catch(() => { if (!this.#closed.signal.aborted) this.#status = 'unavailable' })
    this.#pending.add(task)
    void task.finally(() => { this.#pending.delete(task) })
  }

  async #send(body: string, expiresAt: number): Promise<void> {
    const signal = AbortSignal.any([this.#closed.signal, ...(this.#options.credentials.signal ? [this.#options.credentials.signal] : []), AbortSignal.timeout(15_000)])
    const current = () => {
      signal.throwIfAborted()
      if (expiresAt <= Date.now()) { this.#status = 'expired'; return false }
      return true
    }
    const credential = await this.#options.credentials.acquire(signal)
    if (!current()) return
    let nonce: string | undefined
    for (let attempt = 0; attempt < 2; attempt++) {
      const proof = await installationDPoP(credential, 'POST', this.#endpoint, nonce)
      if (!current()) return
      const response = await fetch(this.#endpoint, { method: 'POST', body,
        headers: { authorization: `DPoP ${credential.credential}`, dpop: proof, 'content-type': 'application/json',
          accept: 'application/json', 'accept-encoding': 'identity', 'cache-control': 'no-store', [restEnvelopeHeader]: restEnvelopeVersion },
        signal, redirect: 'error', credentials: 'omit', cache: 'no-store' })
      const value = await this.#read(response)
      if (!current()) return
      const denied = failure.safeParse(value)
      const challenge = response.headers.get('dpop-nonce')
      if (response.status === 401 && attempt === 0 && challenge && noncePattern.test(challenge) &&
          /^DPoP error="use_dpop_nonce"$/i.test(response.headers.get('www-authenticate') ?? '') && denied.success && denied.data.error.code === 'auth.proof_invalid') {
        nonce = challenge; continue // Only the pre-operation authentication challenge permits replay.
      }
      this.#status = response.ok && accepted.safeParse(value).success ? 'accepted'
        : response.status === 501 && denied.success && denied.data.error.code === 'push.unsupported' ? 'unsupported'
        : response.status === 401 || response.status === 403 ? 'auth_required' : 'unavailable'
      return
    }
  }

  async #read(response: Response): Promise<Record<string, unknown>> {
    if (!response.body || response.headers.get('content-type')?.split(';')[0]?.trim() !== 'application/json') {
      await response.body?.cancel(); throw new Error('notification response invalid')
    }
    const reader = response.body.getReader()
    const chunks: Uint8Array[] = []
    let size = 0
    try {
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        size += value.byteLength
        if (size > 4096) throw new Error('notification response invalid')
        chunks.push(value)
      }
    } finally { await reader.cancel(); reader.releaseLock() }
    const value: unknown = JSON.parse(Buffer.concat(chunks).toString('utf8'))
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('notification response invalid')
    return normalizeRestEnvelope(value as Record<string, unknown>, response.headers)
  }

  async close(): Promise<void> {
    for (const dispose of this.#dispose) dispose()
    this.#dispose.length = 0
    this.#closed.abort()
    await Promise.all(this.#pending)
  }
}
