import { randomUUID } from 'node:crypto'
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'
import type { Duplex } from 'node:stream'
import { Readable } from 'node:stream'
import { pipeline } from 'node:stream/promises'
import { WebSocketServer, type WebSocket } from 'ws'
import type { ConnectionFetchHandler, HostConnectionHandle } from '@deepseek-ai/dsh-client-connection'
import type { ReadableStream as NodeReadableStream } from 'node:stream/web'
import type { PeerScope } from '@deepseek-ai/dsh-typert-protocol'
import type { TypertGateway } from '@deepseek-ai/dsh-api-gateway'
import { parseRemoteStreamClientMessage, REMOTE_STREAM_MUX_PATH } from '@deepseek-ai/dsh-api-gateway/stream-protocol'

/** HTTP stays inside authenticated CONNECT streams; this server never listens on a port. */
export class NativeGateway {
  readonly #server = createServer({ maxHeaderSize: 32 * 1024 })
  readonly #websockets = new WebSocketServer({ noServer: true, maxPayload: 2 * 1024 * 1024, perMessageDeflate: false })
  readonly #peers = new WeakMap<object, PeerScope>()
  readonly #fetch: ConnectionFetchHandler
  readonly #sockets = new Set<Duplex>()
  readonly #gateway: TypertGateway

