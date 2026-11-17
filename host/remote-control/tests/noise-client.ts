import { createHash, randomBytes, randomUUID, X509Certificate } from 'node:crypto'
import { request } from 'node:https'
import { checkServerIdentity } from 'node:tls'
import { request as httpRequest, type IncomingHttpHeaders } from 'node:http'
import type { Socket } from 'node:net'
import { Duplex } from 'node:stream'
import { once } from 'node:events'
import WebSocket, { createWebSocketStream } from 'ws'
import { calculateJwkThumbprint, exportJWK, generateKeyPair, SignJWT, type GenerateKeyPairResult } from 'jose'
import { canonicalObject, parseSigningObject } from '../src/canonical.js'
import { bindingDigest, manifestSchema, type PairingManifest } from '../src/lan-pairing-store.js'
import type { CarrierPurpose, LanPairingDescriptor, RelayLeaseCredential } from '../src/lan-pairing.js'
import { generateNoiseStatic, NativeNoiseSession, NOISE_SUITE } from '../src/native-noise.js'

export interface NoiseClientCredential {
  origin: string; tlsPin: string; deviceId: string; token: string; manifest: PairingManifest; bindingSha256: string
  privateKey: Uint8Array
  signer: GenerateKeyPairResult
}
interface PairOptions {
  origin: string; ca: string; descriptor: LanPairingDescriptor; deviceName?: string
  owner?: PairingManifest['owner']; relay?: PairingManifest['relay']
}
export interface OpenOptions {
  origin: string; ca: string; credential: NoiseClientCredential; purpose?: CarrierPurpose; routeKind?: 'lan' | 'relay'
  relayLease?: RelayLeaseCredential
  path?: string; headers?: Record<string, string>
  capture?: (direction: 'send' | 'receive', bytes: Uint8Array) => void
}

function loopbackOrigin(origin: string): URL {
  const value = new URL(origin)
  if (value.origin !== origin || value.protocol !== 'https:' || !['localhost', '127.0.0.1', '[::1]'].includes(value.hostname)) throw new Error('test Noise client requires explicit TLS loopback origin')
  return value
}
function tlsOptions(ca: string, pin: string) {
  return { ca, rejectUnauthorized: true, family: 4,
    checkServerIdentity(hostname: string, cert: Parameters<typeof checkServerIdentity>[1]) {
      const invalid = checkServerIdentity(hostname, cert)
      if (invalid) return invalid
      const publicKey = new X509Certificate(cert.raw).publicKey.export({ type: 'spki', format: 'der' })
      if (`sha256/${createHash('sha256').update(publicKey).digest('base64')}` !== pin) return new Error('test Noise TLS pin mismatch')
      return undefined
    },
  }
}
export async function noiseDpop(credential: NoiseClientCredential, method: string, path: string, extra: Record<string, unknown> = {}): Promise<string> {
  return new SignJWT({ htm: method, htu: new URL(path.split('?')[0]!, credential.origin).href,
    ath: createHash('sha256').update(credential.token).digest('base64url'), ...extra })
    .setProtectedHeader({ alg: 'ES256', typ: 'dpop+jwt', jwk: await exportJWK(credential.signer.publicKey) })
    .setIssuedAt().setJti(randomUUID()).sign(credential.signer.privateKey)
}

