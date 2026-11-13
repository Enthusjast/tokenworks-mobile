import { createHash, randomUUID } from 'node:crypto'
import { performServerHandshake, type ServerHttp2Session } from 'node:http2'
import { Duplex, Writable } from 'node:stream'
import { setTimeout as delay } from 'node:timers/promises'
import WebSocket, { createWebSocketStream } from 'ws'
import type { HostConnectionHandle } from '@deepseek-ai/dsh-client-connection'
import type { RemoteAccessService } from '@deepseek-ai/dsh-api-remote-access'
import { NativeAccess, type NativeResourceIds, type NativePeerMode } from './native-access.js'
import { NativeGateway } from './native-gateway.js'
import type { HostConnectionCredential, HostCredentialProvider } from './host-connection-credential.js'

export interface HostVersions {
  readonly dsh: string
  readonly application: string
  readonly plugin: string
}

/**
 * Close one tunnel transport without a GOAWAY write into a dead socket.
 * A session whose socket is already gone is left to the transport cleanup;
 * destroying it would assert inside Node's http2 core.
 */
function endTunnel(session: ServerHttp2Session | undefined, transport: Duplex | undefined, socket: WebSocket | undefined): void {
  // Node writes GOAWAY through the session's JavaScript stream socket, and its
  // assert is fatal for the Host once that socket is gone. Only a session whose
  // transport is still open may be destroyed; every other case is left to the
  // transport teardown below.
  if (session && !session.destroyed && transport && !transport.destroyed && !transport.writableEnded && socket?.readyState === WebSocket.OPEN) {
    try { session.destroy() } catch { /* the socket died first */ }
  }
  transport?.destroy()
  socket?.terminate()
}

export type NativeTunnelPhase = 'connecting' | 'online' | 'offline' | 'revoked' | 'auth_required' | 'unsupported' | 'stopped'
export interface NativeTunnelOptions {
  readonly origin: string
  readonly runtimeId: string
  /** Versions reported to the Relay on each tunnel attach. */
  readonly versions: HostVersions
  readonly credentials: HostCredentialProvider
  readonly connection: HostConnectionHandle
  readonly remoteAccess: RemoteAccessService
  readonly gateway: NativeGateway
  /** Live resource identities, read for every policy decision so a kind-wide grant never outlives its resource. */
  readonly resources: () => NativeResourceIds
  /** F-02: how admitted devices map onto dsh Peers; personal deployments use owner. */
  readonly peerMode?: NativePeerMode
}

export async function installationDPoP(credential: HostConnectionCredential, method: string, url: URL, nonce?: string): Promise<string> {
  const header = Buffer.from(JSON.stringify({ typ: 'dpop+jwt', alg: 'ES256', jwk: credential.publicJwk })).toString('base64url')
  const target = new URL(url); target.search = ''; target.hash = ''; if (target.protocol === 'wss:') target.protocol = 'https:'
  const payload = Buffer.from(JSON.stringify({ htm: method, htu: target.href, iat: Math.floor(Date.now() / 1000), jti: randomUUID(), ath: createHash('sha256').update(credential.credential).digest('base64url'), ...(nonce ? { nonce } : {}) })).toString('base64url')
  const input = `${header}.${payload}`
  const signature = await credential.signer.sign(Buffer.from(input))
  if (signature.byteLength !== 64) throw new Error('installation proof unavailable')
  return `${input}.${Buffer.from(signature).toString('base64url')}`
}