  constructor(connection: HostConnectionHandle, gateway: TypertGateway) {
    this.#fetch = connection.createSharedFetchHandler('/api')
    this.#gateway = gateway
    this.#server.requestTimeout = 0
    this.#server.headersTimeout = 15_000
    this.#server.on('request', (request, response) => { void this.#request(request, response) })
    this.#server.on('clientError', (_error, socket) => { socket.destroy() })
    this.#server.on('upgrade', (request, socket, head) => {
      const peer = this.#peers.get(request.socket)
      if (!peer || peer.signal.aborted || request.url?.split('?')[0] !== REMOTE_STREAM_MUX_PATH) {
        socket.end('HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n')
        return
      }
      this.#websockets.handleUpgrade(request, socket, head, ws => this.#mux(ws, peer))
    })
  }

  /** Shared mux entry for both the HTTP-upgrade path and the LAN gateway. */
  acceptMux(socket: WebSocket, peer: PeerScope): void {
    this.#mux(socket, peer)
  }

  accept(socket: Duplex, peer: PeerScope): void {
    if (peer.signal.aborted) { socket.destroy(); return }
    this.#peers.set(socket, peer)
    this.#sockets.add(socket)
    socket.once('close', () => this.#sockets.delete(socket))
    this.#server.emit('connection', socket)
  }

  async #request(request: IncomingMessage, response: ServerResponse): Promise<void> {
    const peer = this.#peers.get(request.socket)
    if (!peer || peer.signal.aborted) { response.writeHead(401).end(); return }
    const cancel = new AbortController()
    const disconnected = () => { if (!response.writableFinished) cancel.abort() }
    response.once('close', disconnected)
    try {
      if (!request.url?.startsWith('/api/')) { response.writeHead(404).end(); return }
      const headers = new Headers()
      for (let i = 0; i < request.rawHeaders.length; i += 2) headers.append(request.rawHeaders[i]!, request.rawHeaders[i + 1]!)
      const init: RequestInit & { duplex?: 'half' } = { method: request.method ?? 'GET', headers, signal: AbortSignal.any([cancel.signal, peer.signal]) }
      if (init.method !== 'GET' && init.method !== 'HEAD') {
        init.body = Readable.toWeb(request) as ReadableStream<Uint8Array>
        init.duplex = 'half'
      }
      const result = await this.#fetch.fetch(new Request(new URL(request.url, 'http://dsh-api'), init), peer)
      response.statusCode = result.status
      result.headers.forEach((value, key) => { if (key !== 'set-cookie') response.setHeader(key, value) })
      const cookies = result.headers.getSetCookie()
      if (cookies.length) response.setHeader('set-cookie', cookies)
      if (result.body) await pipeline(Readable.fromWeb(result.body as NodeReadableStream<Uint8Array>), response)
      else response.end()
    } catch {
      if (!response.headersSent) response.writeHead(502).end()
      else response.destroy()
    } finally { response.off('close', disconnected) }
  }

  #mux(socket: WebSocket, peer: PeerScope): void {
    type Stream = { cancel: AbortController; inbox: unknown[]; bytes: number; ended: boolean; wake?: () => void }
    const streams = new Map<string, Stream>()
    let closed = false
    // F-13: the Android mux parses frames with a 128 KiB budget; a larger item
    // would close the phone-side connection on every reconnect. Serialize,
    // check, and fragment oversized payloads into session-fragment items the
    // phone reassembles via SessionFragments.
    const sendItem = async (streamId: string, value: unknown): Promise<void> => {
      const direct = JSON.stringify(value === undefined ? { type: 'item', streamId } : { type: 'item', streamId, value })
      if (Buffer.byteLength(direct) <= 128 * 1024) { await sendText(direct); return }
      const encoded = Buffer.from(JSON.stringify(value), 'utf8').toString('base64')
      const chunkSize = 96 * 1024
      const count = Math.ceil(encoded.length / chunkSize)
      if (count > 64) { throw new Error('native frame too large to fragment') }
      for (let index = 0; index < count; index++) {
        const fragment = { type: 'session.fragment', fragment_id: randomUUID(), index, count,
          data: encoded.slice(index * chunkSize, (index + 1) * chunkSize) }
        await sendText(JSON.stringify({ type: 'item', streamId, value: { type: 'session.fragment', event: { ...fragment } } }))
      }
    }
    const send = async (value: unknown): Promise<void> => {
      if (closed || socket.readyState !== socket.OPEN) throw new Error('native mux closed')
      if (value && typeof value === 'object' && 'type' in value && (value as { type: string }).type === 'item' && 'streamId' in value) {
        const { streamId, ...rest } = value as { streamId: string; value?: unknown }
        await sendItem(streamId, rest.value)
        return
      }
      await sendText(JSON.stringify(value))
    }
    const sendText = async (text: string): Promise<void> => {
      if (closed || socket.readyState !== socket.OPEN) throw new Error('native mux closed')
      if (socket.bufferedAmount + Buffer.byteLength(text) > 4 * 1024 * 1024) { socket.close(1013, 'receiver too slow'); throw new Error('native mux backpressure') }
      const sent = Promise.withResolvers<void>()
      socket.send(text, error => error ? sent.reject(error) : sent.resolve())
      await sent.promise
    }
    const dispose = () => {
      if (closed) return
      closed = true
      for (const stream of streams.values()) { stream.cancel.abort(); stream.wake?.() }
      streams.clear()
      socket.close(1000)
      peer.signal.removeEventListener('abort', dispose)
    }
    peer.signal.addEventListener('abort', dispose, { once: true })
    socket.once('close', dispose)
    socket.once('error', dispose)
    socket.on('message', (data, binary) => {
      if (binary) { socket.close(1003, 'text required'); return }
      try {
        const message = parseRemoteStreamClientMessage(data.toString())
        if (message.type === 'open') {
          if (streams.has(message.streamId) || streams.size >= 128) { socket.close(1008, 'stream admission refused'); return }
          const stream: Stream = { cancel: new AbortController(), inbox: [], bytes: 0, ended: false }
          streams.set(message.streamId, stream)
          const signal = AbortSignal.any([stream.cancel.signal, peer.signal])
          const uplink: AsyncIterable<unknown> = { async *[Symbol.asyncIterator]() {
            while (!signal.aborted) {
              if (stream.inbox.length) {
                const next = stream.inbox.shift()
                stream.bytes -= Buffer.byteLength(JSON.stringify(next) ?? '')
                yield next
              } else if (stream.ended) return
              else {
                const pending = Promise.withResolvers<void>()
                stream.wake = pending.resolve
                if (signal.aborted) pending.resolve()
                await pending.promise
              }
            }
          } }
          void (async () => {
            try {
              const source = await this.#gateway.wireStream.open(message.endpoint, message.payload, uplink, peer, signal)
              for await (const value of source) {
                if (signal.aborted || closed) break
                await send({ type: 'item', streamId: message.streamId, ...(value === undefined ? {} : { value }) })
              }
              if (!signal.aborted && !closed) await send({ type: 'end', streamId: message.streamId })
            } catch (error) {
              if (!signal.aborted && !closed) await send({ type: 'error', streamId: message.streamId, error: this.#gateway.wireStream.failure(error) }).catch(dispose)
            } finally {
              stream.cancel.abort(); stream.wake?.()
              if (streams.get(message.streamId) === stream) streams.delete(message.streamId)
            }
          })()
        } else {
          const stream = streams.get(message.streamId)
          if (!stream) return
          if (message.type === 'cancel') { stream.cancel.abort(); stream.wake?.(); streams.delete(message.streamId) }
          else if (message.type === 'end') { stream.ended = true; stream.wake?.() }
          else {
            if (stream.ended) { socket.close(1008, 'uplink ended'); return }
            stream.bytes += Buffer.byteLength(JSON.stringify(message.value) ?? '')
            if (stream.bytes > 2 * 1024 * 1024 || stream.inbox.length >= 1024) { socket.close(1009, 'uplink limit'); return }
            stream.inbox.push(message.value); stream.wake?.()
          }
        }
      } catch { socket.close(1008, 'invalid native frame') }
    })
    if (peer.signal.aborted) dispose()
  }

  close(): void {
    for (const socket of this.#sockets) socket.destroy()
    this.#sockets.clear()
    this.#websockets.close()
    this.#server.close()
  }
}