/** Operator-approved local pairing with the production manifest and native static-key generator. */
export async function pairNoiseClient(options: PairOptions): Promise<NoiseClientCredential> {
  loopbackOrigin(options.origin)
  if (options.descriptor.origin !== options.origin || options.descriptor.version !== 2 || !options.descriptor.minimumEncrypted || options.descriptor.suite !== NOISE_SUITE) throw new Error('test pairing descriptor mismatch')
  const signer = await generateKeyPair('ES256', { extractable: true }), keys = generateNoiseStatic()
  const deviceId = randomUUID(), token = randomBytes(32).toString('base64url')
  const owner = options.owner ?? { account_id: randomUUID(), organization_id: randomUUID(), deployment_sha256: createHash('sha256').update('isolated Noise fixture').digest('hex'), user_id: randomUUID(), device_id: randomUUID() }
  const manifest = manifestSchema.parse({ v: 2, runtimeId: options.descriptor.runtimeId, installationId: options.descriptor.installationId, pairId: deviceId,
    tokenSha256: createHash('sha256').update(token).digest('base64url'), proofJkt: await calculateJwkThumbprint(await exportJWK(signer.publicKey)),
    deviceStaticPublicKey: Buffer.from(keys.publicKey).toString('base64url'), deviceKeyEpoch: 1, hostStaticPublicKey: options.descriptor.hostStaticPublicKey,
    hostKeyEpoch: options.descriptor.hostKeyEpoch, owner, ...(options.relay ? { relay: options.relay } : {}) })
  const credential: NoiseClientCredential = { origin: options.origin, tlsPin: options.descriptor.tlsPin, deviceId, token, manifest, bindingSha256: bindingDigest(manifest), privateKey: keys.privateKey, signer }
  const body = canonicalObject({ code: options.descriptor.code, deviceName: options.deviceName ?? 'Isolated Noise proof', deviceId, token, manifest })
  const result = Promise.withResolvers<Buffer>()
  const call = request(options.origin + '/pair', { method: 'POST', ...tlsOptions(options.ca, credential.tlsPin), agent: false,
    headers: { 'content-type': 'application/json', 'content-length': body.length, dpop: await noiseDpop(credential, 'POST', '/pair', { nonce: `${options.descriptor.code}.${credential.bindingSha256}` }) } }, response => {
    const bytes: Buffer[] = []; let size = 0
    response.on('data', (chunk: Buffer) => { size += chunk.length; if (size > 32_768) call.destroy(new Error('test pair response limit')); else bytes.push(Buffer.from(chunk)) })
    response.once('error', result.reject)
    response.once('end', () => response.statusCode === 201 ? result.resolve(Buffer.concat(bytes)) : result.reject(new Error(`test pair refused (${response.statusCode})`)))
  })
  call.once('error', result.reject); call.end(body)
  try {
    const paired = parseSigningObject(await result.promise), received = manifestSchema.parse(paired.manifest)
    if (paired.deviceId !== deviceId || paired.token !== token || paired.runtimeId !== manifest.runtimeId || paired.installationId !== manifest.installationId ||
      paired.origin !== options.origin || paired.tlsPin !== credential.tlsPin || paired.bindingSha256 !== credential.bindingSha256 || bindingDigest(received) !== credential.bindingSha256) throw new Error('test pair result binding mismatch')
    return credential
  } catch (error) { keys.privateKey.fill(0); throw error }
}