export class RelayTunnel {
  readonly #cancel = new AbortController()
  #socket: WebSocket | undefined
  #session: ServerHttp2Session | undefined
  #transport: Duplex | undefined
  #access: NativeAccess | undefined
  #phase: NativeTunnelPhase = 'connecting'
  readonly done: Promise<void>
  constructor(readonly options: NativeTunnelOptions) {
    const origin = new URL(options.origin)
    if (origin.protocol !== 'https:' || origin.username || origin.password || origin.pathname !== '/' || origin.search || origin.hash) throw new Error('Relay origin must be HTTPS')
    if (!/^[0-9a-f-]{36}$/i.test(options.runtimeId)) throw new Error('runtime identity invalid')
    this.done = this.#run()
  }
  status(): NativeTunnelPhase { return this.#phase }

  async #run(): Promise<void> {
    const signal = this.options.credentials.signal ? AbortSignal.any([this.#cancel.signal, this.options.credentials.signal]) : this.#cancel.signal
    let attempts = 0
    while (!signal.aborted) {
      try {
        await this.#connect(signal)
        attempts = 0
      } catch (error) {
        console.error('tunnel: connect failed', error)
        if (signal.aborted || ['revoked', 'auth_required', 'unsupported'].includes(this.#phase)) break
        this.#phase = 'offline'
      } finally {
        // A live session must be closed before its transport: ending the
        // transport first makes http2 write a GOAWAY into the dead socket,
        // which asserts inside Node and kills the Host process.
        endTunnel(this.#session, this.#transport, this.#socket)
        this.#access?.close()
        this.#session = undefined; this.#transport = undefined; this.#socket = undefined; this.#access = undefined
      }
      if (signal.aborted) break
      try { await delay(Math.min(30_000, 1000 * 2 ** Math.min(attempts++, 5)) + Math.floor(Math.random() * 500), undefined, { signal }) } catch { break }
    }
    if (!['revoked', 'auth_required', 'unsupported'].includes(this.#phase)) this.#phase = 'stopped'
  }

  async #connect(signal: AbortSignal): Promise<void> {
    this.#phase = 'connecting'
    const credential = await this.options.credentials.acquire(signal)
    const target = new URL(`/v2/runtimes/${this.options.runtimeId}/tunnel`, this.options.origin)
    let nonce: string | undefined
    let socket: WebSocket | undefined
    for (let challenge = 0; challenge < 2; challenge++) {
      const proof = await installationDPoP(credential, 'GET', target, nonce)
      signal.throwIfAborted()
      const endpoint = new URL(target); endpoint.protocol = 'wss:'
      const opening = Promise.withResolvers<WebSocket>()
      socket = new WebSocket(endpoint, { headers: {
        Authorization: `DPoP ${credential.credential}`, DPoP: proof,
        'X-TokenWorks-Dsh-Version': this.options.versions.dsh,
        'X-TokenWorks-Application-Version': this.options.versions.application,
        'X-TokenWorks-Plugin-Version': this.options.versions.plugin,
      }, followRedirects: false, handshakeTimeout: 15_000, maxPayload: 1024 * 1024, perMessageDeflate: false })
      this.#socket = socket
      let challenged = false
      socket.once('unexpected-response', (_request, response) => {
        const next = response.headers['dpop-nonce']
        if (response.statusCode === 401 && typeof next === 'string' && challenge === 0) { nonce = next; challenged = true }
        else this.#phase = response.statusCode === 403 ? 'revoked' : response.statusCode === 426 ? 'unsupported' : 'auth_required'
        response.resume(); socket?.terminate(); opening.reject(new Error('tunnel admission refused'))
      })
      socket.once('open', () => opening.resolve(socket!))
      socket.once('error', error => opening.reject(error))
      const abort = () => { socket?.terminate(); opening.reject(new Error('tunnel cancelled')) }
      signal.addEventListener('abort', abort, { once: true })
      try { await opening.promise; break }
      catch (error) { if (!challenged) throw error }
      finally { signal.removeEventListener('abort', abort) }
    }
    if (!socket || socket.readyState !== WebSocket.OPEN) throw new Error('tunnel unavailable')
    signal.throwIfAborted()
    const active = socket
    const transport = createWebSocketStream(active)
    this.#transport = transport
    const output = new Writable({ write(chunk: Buffer, _encoding, callback) {
      void (async () => {
        for (let offset = 0; offset < chunk.length; offset += 64 * 1024) {
          const flushed = Promise.withResolvers<void>()
          transport.write(chunk.subarray(offset, offset + 64 * 1024), error => error ? flushed.reject(error) : flushed.resolve())
          await flushed.promise
        }
      })().then(() => callback(), callback)
    }, final(callback) { transport.end(callback) }, destroy(error, callback) { transport.destroy(error ?? undefined); callback(error) } })
    const duplex = Duplex.from({ readable: transport, writable: output })
    const session = performServerHandshake(duplex, { settings: { maxConcurrentStreams: 128, maxHeaderListSize: 32 * 1024 } })
    this.#session = session
    const access = new NativeAccess(this.options.connection, this.options.remoteAccess, this.options.runtimeId, credential.installationId, async (id, secret, lifetime) => {
      const current = await this.options.credentials.acquire(lifetime)
      if (current.installationId !== credential.installationId) throw new Error('installation changed')
      const endpoint = new URL(`/v2/tunnel-leases/${id}`, this.options.origin)
      let challenge: string | undefined
      for (let attempt = 0; attempt < 2; attempt++) {
        const proof = await installationDPoP(current, 'GET', endpoint, challenge)
        const result = await fetch(endpoint, { headers: { Authorization: `DPoP ${current.credential}`, DPoP: proof, 'Proxy-Authorization': `Bearer ${secret}` }, redirect: 'error', signal: AbortSignal.any([lifetime, signal, AbortSignal.timeout(15_000)]) })
        if (result.status === 401 && attempt === 0 && result.headers.has('DPoP-Nonce')) { challenge = result.headers.get('DPoP-Nonce')!; await result.body?.cancel(); continue }
        if (!result.ok) { await result.body?.cancel(); throw new Error('lease introspection refused') }
        const text = await result.text()
        if (Buffer.byteLength(text) > 128 * 1024) throw new Error('lease introspection limit')
        return JSON.parse(text) as unknown
      }
      throw new Error('lease introspection unavailable')
    }, this.options.resources, this.options.peerMode ?? 'owner')
    this.#access = access
    let count = 0
    session.on('stream', (stream, headers) => {
      const reject = (status: number) => { if (!stream.destroyed) { stream.respond({ ':status': status }); stream.end() } }
      if (headers[':method'] !== 'CONNECT' || headers[':authority'] !== 'dsh-api:80' || headers[':path'] !== undefined || headers[':protocol'] !== undefined) { reject(403); return }
      if (count >= 128) { reject(429); return }
      const id = headers['x-tokenworks-lease-id'], authorization = headers['proxy-authorization']
      if (typeof id !== 'string' || typeof authorization !== 'string' || !authorization.startsWith('Bearer ')) { reject(401); return }
      count++
      void (async () => {
        try {
          const admission = await access.admit(id, authorization.slice(7))
          if (stream.destroyed) { admission.release(); return }
          stream.once('close', admission.release)
          stream.once('error', () => {})
          stream.respond({ ':status': 200 })
          this.options.gateway.accept(stream, admission.peer)
        } catch (error) { console.error('tunnel: admission failed', error); reject(401) }
      })()
      stream.once('close', () => { count-- })
    })
    let alive = true
    active.on('pong', () => { alive = true })
    active.on('message', (_data, binary) => { if (!binary) active.close(1003, 'binary required') })
    const heartbeat = setInterval(() => { if (!alive) active.terminate(); else { alive = false; active.ping() } }, 20_000)
    heartbeat.unref()
    const ended = Promise.withResolvers<void>()
    const abort = () => { transport.destroy(); active.terminate() }
    signal.addEventListener('abort', abort, { once: true })
    session.once('close', ended.resolve)
    session.once('error', ended.reject)
    active.once('close', ended.resolve)
    active.on('error', ended.reject)
    this.#phase = 'online'
    try { await ended.promise }
    finally {
      clearInterval(heartbeat); signal.removeEventListener('abort', abort); access.close()
      endTunnel(session, transport, active)
      if (this.#transport === transport) this.#transport = undefined
      if (this.#session === session) this.#session = undefined
    }
  }

  async close(): Promise<void> {
    this.#cancel.abort()
    endTunnel(this.#session, this.#transport, this.#socket)
    this.#session = undefined; this.#transport = undefined; this.#socket = undefined
    await this.done
  }
}
