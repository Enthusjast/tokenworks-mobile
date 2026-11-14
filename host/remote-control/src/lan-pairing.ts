import { createHash, randomInt, timingSafeEqual, X509Certificate } from 'node:crypto'
import { isIP } from 'node:net'
import { calculateJwkThumbprint, decodeProtectedHeader, importJWK, jwtVerify } from 'jose'
import { z } from 'zod'
import type { HostConnectionHandle } from '@deepseek-ai/dsh-client-connection'
import { generateNoiseStatic, NOISE_SUITE } from './native-noise.js'
import { LanPairingStore, bindingDigest, manifestSchema, rawKey, type HostNoiseKey, type LanPairedDevice, type PairingManifest } from './lan-pairing-store.js'
import { PairedPeerRegistry } from './paired-peer-registry.js'

export interface LanPairingIdentity { origin: string; runtimeId: string; installationId: string; tlsPin: string }
export interface PairedIdentity { runtimeId: string; installationId: string; manifest: PairingManifest; bindingSha256: string }
export interface LanPairingDescriptor extends LanPairingIdentity { version: 2; carrierVersion: 1; suite: typeof NOISE_SUITE; hostStaticPublicKey: string; hostKeyEpoch: number; minimumEncrypted: true; code: string; expiresAt: number }
export interface RelayLeaseCredential { readonly leaseId: string; readonly token: string }
export interface AuthenticatedPair { readonly record: LanPairedDevice; readonly token: string; readonly handshakeHash: string; readonly purpose: CarrierPurpose; readonly relayLease?: RelayLeaseCredential }
export type CarrierPurpose = 'native' | 'pairing-control'
export const purposeSchema = z.enum(['native', 'pairing-control'])
export const relayLeaseSchema = z.strictObject({ leaseId: z.string().uuid(), token: rawKey })
const authSchema = z.strictObject({ type: z.literal('auth'), pairId: z.string().uuid(), token: rawKey, hostKeyEpoch: z.number().int().positive(), deviceKeyEpoch: z.number().int().positive(), proof: z.string().min(100).max(8192),
  relayLease: relayLeaseSchema.optional() })
export const rotationSchema = z.strictObject({ operationId: z.string().uuid(), expectedDeviceKeyEpoch: z.number().int().positive(), newDeviceStaticPublicKey: rawKey, newDeviceKeyEpoch: z.number().int().positive() })

