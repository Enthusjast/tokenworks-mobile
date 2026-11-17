import { Context } from '@deepseek-ai/cordis'
import { installConnection, type ConnectionWebSocket } from '@deepseek-ai/dsh-client-connection/client'
import { apply as installGateway, type ClientRemote } from '@deepseek-ai/dsh-api-gateway/client'
import { apply as installRegistry } from '@deepseek-ai/dsh-typert-registry/client'
import { TYPERT_REMOTE as sessionRemote } from '@deepseek-ai/dsh-api-session-controller/remote'
import { TYPERT_REMOTE as workspaceRemote } from '@deepseek-ai/dsh-api-workspace-controller/remote'
import { TYPERT_REMOTE as workspaceFilesRemote } from '@deepseek-ai/dsh-api-workspace-files/remote'
import { TYPERT_REMOTE as fileUploadsRemote } from '@deepseek-ai/dsh-client-file-upload/remote'
import { request, type IncomingMessage } from 'node:http'
import type { Socket } from 'node:net'
import WebSocket from 'ws'
import { TextDecoder } from 'node:util'
import { dialNoiseCarrier, type AuthenticatedNoiseStream, type NoiseReady } from './remote-noise-initiator.js'
import { dialRemoteOuter, remoteOrigin } from './remote-network.js'
import type { RemoteTarget, RemoteCredential } from './remote-targets.js'
import { WireEncoder, WireDecoder, WIRE_FRAME_BYTES, WIRE_LOGICAL_BYTES, WIRE_QUEUE_BYTES, WIRE_QUEUE_COUNT } from './wire-codec.js'
import { bindingDigest } from './lan-pairing-store.js'

export interface RemoteNativeClient {
  readonly remote: ClientRemote
  readonly ready: Readonly<NoiseReady>
  fetch(path: string, options?: { method?: string; body?: BodyInit | null; signal?: AbortSignal }): Promise<Response>
  close(): Promise<void>
}
type SocketEvents = { open: object; error: object; close: object; message: { readonly data: unknown } }
type Listener = { callback: (event: never) => void; once: boolean }
const resident = new Set<RemoteNativeSocket>()

