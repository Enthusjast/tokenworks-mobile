import { createHash, generateKeyPairSync, randomBytes, randomUUID } from 'node:crypto'
import { opendir } from 'node:fs/promises'
import { isIP } from 'node:net'
import { basename, isAbsolute, join, posix, resolve, win32 } from 'node:path'
import { z } from 'zod'
import { canonicalObject, parseSigningObject } from './canonical.js'
import type { HostHandshakeSigner, InstallationPublicJwk } from './host-connection-credential.js'
import { MAX_FILE, MAX_PLAINTEXT, seal, unseal } from './host-enrollment-recovery.js'
import { protectorFrom, type JournalKeyProtector, type Protector } from './journal-key-protection.js'
import { bindingDigest, manifestSchema, ownerSchema, rawKey, type PairingManifest } from './lan-pairing-store.js'
import type { LanPairingDescriptor } from './lan-pairing.js'
import { LinuxInstallationKeyStore } from './linux-installation-key.js'
import { openLinuxPrivateDirectory, publishLinuxPrivateFile, readLinuxPrivateFile, type LinuxPrivateDirectory } from './linux-private-files.js'
import { generateNoiseStatic, NOISE_SUITE } from './native-noise.js'
import { PreprovisionedInstallationSigner } from './preprovisioned-installation-signer.js'
import { encodeSecretBase64 } from './linux-secret-service.js'
import { isSupportedDshVersion } from './product-access.generated.js'

const uuid = z.string().uuid(), sha256 = z.string().regex(/^[a-f0-9]{64}$/)
const label = z.string().min(1).max(80).refine(value => value === value.trim() && !/[\x00-\x1f\x7f\u202a-\u202e\u2066-\u2069]/u.test(value))
const id = z.string().regex(/^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$/)
const absolutePath = z.string().min(1).max(4096).refine(value => {
  if (/[\x00-\x1f\x7f]/.test(value) || value.includes('\\') || value.includes('//') || value.includes('*')) return false
  return posix.isAbsolute(value) && posix.normalize(value) === value && !win32.isAbsolute(value.slice(1))
})
const descriptorSchema = z.strictObject({ version: z.literal(2), carrierVersion: z.literal(1), suite: z.literal(NOISE_SUITE), minimumEncrypted: z.literal(true),
  origin: z.string().max(2048), runtimeId: uuid, installationId: uuid, tlsPin: z.string().regex(/^sha256\/[A-Za-z0-9+/]{43}=$/), hostStaticPublicKey: rawKey, hostKeyEpoch: z.number().int().positive() })
export const REMOTE_ACTIONS = ['discover', 'workspaces', 'create_session', 'prompt', 'wait', 'files.list', 'files.read', 'files.stat', 'files.download', 'files.upload'] as const
const sessionSchema = z.strictObject({ sessionId: z.string().min(1).max(200), workspaceId: z.string().min(1).max(200), sourceAgentId: z.string().min(1).max(200) })
const configSchema = z.strictObject({ id, displayName: label, route: z.literal('direct'), descriptor: descriptorSchema,
  origin: z.string().max(2048), tlsPin: descriptorSchema.shape.tlsPin, allowedAddresses: z.array(z.string().min(1).max(80)).min(1).max(32),
  expectedDshVersion: z.string().refine(isSupportedDshVersion).optional(),
  allowPrivate: z.boolean().default(false), allowLoopback: z.boolean().default(false), trustedCertificate: z.string().min(1).max(8192).optional(),
  workspaces: z.array(z.strictObject({ workspaceId: z.string().min(1).max(200), canonicalRoot: absolutePath })).max(32),
  allowedActions: z.array(z.enum(REMOTE_ACTIONS)).max(REMOTE_ACTIONS.length), allowedAgentPresets: z.array(z.string().min(1).max(200)).max(32),
  allowedPaths: z.array(z.strictObject({ path: absolutePath, actions: z.array(z.enum(['list', 'stat', 'read', 'download', 'upload'])).min(1).max(5), maxBytes: z.number().int().positive().max(32 * 1024 * 1024) })).max(32),
  sessionBindings: z.array(sessionSchema).max(200).default([]) })
