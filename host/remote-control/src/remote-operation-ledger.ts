import { createHash, randomUUID } from 'node:crypto'
import serialize from 'canonicalize'
import { z } from 'zod'
import { canonicalObject } from './canonical.js'
import { bindingDigest } from './lan-pairing-store.js'
import { MAX_PLAINTEXT } from './host-enrollment-recovery.js'
import { ProtectedRemoteFiles, type RemoteStoreOptions, type RemoteTarget } from './remote-targets.js'
import type { LinuxPrivateDirectory } from './linux-private-files.js'

const uuid = z.string().uuid(), digestSchema = z.string().regex(/^[a-f0-9]{64}$/), identityString = z.string().min(1).max(200)
const MAX_BODY = 1024 * 1024, MAX_OPERATIONS = 1000
const MAX_EVENTS = 32, MAX_EVIDENCE = 4096
const failure = (code = 'ledger-unavailable') => new Error(`remote/${code}`)
export type RemoteOperationState = 'prepared' | 'approval-pending' | 'approved' | 'dispatch-started' | 'accepted' | 'observed-terminal' | 'rejected' | 'cancelled-before-dispatch' | 'outcome-unknown'
const stateSchema = z.enum(['prepared', 'approval-pending', 'approved', 'dispatch-started', 'accepted', 'observed-terminal', 'rejected', 'cancelled-before-dispatch', 'outcome-unknown'])
const recordSchema = z.strictObject({ version: z.literal(1), operationId: uuid, requestId: uuid, callKey: digestSchema, digest: digestSchema,
  ownerSha256: digestSchema, sourceInstallationId: uuid, sourceRuntimeId: uuid, sourceAgentId: identityString, callId: identityString, rootCallId: identityString,
  targetId: identityString, targetEpoch: z.number().int().positive(), targetInstallationId: uuid, targetRuntimeId: uuid, hostStaticPublicKey: z.string().min(1).max(100), hostKeyEpoch: z.number().int().positive(),
  credentialRef: uuid, kind: z.string().min(1).max(80), bodyBytes: z.number().int().min(1).max(MAX_BODY), bodyChunks: z.number().int().min(1).max(Math.ceil(MAX_BODY / MAX_PLAINTEXT)), createdAt: z.number().int().nonnegative() })
type OperationReservation = z.infer<typeof recordSchema>
const eventSchema = z.strictObject({ operationId: uuid, revision: z.number().int().positive().max(MAX_EVENTS), state: stateSchema, observedAt: z.number().int().nonnegative(), evidence: z.unknown() })
type OperationEvent = z.infer<typeof eventSchema>
const dispatchSchema = z.strictObject({ operationId: uuid, processInstance: uuid, digest: digestSchema, startedAt: z.number().int().nonnegative(), priorRevision: z.number().int().nonnegative() })
export interface RemoteOperation extends OperationReservation { state: RemoteOperationState; evidence: unknown; revision: number; dispatchStarted: boolean }
export type RemoteOperationBody = Uint8Array | object
export interface RemoteOperationPrepare {
  ownerSha256: string
  sourceAgentId: string
  callId: string
  rootCallId: string
  target: RemoteTarget
  kind: string
  body: RemoteOperationBody | ((requestId: string) => RemoteOperationBody)
}
export interface PreparedRemoteOperation { operation: RemoteOperation; created: boolean }