/** Native Noise IK over the production binary carrier. Returns untouched authenticated HTTP bytes. */
export async function openNoiseClient(options: OpenOptions): Promise<{ stream: Duplex; ready: Record<string, unknown>; handshakeHash: string; close(): void }> {
  loopbackOrigin(options.origin)
  const credential = options.credential, purpose = options.purpose ?? 'native', routeKind = options.routeKind ?? 'lan', path = options.path ?? '/carrier'
  if (credential.origin !== options.origin || !path.startsWith('/') || path.includes('#')) throw new Error('test carrier origin mismatch')
  const socket = new WebSocket(options.origin.replace('https:', 'wss:') + path, { ...tlsOptions(options.ca, credential.tlsPin), perMessageDeflate: false, maxPayload: 65_537,
    headers: { authorization: `DPoP ${credential.token}`, 'x-tokenworks-device-id': credential.deviceId, 'x-tokenworks-carrier-purpose': purpose,
      dpop: await noiseDpop(credential, 'GET', path), ...options.headers } })
  let session: NativeNoiseSession | undefined, plain: Duplex | undefined
  const outer = createWebSocketStream(socket, { highWaterMark: 65_537 })
  outer.on('error', () => {})
  const scratch = Buffer.alloc(65_535), output = Buffer.alloc(65_535), prefix = Buffer.alloc(2)
  let tx = 0n, rx = 0n, txBytes = 0n, rxBytes = 0n, sentClose = false, receivedClose = false, disposed = false
  let stage = 'public hello', handshakeHash = '', requested: (() => void) | undefined
  const close = () => {
    if (disposed) return
    disposed = true; plain?.destroy(); outer.destroy(); socket.terminate(); session?.close()
    scratch.fill(0); output.fill(0); prefix.fill(0)
  }
  async function readable() {
    if (outer.destroyed || outer.readableEnded) throw new Error(`test carrier truncated during ${stage}`)
    const pending = Promise.withResolvers<void>()
    const cleanup = () => { outer.off('readable', data); outer.off('close', ended); outer.off('end', ended); outer.off('error', failed) }
    const data = () => { cleanup(); pending.resolve() }
    const ended = () => { cleanup(); pending.reject(new Error(`test carrier truncated during ${stage}`)) }
    const failed = (error: Error) => { cleanup(); pending.reject(error) }
    outer.once('readable', data); outer.once('close', ended); outer.once('end', ended); outer.once('error', failed)
    if (outer.readableLength) data()
    else if (outer.destroyed || outer.readableEnded) ended()
    await pending.promise
  }
  async function exact(target: Buffer, length: number) {
    let offset = 0
    while (offset < length) {
      const bytes = outer.read(Math.min(length - offset, Math.max(1, outer.readableLength))) as Buffer | null
      if (bytes) { bytes.copy(target, offset); offset += bytes.length }
      else await readable()
    }
  }
  async function record(max = 65_535): Promise<Buffer> {
    await exact(prefix, 2)
    const length = prefix.readUInt16BE()
    if (length < 1 || length > max) throw new Error('test carrier invalid record length')
    const bytes = Buffer.allocUnsafe(length); await exact(bytes, length)
    options.capture?.('receive', Buffer.concat([Buffer.from(prefix), bytes]))
    return bytes
  }
  async function writeRecord(bytes: Uint8Array) {
    if (bytes.length < 1 || bytes.length > 65_535 || disposed) throw new Error('test carrier invalid record length')
    const wire = Buffer.allocUnsafe(bytes.length + 2); wire.writeUInt16BE(bytes.length); wire.set(bytes, 2)
    options.capture?.('send', Buffer.from(wire))
    const written = Promise.withResolvers<void>()
    outer.write(wire, error => error ? written.reject(error) : written.resolve())
    try { await written.promise } finally { wire.fill(0) }
  }
  async function send(bytes: Uint8Array) {
    const count = session!.encrypt(bytes, output); await writeRecord(output.subarray(0, count)); tx++
    if (tx % (2n ** 20n) === 0n) session!.rekeySend()
  }
  async function receive(): Promise<Buffer> {
    const bytes = await record(), count = session!.decrypt(bytes, scratch); rx++
    if (rx % (2n ** 20n) === 0n) session!.rekeyReceive()
    return scratch.subarray(0, count)
  }
  try {
    // Platform I/O has a failure deadline only; success is driven by actual records.
    const deadline = AbortSignal.timeout(10_000), timedOut = () => close()
    deadline.addEventListener('abort', timedOut, { once: true })
    let ready: Record<string, unknown>
    try {
      await once(socket, 'open')
      const helloBytes = await record(4096), hello = parseSigningObject(helloBytes)
      if (!Buffer.from(canonicalObject(hello)).equals(helloBytes) || hello.magic !== 'tokenworks-noise' || hello.v !== 1 || hello.suite !== NOISE_SUITE ||
        hello.runtimeId !== credential.manifest.runtimeId || hello.installationId !== credential.manifest.installationId || hello.hostStaticPublicKey !== credential.manifest.hostStaticPublicKey ||
        hello.hostKeyEpoch !== credential.manifest.hostKeyEpoch || hello.purpose !== purpose || hello.routeKind !== routeKind || hello.dshVersion !== '0.2.2-rc.1') throw new Error('test carrier hello binding mismatch')
      session = new NativeNoiseSession(true, credential.privateKey, Buffer.from(credential.manifest.hostStaticPublicKey, 'base64url'), helloBytes)
      await writeRecord(output.subarray(0, session.handshakeWrite(new Uint8Array(), output)))
      stage = 'IK response'
      if (session.handshakeRead(await record(4096), scratch) !== 0) throw new Error('test carrier handshake payload')
      const binding = session.finishHandshake()
      handshakeHash = Buffer.from(binding.handshakeHash).toString('base64url')
      if (!Buffer.from(binding.remoteStatic).equals(Buffer.from(credential.manifest.hostStaticPublicKey, 'base64url'))) throw new Error('test carrier remote static mismatch')
      const proof = await new SignJWT({ v: 1, h: Buffer.from(binding.handshakeHash).toString('base64url'), runtime_id: credential.manifest.runtimeId, installation_id: credential.manifest.installationId,
        pair_id: credential.deviceId, host_static: credential.manifest.hostStaticPublicKey, device_static: credential.manifest.deviceStaticPublicKey,
        host_key_epoch: credential.manifest.hostKeyEpoch, device_key_epoch: credential.manifest.deviceKeyEpoch, owner_sha256: bindingDigest(credential.manifest.owner), purpose, ath: credential.manifest.tokenSha256 })
        .setProtectedHeader({ alg: 'ES256', typ: 'twm-noise-bind+jwt', jwk: await exportJWK(credential.signer.publicKey) })
        .setIssuedAt().setJti(randomBytes(32).toString('base64url')).sign(credential.signer.privateKey)
      await send(canonicalObject({ type: 'auth', pairId: credential.deviceId, token: credential.token, hostKeyEpoch: credential.manifest.hostKeyEpoch, deviceKeyEpoch: credential.manifest.deviceKeyEpoch, proof, ...(options.relayLease ? { relayLease: options.relayLease } : {}) }))
      stage = 'authenticated ready'
      ready = parseSigningObject(await receive())
      if (ready.type !== 'ready' || ready.v !== 1 || ready.suite !== NOISE_SUITE || ready.dshVersion !== '0.2.2-rc.1' || ready.runtimeId !== credential.manifest.runtimeId ||
        ready.installationId !== credential.manifest.installationId || ready.pairId !== credential.deviceId || ready.hostKeyEpoch !== credential.manifest.hostKeyEpoch ||
        ready.deviceKeyEpoch !== credential.manifest.deviceKeyEpoch || ready.ownerSha256 !== bindingDigest(credential.manifest.owner) || ready.bindingSha256 !== credential.bindingSha256 || typeof ready.peerGeneration !== 'string') throw new Error('test carrier ready binding mismatch')
    } finally { deadline.removeEventListener('abort', timedOut) }
    stage = 'native HTTP bytes'
    plain = new Duplex({ readableHighWaterMark: 65_536, writableHighWaterMark: 65_536, allowHalfOpen: true,
      read() { requested?.(); requested = undefined },
      write(chunk: Buffer, _encoding, callback) {
        void (async () => {
          for (let offset = 0; offset < chunk.length; offset += 65_518) {
            const bytes = chunk.subarray(offset, offset + 65_518), payload = Buffer.allocUnsafe(bytes.length + 1)
            payload[0] = 0; payload.set(bytes, 1)
            try { await send(payload); txBytes += BigInt(bytes.length) } finally { payload.fill(0) }
          }
        })().then(() => callback(), callback)
      },
      final(callback) {
        void (async () => {
          const message = Buffer.alloc(17); message[0] = 1; message.writeBigUInt64BE(txBytes, 1); message.writeBigUInt64BE(tx + 1n, 9)
          await send(message); sentClose = true; if (receivedClose) outer.end()
        })().then(() => callback(), callback)
      },
      destroy(error, callback) { outer.destroy(); socket.terminate(); session?.close(); callback(error) },
    })
    plain.on('error', () => {})
    void (async () => {
      while (!disposed) {
        const bytes = await receive()
        if (receivedClose) throw new Error('test carrier data after close')
        if (bytes[0] === 0 && bytes.length >= 2) {
          rxBytes += BigInt(bytes.length - 1)
          const demand = Promise.withResolvers<void>()
          requested = demand.resolve
          if (!plain!.push(Buffer.from(bytes.subarray(1)))) await demand.promise
          else requested = undefined
        } else if (bytes[0] === 1 && bytes.length === 17 && bytes.readBigUInt64BE(1) === rxBytes && bytes.readBigUInt64BE(9) === rx) {
          receivedClose = true; plain!.push(null); if (sentClose) outer.end(); return
        } else throw new Error('test carrier invalid application record')
      }
    })().catch(error => plain!.destroy(error))
    return { stream: plain, ready, handshakeHash, close }
  } catch (error) { close(); throw error }
}

