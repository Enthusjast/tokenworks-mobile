import { createHash, randomBytes } from 'node:crypto'
import { Duplex } from 'node:stream'
import { calculateJwkThumbprint } from 'jose'
import { canonicalObject, parseSigningObject } from './canonical.js'
import { bindingDigest, manifestSchema, type PairingManifest } from './lan-pairing-store.js'
import { relayLeaseSchema, type CarrierPurpose, type LanPairingDescriptor, type RelayLeaseCredential } from './lan-pairing.js'
import type { HostHandshakeSigner, InstallationPublicJwk } from './host-connection-credential.js'
import { NativeNoiseSession, NOISE_SUITE } from './native-noise.js'

export interface NoiseInitiatorCredential {
  readonly pairId: string; readonly token: string; readonly manifest: PairingManifest; readonly bindingSha256: string
  readonly localPrivate: Uint8Array; readonly publicJwk: InstallationPublicJwk; readonly sign: HostHandshakeSigner['sign']
}
export interface NoiseReady {
  readonly type: 'ready'; readonly v: 1; readonly suite: string; readonly dshVersion: string
  readonly runtimeId: string; readonly installationId: string; readonly pairId: string
  readonly hostKeyEpoch: number; readonly deviceKeyEpoch: number; readonly ownerSha256: string
  readonly bindingSha256: string; readonly peerGeneration: string
}
export interface AuthenticatedNoiseStream { readonly stream: Duplex; readonly ready: Readonly<NoiseReady>; close(): void }
export interface NoiseInitiatorOptions {
  readonly expected: Omit<LanPairingDescriptor, 'code' | 'expiresAt'>
  readonly credential: NoiseInitiatorCredential; readonly purpose: CarrierPurpose; readonly signal: AbortSignal
  readonly relayLease?: RelayLeaseCredential
  readonly dialOuter: (signal: AbortSignal) => Promise<Duplex>
  readonly expectedRouteKind?: 'lan' | 'relay' | 'p2p' | 'turn'
  readonly requiredCapabilities?: readonly string[]; readonly expectedDshVersion?: string
}
const DIRECTION_BYTES = 256 * 1024, REKEY = 2n ** 20n
const active = new Set<{ pair: string; outer?: Duplex; plain?: Duplex }>()

/** Signs with the protected P256 provider; no private signing key is exported. */
export async function signRemoteJwt(credential: Pick<NoiseInitiatorCredential, 'publicJwk' | 'sign'>, typ: string, claims: Record<string, unknown>): Promise<string> {
  const header = Buffer.from(canonicalObject({ alg: 'ES256', typ, jwk: credential.publicJwk })).toString('base64url')
  const payload = Buffer.from(canonicalObject(claims)).toString('base64url'), input = `${header}.${payload}`
  const signature = await credential.sign(Buffer.from(input))
  if (signature.length !== 64) throw new Error('remote/signature-format')
  return `${input}.${Buffer.from(signature).toString('base64url')}`
}
function signingJson(bytes: Buffer, keys: readonly string[]): Record<string, unknown> {
  const value = parseSigningObject(bytes)
  if (Object.keys(value).sort().join(',') !== [...keys].sort().join(',') || !Buffer.from(canonicalObject(value)).equals(bytes)) throw new Error('e2e/noncanonical')
  return value
}

