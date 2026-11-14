import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'
import { Duplex } from 'node:stream'
import { once } from 'node:events'
import { canonicalObject, parseSigningObject } from './canonical.js'
import { NativeNoiseSession, NOISE_SUITE } from './native-noise.js'
import { bindingDigest } from './lan-pairing-store.js'
import { purposeSchema, type AuthenticatedPair, type CarrierPurpose, type LanPairing, type RelayLeaseCredential } from './lan-pairing.js'
import type { NativeGateway } from './native-gateway.js'
import type { NativeLease, NativePeerAdmission } from './native-access.js'
import type { PeerScope } from '@deepseek-ai/dsh-typert-protocol'

const CONNECTION_BYTES = 256 * 1024, PAIR_BYTES = 2 * 1024 * 1024, HOST_BYTES = 32 * 1024 * 1024, REKEY = 2 ** 20
export interface CarrierAdmission {
  routeKind: 'lan' | 'relay' | 'p2p' | 'turn'; purpose: CarrierPurpose; pairId?: string
  relayLease?: RelayLeaseCredential
  inheritedRelay?: { readonly lease: NativeLease; readonly signal: AbortSignal }
}
interface ActiveCarrier { outer: Duplex; plain?: Duplex; pair?: AuthenticatedPair; expectedPairId?: string; bytes: number; controlAck: boolean; controlDeadline?: NodeJS.Timeout }
export type RelayAuthorityFactory = (leaseId: string, token: string, pairedOwner: () => PeerScope) => Promise<NativePeerAdmission>
export interface RtcControlAuthority { readonly lease: NativeLease; readonly fork: (pairedOwner: () => PeerScope) => Promise<NativePeerAdmission> }
export interface RtcControlHandler {
  handle(pair: AuthenticatedPair, method: string, path: string, body: unknown, signal: AbortSignal, authority: RtcControlAuthority | undefined): Promise<{ status: number; body: object }>
  close(): void
  drain(): Promise<void>
}
interface QueuedCarrier extends Duplex { carrierQueuedBytes?: () => { inbound: number; outbound: number } }

