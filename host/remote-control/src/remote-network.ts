import { createHash, randomBytes, X509Certificate } from 'node:crypto'
import { lookup } from 'node:dns/promises'
import { Agent, request } from 'node:https'
import { BlockList, connect as connectTcp, isIP } from 'node:net'
import { checkServerIdentity, connect, type TLSSocket } from 'node:tls'
import { once } from 'node:events'
import { Duplex } from 'node:stream'
import WebSocket, { createWebSocketStream } from 'ws'
import type { RemoteTarget, RemoteCredential, RemotePairRequest } from './remote-targets.js'
import type { CarrierPurpose } from './lan-pairing.js'
import { signRemoteJwt } from './remote-noise-initiator.js'

/** Fixed-origin parsing is shared by every physical dial and pairing signature. */
export function remoteOrigin(target: Pick<RemoteTarget, 'origin' | 'route'>): URL {
  const origin = new URL(target.origin)
  if (target.route !== 'direct' || origin.protocol !== 'https:' || origin.origin !== target.origin || origin.username || origin.password || origin.pathname !== '/' || origin.search || origin.hash || origin.hostname.endsWith('.')) throw new Error('remote/origin-policy')
  return origin
}
const forbidden = new BlockList(), privateNetworks = new BlockList(), loopback = new BlockList(), mappedAddresses = new BlockList()
for (const [ip, prefix] of [['0.0.0.0', 8], ['169.254.0.0', 16], ['224.0.0.0', 4], ['240.0.0.0', 4], ['198.18.0.0', 15]] as const) forbidden.addSubnet(ip, prefix, 'ipv4')
for (const [ip, prefix] of [['::', 128], ['fe80::', 10], ['ff00::', 8]] as const) forbidden.addSubnet(ip, prefix, 'ipv6')
// BlockList compares IPv4 through its mapped IPv6 representation, so this
// subnet must only be queried for an actual IPv6 input, never every IPv4.
mappedAddresses.addSubnet('::ffff:0:0', 96, 'ipv6')
for (const ip of ['100.100.100.200', '168.63.129.16', '192.0.0.192']) forbidden.addAddress(ip, 'ipv4')
forbidden.addAddress('fd00:ec2::254', 'ipv6')
for (const [ip, prefix] of [['10.0.0.0', 8], ['172.16.0.0', 12], ['192.168.0.0', 16], ['100.64.0.0', 10]] as const) privateNetworks.addSubnet(ip, prefix, 'ipv4')
privateNetworks.addSubnet('fc00::', 7, 'ipv6'); privateNetworks.addSubnet('fec0::', 10, 'ipv6')
loopback.addSubnet('127.0.0.0', 8, 'ipv4'); loopback.addAddress('::1', 'ipv6')

