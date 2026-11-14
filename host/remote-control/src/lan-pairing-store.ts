import { createHash, randomUUID } from 'node:crypto'
import { opendir } from 'node:fs/promises'
import { isAbsolute, resolve } from 'node:path'
import { z } from 'zod'
import { canonicalObject } from './canonical.js'
import { generateNoiseStatic, NOISE_SUITE } from './native-noise.js'
import { protectorFrom, type JournalKeyProtector, type Protector } from './journal-key-protection.js'
import { MAX_FILE, seal, unseal } from './host-enrollment-recovery.js'
import { openLinuxPrivateDirectory, publishLinuxPrivateFile, readLinuxPrivateFile, unlinkLinuxPrivateFile, type LinuxPrivateDirectory } from './linux-private-files.js'

const uuid = z.string().uuid(), digest = z.string().regex(/^[a-f0-9]{64}$/)
export const rawKey = z.string().regex(/^[A-Za-z0-9_-]{43}$/).refine(value => Buffer.from(value, 'base64url').length === 32 && Buffer.from(value, 'base64url').toString('base64url') === value)
export const ownerSchema = z.strictObject({ account_id: uuid, organization_id: uuid, deployment_sha256: digest, user_id: uuid, device_id: uuid })
export const manifestSchema = z.strictObject({ v: z.literal(2), runtimeId: uuid, installationId: uuid, pairId: uuid, tokenSha256: rawKey, proofJkt: rawKey,
  deviceStaticPublicKey: rawKey, deviceKeyEpoch: z.number().int().positive(), hostStaticPublicKey: rawKey, hostKeyEpoch: z.number().int().positive(), owner: ownerSchema,
  relay: z.strictObject({ issuer: z.string().url(), organizationId: uuid, deploymentSha256: digest, userId: uuid, deviceId: uuid }).optional() })
export type PairingManifest = z.infer<typeof manifestSchema>
const recordSchema = z.strictObject({ version: z.literal(2), deviceId: uuid, deviceName: z.string().min(1).max(80), createdAt: z.number().int().nonnegative(),
  tokenHash: rawKey, publicJwk: z.strictObject({ kty: z.literal('EC'), crv: z.literal('P-256'), x: rawKey, y: rawKey }), manifest: manifestSchema,
  bindingSha256: digest, authorizationEpoch: z.number().int().positive(), localAuthorization: z.literal('owner') })
export type LanPairedDevice = z.infer<typeof recordSchema>
const hostSchema = z.strictObject({ epoch: z.number().int().positive(), privateKey: rawKey, publicKey: rawKey, operationId: uuid })
export type HostNoiseKey = z.infer<typeof hostSchema>
const magic = Buffer.from('TWMNOI02')
export const bindingDigest = (value: unknown): string => createHash('sha256').update(canonicalObject(value)).digest('hex')