/** Ciphertext framing only. Native HTTP/WS/file formats are never decoded here. */
export class NoiseCarrier {
  readonly #active = new Set<ActiveCarrier>()
  readonly #control = createServer({ maxHeaderSize: 32 * 1024 })
  readonly #contexts = new WeakMap<object, { pair: AuthenticatedPair; entry: ActiveCarrier; used: boolean; authority: RtcControlAuthority | undefined; authoritySignal: AbortSignal | undefined }>()
  readonly #unfence: () => void
  #rtc?: RtcControlHandler
  #relayAuthority?: RelayAuthorityFactory
  setRelayAuthority(authority: RelayAuthorityFactory): void { if (this.#relayAuthority) throw new Error('e2e/authority-already-attached'); this.#relayAuthority = authority }
  setRtcControl(control: RtcControlHandler): void { if (this.#rtc) throw new Error('rtc/already-attached'); this.#rtc = control }
  assertOuterBudget(outer: Duplex, inbound = 0, outbound = 0): void { for (const entry of this.#active) if (entry.outer === outer) { this.#budget(entry, inbound, outbound); return } }
  constructor(readonly pairing: LanPairing, readonly gateway: NativeGateway, readonly dshVersion: string, readonly relayOrigin: string) {
    this.#unfence = pairing.onFence(pairId => { for (const entry of this.#active) if ((!pairId || entry.pair?.record.deviceId === pairId) && (!pairId || !entry.controlAck)) entry.outer.destroy(new Error('e2e/revoked')) })
    this.#control.requestTimeout = 10_000; this.#control.headersTimeout = 10_000
    this.#control.on('request', (request, response) => { void this.#controlRequest(request, response) })
    this.#control.on('upgrade', (_request, socket) => socket.destroy())
    this.#control.on('clientError', (_error, socket) => socket.destroy())
  }
  async admitRelayAuthority(pair: AuthenticatedPair, credential: RelayLeaseCredential, pairedOwner?: () => PeerScope): Promise<NativePeerAdmission> {
    this.pairing.assertActive(pair)
    const scope = pair.record.manifest.relay, authority = this.#relayAuthority
    if (!authority) throw new Error('e2e/relay-authority-required')
    if (!scope || new URL(scope.issuer).origin !== this.relayOrigin || scope.userId !== pair.record.manifest.owner.user_id || scope.deviceId !== pair.record.manifest.owner.device_id || scope.organizationId !== pair.record.manifest.owner.organization_id || scope.deploymentSha256 !== pair.record.manifest.owner.deployment_sha256) throw new Error('e2e/relay-binding')
    const admitted = await authority(credential.leaseId, credential.token, () => {
      this.pairing.assertActive(pair)
      return pairedOwner ? pairedOwner() : this.pairing.peers.acquire(pair.record).peer
    })
    try {
      this.pairing.assertActive(pair)
      const lease = admitted.lease
      if (lease.sub !== scope.userId || lease.device_id !== scope.deviceId || lease.organization_id !== scope.organizationId || lease.runtime_id !== this.pairing.identity.runtimeId || lease.installation_id !== this.pairing.identity.installationId || lease.aud !== this.pairing.identity.runtimeId) throw new Error('e2e/relay-pair-mismatch')
      if (admitted.signal.aborted || lease.exp * 1000 <= Date.now()) throw new Error('e2e/lease-revoked')
      return admitted
    } catch (error) { admitted.release(); throw error }
  }
  #budget(entry: ActiveCarrier, extraIn = 0, extraOut = 0): void {
    const pairId = entry.pair?.record.deviceId ?? entry.expectedPairId
    let host = extraIn + extraOut, pair = extraIn + extraOut, connections = 0
    const queued = (item: ActiveCarrier) => {
      const extra = (item.outer as QueuedCarrier).carrierQueuedBytes?.() ?? { inbound: 0, outbound: 0 }
      return { inbound: item.outer.readableLength + (item.plain?.readableLength ?? 0) + extra.inbound,
        outbound: item.outer.writableLength + (item.plain?.writableLength ?? 0) + extra.outbound }
    }
    for (const item of this.#active) {
      const bytes = queued(item); item.bytes = bytes.inbound + bytes.outbound; host += item.bytes
      if (pairId && (item.pair?.record.deviceId ?? item.expectedPairId) === pairId) { pair += item.bytes; connections++ }
    }
    const bytes = queued(entry)
    if (bytes.inbound + extraIn > CONNECTION_BYTES || bytes.outbound + extraOut > CONNECTION_BYTES || host > HOST_BYTES || pair > PAIR_BYTES || connections > 8) throw new Error('e2e/budget')
  }
  async accept(outer: Duplex, admission: CarrierAdmission): Promise<void> {
    if (this.#active.size >= 128) { outer.destroy(); return }
    const entry: ActiveCarrier = { outer, ...(admission.pairId ? { expectedPairId: admission.pairId } : {}), bytes: 0, controlAck: false }; this.#active.add(entry)
    let session: NativeNoiseSession | undefined, relay: NativePeerAdmission | undefined
    let tx = 0n, rx = 0n, txBytes = 0n, rxBytes = 0n, sentClose = false, receivedClose = false, stopped = false
    const record = Buffer.alloc(65535), decoded = Buffer.alloc(65535), wire = Buffer.alloc(65537), output = wire.subarray(2), sendPlain = Buffer.alloc(65519), prefix = Buffer.alloc(2)
    let recordDeadline: NodeJS.Timeout | undefined, readableWake: (() => void) | undefined
    const handshakeDeadline = setTimeout(() => outer.destroy(new Error('e2e/handshake-timeout')), 10_000); handshakeDeadline.unref()
    const closed = () => { stopped = true; readableWake?.(); entry.plain?.destroy(receivedClose && sentClose ? undefined : new Error('e2e/truncated')) }
    outer.once('close', closed); outer.on('error', () => {})
    const readExact = async (target: Buffer, size: number): Promise<void> => {
      let offset = 0
      while (offset < size) {
        this.#budget(entry)
        const chunk = outer.read(Math.min(size - offset, Math.max(1, outer.readableLength))) as Buffer | null
        if (chunk) {
          if (!recordDeadline) { recordDeadline = setTimeout(() => outer.destroy(new Error('e2e/record-timeout')), 30_000); recordDeadline.unref() }
          chunk.copy(target, offset); offset += chunk.length; continue
        }
        if (stopped || outer.destroyed || outer.readableEnded) throw new Error(offset === 0 && target === prefix && receivedClose ? 'e2e/eof' : 'e2e/truncated')
        const pending = Promise.withResolvers<void>()
        const ready = () => pending.resolve(), failed = () => pending.reject(new Error(receivedClose && offset === 0 && target === prefix ? 'e2e/eof' : 'e2e/truncated'))
        outer.once('readable', ready); outer.once('end', failed); outer.once('close', failed); outer.once('error', failed)
        try { await pending.promise } finally { outer.off('readable', ready); outer.off('end', failed); outer.off('close', failed); outer.off('error', failed) }
      }
    }
    const readRecord = async (max: number, min = 1): Promise<Buffer> => {
      try {
        await readExact(prefix, 2); const length = prefix.readUInt16BE()
        if (length < min || length > max) throw new Error('e2e/record-length')
        await readExact(record, length); return record.subarray(0, length)
      } finally { clearTimeout(recordDeadline); recordDeadline = undefined }
    }
    const writeRecord = async (bytes: Uint8Array): Promise<void> => {
      if (bytes.length > 65535 || stopped || outer.destroyed) throw new Error('e2e/closed')
      wire.writeUInt16BE(bytes.length)
      if (bytes.buffer !== output.buffer || bytes.byteOffset !== output.byteOffset) wire.set(bytes, 2)
      this.#budget(entry)
      const deadline = setTimeout(() => outer.destroy(new Error('e2e/write-timeout')), 30_000); deadline.unref()
      const pending = Promise.withResolvers<void>()
      try { outer.write(wire.subarray(0, bytes.length + 2), error => error ? pending.reject(error) : pending.resolve()); await pending.promise }
      finally { wire.fill(0); clearTimeout(deadline) }
    }
    const send = async (plaintext: Uint8Array): Promise<void> => {
      const length = session!.encrypt(plaintext, output); await writeRecord(output.subarray(0, length)); tx++
      if (tx % BigInt(REKEY) === 0n) session!.rekeySend()
    }
    const receive = async (max: number): Promise<Buffer> => {
      const ciphertext = await readRecord(max, 17), length = session!.decrypt(ciphertext, decoded); rx++
      if (rx % BigInt(REKEY) === 0n) session!.rekeyReceive()
      return decoded.subarray(0, length)
    }
    const json = (bytes: Buffer): Record<string, unknown> => {
      const value = parseSigningObject(bytes)
      if (!Buffer.from(canonicalObject(value)).equals(bytes)) throw new Error('e2e/noncanonical'); return value
    }
    try {
      const purpose = purposeSchema.parse(admission.purpose), key = this.pairing.hostKey
      const hello = canonicalObject({ magic: 'tokenworks-noise', v: 1, suite: NOISE_SUITE, runtimeId: this.pairing.identity.runtimeId, installationId: this.pairing.identity.installationId,
        hostKeyEpoch: key.epoch, hostStaticPublicKey: key.publicKey, purpose, routeKind: admission.routeKind, dshVersion: this.dshVersion, capabilities: ['native.http.v1', 'noise.ik.v1', ...(['p2p', 'turn'].includes(admission.routeKind) ? ['transport.webrtc.v1'] : [])] })
      const privateKey = Buffer.from(key.privateKey, 'base64url')
      try { session = new NativeNoiseSession(false, privateKey, null, hello) } finally { privateKey.fill(0) }
      await writeRecord(hello)
      if (session.handshakeRead(await readRecord(4096), decoded) !== 0) throw new Error('e2e/handshake-payload')
      const ik2 = session.handshakeWrite(new Uint8Array(), output); await writeRecord(output.subarray(0, ik2))
      const binding = session.finishHandshake(), pair = await this.pairing.authenticate(json(await receive(8208)), binding.remoteStatic, binding.handshakeHash, purpose)
      entry.pair = pair
      if (admission.pairId && admission.pairId !== pair.record.deviceId) throw new Error('e2e/outer-pair-mismatch')
      let peerGeneration: string, peer: PeerScope | undefined
      const relayCredential = pair.relayLease, accountBound = pair.record.manifest.relay !== undefined
      if (admission.routeKind === 'relay') {
        if (!admission.relayLease || !relayCredential || admission.relayLease.leaseId !== relayCredential.leaseId || admission.relayLease.token !== relayCredential.token) throw new Error('e2e/relay-outer-mismatch')
        if (!accountBound) throw new Error('e2e/relay-binding')
      } else if (admission.relayLease) throw new Error('e2e/unexpected-relay-metadata')
      if (!accountBound && (relayCredential || admission.inheritedRelay)) throw new Error('e2e/relay-binding')
      if (accountBound && purpose === 'native' && !relayCredential) throw new Error('e2e/relay-lease-required')
      if (relayCredential) {
        relay = await this.admitRelayAuthority(pair, relayCredential)
        if (admission.inheritedRelay) {
          const cap = admission.inheritedRelay
          if (cap.signal.aborted || !sameRelayAuthority(cap.lease, relay.lease)) throw new Error('e2e/relay-signal-cap')
          const revoked = () => outer.destroy(new Error('e2e/lease-revoked'))
          cap.signal.addEventListener('abort', revoked, { once: true }); outer.once('close', () => cap.signal.removeEventListener('abort', revoked))
          if (cap.signal.aborted) throw new Error('e2e/lease-revoked')
        }
        const expired = () => outer.destroy(new Error('e2e/lease-revoked')); relay.signal.addEventListener('abort', expired, { once: true }); outer.once('close', () => relay?.signal.removeEventListener('abort', expired))
        if (relay.signal.aborted) throw new Error('e2e/lease-revoked')
        if (purpose === 'pairing-control') peerGeneration = this.pairing.peers.generation(pair.record)
        else if (!relay.ownerEligible) { peer = relay.peer; peerGeneration = relay.peerGeneration }
        else { const stable = this.pairing.peers.acquire(pair.record); peer = stable.peer; peerGeneration = stable.generation }
      } else if (purpose === 'pairing-control') peerGeneration = this.pairing.peers.generation(pair.record)
      else { const stable = this.pairing.peers.acquire(pair.record); peer = stable.peer; peerGeneration = stable.generation }
      this.pairing.assertActive(pair); this.#budget(entry)
      await send(canonicalObject({ type: 'ready', v: 1, suite: NOISE_SUITE, dshVersion: this.dshVersion, runtimeId: this.pairing.identity.runtimeId, installationId: this.pairing.identity.installationId,
        pairId: pair.record.deviceId, hostKeyEpoch: pair.record.manifest.hostKeyEpoch, deviceKeyEpoch: pair.record.manifest.deviceKeyEpoch, ownerSha256: bindingDigest(pair.record.manifest.owner), bindingSha256: pair.record.bindingSha256, peerGeneration }))
      clearTimeout(handshakeDeadline)
      const plain = new Duplex({ readableHighWaterMark: 65536, writableHighWaterMark: 65536, allowHalfOpen: true,
        read: () => { readableWake?.(); readableWake = undefined; try { this.#budget(entry) } catch (error) { outer.destroy(error as Error) } },
        write: (chunk: Buffer, _encoding, callback) => {
          void (async () => { if (!(purpose === 'pairing-control' && entry.controlAck)) this.pairing.assertActive(pair); this.#budget(entry); if (sentClose) throw new Error('e2e/after-close')
            for (let offset = 0; offset < chunk.length; offset += 65518) { const data = chunk.subarray(offset, offset + 65518); sendPlain[0] = 0; sendPlain.set(data, 1); try { await send(sendPlain.subarray(0, data.length + 1)); txBytes += BigInt(data.length) } finally { sendPlain.fill(0) } }
          })().then(() => callback(), error => { callback(error); outer.destroy(error) })
        },
        final: callback => { void (async () => { sendPlain.fill(0); sendPlain[0] = 1; sendPlain.writeBigUInt64BE(txBytes, 1); sendPlain.writeBigUInt64BE(tx + 1n, 9); await send(sendPlain.subarray(0, 17)); sentClose = true; if (receivedClose) outer.end() })().then(() => callback(), callback) },
        destroy: (error, callback) => { if (error || !sentClose || !receivedClose) outer.destroy(error ?? new Error('e2e/cancelled')); callback(error) },
      })
      entry.plain = plain; plain.on('error', () => {})
      if (purpose === 'native') { const nativePeer = peer!; const revoked = () => plain.destroy(new Error('e2e/revoked')); nativePeer.signal.addEventListener('abort', revoked, { once: true }); plain.once('close', () => nativePeer.signal.removeEventListener('abort', revoked)); this.gateway.accept(plain, nativePeer) }
      else {
        if (admission.routeKind !== 'lan' && admission.routeKind !== 'relay') throw new Error('e2e/control-ingress')
        const lease = relay?.lease, credential = pair.relayLease
        const authority: RtcControlAuthority | undefined = lease && credential ? Object.freeze({ lease, fork: async (owner: () => PeerScope) => {
          const next = await this.admitRelayAuthority(pair, credential, owner)
          if (!sameRelayAuthority(lease, next.lease)) { next.release(); throw new Error('e2e/relay-grant-changed') }
          return next
        } }) : undefined
        this.#contexts.set(plain, { pair, entry, used: false, authority, authoritySignal: relay?.signal })
        entry.controlDeadline = setTimeout(() => plain.destroy(new Error('e2e/control-timeout')), 10_000); entry.controlDeadline.unref()
        plain.once('close', () => clearTimeout(entry.controlDeadline)); this.#control.emit('connection', plain)
      }
      while (!stopped) {
        let plaintext: Buffer
        try { plaintext = await receive(65535) } catch (error) { if (receivedClose && error instanceof Error && error.message === 'e2e/eof') break; throw error }
        if (receivedClose) throw new Error('e2e/after-close')
        if (plaintext[0] === 0 && plaintext.length >= 2) {
          this.pairing.assertActive(pair); rxBytes += BigInt(plaintext.length - 1)
          const pending = Promise.withResolvers<void>(); readableWake = pending.resolve
          if (!plain.push(Buffer.from(plaintext.subarray(1)))) { this.#budget(entry); await pending.promise }
          readableWake = undefined
        } else if (plaintext[0] === 1 && plaintext.length === 17 && plaintext.readBigUInt64BE(1) === rxBytes && plaintext.readBigUInt64BE(9) === rx) {
          receivedClose = true; plain.push(null); if (sentClose) outer.end()
          // Keep reading until EOF so an authenticated CLOSE cannot hide trailing records.
        } else throw new Error('e2e/record-kind')
      }
      if (receivedClose && !plain.writableFinished && !stopped) await once(plain, 'finish')
    } catch (error) { outer.destroy(error instanceof Error ? error : new Error('e2e/failed')) }
    finally { clearTimeout(handshakeDeadline); clearTimeout(recordDeadline); relay?.release(); session?.close(); record.fill(0); decoded.fill(0); wire.fill(0); sendPlain.fill(0); this.#active.delete(entry); outer.off('close', closed) }
  }
  async #controlRequest(request: IncomingMessage, response: ServerResponse): Promise<void> {
    const context = this.#contexts.get(request.socket)
    response.setHeader('Connection', 'close'); response.setHeader('Cache-Control', 'no-store')
    if (!context || context.used) { response.writeHead(403).end(); return }
    context.used = true
    try {
      this.pairing.assertActive(context.pair)
      if (request.method === 'GET' && request.url === '/pair/identity') response.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(this.pairing.identityFor(context.pair.record)))
      else if (request.url?.startsWith('/pair/rtc/')) {
        if (context.pair.record.manifest.relay && !context.authority) throw new Error('e2e/relay-lease-required')
        if (!this.#rtc) { response.writeHead(404, { 'Content-Type': 'application/json' }).end(JSON.stringify({ code: 'rtc/unsupported' })); return }
        clearTimeout(context.entry.controlDeadline)
        context.entry.controlDeadline = setTimeout(() => context.entry.plain?.destroy(new Error('e2e/control-timeout')), 20_000); context.entry.controlDeadline.unref()
        const abort = new AbortController()
        const cancelled = () => abort.abort()
        const closed = () => { if (!response.writableFinished) abort.abort() }
        request.once('aborted', cancelled); response.once('close', closed)
        context.entry.outer.once('close', cancelled)
        context.authoritySignal?.addEventListener('abort', cancelled, { once: true })
        if (context.entry.outer.destroyed || context.authoritySignal?.aborted) abort.abort()
        const buffer = Buffer.alloc(128 * 1024); let length = 0
        try {
          for await (const chunk of request) {
            if (!Buffer.isBuffer(chunk) || length + chunk.length > buffer.length) throw new Error('rtc/control-budget')
            chunk.copy(buffer, length); length += chunk.length
          }
          const body = length ? parseSigningObject(buffer.subarray(0, length)) : undefined
          const result = await this.#rtc.handle(context.pair, request.method ?? '', request.url, body, abort.signal, context.authority)
          this.pairing.assertActive(context.pair)
          response.writeHead(result.status, { 'Content-Type': 'application/json' }).end(JSON.stringify(result.body))
        } finally { buffer.fill(0); request.off('aborted', cancelled); response.off('close', closed); context.entry.outer.off('close', cancelled); context.authoritySignal?.removeEventListener('abort', cancelled) }
      }
      else if (request.method === 'DELETE' && request.url === '/pair') { context.entry.controlAck = true; await this.pairing.revoke(context.pair.record.deviceId); response.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ ok: true })) }
      else if (request.method === 'PUT' && request.url === '/pair/noise-key') {
        const chunks: Buffer[] = []; let bytes = 0
        for await (const chunk of request) { bytes += chunk.length; if (bytes > 8192) throw new Error('e2e/control-budget'); chunks.push(Buffer.from(chunk)) }
        if (typeof request.headers.dpop !== 'string') throw new Error('e2e/control-proof')
        context.entry.controlAck = true
        const result = await this.pairing.rotateDevice(context.pair, parseSigningObject(Buffer.concat(chunks)), request.headers.dpop)
        response.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(result))
      } else response.writeHead(404).end()
    } catch { if (!response.headersSent) response.writeHead(409).end(); else response.destroy() }
  }
  close(): void { this.#rtc?.close(); this.#unfence(); for (const entry of this.#active) entry.outer.destroy(); this.#control.close() }
  async drain(): Promise<void> { await this.#rtc?.drain() }
}

function sameRelayAuthority(left: NativeLease, right: NativeLease): boolean {
  return left.sub === right.sub && left.device_id === right.device_id && left.organization_id === right.organization_id &&
    left.runtime_id === right.runtime_id && left.installation_id === right.installation_id && left.aud === right.aud &&
    left.role === right.role && left.is_installation_owner === right.is_installation_owner && left.grant_version === right.grant_version &&
    JSON.stringify(left.bindings) === JSON.stringify(right.bindings)
}