export interface NoiseHttpResponse { status: number; body: Buffer; headers: IncomingHttpHeaders }

/** Node's real HTTP parser consumes the admitted byte stream; there is no second native RPC codec. */
export async function noiseHttpRequest(options: Omit<OpenOptions, 'path' | 'headers'>, method: string, path: string, body?: Buffer, headers: Record<string, string> = {}): Promise<NoiseHttpResponse> {
  const carrier = await openNoiseClient(options)
  const response = Promise.withResolvers<NoiseHttpResponse>()
  const call = httpRequest({ host: 'localhost', port: 80, path, method,
    createConnection: () => carrier.stream as Socket,
    signal: AbortSignal.timeout(15_000),
    headers: { Host: new URL(options.origin).host, Connection: 'close', ...(body ? { 'content-length': String(body.length) } : {}), ...headers },
  }, incoming => {
    const chunks: Buffer[] = []; let size = 0
    incoming.on('data', (bytes: Buffer) => { size += bytes.length; if (size > 2 * 1024 * 1024) call.destroy(new Error('test native HTTP response limit')); else chunks.push(Buffer.from(bytes)) })
    incoming.once('error', response.reject)
    incoming.once('end', () => response.resolve({ status: incoming.statusCode!, body: Buffer.concat(chunks), headers: incoming.headers }))
  })
  call.once('error', response.reject); call.end(body)
  try { return await response.promise } finally { carrier.close() }
}
