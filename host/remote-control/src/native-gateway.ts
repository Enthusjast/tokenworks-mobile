import { WireDecoder, WireEncoder, WireError, WIRE_FRAME_BYTES, WIRE_LOGICAL_BYTES, WIRE_QUEUE_BYTES, WIRE_QUEUE_COUNT } from './wire-codec.js'
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
  readonly #websockets = new WebSocketServer({ noServer: true, maxPayload: WIRE_FRAME_BYTES, perMessageDeflate: false })
  readonly #peers = new WeakMap<object, PeerScope>()
  readonly #upgraded = new WeakSet<object>()
  readonly #fetch: ConnectionFetchHandler
  readonly #sockets = new Set<Duplex>()
  readonly #gateway: TypertGateway
  readonly #muxSockets = new Set<WebSocket>()

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
      this.#upgraded.add(request.socket)
      this.#websockets.handleUpgrade(request, socket, head, ws => this.#mux(ws, peer))
    })
  }

  accept(socket: Duplex, peer: PeerScope): void {
    if (peer.signal.aborted) { socket.destroy(); return }
    this.#peers.set(socket, peer)
    this.#sockets.add(socket)
    const revoked = () => { if (!this.#upgraded.has(socket)) socket.destroy(new Error('native/revoked')) }
    peer.signal.addEventListener('abort', revoked, { once: true })
    socket.once('close', () => { this.#sockets.delete(socket); peer.signal.removeEventListener('abort', revoked) })
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
      if (result.body) await pipeline(
        Readable.fromWeb(result.body as NodeReadableStream<Uint8Array>),
        async function* (source: AsyncIterable<Uint8Array>) {
          // Native file bodies may arrive as one large chunk. Bound each write
          // before ServerResponse queues it on the encrypted carrier.
          for await (const chunk of source) {
            for (let offset = 0; offset < chunk.length; offset += 32 * 1024) yield chunk.subarray(offset, offset + 32 * 1024)
          }
        },
        response,
      )
      else response.end()
    } catch {
      if (!response.headersSent) response.writeHead(502).end()
      else response.destroy()
    } finally { response.off('close', disconnected) }
  }

  #mux(socket: WebSocket, peer: PeerScope): void {
    type Stream = { cancel: AbortController; inbox: { value: unknown; bytes: number }[]; bytes: number; ended: boolean; wake?: () => void }
    type Output = { text: string; bytes: number; signal: AbortSignal; resolve: () => void; reject: (error: unknown) => void }
    const streams = new Map<string, Stream>()
    const outputs: Output[] = []
    const encoder = new WireEncoder()
    const lifetime = new AbortController()
    let bytes = 0, count = 0, writing = false, closed = false
    this.#muxSockets.add(socket)
    const dispose = () => {
      if (closed) return
      closed = true
      lifetime.abort()
      decoder.close()
      for (const stream of streams.values()) { stream.cancel.abort(); stream.wake?.(); stream.inbox.length = 0 }
      streams.clear()
      for (const output of outputs.splice(0)) output.reject(new WireError('wire/closed'))
      bytes = 0; count = 0
      this.#muxSockets.delete(socket)
      peer.signal.removeEventListener('abort', revoked)
      socket.close(1000)
    }
    const rejectPeer = (code: number, reason: string) => { socket.close(code, reason); dispose() }
    const revoked = () => rejectPeer(1008, 'wire/revoked')
    const decoder = new WireDecoder(error => rejectPeer(1008, error.code))
    const sendText = async (text: string): Promise<void> => {
      if (closed || socket.readyState !== socket.OPEN) throw new WireError('wire/closed')
      if (bytes + decoder.bufferedBytes + socket.bufferedAmount + Buffer.byteLength(text) > WIRE_QUEUE_BYTES) {
        rejectPeer(1013, 'wire/slow-peer')
        throw new WireError('wire/slow-peer')
      }
      const sent = Promise.withResolvers<void>()
      const aborted = () => { sent.reject(new WireError('wire/closed')) }
      const deadline = setTimeout(() => {
        rejectPeer(1013, 'wire/slow-peer')
        sent.reject(new WireError('wire/slow-peer'))
      }, 30_000)
      deadline.unref()
      lifetime.signal.addEventListener('abort', aborted, { once: true })
      try {
        socket.send(text, error => { if (error) sent.reject(new WireError('wire/write-failed')); else sent.resolve() })
        await sent.promise
      } finally {
        clearTimeout(deadline)
        lifetime.signal.removeEventListener('abort', aborted)
      }
    }
    const pump = async () => {
      if (writing) return
      writing = true
      try {
        while (outputs.length && !closed) {
          const output = outputs[0]!
          try {
            if (output.signal.aborted) throw new WireError('wire/cancelled')
            // Finish admitted frames even on stream cancellation: partial carriers cannot be interleaved.
            for (const text of encoder.encode(output.text)) await sendText(text)
            output.resolve()
          } catch (error) {
            output.reject(error)
            if (!(error instanceof WireError && error.code === 'wire/cancelled')) rejectPeer(1011, 'wire/write-failed')
          } finally {
            if (!closed) { outputs.shift(); bytes -= output.bytes; count-- }
          }
        }
      } finally { writing = false }
    }
    const send = (value: unknown, signal: AbortSignal): Promise<void> => {
      const text = JSON.stringify(value)
      const size = Buffer.byteLength(text)
      if (size > WIRE_LOGICAL_BYTES) return Promise.reject(new WireError('wire/logical-limit'))
      if (closed) return Promise.reject(new WireError('wire/closed'))
      if (bytes + decoder.bufferedBytes + socket.bufferedAmount + size > WIRE_QUEUE_BYTES ||
          count + (decoder.bufferedBytes > 0 ? 1 : 0) >= WIRE_QUEUE_COUNT) {
        rejectPeer(1013, 'wire/slow-peer')
        return Promise.reject(new WireError('wire/slow-peer'))
      }
      bytes += size; count++
      const pending = Promise.withResolvers<void>()
      outputs.push({ text, bytes: size, signal, resolve: pending.resolve, reject: pending.reject })
      void pump()
      return pending.promise
    }
    const release = (stream: Stream) => {
      for (const item of stream.inbox) { bytes -= item.bytes; count-- }
      stream.inbox.length = 0; stream.bytes = 0
      stream.cancel.abort(); stream.wake?.()
      if (closed) return
      for (let index = outputs.length - 1; index >= 0; index--) {
        if (writing && index === 0) continue
        const output = outputs[index]!
        if (output.signal.aborted) {
          outputs.splice(index, 1); bytes -= output.bytes; count--
          output.reject(new WireError('wire/cancelled'))
        }
      }
    }
    peer.signal.addEventListener('abort', revoked, { once: true })
    socket.once('close', dispose)
    socket.once('error', dispose)
    socket.on('message', (data, binary) => {
      if (closed) return
      if (binary) { rejectPeer(1003, 'wire/text-required'); return }
      try {
        const text = decoder.decode(data.toString())
        if (bytes + decoder.bufferedBytes + socket.bufferedAmount > WIRE_QUEUE_BYTES || (decoder.bufferedBytes > 0 && count >= WIRE_QUEUE_COUNT)) {
          rejectPeer(1013, 'wire/slow-peer'); return
        }
        if (text === undefined) return
        const message = parseRemoteStreamClientMessage(text)
        if (message.type === 'open') {
          if (streams.has(message.streamId) || streams.size >= 128) { rejectPeer(1008, 'wire/stream-limit'); return }
          const stream: Stream = { cancel: new AbortController(), inbox: [], bytes: 0, ended: false }
          streams.set(message.streamId, stream)
          const signal = AbortSignal.any([stream.cancel.signal, peer.signal, lifetime.signal])
          const uplink: AsyncIterable<unknown> = { async *[Symbol.asyncIterator]() {
            while (!signal.aborted) {
              if (stream.inbox.length) {
                const next = stream.inbox.shift()!
                stream.bytes -= next.bytes; bytes -= next.bytes; count--
                yield next.value
              } else if (stream.ended) return
              else {
                const pending = Promise.withResolvers<void>()
                stream.wake = pending.resolve
                if (signal.aborted) pending.resolve()
                await pending.promise
                delete stream.wake
              }
            }
          } }
          void (async () => {
            try {
              const source = await this.#gateway.wireStream.open(message.endpoint, message.payload, uplink, peer, signal)
              for await (const value of source) {
                if (signal.aborted || closed) break
                await send({ type: 'item', streamId: message.streamId, ...(value === undefined ? {} : { value }) }, signal)
              }
              if (!signal.aborted && !closed) await send({ type: 'end', streamId: message.streamId }, signal)
            } catch (error) {
              if (!signal.aborted && !closed) {
                const failure = error instanceof WireError
                  ? { code: error.code, message: error.code, details: {} }
                  : this.#gateway.wireStream.failure(error)
                await send({ type: 'error', streamId: message.streamId, error: failure }, signal).catch(() => rejectPeer(1011, 'wire/send-failed'))
              }
            } finally {
              if (!closed) release(stream)
              if (streams.get(message.streamId) === stream) streams.delete(message.streamId)
            }
          })()
        } else {
          const stream = streams.get(message.streamId)
          // Late frames for a locally completed/cancelled stream are harmless.
          if (!stream) return
          if (message.type === 'cancel') { release(stream); streams.delete(message.streamId) }
          else if (message.type === 'end') { stream.ended = true; stream.wake?.() }
          else {
            if (stream.ended) { rejectPeer(1008, 'wire/uplink-ended'); return }
            const size = Buffer.byteLength(text)
            if (stream.bytes + size > WIRE_QUEUE_BYTES || stream.inbox.length >= WIRE_QUEUE_COUNT ||
              bytes + size + socket.bufferedAmount > WIRE_QUEUE_BYTES || count >= WIRE_QUEUE_COUNT) {
              rejectPeer(1013, 'wire/slow-peer'); return
            }
            stream.bytes += size; bytes += size; count++
            stream.inbox.push({ value: message.value, bytes: size }); stream.wake?.()
          }
        }
      } catch (error) { rejectPeer(1008, error instanceof WireError ? error.code : 'wire/invalid-native-frame') }
    })
    if (peer.signal.aborted) revoked()
  }

  close(): void {
    for (const socket of this.#muxSockets) socket.terminate()
    for (const socket of this.#sockets) socket.destroy()
    this.#sockets.clear()
    this.#websockets.close()
    this.#server.close()
  }
}