/** Local operator approval, never Relay assertions, creates paired native owner authority. */
export class LanPairing {
  readonly identity: LanPairingIdentity; readonly peers: PairedPeerRegistry
  readonly #store: LanPairingStore; readonly #devices = new Map<string, LanPairedDevice>()
  readonly #fences = new Set<(pairId?: string) => void>()
  #key: HostNoiseKey; #deny = false; #closed = false
  #rotation: HostNoiseKey | undefined
  readonly #revoked = new Set<string>()
  #pending: { descriptor: LanPairingDescriptor; attempts: number } | undefined
  private constructor(identity: LanPairingIdentity, store: LanPairingStore, connection: HostConnectionHandle, key: HostNoiseKey) {
    this.identity = Object.freeze(identity); this.#store = store; this.peers = new PairedPeerRegistry(connection); this.#key = key
  }
  static async open(identity: Omit<LanPairingIdentity, 'tlsPin'>, certificate: string, store: LanPairingStore, connection: HostConnectionHandle): Promise<LanPairing> {
    const origin = new URL(identity.origin)
    if (origin.protocol !== 'https:' || origin.username || origin.password || origin.origin !== identity.origin) throw new Error('pairing requires explicit HTTPS origin')
    const cert = new X509Certificate(certificate), hostname = origin.hostname.replace(/^\[|\]$/g, '')
    if (Date.parse(cert.validFrom) > Date.now() || Date.parse(cert.validTo) <= Date.now() || !(isIP(hostname) ? cert.checkIP(hostname) : cert.checkHost(hostname))) throw new Error('pairing certificate invalid')
    const tlsPin = `sha256/${createHash('sha256').update(cert.publicKey.export({ type: 'spki', format: 'der' })).digest('base64')}`
    const pairing = new LanPairing({ ...identity, tlsPin }, store, connection, await store.hostKey())
    for (const record of await store.devices()) if (record.manifest.hostKeyEpoch === pairing.#key.epoch && record.manifest.hostStaticPublicKey === pairing.#key.publicKey) pairing.#devices.set(record.deviceId, record)
    return pairing
  }
  get hostKey(): Readonly<HostNoiseKey> { if (this.#closed || this.#deny) throw new Error('host key unavailable'); return this.#key }
  status(): { hostKeyEpoch: number; hostStaticPublicKey: string; denied: boolean; operationId: string } { return { hostKeyEpoch: this.#key.epoch, hostStaticPublicKey: this.#key.publicKey, denied: this.#deny, operationId: this.#rotation?.operationId ?? this.#key.operationId } }
  onFence(handler: (pairId?: string) => void): () => void { this.#fences.add(handler); return () => this.#fences.delete(handler) }
  #fence(pairId?: string): void { if (pairId) this.peers.invalidate(pairId); else this.peers.close(); for (const fence of this.#fences) fence(pairId) }
  begin(): LanPairingDescriptor {
    const key = this.hostKey
    const descriptor: LanPairingDescriptor = { version: 2, ...this.identity, carrierVersion: 1, suite: NOISE_SUITE, hostStaticPublicKey: key.publicKey, hostKeyEpoch: key.epoch, minimumEncrypted: true,
      code: String(randomInt(100_000_000)).padStart(8, '0'), expiresAt: Date.now() + 120_000 }
    this.#pending = { descriptor, attempts: 0 }; return { ...descriptor }
  }
  list(): { deviceId: string; deviceName: string; createdAt: number; deviceKeyEpoch: number; hostKeyEpoch: number }[] { return [...this.#devices.values()].map(record => ({ deviceId: record.deviceId, deviceName: record.deviceName, createdAt: record.createdAt, deviceKeyEpoch: record.manifest.deviceKeyEpoch, hostKeyEpoch: record.manifest.hostKeyEpoch })) }
  async #proof(compact: string, method: string, path: string, jwk?: LanPairedDevice['publicJwk'], token?: string, nonce?: string): Promise<{ jwk: LanPairedDevice['publicJwk']; jti: string; issuedAt: number }> {
    if (compact.length > 8192 || compact.length < 100 || path.includes('#')) throw new Error('pair proof refused')
    const header = decodeProtectedHeader(compact), key = this.#proofKey(header.jwk, jwk)
    const verified = await jwtVerify(compact, await importJWK(key, 'ES256'), { algorithms: ['ES256'], typ: 'dpop+jwt', maxTokenAge: '60s', clockTolerance: 5, requiredClaims: ['iat', 'jti', 'htm', 'htu'] })
    const claims = verified.payload, target = new URL(path, this.identity.origin); target.search = ''; target.hash = ''
    if (target.origin !== this.identity.origin || claims.htm !== method || claims.htu !== target.href || typeof claims.jti !== 'string' || !/^[\x21-\x7e]{16,256}$/.test(claims.jti) || !Number.isSafeInteger(claims.iat) ||
      (nonce !== undefined && claims.nonce !== nonce) || (token !== undefined && claims.ath !== createHash('sha256').update(token).digest('base64url'))) throw new Error('pair request proof refused')
    return { jwk: key, jti: claims.jti, issuedAt: claims.iat! * 1000 }
  }
  #proofKey(value: unknown, expected?: LanPairedDevice['publicJwk']): LanPairedDevice['publicJwk'] {
    const key = z.strictObject({ kty: z.literal('EC'), crv: z.literal('P-256'), x: rawKey, y: rawKey }).parse(value)
    if (expected && (key.x !== expected.x || key.y !== expected.y)) throw new Error('paired P256 key refused'); return key
  }
  async pair(code: string, deviceName: string, proof: string, deviceId: string, token: string, input: unknown): Promise<LanPairingIdentity & { deviceId: string; token: string; manifest: PairingManifest; bindingSha256: string }> {
    const pending = this.#pending, key = this.hostKey, manifest = manifestSchema.parse(input), digest = bindingDigest(manifest)
    if (!pending || ++pending.attempts > 8 || pending.descriptor.expiresAt <= Date.now()) { this.#pending = undefined; throw new Error('pair code unavailable') }
    if (!/^\d{8}$/.test(code) || !timingSafeEqual(Buffer.from(code), Buffer.from(pending.descriptor.code)) || !deviceName.trim() || deviceName !== deviceName.trim() || deviceName.length > 80 || /[\x00-\x1f\x7f]/.test(deviceName) ||
      !z.string().uuid().safeParse(deviceId).success || !rawKey.safeParse(token).success || this.#devices.has(deviceId) || manifest.pairId !== deviceId || manifest.runtimeId !== this.identity.runtimeId || manifest.installationId !== this.identity.installationId ||
      manifest.hostStaticPublicKey !== key.publicKey || manifest.hostKeyEpoch !== key.epoch || manifest.deviceKeyEpoch !== 1 || manifest.tokenSha256 !== createHash('sha256').update(token).digest('base64url')) throw new Error('pair manifest refused')
    const signed = await this.#proof(proof, 'POST', '/pair', undefined, token, `${code}.${digest}`)
    if (manifest.proofJkt !== await calculateJwkThumbprint(signed.jwk, 'sha256') || this.#pending !== pending || this.#closed || this.#deny) throw new Error('pair binding refused')
    this.#pending = undefined
    if (this.#devices.size >= 32) throw new Error('paired device limit')
    const record: LanPairedDevice = { version: 2, deviceId, deviceName, createdAt: Date.now(), tokenHash: manifest.tokenSha256, publicJwk: signed.jwk, manifest, bindingSha256: digest, authorizationEpoch: 1, localAuthorization: 'owner' }
    await this.#store.add(record)
    if (this.#closed || this.#deny || this.#revoked.has(deviceId) || this.#key.epoch !== manifest.hostKeyEpoch) throw new Error('pairing closed')
    this.#devices.set(deviceId, record); return { ...this.identity, deviceId, token, manifest, bindingSha256: digest }
  }
  #credential(deviceId: string, token: string): LanPairedDevice {
    const current = this.#devices.get(deviceId)
    if (this.#closed || this.#deny || !current || this.#revoked.has(deviceId) || current.manifest.hostKeyEpoch !== this.#key.epoch || !rawKey.safeParse(token).success || !timingSafeEqual(Buffer.from(current.tokenHash), Buffer.from(createHash('sha256').update(token).digest('base64url')))) throw new Error('pair revoked or unauthorized')
    return current
  }
  async verifyCredential(deviceId: string, authorization: string, proof: string, method: string, path: string): Promise<LanPairedDevice> {
    const token = authorization.startsWith('DPoP ') ? authorization.slice(5) : '', current = this.#credential(deviceId, token)
    const signed = await this.#proof(proof, method, path, current.publicJwk, token)
    await this.#store.consumeProof(deviceId, signed.jti, signed.issuedAt)
    if (this.#credential(deviceId, token) !== current) throw new Error('pair changed'); return current
  }
  identityFor(record: LanPairedDevice): PairedIdentity { return { runtimeId: this.identity.runtimeId, installationId: this.identity.installationId, manifest: record.manifest, bindingSha256: record.bindingSha256 } }
  async authenticate(input: unknown, remoteStatic: Uint8Array, handshakeHash: Uint8Array, purpose: CarrierPurpose): Promise<AuthenticatedPair> {
    const auth = authSchema.parse(input), current = this.#credential(auth.pairId, auth.token), manifest = current.manifest
    if (auth.hostKeyEpoch !== this.hostKey.epoch || auth.deviceKeyEpoch !== manifest.deviceKeyEpoch || manifest.hostKeyEpoch !== auth.hostKeyEpoch || !timingSafeEqual(Buffer.from(remoteStatic), Buffer.from(manifest.deviceStaticPublicKey, 'base64url'))) throw new Error('noise paired key refused')
    const header = decodeProtectedHeader(auth.proof), jwk = this.#proofKey(header.jwk, current.publicJwk)
    if (Object.keys(header).sort().join(',') !== 'alg,jwk,typ') throw new Error('noise proof header refused')
    const result = await jwtVerify(auth.proof, await importJWK(jwk, 'ES256'), { algorithms: ['ES256'], typ: 'twm-noise-bind+jwt', clockTolerance: 90, requiredClaims: ['iat', 'jti'] })
    const claims = result.payload, expected = { v: 1, h: Buffer.from(handshakeHash).toString('base64url'), runtime_id: this.identity.runtimeId, installation_id: this.identity.installationId, pair_id: current.deviceId,
      host_static: manifest.hostStaticPublicKey, device_static: manifest.deviceStaticPublicKey, host_key_epoch: manifest.hostKeyEpoch, device_key_epoch: manifest.deviceKeyEpoch,
      owner_sha256: bindingDigest(manifest.owner), purpose, ath: current.tokenHash }
    if (Object.keys(claims).sort().join(',') !== [...Object.keys(expected), 'iat', 'jti'].sort().join(',') || Object.entries(expected).some(([name, value]) => claims[name] !== value) ||
      !Number.isSafeInteger(claims.iat) || Math.abs(Date.now() / 1000 - claims.iat!) > 90 || typeof claims.jti !== 'string' || !rawKey.safeParse(claims.jti).success) throw new Error('noise channel proof refused')
    await this.#store.consumeProof(current.deviceId, claims.jti, claims.iat! * 1000)
    if (this.#credential(current.deviceId, auth.token) !== current) throw new Error('pair changed')
    return Object.freeze({ record: current, token: auth.token, handshakeHash: expected.h, purpose, ...(auth.relayLease ? { relayLease: Object.freeze(auth.relayLease) } : {}) })
  }
  assertActive(pair: AuthenticatedPair): void { if (this.#credential(pair.record.deviceId, pair.token) !== pair.record) throw new Error('pair changed') }
  async rotateDevice(pair: AuthenticatedPair, input: unknown, proof: string): Promise<PairedIdentity> {
    this.assertActive(pair); const body = rotationSchema.parse(input), old = pair.record
    if (pair.purpose !== 'pairing-control' || body.expectedDeviceKeyEpoch !== old.manifest.deviceKeyEpoch || body.newDeviceKeyEpoch !== body.expectedDeviceKeyEpoch + 1 || body.newDeviceStaticPublicKey === old.manifest.deviceStaticPublicKey) throw new Error('device rotation refused')
    const nonce = bindingDigest({ ...body, handshakeHash: pair.handshakeHash, oldDeviceStaticPublicKey: old.manifest.deviceStaticPublicKey }), signed = await this.#proof(proof, 'PUT', '/pair/noise-key', old.publicJwk, pair.token, nonce)
    await this.#store.consumeProof(old.deviceId, signed.jti, signed.issuedAt); this.assertActive(pair)
    this.#devices.delete(old.deviceId); this.#fence(old.deviceId)
    const manifest = { ...old.manifest, deviceStaticPublicKey: body.newDeviceStaticPublicKey, deviceKeyEpoch: body.newDeviceKeyEpoch }, record = { ...old, manifest, bindingSha256: bindingDigest(manifest), authorizationEpoch: old.authorizationEpoch + 1 }
    await this.#store.rotateDevice(record, body.expectedDeviceKeyEpoch, body.operationId)
    if (this.#closed || this.#deny || this.#revoked.has(old.deviceId) || record.manifest.hostKeyEpoch !== this.#key.epoch) throw new Error('pairing closed'); this.#devices.set(old.deviceId, record); return this.identityFor(record)
  }
  async revoke(deviceId: string): Promise<void> { z.string().uuid().parse(deviceId); this.#revoked.add(deviceId); this.#devices.delete(deviceId); this.#fence(deviceId); await this.#store.revoke(deviceId) }
  async rotateHost(operationId: string): Promise<{ operationId: string; hostKeyEpoch: number; hostStaticPublicKey: string }> {
    z.string().uuid().parse(operationId)
    if (this.#key.epoch > 1 && this.#key.operationId === operationId && !this.#deny) return { operationId, hostKeyEpoch: this.#key.epoch, hostStaticPublicKey: this.#key.publicKey }
    if (!this.#rotation) {
      const used = await this.#store.hasHostOperation(operationId)
      if (this.#key.epoch > 1 && this.#key.operationId === operationId && !this.#deny) return { operationId, hostKeyEpoch: this.#key.epoch, hostStaticPublicKey: this.#key.publicKey }
      const pending = this.#rotation as HostNoiseKey | undefined // Another request may have staged while storage was awaited.
      if (used && pending?.operationId !== operationId) throw new Error('host rotation operation already used or superseded')
    }
    if (this.#closed || (this.#rotation && this.#rotation.operationId !== operationId)) throw new Error('host rotation unresolved')
    this.#deny = true; this.#pending = undefined; this.#devices.clear(); this.#fence()
    if (!this.#rotation) {
      const generated = generateNoiseStatic()
      try { this.#rotation = { epoch: this.#key.epoch + 1, operationId, privateKey: Buffer.from(generated.privateKey).toString('base64url'), publicKey: Buffer.from(generated.publicKey).toString('base64url') } }
      finally { generated.privateKey.fill(0) }
    }
    const next = this.#rotation
    await this.#store.rotateHost(next, this.#key.epoch)
    this.#key = next; this.#rotation = undefined; this.#deny = false
    return { operationId, hostKeyEpoch: next.epoch, hostStaticPublicKey: next.publicKey }
  }
  close(): void { this.#closed = true; this.#pending = undefined; this.#fence(); this.#devices.clear() }
}