export function assertRemoteAddress(target: Pick<RemoteTarget, 'allowedAddresses' | 'allowPrivate' | 'allowLoopback'>, address: string): void {
  const family = isIP(address), kind = family === 4 ? 'ipv4' : 'ipv6'
  if (!family || address.includes('%') || (family === 6 && mappedAddresses.check(address, 'ipv6')) || forbidden.check(address, kind) ||
    (loopback.check(address, kind) && !target.allowLoopback) || (privateNetworks.check(address, kind) && !target.allowPrivate)) throw new Error('remote/address-policy')
  const allowed = new BlockList()
  for (const entry of target.allowedAddresses) {
    const parts = entry.split('/'), ip = parts[0]!, version = isIP(ip)
    if (!version || parts.length > 2 || ip.includes('%')) throw new Error('remote/address-allowlist')
    const type = version === 4 ? 'ipv4' : 'ipv6'
    if (parts.length === 1) allowed.addAddress(ip, type)
    else {
      const prefix = Number(parts[1])
      if (!/^\d+$/.test(parts[1]!) || !Number.isInteger(prefix) || prefix < (version === 4 ? 24 : 64) || prefix > (version === 4 ? 32 : 128)) throw new Error('remote/address-allowlist')
      allowed.addSubnet(ip, prefix, type)
    }
  }
  if (!allowed.check(address, kind)) throw new Error('remote/address-policy')
}
async function pinnedTls(target: RemoteTarget, signal: AbortSignal): Promise<TLSSocket> {
  signal.throwIfAborted()
  if (target.origin !== target.descriptor.origin || target.tlsPin !== target.descriptor.tlsPin) throw new Error('remote/target-binding')
  const origin = remoteOrigin(target), hostname = origin.hostname.replace(/^\[|\]$/g, '')
  const timeout = AbortSignal.timeout(10_000), bounded = AbortSignal.any([signal, timeout])
  const pending = Promise.withResolvers<never>(), aborted = () => pending.reject(new Error('remote/network-timeout'))
  bounded.addEventListener('abort', aborted, { once: true })
  let addresses: { address: string; family: number }[]
  try {
    addresses = isIP(hostname) ? [{ address: hostname, family: isIP(hostname) }] : await Promise.race([lookup(hostname, { all: true, verbatim: true }), pending.promise])
    bounded.throwIfAborted()
  } finally { bounded.removeEventListener('abort', aborted) }
  if (!addresses.length || addresses.length > 64) throw new Error('remote/dns-policy')
  for (const address of addresses) assertRemoteAddress(target, address.address)
  const selected = addresses[0]!, port = Number(origin.port || 443)
  const tcp = connectTcp({ host: selected.address, family: selected.family, port })
  let socket: TLSSocket | undefined
  tcp.on('error', () => {})
  const cancelled = () => { socket?.destroy(new Error('remote/cancelled')); tcp.destroy(new Error('remote/cancelled')) }
  signal.addEventListener('abort', cancelled, { once: true })
  tcp.once('close', () => signal.removeEventListener('abort', cancelled))
  if (signal.aborted) cancelled()
  try {
    await once(tcp, 'connect', { signal: bounded })
    bounded.throwIfAborted()
    socket = connect({ socket: tcp, servername: isIP(hostname) ? undefined : hostname, minVersion: 'TLSv1.2',
      rejectUnauthorized: true, ...(target.trustedCertificate ? { ca: target.trustedCertificate } : {}), ALPNProtocols: ['http/1.1'],
      checkServerIdentity(_hostname, certificate) {
        const invalid = checkServerIdentity(hostname, certificate)
        if (invalid) return invalid
        const spki = new X509Certificate(certificate.raw).publicKey.export({ type: 'spki', format: 'der' })
        if (`sha256/${createHash('sha256').update(spki).digest('base64')}` !== target.tlsPin) return new Error('remote/tls-pin')
        return undefined
      },
    })
    socket.on('error', () => {})
    await once(socket, 'secureConnect', { signal: bounded })
    const remote = socket.remoteAddress?.replace(/^::ffff:(\d+\.\d+\.\d+\.\d+)$/, '$1')
    const verified = new BlockList(); verified.addAddress(selected.address, selected.family === 4 ? 'ipv4' : 'ipv6')
    if (!socket.authorized || !remote || !verified.check(remote, isIP(remote) === 4 ? 'ipv4' : 'ipv6') || socket.remotePort !== port) throw new Error('remote/connected-address')
    // The timeout is establishment-only; carrier/request owners enforce their own deadlines.
    socket.setTimeout(0)
    return socket
  } catch (error) { socket?.destroy(); tcp.destroy(); throw error }
}

export async function signPairingProof(target: RemoteTarget, credential: Pick<RemoteCredential, 'publicJwk' | 'sign' | 'token'>, method: 'POST' | 'GET', path: '/pair' | '/pair/identity' | '/carrier', nonce?: string): Promise<string> {
  const origin = remoteOrigin(target)
  return signRemoteJwt(credential, 'dpop+jwt', { htm: method, htu: new URL(path, origin).href,
    ath: createHash('sha256').update(credential.token).digest('base64url'), ...(nonce === undefined ? {} : { nonce }), iat: Math.floor(Date.now() / 1000), jti: randomBytes(32).toString('base64url') })
}