/** Inert canonical JSON for large native bodies; unlike signing metadata, prompts may exceed 128KiB. */
function bodyBytes(body: RemoteOperationBody): Buffer {
  if (body instanceof Uint8Array) { if (!body.length || body.length > MAX_BODY) throw failure('operation-body-too-large'); return Buffer.from(body) }
  let nodes = 0, textBytes = 0
  const ancestors = new Set<object>()
  const visit = (value: unknown, depth: number): void => {
    if (++nodes > 32768 || depth > 64) throw failure('operation-body-invalid')
    if (typeof value === 'string') { textBytes += Buffer.byteLength(value); if (textBytes > MAX_BODY) throw failure('operation-body-too-large'); return }
    if (value === null || typeof value === 'boolean' || (typeof value === 'number' && Number.isFinite(value))) return
    if (!value || typeof value !== 'object' || ancestors.has(value)) throw failure('operation-body-invalid')
    const prototype = Object.getPrototypeOf(value)
    if (Array.isArray(value) ? prototype !== Array.prototype : prototype !== Object.prototype && prototype !== null) throw failure('operation-body-invalid')
    if (Object.getOwnPropertySymbols(value).length) throw failure('operation-body-invalid')
    ancestors.add(value)
    const descriptors = Object.getOwnPropertyDescriptors(value)
    if (Array.isArray(value) && Object.keys(value).length !== value.length) throw failure('operation-body-invalid')
    for (const [key, descriptor] of Object.entries(descriptors)) {
      if (Array.isArray(value) && key === 'length') continue
      if (!('value' in descriptor) || !descriptor.enumerable || key === 'toJSON') throw failure('operation-body-invalid')
      textBytes += Buffer.byteLength(key); if (textBytes > MAX_BODY) throw failure('operation-body-too-large')
      visit(descriptor.value, depth + 1)
    }
    ancestors.delete(value)
  }
  visit(body, 0)
  const encoded = Buffer.from(serialize(body)!)
  if (!encoded.length || encoded.length > MAX_BODY) { encoded.fill(0); throw failure('operation-body-too-large') }
  return encoded
}