/** Public Connection socket adapter. Wire framing is not a second Gateway mux parser. */
export class RemoteNativeSocket implements ConnectionWebSocket {
  #state = 0; #socket?: WebSocket; #carrier?: AuthenticatedNoiseStream
  readonly #listeners = new Map<keyof SocketEvents, Set<Listener>>()
  readonly #encoder = new WireEncoder()
  readonly #decoder = new WireDecoder(() => this.#fail())
  readonly #queue: { text: string; bytes: number }[] = []
  #outbound = 0; #delivered = 0; #receivedCount = 0; #writing = false
  #currentBytes = 0; #encodingBytes = 0; #frameBytes = 0
  readonly #opened = Promise.withResolvers<AuthenticatedNoiseStream>()
  readonly opened = this.#opened.promise
  constructor(dial: () => Promise<AuthenticatedNoiseStream>) {
    resident.add(this)
    // Rejections are still observed by connectRemoteNative, but disposal may precede its await.
    void this.opened.catch(() => {})
    void (async () => {
      try {
        const carrier = await dial(); this.#carrier = carrier
        if (this.#state !== 0) { carrier.close(); return }
        const socket = new WebSocket('ws://dsh-api/api/remote.mux', { createConnection: () => carrier.stream as Socket, perMessageDeflate: false, maxPayload: WIRE_FRAME_BYTES, handshakeTimeout: 15_000, followRedirects: false })
        this.#socket = socket
        socket.on('open', () => { if (this.#state !== 0) return; this.#state = 1; this.#opened.resolve(carrier); this.#emit('open', {}) })
        socket.on('error', () => this.#fail())
        socket.on('close', () => this.close())
        socket.on('message', (bytes, binary) => {
          try {
            if (this.#state !== 1 || binary) throw new Error('remote/wire-frame')
            const buffer = Array.isArray(bytes) ? Buffer.concat(bytes) : bytes instanceof ArrayBuffer ? Buffer.from(bytes) : bytes
            const text = new TextDecoder('utf-8', { fatal: true }).decode(buffer), logical = this.#decoder.decode(text)
            this.#budget()
            if (logical !== undefined) {
              // rc.5's private SDK inbox exposes no dequeue hook. Cumulative admission
              // conservatively bounds everything it could retain; never reset on send.
              this.#delivered += Buffer.byteLength(logical); this.#receivedCount++
              this.#budget()
              this.#emit('message', { data: logical })
            }
          } catch { this.#fail() }
        })
      } catch { this.#fail() }
    })()
  }
  get readyState(): number { return this.#state }
  #emit<Event extends keyof SocketEvents>(name: Event, event: SocketEvents[Event]): void {
    for (const listener of [...this.#listeners.get(name) ?? []]) {
      if (listener.once) this.#listeners.get(name)?.delete(listener)
      listener.callback(event as never)
    }
  }
  addEventListener<Event extends keyof SocketEvents>(name: Event, callback: (event: SocketEvents[Event]) => void, options?: { readonly once?: boolean }): void {
    let listeners = this.#listeners.get(name)
    if (!listeners) { listeners = new Set(); this.#listeners.set(name, listeners) }
    if (![...listeners].some(listener => listener.callback === callback)) listeners.add({ callback: callback as (event: never) => void, once: options?.once === true })
  }
  removeEventListener<Event extends keyof SocketEvents>(name: Event, callback: (event: SocketEvents[Event]) => void): void {
    for (const listener of this.#listeners.get(name) ?? []) if (listener.callback === callback) this.#listeners.get(name)?.delete(listener)
  }
  #budget(): void {
    let total = 0
    for (const item of resident) {
      const bytes = item.#outbound + item.#encodingBytes + item.#frameBytes + item.#delivered + item.#decoder.bufferedBytes + (item.#socket?.bufferedAmount ?? 0) +
        (item.#carrier?.stream.readableLength ?? 0) + (item.#carrier?.stream.writableLength ?? 0)
      if (bytes > WIRE_QUEUE_BYTES || item.#queue.length > WIRE_QUEUE_COUNT || item.#receivedCount > WIRE_QUEUE_COUNT) throw new Error('remote/resource-limit')
      total += bytes
    }
    if (total > 32 * 1024 * 1024) throw new Error('remote/resource-limit')
  }
  #fail(): void {
    if (this.#state === 3) return
    this.#opened.reject(new Error('remote/carrier-or-resource-failure'))
    this.#emit('error', {}); this.close()
  }
  send(text: string): void {
    const bytes = Buffer.byteLength(text)
    if (this.#state !== 1 || bytes > WIRE_LOGICAL_BYTES) throw new Error('remote/wire-send')
    this.#queue.push({ text, bytes }); this.#outbound += bytes
    try { this.#budget() } catch (error) { this.#fail(); throw error }
    if (this.#writing) return
    this.#writing = true
    void (async () => {
      try {
        while (this.#queue.length && this.#state === 1) {
          const item = this.#queue[0]!
          this.#currentBytes = item.bytes; this.#encodingBytes = item.bytes
          for (const frame of this.#encoder.encode(item.text)) {
            this.#frameBytes = Buffer.byteLength(frame)
            this.#budget()
            const written = Promise.withResolvers<void>(), timer = setTimeout(() => { written.reject(new Error('remote/write-timeout')); this.#fail() }, 30_000); timer.unref()
            try { this.#socket!.send(frame, error => error ? written.reject(error) : written.resolve()); await written.promise } finally { clearTimeout(timer) }
          }
          this.#currentBytes = 0; this.#encodingBytes = 0; this.#frameBytes = 0
          if (this.#state === 1) { this.#queue.shift(); this.#outbound -= item.bytes }
        }
      } catch { this.#fail() }
      finally { this.#writing = false; this.#currentBytes = 0; this.#encodingBytes = 0; this.#frameBytes = 0; if (this.#state === 3) { this.#outbound = 0; resident.delete(this) } }
    })()
  }
  close(): void {
    if (this.#state === 3) return
    this.#state = 3; this.#decoder.close(); this.#queue.length = 0; this.#outbound = this.#writing ? this.#currentBytes : 0
    this.#opened.reject(new Error('remote/closed')); this.#socket?.terminate(); this.#carrier?.close(); if (!this.#writing) resident.delete(this)
    this.#emit('close', {}); this.#listeners.clear()
  }
}

const contributions = [sessionRemote, workspaceRemote, workspaceFilesRemote, fileUploadsRemote]
const unaryRoutes = new Set(contributions.flatMap(info => info.descriptors.filter(descriptor => descriptor.mode !== 'stream').map(descriptor => `/api/${descriptor.namespace}/${descriptor.method}`)))

/** A file version response may change only the version, never its authority or path. */
export function nativeFileVersionPath(path: string, location: string | null): string {
  if (!location?.startsWith('?') || location.length > 16_384 || /[\\\r\n#]/.test(location)) throw new Error('remote/native-file-version')
  const original = new URL(path, 'http://dsh-api'), next = new URL(location, original)
  const keys = [...next.searchParams.keys()]
  if (original.pathname !== '/api/file' || next.origin !== original.origin || next.pathname !== original.pathname ||
    keys.length !== 2 || next.searchParams.getAll('path').length !== 1 || next.searchParams.getAll('v').length !== 1 ||
    !original.searchParams.get('path') || next.searchParams.get('path') !== original.searchParams.get('path') ||
    !next.searchParams.get('v') || next.searchParams.get('v') === original.searchParams.get('v')) throw new Error('remote/native-file-version')
  return next.pathname + next.search
}

/** All business bytes use standard HTTP/WS libraries on authenticated Noise Duplexes. */
export async function connectRemoteNative(target: RemoteTarget, credential: RemoteCredential, signal: AbortSignal): Promise<RemoteNativeClient> {
  signal.throwIfAborted(); remoteOrigin(target)
  if (target.status !== 'paired' || target.ownerSha256 !== bindingDigest(credential.manifest.owner) || target.origin !== target.descriptor.origin || target.tlsPin !== target.descriptor.tlsPin) throw new Error('remote/target-binding')
  const lifetime = new AbortController(), ownerSignal = AbortSignal.any([signal, lifetime.signal]), ctx = new Context()
  const carriers = new Set<AuthenticatedNoiseStream>(), sockets = new Set<RemoteNativeSocket>()
  let generation: string | undefined, mux: RemoteNativeSocket | undefined, unary = 0, closed = false
  const dial = async (requestSignal: AbortSignal): Promise<AuthenticatedNoiseStream> => {
    ownerSignal.throwIfAborted()
    const bounded = AbortSignal.any([ownerSignal, requestSignal])
    const carrier = await dialNoiseCarrier({ expected: target.descriptor, credential, purpose: 'native', signal: bounded,
      dialOuter: outerSignal => dialRemoteOuter(target, credential, 'native', outerSignal) })
    if (generation !== undefined && generation !== carrier.ready.peerGeneration) { carrier.close(); lifetime.abort(); throw new Error('remote/peer-generation-changed') }
    generation = carrier.ready.peerGeneration; carriers.add(carrier)
    carrier.stream.once('close', () => carriers.delete(carrier)); return carrier
  }
  const close = async (): Promise<void> => {
    if (closed) return
    closed = true; lifetime.abort(); signal.removeEventListener('abort', aborted)
    for (const socket of sockets) socket.close()
    for (const carrier of carriers) carrier.close()
    carriers.clear(); sockets.clear(); await ctx.fiber.dispose()
  }
  const aborted = () => { void close() }
  signal.addEventListener('abort', aborted, { once: true })
  async function fetchOnce(input: string | URL, init: RequestInit = {}): Promise<Response> {
    ownerSignal.throwIfAborted()
    const raw = String(input)
    if (!/^(?:\/)?api\//.test(raw) || /[\\\r\n#]/.test(raw)) throw new Error('remote/native-route')
    const url = new URL(raw.startsWith('/') ? raw : `/${raw}`, 'http://dsh-api'), method = (init.method ?? 'GET').toUpperCase()
    const file = url.pathname === '/api/file' && (method === 'GET' || method === 'HEAD')
    const upload = url.pathname === '/api/session/uploadFileBinary' && method === 'POST'
    if (url.origin !== 'http://dsh-api' || !(file || upload || method === 'POST' && !url.search && unaryRoutes.has(url.pathname)) || /%(?:2f|5c|00)/i.test(url.pathname)) throw new Error('remote/native-route')
    if (unary >= 2) throw new Error('remote/connection-limit')
    unary++
    const requestSignal = AbortSignal.any([ownerSignal, init.signal ?? ownerSignal, AbortSignal.timeout(file || upload ? 120_000 : 30_000)])
    let carrier: AuthenticatedNoiseStream | undefined, incoming: IncomingMessage | undefined, released = false
    const release = () => { if (released) return; released = true; unary--; carrier?.close() }
    try {
      const cap = upload ? 32 * 1024 * 1024 : 1024 * 1024
      const headers: Record<string, string> = { host: 'dsh-api', connection: 'close', 'accept-encoding': 'identity' }
      const supplied = new Headers(init.headers)
      supplied.forEach((value, key) => { if (key !== 'content-type') throw new Error('remote/native-header'); headers[key] = value })
      if (upload) headers['content-type'] = 'application/octet-stream'
      let body: Uint8Array | undefined
      if (init.body != null) {
        let bytes: Uint8Array
        if (typeof init.body === 'string') bytes = Buffer.from(init.body)
        else if (init.body instanceof Uint8Array) bytes = init.body
        else if (init.body instanceof ArrayBuffer) bytes = new Uint8Array(init.body)
        else if (init.body instanceof Blob) { if (init.body.size > cap) throw new Error('remote/request-limit'); bytes = new Uint8Array(await init.body.arrayBuffer()) }
        else throw new Error('remote/body-type')
        if (bytes.byteLength > cap || method === 'GET' || method === 'HEAD') throw new Error('remote/request-limit')
        body = bytes; headers['content-length'] = String(bytes.byteLength)
      }
      carrier = await dial(requestSignal)
      const pending = Promise.withResolvers<IncomingMessage>()
      const call = request({ host: 'dsh-api', port: 80, method, path: url.pathname + url.search, headers, maxHeaderSize: 32 * 1024, signal: requestSignal,
        createConnection: () => carrier!.stream as Socket }, pending.resolve)
      const headerDeadline = setTimeout(() => call.destroy(new Error('remote/header-timeout')), 15_000); headerDeadline.unref()
      call.once('error', pending.reject)
      void (async () => {
        try {
          if (body) for (let offset = 0; offset < body.length; offset += 32768) {
            const pendingWrite = Promise.withResolvers<void>()
            call.write(body.subarray(offset, offset + 32768), error => error ? pendingWrite.reject(error) : pendingWrite.resolve()); await pendingWrite.promise
          }
          call.end()
        } catch (error) { call.destroy(error as Error) }
      })()
      try { incoming = await pending.promise } finally { clearTimeout(headerDeadline) }
      const response = incoming, status = response.statusCode ?? 0, responseCap = file ? 32 * 1024 * 1024 : 1024 * 1024
      if (status < 200 || status >= 300 && status < 400 && !(file && status === 302) || response.headers['content-encoding'] && response.headers['content-encoding'] !== 'identity' || Number(response.headers['content-length'] ?? 0) > responseCap) throw new Error('remote/native-response')
      const outputHeaders = new Headers()
      for (let index = 0; index < response.rawHeaders.length; index += 2) {
        const name = response.rawHeaders[index]!, value = response.rawHeaders[index + 1]!
        if (!['set-cookie', 'connection', 'transfer-encoding', 'keep-alive'].includes(name.toLowerCase())) outputHeaders.append(name, value)
      }
      response.on('error', () => {}); response.once('close', release)
      if (method === 'HEAD' || [204, 205, 304].includes(status)) { response.resume(); response.once('end', release); return new Response(null, { status, headers: outputHeaders }) }
      let received = 0
      const iterator = response[Symbol.asyncIterator]()
      const bodyStream = new ReadableStream<Uint8Array>({
        async pull(controller) {
          const idle = setTimeout(() => call.destroy(new Error('remote/read-timeout')), 30_000); idle.unref()
          try {
            const next = await iterator.next()
            if (next.done) { if (!response.complete) throw new Error('remote/http-truncated'); controller.close(); release(); return }
            const bytes = next.value as Buffer; received += bytes.length
            if (received > responseCap) throw new Error('remote/response-limit')
            controller.enqueue(bytes)
          } catch (error) { controller.error(error); call.destroy(); release() }
          finally { clearTimeout(idle) }
        },
        cancel() { call.destroy(); release() },
      }, { highWaterMark: 0 })
      return new Response(bodyStream, { status, headers: outputHeaders })
    } catch (error) { incoming?.destroy(); release(); throw error }
  }
  async function fetchNative(input: string | URL, init: RequestInit = {}): Promise<Response> {
    const response = await fetchOnce(input, init)
    if (response.status !== 302) return response
    let versioned: string
    try { versioned = nativeFileVersionPath(String(input), response.headers.get('location')) }
    finally { await response.body?.cancel() }
    const result = await fetchOnce(versioned, init)
    if (result.status === 302) { await result.body?.cancel(); throw new Error('remote/native-file-version') }
    return result
  }
  try {
    installRegistry(ctx)
    installConnection(ctx, { interactions: 'none', location: { hostname: 'dsh-api' }, transport: { fetch: fetchNative, streamBaseUrl: 'http://dsh-api', createWebSocket(url) {
      if (url !== 'ws://dsh-api/api/remote.mux' || mux) throw new Error('remote/mux-route')
      mux = new RemoteNativeSocket(() => dial(ownerSignal)); sockets.add(mux); return mux
    } } })
    installGateway(ctx)
    for (const info of contributions) await ctx.remote.$mount(info)
    if (!mux) throw new Error('remote/mux-unavailable')
    const authenticated = await (mux as RemoteNativeSocket).opened
    ownerSignal.throwIfAborted()
    return { remote: ctx.remote, ready: authenticated.ready, fetch: (path, options = {}) => fetchNative(path, options), close }
  } catch (error) { await close(); throw error }
}
