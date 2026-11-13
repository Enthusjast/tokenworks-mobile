import { type IncomingMessage, type ServerResponse } from 'node:http'

import { Duplex, Readable } from 'node:stream'
import { pipeline } from 'node:stream/promises'
import { createSecureServer, type Http2SecureServer } from 'node:http2'
import { WebSocketServer, type WebSocket } from 'ws'
import { timingSafeEqual } from 'node:crypto'
import type { HostConnectionHandle } from '@deepseek-ai/dsh-client-connection'
import type { RemoteAccessService } from '@deepseek-ai/dsh-api-remote-access'
import type { PeerScope } from '@deepseek-ai/dsh-typert-protocol'
import type { TypertGateway } from '@deepseek-ai/dsh-api-gateway'
import type { NativeGateway } from './native-gateway.js'
import { NativeAccess, type NativeResourceIds, type NativePeerMode } from './native-access.js'

export interface LanGatewayOptions {
  /** Bind address; a specific LAN IP by contract, never a wildcard. */
  readonly host: string
  readonly port: number
  readonly runtimeId: string
  readonly installationId: string
  readonly connection: HostConnectionHandle
  readonly remoteAccess: RemoteAccessService
  readonly gateway: NativeGateway
  readonly resources: () => NativeResourceIds
  /** PEM server certificate chain + key; the operator provisions both. */
  readonly certificate: { readonly cert: string; readonly key: string }
  /** The paired device secret (43 chars url-safe); requests must present it verbatim. */
  readonly deviceToken: () => string | undefined
  /** F-02: LAN pairs are personal devices; owner is the default and only tested mode. */
  readonly peerMode?: NativePeerMode
}

export type LanPhase = 'stopped' | 'listening' | 'closed'

/**
 * P1-5 LAN direct connection: one HTTPS/WSS server bound to a LAN IP that
 * serves the exact same protocol surface the Relay tunnel does — HTTP under
 * CONNECT-equivalent admission, mux over WebSocket — but pairs devices with a
 * shared secret instead of Relay leases. No Relay account is involved.
 */
export class LanGateway {
  readonly #options: LanGatewayOptions
  readonly #access: NativeAccess
  #fetch: ReturnType<HostConnectionHandle['createSharedFetchHandler']>
  readonly #server: Http2SecureServer
  readonly #websockets = new WebSocketServer({ noServer: true, maxPayload: 2 * 1024 * 1024, perMessageDeflate: false })
  readonly #peers = new WeakMap<object, PeerScope>()
  #phase: LanPhase = 'stopped'
  #connections = 0

