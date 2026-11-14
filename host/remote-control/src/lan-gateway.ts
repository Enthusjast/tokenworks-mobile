import { type IncomingMessage, type ServerResponse } from 'node:http'
import type { Duplex } from 'node:stream'
import type { AddressInfo } from 'node:net'
import { createSecureServer, type Http2SecureServer } from 'node:http2'
import { WebSocketServer, createWebSocketStream } from 'ws'
import type { HostConnectionHandle } from '@deepseek-ai/dsh-client-connection'
import type { NativeGateway } from './native-gateway.js'
import { purposeSchema, type LanPairing } from './lan-pairing.js'
import type { NoiseCarrier } from './noise-carrier.js'
import { parseSigningObject } from './canonical.js'

export interface LanGatewayOptions {
  readonly host: string; readonly port: number; readonly connection: HostConnectionHandle; readonly gateway: NativeGateway
  readonly certificate: { readonly cert: string; readonly key: string }; readonly pairing: LanPairing; readonly carrier: NoiseCarrier
}
export type LanPhase = 'stopped' | 'listening' | 'closed'

/** Public TLS contains only operator pairing bootstrap and an encrypted byte carrier. */
export class LanGateway {
  readonly #options: LanGatewayOptions; readonly #server: Http2SecureServer
  readonly #websockets = new WebSocketServer({ noServer: true, maxPayload: 256 * 1024, perMessageDeflate: false })
  readonly #sockets = new Set<Duplex>(); #phase: LanPhase = 'stopped'; #requests = 0
  constructor(options: LanGatewayOptions) {
    if (['0.0.0.0', '::', '', '[::]'].includes(options.host)) throw new Error('LAN gateway must bind a specific address')
    if (!Number.isInteger(options.port) || options.port < 0 || options.port > 65535) throw new Error('LAN port out of range')
    this.#options = options
    this.#server = createSecureServer({ allowHTTP1: true, key: options.certificate.key, cert: options.certificate.cert, minVersion: 'TLSv1.2', handshakeTimeout: 10_000,
      settings: { maxConcurrentStreams: 128, maxHeaderListSize: 32 * 1024 } })
    this.#server.maxConnections = 128
    this.#server.on('connection', socket => { this.#sockets.add(socket); socket.once('close', () => this.#sockets.delete(socket)) })
    this.#server.on('request', (request, response) => { void this.#request(request as unknown as IncomingMessage, response as unknown as ServerResponse) })
    this.#server.on('upgrade', (request, socket, head) => {
      if (request.url !== '/carrier' || request.method !== 'GET' || this.#phase !== 'listening' || this.#websockets.clients.size >= 128) { socket.end('HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n'); return }
      void (async () => {
        const record = await this.#authorize(request), purpose = purposeSchema.parse(request.headers['x-tokenworks-carrier-purpose'])
        if (socket.destroyed) return
        this.#websockets.handleUpgrade(request, socket, head, ws => {
          ws.on('message', (_data, binary) => { if (!binary) ws.terminate() })
          const stream = createWebSocketStream(ws, { highWaterMark: 64 * 1024 })
          void options.carrier.accept(stream, { routeKind: 'lan', purpose, pairId: record.deviceId })
        })
      })().catch(() => socket.end('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n'))
    })
    this.#server.on('clientError', (_error, socket) => socket.destroy())
  }
  async #authorize(request: IncomingMessage) {
    const id = request.headers['x-tokenworks-device-id'], authorization = request.headers.authorization, proof = request.headers.dpop
    if (typeof id !== 'string' || typeof authorization !== 'string' || typeof proof !== 'string') throw new Error('LAN proof required')
    return this.#options.pairing.verifyCredential(id, authorization, proof, request.method ?? 'GET', request.url ?? '/')
  }
  async #request(request: IncomingMessage, response: ServerResponse): Promise<void> {
    response.setHeader('Cache-Control', 'no-store'); response.setHeader('X-Content-Type-Options', 'nosniff')
    if (this.#phase !== 'listening' || this.#requests >= 128) { response.writeHead(503).end(); return }
    this.#requests++
    try {
      if (request.method === 'GET' && request.url === '/pair/tls') { response.writeHead(204).end(); return }
      if (request.method === 'POST' && request.url === '/pair') {
        const chunks: Buffer[] = []; let size = 0
        const timer = setTimeout(() => request.destroy(), 10_000); timer.unref()
        try { for await (const chunk of request) { size += chunk.length; if (size > 8192) { response.writeHead(413).end(); return }; chunks.push(Buffer.from(chunk)) } } finally { clearTimeout(timer) }
        const values = parseSigningObject(Buffer.concat(chunks)), proof = request.headers.dpop
        if (Object.keys(values).sort().join(',') !== 'code,deviceId,deviceName,manifest,token' || typeof values.code !== 'string' || typeof values.deviceName !== 'string' || typeof values.deviceId !== 'string' || typeof values.token !== 'string' || typeof proof !== 'string') { response.writeHead(400).end(); return }
        const paired = await this.#options.pairing.pair(values.code, values.deviceName, proof, values.deviceId, values.token, values.manifest)
        response.writeHead(201, { 'Content-Type': 'application/json' }).end(JSON.stringify(paired)); return
      }
      if (request.method === 'GET' && request.url === '/pair/identity') { const record = await this.#authorize(request); response.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(this.#options.pairing.identityFor(record))); return }
      response.writeHead(404).end()
    } catch { if (!response.headersSent) response.writeHead(401).end(); else response.destroy() }
    finally { this.#requests-- }
  }
  listen(): Promise<void> {
    const { promise, resolve, reject } = Promise.withResolvers<void>(); this.#server.once('error', reject)
    this.#server.listen(this.#options.port, this.#options.host, () => { this.#server.off('error', reject); this.#phase = 'listening'; resolve() }); return promise
  }
  address(): AddressInfo | string | null { return this.#server.address() }
  phase(): LanPhase { return this.#phase }
  async close(): Promise<void> {
    this.#phase = 'closed'; for (const socket of this.#sockets) socket.destroy()
    for (const socket of this.#websockets.clients) socket.terminate()
    const { promise, resolve } = Promise.withResolvers<void>(); this.#server.close(() => resolve()); await promise
  }
}