/** Production IK initiator over an already policy-authenticated opaque byte transport. */
export async function dialNoiseCarrier(options: NoiseInitiatorOptions): Promise<AuthenticatedNoiseStream> {
  options.signal.throwIfAborted()
  const { credential, expected, purpose } = options, manifest = manifestSchema.parse(credential.manifest)
  const version = options.expectedDshVersion ?? '0.2.1-rc.5'
  if (expected.version !== 2 || expected.carrierVersion !== 1 || expected.minimumEncrypted !== true || expected.suite !== NOISE_SUITE ||
    manifest.pairId !== credential.pairId || manifest.runtimeId !== expected.runtimeId || manifest.installationId !== expected.installationId ||
    manifest.hostKeyEpoch !== expected.hostKeyEpoch || manifest.hostStaticPublicKey !== expected.hostStaticPublicKey || credential.localPrivate.length !== 32 ||
    manifest.tokenSha256 !== createHash('sha256').update(credential.token).digest('base64url') || bindingDigest(manifest) !== credential.bindingSha256 ||
    await calculateJwkThumbprint(credential.publicJwk) !== manifest.proofJkt) throw new Error('e2e/credential-binding')
  let relayLease: RelayLeaseCredential | undefined
  if (options.relayLease !== undefined) {
    const parsed = relayLeaseSchema.safeParse(options.relayLease)
    if (!parsed.success) throw new Error('e2e/relay-lease-invalid')
    relayLease = parsed.data
  }
  if (manifest.relay && (purpose === 'native' || options.expectedRouteKind === 'relay') && !relayLease) throw new Error('e2e/relay-lease-required')
  if (!manifest.relay && (relayLease || options.expectedRouteKind === 'relay')) throw new Error('e2e/relay-binding')
  const pair = `${manifest.installationId}:${credential.pairId}`
  if (active.size >= 32 || [...active].filter(item => item.pair === pair).length >= 8) throw new Error('e2e/connection-budget')
  const entry: { pair: string; outer?: Duplex; plain?: Duplex } = { pair }; active.add(entry)
  const controller = new AbortController(), signal = AbortSignal.any([options.signal, controller.signal])
  let outer: Duplex | undefined, plain: Duplex | undefined, session: NativeNoiseSession | undefined
  let tx = 0n, rx = 0n, txBytes = 0n, rxBytes = 0n, sentClose = false, receivedClose = false, stopped = false
  let partial: NodeJS.Timeout | undefined, wake: (() => void) | undefined, pendingWrite: Promise<void> | undefined
  const record = Buffer.alloc(65535), decoded = Buffer.alloc(65535), wire = Buffer.alloc(65537), output = wire.subarray(2), payload = Buffer.alloc(65519), prefix = Buffer.alloc(2)
  const fail = (error = new Error('e2e/cancelled')) => { if (stopped) return; stopped = true; controller.abort(error); wake?.(); plain?.destroy(error); outer?.destroy(error); session?.close() }
  const aborted = () => fail(new Error('e2e/cancelled'))
  options.signal.addEventListener('abort', aborted, { once: true })
  const deadline = setTimeout(() => fail(new Error('e2e/handshake-timeout')), 15_000); deadline.unref()
  const cleanup = async () => {
    clearTimeout(deadline); clearTimeout(partial); options.signal.removeEventListener('abort', aborted)
    await pendingWrite?.catch(() => {})
    session?.close(); record.fill(0); decoded.fill(0); wire.fill(0); payload.fill(0); active.delete(entry)
  }
  const budget = () => {
    let total = 0, paired = 0
    for (const item of active) {
      const queued = (item.outer as (Duplex & { carrierQueuedBytes?(): { inbound: number; outbound: number } }) | undefined)?.carrierQueuedBytes?.()
      const inbound = (item.outer?.readableLength ?? 0) + (item.plain?.readableLength ?? 0) + (queued?.inbound ?? 0)
      const outbound = (item.outer?.writableLength ?? 0) + (item.plain?.writableLength ?? 0) + (queued?.outbound ?? 0)
      if (inbound > DIRECTION_BYTES || outbound > DIRECTION_BYTES) throw new Error('e2e/budget')
      total += inbound + outbound; if (item.pair === pair) paired += inbound + outbound
    }
    if (total > 32 * 1024 * 1024 || paired > 2 * 1024 * 1024) throw new Error('e2e/budget')
  }
  async function exact(target: Buffer, length: number): Promise<void> {
    let offset = 0
    while (offset < length) {
      budget()
      const bytes = outer!.read(Math.min(length - offset, Math.max(1, outer!.readableLength))) as Buffer | null
      if (bytes) {
        if (!partial) { partial = setTimeout(() => fail(new Error('e2e/record-timeout')), 30_000); partial.unref() }
        bytes.copy(target, offset); offset += bytes.length; continue
      }
      if (stopped || outer!.destroyed || outer!.readableEnded) throw new Error(receivedClose && offset === 0 && target === prefix ? 'e2e/eof' : 'e2e/truncated')
      const waiting = Promise.withResolvers<void>()
      const ready = () => waiting.resolve(), ended = () => waiting.reject(new Error(receivedClose && offset === 0 && target === prefix ? 'e2e/eof' : 'e2e/truncated'))
      outer!.once('readable', ready); outer!.once('end', ended); outer!.once('close', ended); outer!.once('error', ended)
      try { await waiting.promise } finally { outer!.off('readable', ready); outer!.off('end', ended); outer!.off('close', ended); outer!.off('error', ended) }
    }
  }
  async function readRecord(max: number, min = 1): Promise<Buffer> {
    try { await exact(prefix, 2); const length = prefix.readUInt16BE(); if (length < min || length > max) throw new Error('e2e/record-length'); await exact(record, length); return record.subarray(0, length) }
    finally { clearTimeout(partial); partial = undefined }
  }
  async function writeRecord(bytes: Uint8Array): Promise<void> {
    if (stopped || outer!.destroyed || bytes.length < 1 || bytes.length > 65535) throw new Error('e2e/closed')
    budget(); wire.writeUInt16BE(bytes.length)
    if (bytes.buffer !== output.buffer || bytes.byteOffset !== output.byteOffset) wire.set(bytes, 2)
    const waiting = Promise.withResolvers<void>(), timer = setTimeout(() => fail(new Error('e2e/write-timeout')), 30_000); timer.unref()
    pendingWrite = waiting.promise
    try { outer!.write(wire.subarray(0, bytes.length + 2), error => error ? waiting.reject(error) : waiting.resolve()); await waiting.promise }
    finally { clearTimeout(timer); wire.fill(0); pendingWrite = undefined }
  }
  async function send(bytes: Uint8Array): Promise<void> { const length = session!.encrypt(bytes, output); await writeRecord(output.subarray(0, length)); if (++tx % REKEY === 0n) session!.rekeySend() }
  async function receive(max = 65535): Promise<Buffer> { const count = session!.decrypt(await readRecord(max, 17), decoded); if (++rx % REKEY === 0n) session!.rekeyReceive(); return decoded.subarray(0, count) }
  try {
    outer = await options.dialOuter(signal); entry.outer = outer
    outer.on('error', () => {}); outer.once('close', () => { wake?.(); if (!receivedClose || !sentClose) fail(new Error('e2e/truncated')) })
    signal.throwIfAborted()
    const helloBytes = await readRecord(4096), hello = signingJson(helloBytes, ['magic', 'v', 'suite', 'runtimeId', 'installationId', 'hostKeyEpoch', 'hostStaticPublicKey', 'purpose', 'routeKind', 'dshVersion', 'capabilities'])
    if (hello.magic !== 'tokenworks-noise' || hello.v !== 1 || hello.suite !== NOISE_SUITE || hello.runtimeId !== expected.runtimeId || hello.installationId !== expected.installationId ||
      hello.hostKeyEpoch !== expected.hostKeyEpoch || hello.hostStaticPublicKey !== expected.hostStaticPublicKey || hello.purpose !== purpose || hello.routeKind !== (options.expectedRouteKind ?? 'lan') || hello.dshVersion !== version ||
      !Array.isArray(hello.capabilities) || hello.capabilities.some(item => typeof item !== 'string') || new Set(hello.capabilities).size !== hello.capabilities.length ||
      ['native.http.v1', 'noise.ik.v1', ...(options.requiredCapabilities ?? []), ...(['p2p', 'turn'].includes(options.expectedRouteKind ?? '') ? ['transport.webrtc.v1'] : [])].some(item => !(hello.capabilities as unknown[]).includes(item))) throw new Error('e2e/hello-binding')
    session = new NativeNoiseSession(true, credential.localPrivate, Buffer.from(expected.hostStaticPublicKey, 'base64url'), helloBytes)
    await writeRecord(output.subarray(0, session.handshakeWrite(new Uint8Array(), output)))
    if (session.handshakeRead(await readRecord(4096), decoded) !== 0) throw new Error('e2e/handshake-payload')
    const binding = session.finishHandshake()
    if (!Buffer.from(binding.remoteStatic).equals(Buffer.from(expected.hostStaticPublicKey, 'base64url'))) throw new Error('e2e/host-pin')
    const proof = await signRemoteJwt(credential, 'twm-noise-bind+jwt', { v: 1, h: Buffer.from(binding.handshakeHash).toString('base64url'), runtime_id: manifest.runtimeId, installation_id: manifest.installationId,
      pair_id: credential.pairId, host_static: manifest.hostStaticPublicKey, device_static: manifest.deviceStaticPublicKey, host_key_epoch: manifest.hostKeyEpoch, device_key_epoch: manifest.deviceKeyEpoch,
      owner_sha256: bindingDigest(manifest.owner), purpose, ath: manifest.tokenSha256, iat: Math.floor(Date.now() / 1000), jti: randomBytes(32).toString('base64url') })
    binding.handshakeHash.fill(0)
    const auth = canonicalObject({ type: 'auth', pairId: credential.pairId, token: credential.token, hostKeyEpoch: manifest.hostKeyEpoch, deviceKeyEpoch: manifest.deviceKeyEpoch, proof, ...(relayLease ? { relayLease } : {}) })
    try { if (auth.length > 8192) throw new Error('e2e/auth-limit'); await send(auth) } finally { auth.fill(0) }
    const ready = signingJson(await receive(8208), ['type', 'v', 'suite', 'dshVersion', 'runtimeId', 'installationId', 'pairId', 'hostKeyEpoch', 'deviceKeyEpoch', 'ownerSha256', 'bindingSha256', 'peerGeneration'])
    if (ready.type !== 'ready' || ready.v !== 1 || ready.suite !== NOISE_SUITE || ready.dshVersion !== version || ready.runtimeId !== manifest.runtimeId || ready.installationId !== manifest.installationId ||
      ready.pairId !== credential.pairId || ready.hostKeyEpoch !== manifest.hostKeyEpoch || ready.deviceKeyEpoch !== manifest.deviceKeyEpoch || ready.ownerSha256 !== bindingDigest(manifest.owner) ||
      ready.bindingSha256 !== credential.bindingSha256 || typeof ready.peerGeneration !== 'string' || !/^[\x21-\x7e]{1,256}$/.test(ready.peerGeneration)) throw new Error('e2e/ready-binding')
    clearTimeout(deadline)
    plain = new Duplex({ allowHalfOpen: true, readableHighWaterMark: 65536, writableHighWaterMark: 65536,
      read() { wake?.(); wake = undefined },
      write(chunk: Buffer, _encoding, callback) {
        void (async () => { budget(); if (sentClose) throw new Error('e2e/after-close'); for (let offset = 0; offset < chunk.length; offset += 65518) {
          const part = chunk.subarray(offset, offset + 65518); payload[0] = 0; payload.set(part, 1)
          try { await send(payload.subarray(0, part.length + 1)); txBytes += BigInt(part.length) } finally { payload.fill(0) }
        } })().then(() => callback(), error => { callback(error); fail(error) })
      },
      final(callback) { void (async () => { payload.fill(0); payload[0] = 1; payload.writeBigUInt64BE(txBytes, 1); payload.writeBigUInt64BE(tx + 1n, 9); await send(payload.subarray(0, 17)); sentClose = true; if (receivedClose) outer!.end() })().then(() => callback(), error => { callback(error); fail(error) }) },
      destroy(error, callback) { if (error || !receivedClose || !sentClose) fail(error ?? new Error('e2e/cancelled')); callback(error) },
    })
    entry.plain = plain; plain.on('error', () => {})
    void (async () => {
      try {
        while (!stopped) {
          let bytes: Buffer
          try { bytes = await receive() } catch (error) { if (receivedClose && error instanceof Error && error.message === 'e2e/eof') break; throw error }
          if (receivedClose) throw new Error('e2e/after-close')
          if (bytes[0] === 0 && bytes.length >= 2) {
            rxBytes += BigInt(bytes.length - 1); const demand = Promise.withResolvers<void>(); wake = demand.resolve
            if (!plain!.push(Buffer.from(bytes.subarray(1)))) { budget(); await demand.promise }
            wake = undefined
          } else if (bytes[0] === 1 && bytes.length === 17 && bytes.readBigUInt64BE(1) === rxBytes && bytes.readBigUInt64BE(9) === rx) {
            receivedClose = true; plain!.push(null); if (sentClose) outer!.end()
          } else throw new Error('e2e/record-kind')
        }
      } catch (error) { fail(error instanceof Error ? error : new Error('e2e/failed')) }
      finally { await cleanup() }
    })()
    return { stream: plain, ready: Object.freeze(ready) as unknown as NoiseReady, close: () => fail() }
  } catch (error) { fail(error instanceof Error ? error : new Error('e2e/failed')); await cleanup(); throw error }
}