/** Immutable reservations and dispatch markers form the crash boundary, not native exactly-once delivery. */
export class RemoteOperationLedger {
  readonly #files: ProtectedRemoteFiles
  readonly #processInstance = randomUUID()
  readonly #dispatchRights = new Set<string>()
  readonly #inflight = new Set<string>()
  constructor(options: RemoteStoreOptions) { this.#files = new ProtectedRemoteFiles(options, 'operations') }
  close(): void { this.#dispatchRights.clear(); this.#files.close() }
  async #reservation(directory: LinuxPrivateDirectory, operationId: string, signal: AbortSignal): Promise<OperationReservation> {
    uuid.parse(operationId)
    const index = await this.#files.read<{ callKey: string }>(directory, `index.${operationId}`, signal)
    let value: unknown
    if (index && digestSchema.safeParse(index.callKey).success) value = await this.#files.read(directory, `operation.${index.callKey}`, signal)
    else {
      for (const name of (await this.#files.names(directory)).filter(name => /^operation\.[a-f0-9]{64}$/.test(name))) {
        const candidate = recordSchema.parse(await this.#files.read(directory, name, signal))
        if (candidate.operationId === operationId) { value = candidate; break }
      }
    }
    if (!value) throw failure('operation-unavailable')
    const record = recordSchema.parse(value)
    if (record.operationId !== operationId || record.sourceInstallationId !== this.#files.sourceInstallationId || record.sourceRuntimeId !== this.#files.sourceRuntimeId) throw failure('operation-binding-invalid')
    return record
  }
  async #view(directory: LinuxPrivateDirectory, record: OperationReservation, signal: AbortSignal): Promise<RemoteOperation> {
    const prefix = `event.${record.operationId}.`, revisions = (await this.#files.names(directory)).filter(name => name.startsWith(prefix) && /^\d+$/.test(name.slice(prefix.length))).map(name => Number(name.slice(prefix.length))).sort((a, b) => b - a)
    let event: OperationEvent | undefined
    if (revisions.length) { event = eventSchema.parse(await this.#files.read(directory, `${prefix}${revisions[0]}`, signal)); if (event.operationId !== record.operationId || event.revision !== revisions[0]) throw failure('operation-binding-invalid') }
    const dispatchValue = await this.#files.read(directory, `dispatch.${record.operationId}`, signal), dispatch = dispatchValue ? dispatchSchema.parse(dispatchValue) : undefined
    if (dispatch && (dispatch.operationId !== record.operationId || dispatch.digest !== record.digest)) throw failure('operation-binding-invalid')
    let state: RemoteOperationState = event?.state ?? 'prepared'
    if (dispatch && (!event || event.revision <= dispatch.priorRevision || ['prepared', 'approval-pending', 'approved', 'dispatch-started'].includes(state))) state = dispatch.processInstance === this.#processInstance && this.#inflight.has(record.operationId) ? 'dispatch-started' : 'outcome-unknown'
    return { ...record, state, evidence: event?.evidence ?? null, revision: event?.revision ?? 0, dispatchStarted: Boolean(dispatch) }
  }
  async prepare(input: RemoteOperationPrepare): Promise<PreparedRemoteOperation> {
    const target = input.target
    if (target.status !== 'paired' || !target.credentialRef || target.ownerSha256 !== input.ownerSha256) throw failure('target-fenced')
    const identity = { ownerSha256: digestSchema.parse(input.ownerSha256), sourceInstallationId: this.#files.sourceInstallationId, sourceRuntimeId: this.#files.sourceRuntimeId,
      sourceAgentId: identityString.parse(input.sourceAgentId), callId: identityString.parse(input.callId), rootCallId: identityString.parse(input.rootCallId),
      targetId: target.id, targetEpoch: target.authorizationEpoch, targetInstallationId: target.descriptor.installationId, targetRuntimeId: target.descriptor.runtimeId,
      hostStaticPublicKey: target.descriptor.hostStaticPublicKey, hostKeyEpoch: target.descriptor.hostKeyEpoch, credentialRef: target.credentialRef, kind: input.kind }
    const callKey = bindingDigest(identity)
    return this.#files.run(async (directory, signal) => {
      let existing = await this.#files.read(directory, `operation.${callKey}`, signal)
      const requestId = existing ? recordSchema.parse(existing).requestId : randomUUID()
      let bytes = bodyBytes(typeof input.body === 'function' ? input.body(requestId) : input.body)
      try {
        let digest = createHash('sha256').update(bytes).digest('hex'), created = false
        let record: OperationReservation
        if (existing) record = recordSchema.parse(existing)
        else {
          record = recordSchema.parse({ version: 1, operationId: randomUUID(), requestId, callKey, digest, ...identity, bodyBytes: bytes.length, bodyChunks: Math.ceil(bytes.length / MAX_PLAINTEXT), createdAt: Date.now() })
          await this.#files.reserveOperation(directory, signal)
          created = await this.#files.publish(directory, `operation.${callKey}`, record, signal)
          if (!created) {
            existing = await this.#files.read(directory, `operation.${callKey}`, signal); record = recordSchema.parse(existing)
            if (typeof input.body === 'function') { bytes.fill(0); bytes = bodyBytes(input.body(record.requestId)); digest = createHash('sha256').update(bytes).digest('hex') }
          }
        }
        if (record.callKey !== callKey || record.digest !== digest || record.bodyBytes !== bytes.length) throw failure('operation-conflict')
        if (!created) return { operation: await this.#view(directory, record, signal), created: false }
        // The key reservation is already durable. Only this winner may finish preparing and obtain dispatch rights.
        for (let index = 0; index < record.bodyChunks; index++) {
          const chunk = bytes.subarray(index * MAX_PLAINTEXT, Math.min(bytes.length, (index + 1) * MAX_PLAINTEXT))
          if (!await this.#files.publishBytes(directory, `body.${record.operationId}.${index}`, chunk, signal)) throw failure('operation-conflict')
        }
        if (!await this.#files.publish(directory, `index.${record.operationId}`, { callKey }, signal) || !await this.#files.publish(directory, `prepared.${record.operationId}`, { operationId: record.operationId, digest: record.digest }, signal)) throw failure('operation-conflict')
        this.#dispatchRights.add(record.operationId)
        return { operation: await this.#view(directory, record, signal), created: true }
      } finally { bytes.fill(0) }
    })
  }
  async get(operationId: string): Promise<RemoteOperation> { return this.#files.run(async (directory, signal) => this.#view(directory, await this.#reservation(directory, operationId, signal), signal)) }
  async list(): Promise<RemoteOperation[]> {
    return this.#files.run(async (directory, signal) => {
      const names = (await this.#files.names(directory)).filter(name => /^operation\.[a-f0-9]{64}$/.test(name))
      if (names.length > MAX_OPERATIONS) throw failure('ledger-full')
      const operations: RemoteOperation[] = []
      for (const name of names) { const record = recordSchema.parse(await this.#files.read(directory, name, signal)); operations.push(await this.#view(directory, record, signal)) }
      return operations
    })
  }
  async body(operationId: string): Promise<Buffer> {
    return this.#files.run(async (directory, signal) => {
      const record = await this.#reservation(directory, operationId, signal), result = Buffer.alloc(record.bodyBytes)
      try {
        for (let index = 0; index < record.bodyChunks; index++) {
          const chunk = await this.#files.readBytes(directory, `body.${operationId}.${index}`, signal)
          if (!chunk) throw failure('operation-incomplete')
          try {
            const expected = Math.min(MAX_PLAINTEXT, record.bodyBytes - index * MAX_PLAINTEXT)
            if (chunk.length !== expected) throw failure('operation-body-invalid')
            chunk.copy(result, index * MAX_PLAINTEXT)
          } finally { chunk.fill(0) }
        }
        if (createHash('sha256').update(result).digest('hex') !== record.digest) throw failure('operation-body-invalid')
        return result
      } catch (error) { result.fill(0); throw error }
    })
  }
  async markDispatchStarted(operationId: string): Promise<RemoteOperation> {
    return this.#files.run(async (directory, signal) => {
      if (!this.#dispatchRights.delete(operationId)) throw failure('operation-dispatch-forbidden')
      const record = await this.#reservation(directory, operationId, signal), current = await this.#view(directory, record, signal)
      const prepared = await this.#files.read<{ operationId: string; digest: string }>(directory, `prepared.${operationId}`, signal)
      if (!prepared || prepared.operationId !== operationId || prepared.digest !== record.digest || current.dispatchStarted || !['prepared', 'approval-pending', 'approved'].includes(current.state)) throw failure('operation-dispatch-forbidden')
      if (!await this.#files.publish(directory, `dispatch.${operationId}`, { operationId, processInstance: this.#processInstance, digest: record.digest, startedAt: Date.now(), priorRevision: current.revision }, signal)) throw failure('operation-dispatch-forbidden')
      this.#inflight.add(operationId)
      return this.#view(directory, record, signal)
    })
  }
  async record(operationId: string, state: RemoteOperationState, evidence: unknown = null): Promise<RemoteOperation> {
    stateSchema.parse(state)
    if (state === 'prepared' || state === 'dispatch-started') throw failure('operation-transition-invalid')
    const encoded = Buffer.from(canonicalObject({ evidence }))
    try { if (encoded.length > MAX_EVIDENCE) throw failure('operation-evidence-too-large') } finally { encoded.fill(0) }
    return this.#files.run(async (directory, signal) => {
      const reservation = await this.#reservation(directory, operationId, signal), current = await this.#view(directory, reservation, signal)
      if (current.revision >= MAX_EVENTS) throw failure('operation-history-full')
      if (current.dispatchStarted && ['approval-pending', 'approved', 'rejected', 'cancelled-before-dispatch'].includes(state)) throw failure('operation-transition-invalid')
      if (!current.dispatchStarted && ['accepted', 'observed-terminal', 'outcome-unknown'].includes(state)) throw failure('operation-transition-invalid')
      if (['rejected', 'cancelled-before-dispatch', 'observed-terminal'].includes(current.state) && state !== current.state) throw failure('operation-transition-invalid')
      const event = eventSchema.parse({ operationId, revision: current.revision + 1, state, observedAt: Date.now(), evidence })
      if (!await this.#files.publish(directory, `event.${operationId}.${event.revision}`, event, signal)) throw failure('operation-conflict')
      if (['accepted', 'observed-terminal', 'outcome-unknown', 'rejected', 'cancelled-before-dispatch'].includes(state)) this.#inflight.delete(operationId)
      return this.#view(directory, reservation, signal)
    })
  }
}