export type RemoteTargetConfig = z.input<typeof configSchema>
export type RemoteSessionBinding = z.infer<typeof sessionSchema>
export interface RemoteTarget extends z.infer<typeof configSchema> {
  authorizationEpoch: number
  status: 'unpaired' | 'pair-pending' | 'paired' | 'revoked' | 're-pair-required'
  ownerSha256: string
  credentialRef?: string
}
export interface RemoteCredential {
  readonly pairId: string
  readonly token: string
  readonly manifest: PairingManifest
  readonly bindingSha256: string
  readonly localPrivate: Uint8Array
  readonly publicJwk: InstallationPublicJwk
  readonly sign: HostHandshakeSigner['sign']
  close(): void
}
export interface RemotePairResponse { status: number; body: Uint8Array }
export type RemotePairRequest = (target: RemoteTarget, request: { method: 'POST' | 'GET'; path: '/pair' | '/pair/identity'; headers: Record<string, string>; body?: Uint8Array }, signal: AbortSignal) => Promise<RemotePairResponse>
export interface RemoteProofIdentity { readonly publicJwk: InstallationPublicJwk; readonly thumbprint: string; readonly signer: HostHandshakeSigner; close(): void }
export interface RemoteProofKeys {
  create(reference: string, signal: AbortSignal): Promise<RemoteProofIdentity>
  open(reference: string, signal: AbortSignal): Promise<RemoteProofIdentity | undefined>
}
export function createLinuxRemoteProofKeys(options: { executable: string; service: string; ownerSignal: AbortSignal }): RemoteProofKeys {
  const store = (path: string) => new LinuxInstallationKeyStore({ path, ...options })
  return {
    async create(reference, signal) { const keys = store(reference); try { return await keys.create(signal) } catch (error) { keys.close(); throw error } },
    async open(reference, signal) { const keys = store(reference); try { const identity = await keys.open(signal); if (!identity) keys.close(); return identity } catch (error) { keys.close(); throw error } },
  }
}
export interface RemoteStoreOptions {
  dataDir: string
  sourceInstallationId: string
  sourceRuntimeId: string
  protector: JournalKeyProtector
  ownerSignal?: AbortSignal
}
export interface RemoteTargetStoreOptions extends RemoteStoreOptions { proofKeys?: RemoteProofKeys }
const failure = (code = 'store-unavailable') => new Error(`remote/${code}`)
const magic = Buffer.from('TWMREM01')