/** Protected append-only records; publication includes file and directory fsync. */
export class LanPairingStore {
  readonly #path: string; readonly #protector: Protector; readonly #context: Buffer
  #pending: Promise<void> = Promise.resolve()
  constructor(directory: string, runtimeId: string, installationId: string, protector: JournalKeyProtector) {
    if (!isAbsolute(directory) || directory.includes('\0')) throw new Error('pairing directory must be absolute')
    uuid.parse(runtimeId); uuid.parse(installationId)
    this.#path = resolve(directory); this.#protector = protectorFrom(protector)
    this.#context = Buffer.from(`tokenworks-mobile-noise-pairing-v2\0${this.#path}\0${runtimeId}\0${installationId}`)
  }
  async #directory<T>(action: (directory: LinuxPrivateDirectory, signal: AbortSignal) => Promise<T>): Promise<T> {
    const previous = this.#pending, next = Promise.withResolvers<void>(); this.#pending = next.promise; await previous
    let directory: LinuxPrivateDirectory | undefined
    try { directory = await openLinuxPrivateDirectory(this.#path); return await action(directory, AbortSignal.timeout(15_000)) }
    finally { try { await directory?.file.close() } finally { next.resolve() } }
  }
  async #names(directory: LinuxPrivateDirectory): Promise<string[]> {
    const names: string[] = []; for await (const entry of await opendir(directory.proc)) { if (names.length >= 8192) throw new Error('pairing storage limit'); names.push(entry.name) }; return names
  }
  async #read(directory: LinuxPrivateDirectory, name: string, signal: AbortSignal): Promise<unknown> {
    const raw = await readLinuxPrivateFile(directory, name, MAX_FILE, signal); if (!raw) throw new Error('pairing commit missing')
    let plain: Buffer | undefined
    try {
      if (raw.bytes.subarray(0, 8).toString('ascii') === 'TWMLAN01') throw new Error('pairing/upgrade-required: operator re-pair required')
      plain = await unseal(raw.bytes, this.#protector, Buffer.concat([this.#context, Buffer.from(`\0${name}`)]), signal, magic); return JSON.parse(plain.toString('utf8'))
    }
    finally { raw.bytes.fill(0); plain?.fill(0) }
  }
  async #publish(directory: LinuxPrivateDirectory, name: string, value: unknown, signal: AbortSignal): Promise<void> {
    const existing = await readLinuxPrivateFile(directory, name, MAX_FILE, signal)
    if (existing) { existing.bytes.fill(0); if (bindingDigest(await this.#read(directory, name, signal)) !== bindingDigest(value)) throw new Error('pairing commit conflict'); await directory.file.sync(); return }
    const plain = Buffer.from(canonicalObject(value)); let encrypted: Buffer | undefined
    try { encrypted = await seal(plain, this.#protector, Buffer.concat([this.#context, Buffer.from(`\0${name}`)]), signal, magic); if (!await publishLinuxPrivateFile(directory, name, encrypted, MAX_FILE, signal)) throw new Error('pairing concurrent commit') }
    finally { plain.fill(0); encrypted?.fill(0) }
  }
  async hostKey(): Promise<HostNoiseKey> {
    return this.#directory(async (directory, signal) => {
      const names = await this.#names(directory), epochs = names.filter(name => /^host-key\.\d+$/.test(name)).map(name => Number(name.slice(9))).sort((a,b) => a-b)
      if (!epochs.length) {
        // Existing v1 records are never upgraded into an invented trust binding.
        if (names.some(name => name.startsWith('host-key-active.'))) throw new Error('host key missing')
        const generated = generateNoiseStatic()
        try { const key = hostSchema.parse({ epoch: 1, privateKey: Buffer.from(generated.privateKey).toString('base64url'), publicKey: Buffer.from(generated.publicKey).toString('base64url'), operationId: randomUUID() }); await this.#publishHost(directory, key, 0, signal); return key }
        finally { generated.privateKey.fill(0) }
      }
      let previous = 0, active: HostNoiseKey | undefined
      if (names.some(name => /^host-key-active\.\d+$/.test(name) && !names.includes(name.replace('host-key-active.', 'host-key.')))) throw new Error('host key commit missing material')
      for (const epoch of epochs) {
        const key = hostSchema.parse(await this.#read(directory, `host-key.${epoch}`, signal))
        if (key.epoch !== epoch || epoch !== previous + 1) throw new Error('host key chain invalid')
        // A durable stage is the exact operator-authorized rotate-and-revoke-all intent.
        // Finish its commit after a lost ACK; never accept the predecessor key.
        if (!names.includes(`host-key-active.${epoch}`)) await this.#publishHost(directory, key, previous, signal)
        const commit = await this.#read(directory, `host-key-active.${epoch}`, signal) as Record<string, unknown>
        if (key.epoch !== epoch || epoch !== previous + 1 || commit.previous !== previous || commit.digest !== bindingDigest(key) || commit.operationId !== key.operationId) throw new Error('host key chain invalid')
        active = key; previous = epoch
      }
      return active!
    })
  }
  async #publishHost(directory: LinuxPrivateDirectory, key: HostNoiseKey, previous: number, signal: AbortSignal): Promise<void> {
    await this.#publish(directory, `host-key.${key.epoch}`, key, signal)
    await this.#publish(directory, `host-key-active.${key.epoch}`, { previous, digest: bindingDigest(key), operationId: key.operationId }, signal)
  }
  async rotateHost(key: HostNoiseKey, previous: number): Promise<void> { await this.#directory((directory, signal) => this.#publishHost(directory, hostSchema.parse(key), previous, signal)) }
  async hasHostOperation(operationId: string): Promise<boolean> { return this.#directory(async (directory, signal) => {
    for (const name of await this.#names(directory)) if (/^host-key\.\d+$/.test(name) && hostSchema.parse(await this.#read(directory, name, signal)).operationId === operationId) return true
    return false
  }) }
  async devices(): Promise<LanPairedDevice[]> {
    return this.#directory(async (directory, signal) => {
      const names = await this.#names(directory), devices: LanPairedDevice[] = []
      for (const name of names.filter(name => /^device\.[0-9a-f-]{36}$/.test(name))) {
        const id = name.slice(7); if (names.includes(`revoked.${id}`)) { await this.#read(directory, `revoked.${id}`, signal); continue }
        let record = recordSchema.parse(await this.#read(directory, name, signal))
        if (record.deviceId !== id || bindingDigest(record.manifest) !== record.bindingSha256) throw new Error('pairing identity changed')
        if (names.some(name => name.startsWith(`device-key-active.${id}.`) && !names.includes(name.replace('device-key-active.', 'device-key.')))) throw new Error('device key commit missing material')
        const epochs = names.filter(name => name.startsWith(`device-key.${id}.`)).map(name => Number(name.split('.').at(-1))).sort((a,b) => a-b)
        for (const epoch of epochs) {
          const next = recordSchema.parse(await this.#read(directory, `device-key.${id}.${epoch}`, signal)), commit = await this.#read(directory, `device-key-active.${id}.${epoch}`, signal) as Record<string, unknown>
          if (epoch !== record.manifest.deviceKeyEpoch + 1 || next.manifest.deviceKeyEpoch !== epoch || commit.previous !== record.manifest.deviceKeyEpoch || commit.digest !== bindingDigest(next) || bindingDigest(next.manifest) !== next.bindingSha256) throw new Error('device key chain invalid')
          record = next
        }
        devices.push(record)
      }
      return devices
    })
  }
  async add(device: LanPairedDevice): Promise<void> { await this.#directory(async (directory, signal) => {
    if (await readLinuxPrivateFile(directory, `revoked.${device.deviceId}`, MAX_FILE, signal)) throw new Error('pair revoked')
    await this.#publish(directory, `device.${device.deviceId}`, recordSchema.parse(device), signal)
  }) }
  async rotateDevice(device: LanPairedDevice, previous: number, operationId: string): Promise<void> { await this.#directory(async (directory, signal) => {
    uuid.parse(operationId); const epoch = device.manifest.deviceKeyEpoch
    await this.#publish(directory, `device-key.${device.deviceId}.${epoch}`, recordSchema.parse(device), signal)
    await this.#publish(directory, `device-key-active.${device.deviceId}.${epoch}`, { previous, operationId, digest: bindingDigest(device) }, signal)
  }) }
  async revoke(deviceId: string): Promise<void> { uuid.parse(deviceId); await this.#directory((directory, signal) => this.#publish(directory, `revoked.${deviceId}`, { revoked: true, deviceId }, signal)) }
  async consumeProof(deviceId: string, jti: string, issuedAt: number): Promise<void> { await this.#directory(async (directory, signal) => {
    const name = `proof.${createHash('sha256').update(`${deviceId}\0${jti}`).digest('hex')}`
    if (!await publishLinuxPrivateFile(directory, name, Buffer.from(String(issuedAt + 181_000)), 64, signal)) throw new Error('pair proof replay')
    for (const entry of await this.#names(directory)) {
      if (!/^proof\.[a-f0-9]{64}$/.test(entry) || entry === name) continue
      const old = await readLinuxPrivateFile(directory, entry, 64, signal)
      if (old && Number(old.bytes.toString()) < Date.now()) await unlinkLinuxPrivateFile(directory, entry, old, 64, signal)
    }
  }) }
}