/** Dedicated bootstrap endpoints only: no redirects, proxy, ambient credentials or retries. */
export const pinnedRequest: RemotePairRequest = async (target, input, signal) => {
  if ((input.path !== '/pair' || input.method !== 'POST') && (input.path !== '/pair/identity' || input.method !== 'GET')) throw new Error('remote/bootstrap-route')
  if ((input.body?.byteLength ?? 0) > 8192 || (input.method === 'GET' && input.body)) throw new Error('remote/bootstrap-limit')
  const headers: Record<string, string> = {}
  for (const [key, value] of Object.entries(input.headers)) {
    const name = key.toLowerCase()
    if (!['authorization', 'dpop', 'x-tokenworks-device-id', 'content-type'].includes(name) || /[\r\n]/.test(value)) throw new Error('remote/bootstrap-header')
    headers[name] = value
  }
  const bounded = AbortSignal.any([signal, AbortSignal.timeout(15_000)]), socket = await pinnedTls(target, bounded)
  const agent = new Agent({ keepAlive: false, maxSockets: 1 })
  agent.createConnection = (_options, callback) => { callback!(null, socket); return undefined }
  const result = Promise.withResolvers<{ status: number; body: Uint8Array }>()
  const call = request(new URL(input.path, remoteOrigin(target)), { method: input.method, agent, signal: bounded, maxHeaderSize: 32 * 1024,
    headers: { ...headers, 'accept-encoding': 'identity', connection: 'close', ...(input.body ? { 'content-length': input.body.byteLength } : {}) } }, response => {
    const chunks: Buffer[] = []; let size = 0
    response.on('data', (chunk: Buffer) => { size += chunk.length; if (size > 32768) call.destroy(new Error('remote/bootstrap-limit')); else chunks.push(chunk) })
    response.once('error', result.reject)
    response.once('end', () => {
      const status = response.statusCode ?? 0
      if (!response.complete || status >= 300 && status < 400 || response.headers['content-encoding'] && response.headers['content-encoding'] !== 'identity') result.reject(new Error('remote/bootstrap-response'))
      else result.resolve({ status, body: Buffer.concat(chunks, size) })
    })
  })
  call.once('error', result.reject); call.end(input.body)
  try { return await result.promise } finally { agent.destroy(); socket.destroy() }
}

/** Outer WS transports ciphertext only, over the same checked-IP TLS connection. */
export async function dialRemoteOuter(target: RemoteTarget, credential: RemoteCredential, purpose: CarrierPurpose, signal: AbortSignal): Promise<Duplex> {
  const proof = await signPairingProof(target, credential, 'GET', '/carrier'), socket = await pinnedTls(target, signal)
  const url = new URL('/carrier', remoteOrigin(target)); url.protocol = 'wss:'
  let websocket: WebSocket | undefined
  try {
    websocket = new WebSocket(url, { createConnection: () => socket, perMessageDeflate: false, maxPayload: 65537, handshakeTimeout: 10_000, followRedirects: false,
      headers: { authorization: `DPoP ${credential.token}`, dpop: proof, 'x-tokenworks-device-id': credential.pairId, 'x-tokenworks-carrier-purpose': purpose } })
    const ws = websocket, aborted = () => { ws.terminate(); socket.destroy() }
    signal.addEventListener('abort', aborted, { once: true }); ws.once('close', () => signal.removeEventListener('abort', aborted))
    ws.on('error', () => {}); ws.on('message', (_bytes, binary) => { if (!binary) ws.terminate() })
    const stream = createWebSocketStream(ws, { highWaterMark: 65537 }); stream.on('error', () => {})
    Object.assign(stream, { carrierQueuedBytes: () => ({ inbound: socket.readableLength, outbound: ws.bufferedAmount + socket.writableLength }) })
    stream.once('close', () => { ws.terminate(); socket.destroy() })
    signal.throwIfAborted(); await once(ws, 'open', { signal }); return stream
  } catch (error) { websocket?.terminate(); socket.destroy(); throw error }
}