/** Shared private, authenticated, immutable publications. No plaintext file-key fallback. */
export class ProtectedRemoteFiles {
  readonly path: string
  readonly sourceInstallationId: string
  readonly sourceRuntimeId: string
  readonly signal: AbortSignal
  readonly #operations: boolean
  readonly #protector: Protector
  readonly #context: Buffer
  readonly #closed = new AbortController()
  #pending = Promise.resolve()
  #admissions = 0
  constructor(options: RemoteStoreOptions, purpose: 'targets' | 'operations') {
    this.#operations = purpose === 'operations'
    if (process.platform !== 'linux' || process.getuid?.() === undefined || !isAbsolute(options.dataDir) || options.dataDir.includes('\0')) throw failure()
    this.sourceInstallationId = uuid.parse(options.sourceInstallationId); this.sourceRuntimeId = uuid.parse(options.sourceRuntimeId)
    this.path = join(resolve(options.dataDir), `remote-${purpose}`)
    this.#protector = protectorFrom(options.protector)
    this.#context = Buffer.from(`tokenworks-remote-${purpose}-v1\0${this.path}\0${this.sourceInstallationId}\0${this.sourceRuntimeId}\0linux-uid:${process.getuid!()}\0${this.#protector.id}`)
    this.signal = AbortSignal.any([this.#closed.signal, ...(options.ownerSignal ? [options.ownerSignal] : [])])
  }
  close(): void { this.#closed.abort() }
  async run<T>(action: (directory: LinuxPrivateDirectory, signal: AbortSignal) => Promise<T>, caller?: AbortSignal): Promise<T> {
    if (this.#admissions >= 32) throw failure('store-busy')
    const signal = AbortSignal.any([this.signal, AbortSignal.timeout(30_000), ...(caller ? [caller] : [])])
    signal.throwIfAborted()
    this.#admissions++
    const previous = this.#pending, next = Promise.withResolvers<void>(), cancelled = Promise.withResolvers<never>()
    this.#pending = next.promise
    const onAbort = () => cancelled.reject(signal.reason)
    signal.addEventListener('abort', onAbort, { once: true })
    if (signal.aborted) onAbort()
    let directory: LinuxPrivateDirectory | undefined, entered = false
    try {
      await Promise.race([previous, cancelled.promise])
      entered = true
      signal.throwIfAborted(); directory = await openLinuxPrivateDirectory(this.path)
      signal.throwIfAborted(); return await action(directory, signal)
    } finally {
      signal.removeEventListener('abort', onAbort)
      const release = () => { this.#admissions--; next.resolve() }
      if (entered) { try { await directory?.file.close() } finally { release() } }
      else {
        // A cancelled waiter must not let later work overtake its still-active predecessor.
        // Keep its admission occupied until that predecessor releases the chain.
        void previous.then(release, release)
      }
    }
  }
  async names(directory: LinuxPrivateDirectory, max = 32768): Promise<string[]> {
    const names: string[] = []
    for await (const entry of await opendir(directory.proc)) { if (names.length >= max) throw failure('storage-full'); names.push(entry.name) }
    return names
  }
  async readBytes(directory: LinuxPrivateDirectory, name: string, signal: AbortSignal): Promise<Buffer | undefined> {
    const raw = await readLinuxPrivateFile(directory, name, MAX_FILE, signal)
    if (!raw) return undefined
    try { return await unseal(raw.bytes, this.#protector, Buffer.concat([this.#context, Buffer.from(`\0${name}`)]), signal, magic) }
    finally { raw.bytes.fill(0) }
  }
  async read<T>(directory: LinuxPrivateDirectory, name: string, signal: AbortSignal): Promise<T | undefined> {
    const plain = await this.readBytes(directory, name, signal)
    try { return plain ? JSON.parse(plain.toString('utf8')) as T : undefined } finally { plain?.fill(0) }
  }
  async reserveOperation(directory: LinuxPrivateDirectory, signal: AbortSignal): Promise<void> {
    const marker = randomBytes(32), names = new Set(await this.names(directory))
    try {
      for (let slot = 0; slot < 1000; slot++) {
        const name = `entry.${slot}`
        if (!names.has(name) && await publishLinuxPrivateFile(directory, name, marker, 32, signal)) return
      }
      throw failure('ledger-full')
    } finally { marker.fill(0) }
  }
  async publishBytes(directory: LinuxPrivateDirectory, name: string, plain: Buffer, signal: AbortSignal): Promise<boolean> {
    if (plain.length < 1 || plain.length > MAX_PLAINTEXT) throw failure('record-too-large')
    let encrypted: Buffer | undefined
    try {
      encrypted = await seal(plain, this.#protector, Buffer.concat([this.#context, Buffer.from(`\0${name}`)]), signal, magic)
      if (this.#operations) {
        // Every immutable ciphertext reserves 4KiB pages with cross-process no-overwrite claims.
        // Include marker bytes and the 1000 operation-entry markers in the 16MiB hard bound.
        const pages = Math.ceil(encrypted.length / 4096), totalPages = Math.floor((16 * 1024 * 1024 - 1000 * 32) / (4096 + 32))
        const occupied = new Set(await this.names(directory)), marker = createHash('sha256').update(name).digest()
        let claimed = 0
        try {
          for (let page = 0; page < totalPages && claimed < pages; page++) {
            const reservation = `quota.${page}`
            if (!occupied.has(reservation) && await publishLinuxPrivateFile(directory, reservation, marker, 32, signal)) claimed++
          }
          if (claimed !== pages) throw failure('ledger-full')
        } finally { marker.fill(0) }
      }
      return await publishLinuxPrivateFile(directory, name, encrypted, MAX_FILE, signal)
    } finally { encrypted?.fill(0) }
  }
  async publish(directory: LinuxPrivateDirectory, name: string, value: unknown, signal: AbortSignal): Promise<boolean> {
    const plain = Buffer.from(canonicalObject(value))
    try { return await this.publishBytes(directory, name, plain, signal) } finally { plain.fill(0) }
  }
}

const protectedProofSchema = z.strictObject({ publicJwk: z.strictObject({ kty: z.literal('EC'), crv: z.literal('P-256'), x: rawKey, y: rawKey }), thumbprint: rawKey, privateDer: z.string().min(1).max(2048) })
function protectedProofKeys(files: ProtectedRemoteFiles): RemoteProofKeys {
  const nameFor = (reference: string) => {
    const name = basename(reference)
    if (reference !== join(files.path, name) || !/^proof\.[0-9a-f-]{36}$/.test(name)) throw failure('credential-reference-invalid')
    return name
  }
  const open = async (reference: string, signal: AbortSignal): Promise<RemoteProofIdentity | undefined> => {
    const name = nameFor(reference)
    const record = await files.run(async (directory, lifetime) => {
      const raw = await files.read(directory, name, lifetime)
      if (!raw) return undefined
      const saved = protectedProofSchema.parse(raw)
      if (createHash('sha256').update(canonicalObject(saved.publicJwk)).digest('base64url') !== saved.thumbprint) throw failure('proof-unavailable')
      return { publicJwk: saved.publicJwk, thumbprint: saved.thumbprint }
    }, signal)
    if (!record) return undefined
    const signer = new PreprovisionedInstallationSigner({ publicJwk: record.publicJwk, ownerSignal: files.signal, typeName: 'RemoteTargetProofSigner', reason: 'remote/proof-unavailable', lookup: {
      async lookup(maxOutput, caller) {
        return files.run(async (directory, lifetime) => {
          const saved = protectedProofSchema.parse(await files.read(directory, name, lifetime))
          if (saved.thumbprint !== record.thumbprint || bindingDigest(saved.publicJwk) !== bindingDigest(record.publicJwk) || Buffer.byteLength(saved.privateDer) > maxOutput) throw failure('proof-unavailable')
          return Buffer.from(saved.privateDer, 'ascii')
        }, caller)
      },
    } })
    return { ...record, signer, close() { signer.close() } }
  }
  return { open, async create(reference, signal) {
    const name = nameFor(reference), pair = generateKeyPairSync('ec', { namedCurve: 'prime256v1' })
    const publicJwk = protectedProofSchema.shape.publicJwk.parse(pair.publicKey.export({ format: 'jwk' }))
    const der = pair.privateKey.export({ format: 'der', type: 'pkcs8' }), encoded = encodeSecretBase64(der)
    try {
      await files.run(async (directory, lifetime) => {
        if (!await files.publish(directory, name, { publicJwk, thumbprint: createHash('sha256').update(canonicalObject(publicJwk)).digest('base64url'), privateDer: encoded.toString('ascii') }, lifetime)) throw failure('proof-conflict')
      }, signal)
    } finally { der.fill(0); encoded.fill(0) }
    const identity = await open(reference, signal)
    if (!identity) throw failure('proof-unavailable')
    return identity
  } }
}

const ownerRecordSchema = z.strictObject({ v: z.literal(1), kind: z.literal('host-local-outbound'), relayDeviceId: z.null(), sourceInstallationId: uuid, sourceRuntimeId: uuid,
  osOwner: z.strictObject({ platform: z.literal('linux'), uid: z.number().int().nonnegative() }), owner: ownerSchema })
type LocalCaller = z.infer<typeof ownerRecordSchema>
const credentialSchema = z.strictObject({ v: z.literal(1), proofReference: z.string().min(1).max(8192), publicJwk: z.strictObject({ kty: z.literal('EC'), crv: z.literal('P-256'), x: rawKey, y: rawKey }),
  pairId: uuid, token: rawKey, localPrivate: rawKey, manifest: manifestSchema, bindingSha256: sha256, ownerSha256: sha256, sourceInstallationId: uuid, sourceRuntimeId: uuid, targetEpoch: z.number().int().positive() })
type SavedCredential = z.infer<typeof credentialSchema>
const targetRecordSchema = configSchema.extend({ authorizationEpoch: z.number().int().positive(), status: z.enum(['unpaired', 'pair-pending', 'paired', 'revoked', 're-pair-required']), ownerSha256: sha256,
  credentialRef: uuid.optional(), revision: z.number().int().positive() }).strict()
type TargetRecord = z.infer<typeof targetRecordSchema>
const enabledSchema = z.strictObject({ revision: z.number().int().positive(), enabled: z.boolean() })
const fullOwnerRisk = 'Target pairing grants full native owner authority. Source action/workspace/path filters are not a target-enforced sandbox. Local revoke/rotation does not revoke the target native peer; revoke it on the target too.'
export const REMOTE_PAIRING_RISK = fullOwnerRisk

function validateConfig(input: RemoteTargetConfig): z.infer<typeof configSchema> {
  if (input.route !== 'direct') throw failure('capability-unavailable: Relay caller credential unsupported')
  const config = configSchema.parse(input), origin = new URL(config.origin)
  if (origin.protocol !== 'https:' || origin.origin !== config.origin || origin.username || origin.password || origin.pathname !== '/' || origin.search || origin.hash || origin.hostname.includes('*') || config.descriptor.origin !== config.origin || config.descriptor.tlsPin !== config.tlsPin) throw failure('target-config-invalid')
  for (const address of config.allowedAddresses) {
    const [host, prefix, extra] = address.split('/'), family = isIP(host!)
    if (!family || extra !== undefined || (prefix !== undefined && (!/^\d+$/.test(prefix) || Number(prefix) < (family === 4 ? 24 : 112) || Number(prefix) > (family === 4 ? 32 : 128)))) throw failure('target-address-invalid')
  }
  for (const values of [config.allowedAddresses, config.allowedActions, config.allowedAgentPresets, config.workspaces.map(value => value.workspaceId), config.allowedPaths.map(value => value.path), config.sessionBindings.map(value => value.sessionId)]) if (new Set(values).size !== values.length || values.some(value => value.includes('*'))) throw failure('target-config-invalid')
  for (const binding of config.sessionBindings) if (!config.workspaces.some(workspace => workspace.workspaceId === binding.workspaceId)) throw failure('workspace-denied')
  return config
}
function snapshot(record: TargetRecord): RemoteTarget {
  const { revision: _revision, credentialRef, ...target } = record
  return structuredClone({ ...target, ...(credentialRef === undefined ? {} : { credentialRef }) })
}

/** Operator-owned direct-only targets; the tool layer is never a configuration authority. */
export class RemoteTargetStore {
  readonly #files: ProtectedRemoteFiles
  readonly #proofKeys: RemoteProofKeys
  readonly #fences = new Set<(targetId?: string) => void>()
  readonly #denied = new Set<string>()
  readonly #credentials = new Map<string, Set<RemoteCredential>>()
  #disabled = false
  constructor(options: RemoteTargetStoreOptions) {
    this.#files = new ProtectedRemoteFiles(options, 'targets'); this.#proofKeys = options.proofKeys ?? protectedProofKeys(this.#files)
    this.signal.addEventListener('abort', () => this.#fence(), { once: true })
  }
  get signal(): AbortSignal { return this.#files.signal }
  onFence(handler: (targetId?: string) => void): () => void { this.#fences.add(handler); return () => this.#fences.delete(handler) }
  #fence(targetId?: string): void {
    for (const [id, credentials] of this.#credentials) if (targetId === undefined || id === targetId) for (const credential of credentials) credential.close()
    for (const handler of this.#fences) { try { handler(targetId) } catch { /* All observers must be fenced even if another observer fails. */ } }
  }
  close(): void { this.#disabled = true; this.#files.close(); this.#fence() }
  async #caller(directory: LinuxPrivateDirectory, signal: AbortSignal): Promise<LocalCaller> {
    let caller = await this.#files.read<LocalCaller>(directory, 'local-caller', signal)
    if (!caller) {
      const candidate: LocalCaller = { v: 1, kind: 'host-local-outbound', relayDeviceId: null, sourceInstallationId: this.#files.sourceInstallationId, sourceRuntimeId: this.#files.sourceRuntimeId,
        osOwner: { platform: 'linux', uid: process.getuid!() }, owner: { account_id: randomUUID(), organization_id: randomUUID(), user_id: randomUUID(), device_id: randomUUID(),
          deployment_sha256: bindingDigest({ purpose: 'host-local-outbound', installationId: this.#files.sourceInstallationId, runtimeId: this.#files.sourceRuntimeId, uid: process.getuid!() }) } }
      await this.#files.publish(directory, 'local-caller', candidate, signal)
      caller = await this.#files.read<LocalCaller>(directory, 'local-caller', signal)
    }
    const parsed = ownerRecordSchema.parse(caller)
    if (parsed.sourceInstallationId !== this.#files.sourceInstallationId || parsed.sourceRuntimeId !== this.#files.sourceRuntimeId || parsed.osOwner.uid !== process.getuid!() || parsed.owner.device_id === this.#files.sourceInstallationId) throw failure('owner-unavailable')
    return parsed
  }
  async owner(): Promise<{ kind: 'host-local-outbound'; ownerSha256: string; sourceInstallationId: string; sourceRuntimeId: string; osOwner: LocalCaller['osOwner']; localCallerId: string; relayDeviceId: null }> {
    return this.#files.run(async (directory, signal) => { const caller = await this.#caller(directory, signal); return { kind: caller.kind, ownerSha256: bindingDigest(caller.owner), sourceInstallationId: caller.sourceInstallationId, sourceRuntimeId: caller.sourceRuntimeId, osOwner: caller.osOwner, localCallerId: caller.owner.device_id, relayDeviceId: null } })
  }
  async #enabled(directory: LinuxPrivateDirectory, signal: AbortSignal): Promise<z.infer<typeof enabledSchema>> {
    const revisions = (await this.#files.names(directory)).filter(name => /^enabled\.\d+$/.test(name)).map(name => Number(name.slice(8))).sort((a, b) => b - a)
    if (!revisions.length) return { revision: 0, enabled: false }
    const value = enabledSchema.parse(await this.#files.read(directory, `enabled.${revisions[0]}`, signal))
    if (value.revision !== revisions[0]) throw failure()
    return value
  }
  async #record(directory: LinuxPrivateDirectory, targetId: string, signal: AbortSignal): Promise<TargetRecord | undefined> {
    id.parse(targetId)
    const prefix = `target.${targetId}.`, revisions = (await this.#files.names(directory)).filter(name => name.startsWith(prefix) && /^\d+$/.test(name.slice(prefix.length))).map(name => Number(name.slice(prefix.length))).sort((a, b) => b - a)
    if (!revisions.length) return undefined
    const record = targetRecordSchema.parse(await this.#files.read(directory, `${prefix}${revisions[0]}`, signal))
    if (record.id !== targetId || record.revision !== revisions[0] || record.ownerSha256 !== bindingDigest((await this.#caller(directory, signal)).owner)) throw failure('owner-unavailable')
    return record
  }
  async #commit(directory: LinuxPrivateDirectory, record: TargetRecord, signal: AbortSignal): Promise<RemoteTarget> {
    targetRecordSchema.parse(record)
    if (!await this.#files.publish(directory, `target.${record.id}.${record.revision}`, record, signal)) throw failure('target-conflict')
    return snapshot(record)
  }
  async status(): Promise<{ enabled: boolean; fullNativeOwnerRisk: string; route: 'direct-only' }> {
    return this.#files.run(async (directory, signal) => ({ enabled: !this.#disabled && (await this.#enabled(directory, signal)).enabled, fullNativeOwnerRisk: fullOwnerRisk, route: 'direct-only' }))
  }
  async setEnabled(enabled: boolean): Promise<void> {
    if (typeof enabled !== 'boolean') throw failure('target-config-invalid')
    if (!enabled) { this.#disabled = true; this.#fence() }
    await this.#files.run(async (directory, signal) => { await this.#caller(directory, signal); const old = await this.#enabled(directory, signal); if (old.enabled !== enabled && !await this.#files.publish(directory, `enabled.${old.revision + 1}`, { revision: old.revision + 1, enabled }, signal)) throw failure('target-conflict') })
    this.#disabled = !enabled
  }
  async configure(input: RemoteTargetConfig): Promise<RemoteTarget> {
    const config = validateConfig(input); this.#denied.add(config.id); this.#fence(config.id)
    const result = await this.#files.run(async (directory, signal) => {
      const caller = await this.#caller(directory, signal), old = await this.#record(directory, config.id, signal)
      if (!old && (await this.#files.names(directory)).filter(name => /^target\..+\.1$/.test(name)).length >= 32) throw failure('target-limit')
      const sameIdentity = old && bindingDigest({ descriptor: old.descriptor, certificate: old.trustedCertificate ?? null }) === bindingDigest({ descriptor: config.descriptor, certificate: config.trustedCertificate ?? null })
      return this.#commit(directory, { ...config, authorizationEpoch: (old?.authorizationEpoch ?? 0) + 1, ownerSha256: bindingDigest(caller.owner), revision: (old?.revision ?? 0) + 1,
        status: sameIdentity && old.status === 'paired' ? 'paired' : 'unpaired', ...(sameIdentity && old.status === 'paired' && old.credentialRef ? { credentialRef: old.credentialRef } : {}) }, signal)
    })
    this.#denied.delete(config.id); return result
  }
  async list(): Promise<RemoteTarget[]> {
    return this.#files.run(async (directory, signal) => {
      const ids = [...new Set((await this.#files.names(directory)).filter(name => /^target\..+\.\d+$/.test(name)).map(name => name.slice(7, name.lastIndexOf('.'))))]
      if (ids.length > 32) throw failure('target-limit')
      const targets: RemoteTarget[] = []; for (const targetId of ids) { const record = await this.#record(directory, targetId, signal); if (record) targets.push(snapshot(record)) }; return targets
    })
  }
  async target(targetId: string): Promise<RemoteTarget> {
    return this.#files.run(async (directory, signal) => { const record = await this.#record(directory, targetId, signal); if (!record) throw failure('target-unavailable'); return snapshot(record) })
  }
  async assertCurrent(target: RemoteTarget): Promise<void> {
    this.signal.throwIfAborted()
    if (this.#disabled || this.#denied.has(target.id)) throw failure('target-fenced')
    await this.#files.run(async (directory, signal) => {
      const current = await this.#record(directory, target.id, signal)
      const currentPolicy = current ? { ...snapshot(current), sessionBindings: [] } : null, capturedPolicy = { ...target, sessionBindings: [] }
      if (!(await this.#enabled(directory, signal)).enabled || !current || current.status !== 'paired' || current.authorizationEpoch !== target.authorizationEpoch || bindingDigest(currentPolicy) !== bindingDigest(capturedPolicy)) { this.#fence(target.id); throw failure('target-fenced') }
    })
  }
  async #invalidate(targetId: string, status: 'revoked' | 're-pair-required'): Promise<RemoteTarget> {
    this.#denied.add(targetId); this.#fence(targetId)
    return this.#files.run(async (directory, signal) => { const old = await this.#record(directory, targetId, signal); if (!old) throw failure('target-unavailable'); const { credentialRef: _ref, ...rest } = old; return this.#commit(directory, { ...rest, status, authorizationEpoch: old.authorizationEpoch + 1, revision: old.revision + 1 }, signal) })
  }
  async revoke(targetId: string): Promise<RemoteTarget> { return this.#invalidate(targetId, 'revoked') }
  async rotate(targetId: string): Promise<RemoteTarget> { return this.#invalidate(targetId, 're-pair-required') }
  async bindSession(targetId: string, binding: RemoteSessionBinding, expectedEpoch: number): Promise<void> {
    const parsed = sessionSchema.parse(binding)
    z.number().int().positive().parse(expectedEpoch)
    await this.#files.run(async (directory, signal) => {
      const old = await this.#record(directory, targetId, signal)
      if (!old || old.status !== 'paired' || old.authorizationEpoch !== expectedEpoch || this.#denied.has(targetId) || !(await this.#enabled(directory, signal)).enabled) throw failure('target-fenced')
      if (!old.workspaces.some(workspace => workspace.workspaceId === parsed.workspaceId)) throw failure('workspace-denied')
      const existing = old.sessionBindings.find(value => value.sessionId === parsed.sessionId)
      if (existing) { if (bindingDigest(existing) !== bindingDigest(parsed)) throw failure('session-binding-conflict'); return }
      if (old.sessionBindings.length >= 200) throw failure('session-limit')
      await this.#commit(directory, { ...old, sessionBindings: [...old.sessionBindings, parsed], revision: old.revision + 1 }, signal)
    })
  }
  async assertSession(targetId: string, sessionId: string, sourceAgentId: string): Promise<RemoteSessionBinding> {
    const target = await this.target(targetId); await this.assertCurrent(target)
    const binding = target.sessionBindings.find(value => value.sessionId === sessionId && value.sourceAgentId === sourceAgentId)
    if (!binding) throw failure('session-denied'); return structuredClone(binding)
  }
  async #saved(directory: LinuxPrivateDirectory, record: TargetRecord, signal: AbortSignal): Promise<SavedCredential> {
    if (!record.credentialRef) throw failure('credential-unavailable')
    const credential = credentialSchema.parse(await this.#files.read(directory, `credential.${record.credentialRef}`, signal))
    if (credential.sourceInstallationId !== this.#files.sourceInstallationId || credential.sourceRuntimeId !== this.#files.sourceRuntimeId || credential.ownerSha256 !== record.ownerSha256 || credential.bindingSha256 !== bindingDigest(credential.manifest) || credential.manifest.installationId !== record.descriptor.installationId || credential.manifest.runtimeId !== record.descriptor.runtimeId || credential.manifest.hostStaticPublicKey !== record.descriptor.hostStaticPublicKey || credential.manifest.hostKeyEpoch !== record.descriptor.hostKeyEpoch || credential.manifest.relay || credential.manifest.pairId !== credential.pairId || credential.manifest.tokenSha256 !== createHash('sha256').update(credential.token).digest('base64url')) throw failure('credential-binding-invalid')
    if (bindingDigest(credential.manifest.owner) !== record.ownerSha256 || createHash('sha256').update(canonicalObject(credential.publicJwk)).digest('base64url') !== credential.manifest.proofJkt) throw failure('credential-binding-invalid')
    return credential
  }
  async #openCredential(saved: SavedCredential, signal: AbortSignal, targetId: string): Promise<RemoteCredential> {
    if (!saved.proofReference.startsWith(`${this.#files.path}/proof.`)) throw failure('capability-unavailable')
    const credentials = this.#credentials.get(targetId) ?? new Set<RemoteCredential>()
    if (credentials.size >= 8) throw failure('connection-limit')
    const proof = await this.#proofKeys.open(saved.proofReference, signal)
    if (!proof || proof.thumbprint !== saved.manifest.proofJkt || bindingDigest(proof.publicJwk) !== bindingDigest(saved.publicJwk)) { proof?.close(); throw failure('credential-unavailable') }
    const localPrivate = Buffer.from(saved.localPrivate, 'base64url'), lifecycle = AbortSignal.any([signal, this.signal]); let closed = false
    const credential: RemoteCredential = { pairId: saved.pairId, token: saved.token, manifest: structuredClone(saved.manifest), bindingSha256: saved.bindingSha256, localPrivate, publicJwk: proof.publicJwk,
      sign: async bytes => { if (closed || lifecycle.aborted || this.#denied.has(targetId)) throw failure('credential-unavailable'); return proof.signer.sign(bytes) },
      close() { if (!closed) { closed = true; lifecycle.removeEventListener('abort', onAbort); localPrivate.fill(0); proof.close(); credentials.delete(credential) } } }
    const onAbort = () => credential.close()
    lifecycle.addEventListener('abort', onAbort, { once: true }); credentials.add(credential); this.#credentials.set(targetId, credentials)
    if (lifecycle.aborted) { credential.close(); throw failure('credential-unavailable') }
    return credential
  }
  async credential(targetId: string, signal: AbortSignal): Promise<RemoteCredential> {
    const target = await this.target(targetId); await this.assertCurrent(target)
    const saved = await this.#files.run(async (directory, lifetime) => { const record = await this.#record(directory, targetId, lifetime); if (!record || record.authorizationEpoch !== target.authorizationEpoch || record.status !== 'paired') throw failure('target-fenced'); return this.#saved(directory, record, lifetime) }, signal)
    const credential = await this.#openCredential(saved, signal, targetId)
    try { await this.assertCurrent(target); return credential } catch (error) { credential.close(); throw error }
  }
  async pair(targetId: string, descriptor: LanPairingDescriptor, request: RemotePairRequest, signal: AbortSignal): Promise<RemoteTarget> {
    if (!/^\d{8}$/.test(descriptor.code) || !Number.isSafeInteger(descriptor.expiresAt) || descriptor.expiresAt <= Date.now()) throw failure('pair-code-unavailable')
    const { code, expiresAt: _expiresAt, ...pinned } = descriptor
    const target = await this.target(targetId)
    if (target.status === 'pair-pending' || target.status === 'paired') throw failure('pair-reconcile-required')
    if (bindingDigest(pinned) !== bindingDigest(target.descriptor)) throw failure('target-pin-mismatch')
    const credentialRef = randomUUID(), proofReference = join(this.#files.path, `proof.${credentialRef}`)
    const proof = await this.#proofKeys.create(proofReference, signal)
    let saved: SavedCredential, noise: { privateKey: Uint8Array; publicKey: Uint8Array } | undefined
    try {
      noise = generateNoiseStatic()
      const generated = noise
      saved = await this.#files.run(async (directory, lifetime) => {
        const current = await this.#record(directory, targetId, lifetime), caller = await this.#caller(directory, lifetime)
        if (!current || current.authorizationEpoch !== target.authorizationEpoch || current.revision < 1 || current.status === 'pair-pending' || current.status === 'paired') throw failure('target-conflict')
        const token = randomBytes(32).toString('base64url'), pairId = randomUUID()
        const manifest = manifestSchema.parse({ v: 2, runtimeId: target.descriptor.runtimeId, installationId: target.descriptor.installationId, pairId, tokenSha256: createHash('sha256').update(token).digest('base64url'), proofJkt: proof.thumbprint,
          deviceStaticPublicKey: Buffer.from(generated.publicKey).toString('base64url'), deviceKeyEpoch: 1, hostStaticPublicKey: target.descriptor.hostStaticPublicKey, hostKeyEpoch: target.descriptor.hostKeyEpoch, owner: caller.owner })
        const intent: SavedCredential = { v: 1, proofReference, publicJwk: proof.publicJwk, pairId, token, localPrivate: Buffer.from(generated.privateKey).toString('base64url'), manifest, bindingSha256: bindingDigest(manifest), ownerSha256: current.ownerSha256,
          sourceInstallationId: this.#files.sourceInstallationId, sourceRuntimeId: this.#files.sourceRuntimeId, targetEpoch: target.authorizationEpoch }
        if (!await this.#files.publish(directory, `credential.${credentialRef}`, intent, lifetime)) throw failure('target-conflict')
        await this.#commit(directory, { ...current, status: 'pair-pending', credentialRef, revision: current.revision + 1 }, lifetime)
        return intent
      }, signal)
    } finally { noise?.privateKey.fill(0); proof.close() }
    this.#denied.delete(targetId)
    const credential = await this.#openCredential(saved, signal, targetId)
    try {
      // A separate immutable marker means even a crash before the first socket byte never permits a second POST.
      await this.#files.run(async (directory, lifetime) => { const current = await this.#record(directory, targetId, lifetime); if (!current || current.credentialRef !== credentialRef || current.authorizationEpoch !== target.authorizationEpoch || current.status !== 'pair-pending') throw failure('target-fenced'); if (!await this.#files.publish(directory, `pair-dispatch.${credentialRef}`, { credentialRef, bindingSha256: saved.bindingSha256 }, lifetime)) throw failure('pair-reconcile-required') }, signal)
      const headers = { 'content-type': 'application/json', dpop: await pairingProof(target, credential, 'POST', '/pair', `${code}.${saved.bindingSha256}`) }
      const body = Buffer.from(canonicalObject({ code, deviceId: credential.pairId, deviceName: target.displayName, token: credential.token, manifest: credential.manifest }))
      let response: RemotePairResponse
      try {
        await this.#files.run(async (directory, lifetime) => { const current = await this.#record(directory, targetId, lifetime); if (!current || current.credentialRef !== credentialRef || current.authorizationEpoch !== target.authorizationEpoch || current.status !== 'pair-pending' || this.#denied.has(targetId)) throw failure('target-fenced') }, signal)
        signal.throwIfAborted(); this.signal.throwIfAborted()
        response = await request(target, { method: 'POST', path: '/pair', headers, body }, signal)
      } finally { body.fill(0) }
      if (response.status !== 201) throw failure('pair-outcome-unknown')
      return await this.#acceptIdentity(target, credentialRef, saved, response.body, true, signal)
    } finally { credential.close() }
  }
  async reconcilePair(targetId: string, request: RemotePairRequest, signal: AbortSignal): Promise<RemoteTarget> {
    const target = await this.target(targetId)
    if (target.status !== 'pair-pending' && target.status !== 'paired') throw failure('pair-reconcile-unavailable')
    const saved = await this.#files.run(async (directory, lifetime) => { const record = await this.#record(directory, targetId, lifetime); if (!record || record.authorizationEpoch !== target.authorizationEpoch) throw failure('target-fenced'); return this.#saved(directory, record, lifetime) }, signal)
    const credential = await this.#openCredential(saved, signal, targetId)
    try {
      const response = await request(target, { method: 'GET', path: '/pair/identity', headers: { authorization: `DPoP ${credential.token}`, dpop: await pairingProof(target, credential, 'GET', '/pair/identity'), 'x-tokenworks-device-id': credential.pairId } }, signal)
      if (response.status !== 200) throw failure('pair-outcome-unknown')
      return await this.#acceptIdentity(target, target.credentialRef!, saved, response.body, false, signal)
    } finally { credential.close() }
  }
  async #acceptIdentity(target: RemoteTarget, credentialRef: string, saved: SavedCredential, bytes: Uint8Array, pairedReply: boolean, signal: AbortSignal): Promise<RemoteTarget> {
    if (bytes.length > 8192) throw failure('pair-identity-invalid')
    const identitySchema = z.strictObject({ runtimeId: uuid, installationId: uuid, manifest: manifestSchema, bindingSha256: sha256 })
    const raw = parseSigningObject(bytes)
    let identity: z.infer<typeof identitySchema>
    if (pairedReply) {
      const paired = identitySchema.extend({ origin: z.string(), tlsPin: z.string(), deviceId: uuid, token: rawKey }).strict().parse(raw)
      if (paired.token !== saved.token || paired.deviceId !== saved.pairId || paired.origin !== target.origin || paired.tlsPin !== target.tlsPin) throw failure('pair-identity-invalid')
      identity = paired
    } else identity = identitySchema.parse(raw)
    if (identity.runtimeId !== target.descriptor.runtimeId || identity.installationId !== target.descriptor.installationId || identity.bindingSha256 !== saved.bindingSha256 || bindingDigest(identity.manifest) !== saved.bindingSha256) throw failure('pair-identity-invalid')
    return this.#files.run(async (directory, lifetime) => {
      const old = await this.#record(directory, target.id, lifetime)
      if (!old || old.authorizationEpoch !== target.authorizationEpoch || old.credentialRef !== credentialRef || (old.status !== 'pair-pending' && old.status !== 'paired')) throw failure('target-fenced')
      if (old.status === 'paired') return snapshot(old)
      return this.#commit(directory, { ...old, status: 'paired', revision: old.revision + 1 }, lifetime)
    }, signal)
  }
}
async function pairingProof(target: RemoteTarget, credential: RemoteCredential, method: 'POST' | 'GET', path: string, nonce?: string): Promise<string> {
  const encode = (value: unknown) => Buffer.from(canonicalObject(value)).toString('base64url')
  const header = encode({ typ: 'dpop+jwt', alg: 'ES256', jwk: credential.publicJwk }), payload = encode({ htm: method, htu: new URL(path, target.origin).href, iat: Math.floor(Date.now() / 1000), jti: randomBytes(32).toString('base64url'), ath: createHash('sha256').update(credential.token).digest('base64url'), ...(nonce ? { nonce } : {}) })
  const input = Buffer.from(`${header}.${payload}`), signature = await credential.sign(input)
  try { if (signature.length !== 64) throw failure('proof-unavailable'); return `${header}.${payload}.${Buffer.from(signature).toString('base64url')}` } finally { input.fill(0); signature.fill(0) }
}