  constructor(options: LanGatewayOptions) {
    if (!/^[0-9a-f-]{36}$/i.test(options.runtimeId)) throw new Error('runtime identity invalid')
    if (options.host === '0.0.0.0' || options.host === '::' || options.host === '') throw new Error('LAN gateway must bind a specific address')
    this.#options = options
    this.#access = new NativeAccess(options.connection, options.remoteAccess, options.runtimeId, options.installationId,
      // LAN pairs cannot introspect Relay leases; the paired token is the whole credential.
      async (id, secret) => this.#introspect(id, secret), options.resources, options.peerMode ?? 'owner')
    // F-12: one shared-fetch handler for the gateway's lifetime; requests reuse it.
    this.#fetch = options.connection.createSharedFetchHandler('/api')
    this.#server = createSecureServer({ allowHTTP1: true, key: options.certificate.key, cert: options.certificate.cert, settings: { maxConcurrentStreams: 128, maxHeaderListSize: 32 * 1024 } })
    this.#server.on('request', (request, response) => { void this.#request(request as unknown as IncomingMessage, response as unknown as ServerResponse) })
    this.#server.on('session', session => {
      session.on('stream', (stream, headers) => {
        const reject = (status: number) => { if (!stream.destroyed) { stream.respond({ ':status': status }); stream.end() } }
        if (headers[':method'] !== 'CONNECT' || headers[':authority'] !== 'dsh-api:80') { reject(403); return }
        if (this.#connections >= 128) { reject(429); return }
        const id = headers['x-tokenworks-device-id'], authorization = headers['proxy-authorization']
        if (typeof id !== 'string' || typeof authorization !== 'string' || !authorization.startsWith('Bearer ')) { reject(401); return }
        void this.#admit(id, authorization.slice(7)).then(admission => {
          if (stream.destroyed) { admission.release(); return }
          stream.once('close', admission.release)
          stream.respond({ ':status': 200 })
          this.#connections++
          stream.once('close', () => { this.#connections-- })
          this.#options.gateway.accept(stream, admission.peer)
        }).catch(() => reject(401))
      })
    })
    this.#server.on('upgrade', (request, socket, head) => {
      const token = this.#token(request)
      const id = singleHeader(request, 'x-tokenworks-device-id')
      if (!token) { socket.end('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n'); return }
      void this.#admit(id, token).then(admission => {
        this.#websockets.handleUpgrade(request, socket, head, ws => this.#mux(ws, admission))
      }).catch(() => socket.end('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n'))
    })
    this.#server.on('clientError', (_error, socket) => socket.destroy())
  }

  #token(request: IncomingMessage): string | undefined {
    const expected = this.#options.deviceToken()
    if (!expected || !/^[A-Za-z0-9_-]{43}$/.test(expected)) return undefined
    const presented = request.headers['authorization']?.startsWith('Bearer ')
      ? request.headers['authorization'].slice(7) : singleHeader(request, 'x-tokenworks-device-token')
    if (!presented || presented.length !== expected.length) return undefined
    return timingSafeEqual(Buffer.from(presented), Buffer.from(expected)) ? presented : undefined
  }

  /** LAN admission yields the same lease-shaped introspection NativeAccess expects. */
  async #introspect(id: string, secret: string): Promise<unknown> {
    const token = this.#options.deviceToken()
    if (!token || secret !== token || !/^[0-9a-f-]{36}$/i.test(id)) throw new Error('lan admission refused')
    // F-03: kind-level bindings (no id) covering the same methods the Relay
    // matrix grants, in the namespace/method syntax NativeAccess validates.
    // Owner peers bypass remoteAccess anyway; these bindings only document
    // the intended surface and satisfy the introspection schema.
    const sessionActions = ['session/list', 'session/search', 'session/create', 'session/follow', 'session/page',
      'session/prompt', 'session/cancel', 'session/updateQueue', 'session/selectModel', 'session/fork',
      'session/rename', 'session/attachment', 'session/projections', 'session/control', 'session/openWorkspacePath',
      'userQuestions/answer', 'userQuestions/attachWait']
    const workspaceActions = ['workspace/follow', 'workspace/archiveSession', 'workspace/unarchiveSession',
      'workspace/rename', 'workspace/delete', 'workspace/insertBefore', 'workspace/insertSessionBefore',
      'workspace/pinSession', 'workspace/unpinSession']
    const globalActions = ['remoteAccess/follow', 'session/modelCatalog', 'session/canOpenWorkspacePath',
      'session/workspacePathApplications', 'session/initializeDefaultModel', 'fileReferences/list', 'skills/list',
      'fileUploads/upload', 'directoryPicker/list', 'directoryPicker/createDirectory', 'directoryPicker/pick',
      'workspace/create', 'workspace/initializeDefault', 'terminal/list', 'terminal/create', 'terminal/close',
      'terminal/environment', 'terminal/rename', 'terminal/shells', 'terminal/retain']
    return {
      active: true, sub: 'lan-paired', aud: this.#options.runtimeId, exp: Math.floor(Date.now() / 1000) + 300,
      client_id: 'tokenworks-lan', device_id: id, runtime_id: this.#options.runtimeId, installation_id: this.#options.installationId,
      grant_version: 1, bindings: [
        { resource: { kind: 'global' }, actions: globalActions },
        { resource: { kind: 'session' }, actions: sessionActions },
        { resource: { kind: 'workspace' }, actions: workspaceActions },
      ],
    }
  }

  async #admit(id: string, secret: string) {
    return this.#access.admit(id, secret)
  }

  #mux(socket: WebSocket, admission: { peer: PeerScope; release(): void }): void {
    this.#peers.set(socket, admission.peer)
    socket.once('close', admission.release)
    // F-03: the mux is driven by NativeGateway.acceptMux — the same entry the
    // HTTP-upgrade path uses — not by feeding the WS into the HTTP server.
    this.#options.gateway.acceptMux(socket, admission.peer)
  }

  async #request(request: IncomingMessage, response: ServerResponse): Promise<void> {
    // Plain HTTP requests carry the paired token in the same header the mux does.
    const token = request.headers['authorization']?.startsWith('Bearer ')
      ? request.headers['authorization'].slice(7) : singleHeader(request, 'x-tokenworks-device-token')
    const expected = this.#options.deviceToken()
    if (!token || !expected || token.length !== expected.length || !timingSafeEqual(Buffer.from(token), Buffer.from(expected))) {
      response.writeHead(401).end(); return
    }
    const admission = await this.#admit(singleHeader(request, 'x-tokenworks-device-id') || '00000000-0000-4000-8000-000000000000', token)
      .catch(() => undefined)
    if (!admission) { response.writeHead(401).end(); return }
    try {
      if (request.url?.startsWith('/api/')) {
        await this.#proxy(request, response, admission)
        return
      }
      response.writeHead(404).end()
    } finally { admission.release() }
  }

  async #proxy(request: IncomingMessage, response: ServerResponse, admission: { peer: PeerScope }): Promise<void> {
    // The gateway owns the shared-fetch handler; expose LAN requests through it.
    const init: RequestInit & { duplex?: 'half' } = { method: request.method ?? 'GET', headers: lanHeaders(request), signal: admission.peer.signal }
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      init.body = Readable.toWeb(request) as ReadableStream<Uint8Array>
      init.duplex = 'half'
    }
    const result = await this.#options.connection.createSharedFetchHandler('/api').fetch(new Request(new URL(request.url ?? '/', 'http://dsh-api'), init), admission.peer)
    response.statusCode = result.status
    result.headers.forEach((value, key) => { if (key !== 'set-cookie') response.setHeader(key, value) })
    const cookies = result.headers.getSetCookie()
    if (cookies.length) response.setHeader('set-cookie', cookies)
    if (result.body) await pipeline(Readable.fromWeb(result.body as import('node:stream/web').ReadableStream<Uint8Array>), response)
    else response.end()
  }

  listen(): Promise<void> {
    const { promise, resolve, reject } = Promise.withResolvers<void>()
    this.#server.once('error', reject)
    this.#server.listen(this.#options.port, this.#options.host, () => { this.#phase = 'listening'; resolve() })
    return promise
  }

  phase(): LanPhase { return this.#phase }

  async close(): Promise<void> {
    this.#phase = 'closed'
    this.#access.close()
    await new Promise<void>(resolve => this.#server.close(() => resolve()))
  }
}

function singleHeader(request: IncomingMessage, name: string): string {
  const values = request.headers[name]
  if (Array.isArray(values)) return values.length === 1 ? values[0]! : ''
  return values ?? ''
}

function lanHeaders(request: IncomingMessage): Headers {
  const headers = new Headers()
  for (let i = 0; i < request.rawHeaders.length; i += 2) headers.append(request.rawHeaders[i]!, request.rawHeaders[i + 1]!)
  return headers
}
