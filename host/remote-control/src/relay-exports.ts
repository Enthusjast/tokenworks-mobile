import { randomUUID } from 'node:crypto'
import { FileOperationLease, fileOperationClock } from './file-operation-lease.js'
import type { HostConnectionClock } from './connection-lease.js'
import type { DshClient } from './dsh-client.js'
import type { FileExportRegistry, ExportEntry } from './file-export-registry.js'
import { ExportCatalog, type CatalogAuthority } from './export-catalog.js'
import { HostExportFrame, exportErrors } from './export-protocol.js'
import type { FileTransportBinding } from './relay-files.js'
import { putExportSource } from './export-upload.js'
import { ProbeError } from './protocol.js'
import { hostMediaFrame, mediaErrors, mediaResolveResult, type HostMediaResolveFrame } from './media-protocol.js'

export interface HostExportPeer { handle(value: unknown): boolean | Promise<boolean>; close(): void | Promise<void> }
interface Job { id: string; session: string; source?: string; phase: 'listing' | 'preparing' | 'manifest' | 'uploading' | 'committing';
  abort: AbortController; authority: CatalogAuthority; lease: FileOperationLease; active: boolean; entry?: ExportEntry; file?: string }
const inbound = new Set(['file.exports.list', 'file.export.request', 'file.export.upload', 'file.export.committed', 'file.export.abort', 'file.export.error', 'file.export.renewed'])

export class RelayExports implements HostExportPeer {
  #catalog: ExportCatalog
  #jobs = new Map<string, Job>()
  #tasks = new Set<Promise<void>>()
  #running = new Map<Job, number>()
  #closed = false
  #media = new Map<string, AbortController>()
  #timer: NodeJS.Timeout
  #renew: { id: string; ids: string[]; wall: number; mono: number } | undefined
  #lastRenew = -Infinity
  constructor(private readonly registry: FileExportRegistry, client: DshClient, private readonly epoch: string, private readonly binding: FileTransportBinding,
    mediaCurrent?: (session: string, sync: string) => boolean, private readonly clock: HostConnectionClock = fileOperationClock) {
    this.#catalog = new ExportCatalog(client, registry, mediaCurrent)
    this.runtimeId = client.runtimeId
    this.#timer = setInterval(() => this.#tick(), 250); this.#timer.unref()
  }
  private readonly runtimeId: string
  #count(): number {
    let count = this.#jobs.size + this.#media.size
    for (const job of this.#running.keys()) if (this.#jobs.get(job.id) !== job) count++
    return count
  }
  #reserved(id: string): boolean {
    return this.#jobs.has(id) || this.#media.has(id) || [...this.#running.keys()].some(job => job.id === id)
  }
  #valid(job: Job) { return !this.#closed && this.registry.accepting && job.active && !job.abort.signal.aborted && !this.binding.signal.aborted && job.lease.current() }
  #current(job: Job) { if (!this.#valid(job)) throw new ProbeError('access.denied') }
  #send(type: string, job: Job, payload: unknown) { return this.binding.send({ v: 1, type, request_id: job.id, runtime_id: this.runtimeId, runtime_epoch: this.epoch, session_id: job.session, payload }) }
  #task(job: Job, work: () => Promise<void>, end: boolean) {
    this.#running.set(job, (this.#running.get(job) ?? 0) + 1)
    let task!: Promise<void>
    task = Promise.resolve().then(() => { this.#current(job); return work() }).catch(async error => {
      const code = error instanceof ProbeError && exportErrors.safeParse(error.code).success ? error.code : 'file.export_unavailable'
      if (job.active && !this.#closed && this.registry.accepting && !this.binding.signal.aborted) await this.#send('file.export.error', job, { code }).catch(() => this.binding.fail())
      this.#end(job)
    }).finally(() => {
      if (end) this.#end(job)
      const remaining = (this.#running.get(job) ?? 1) - 1
      if (remaining) this.#running.set(job, remaining); else this.#running.delete(job)
      this.#tasks.delete(task)
    })
    this.#tasks.add(task)
  }
  #end(job: Job) {
    if (!job.active) return
    if (job.entry) void this.registry.release(job.entry.export_id, job.session, job.authority).catch(() => {})
    job.active = false; this.#jobs.delete(job.id); job.abort.abort()
  }
  handle(value: unknown): boolean {
    if (!value || typeof value !== 'object') return false
    const type = String((value as { type?: unknown }).type)
    if (type === 'session.media.resolve') {
      if (this.#closed || !this.registry.accepting || this.binding.signal.aborted) return true
      const frame = hostMediaFrame.parse(value)
      if (frame.runtime_id !== this.runtimeId || frame.runtime_epoch !== this.epoch) throw new ProbeError('runtime.changed')
      if (this.#count() >= 8 || this.#reserved(frame.request_id)) throw new ProbeError('rate.limited')
      const abort = new AbortController()
      this.#media.set(frame.request_id, abort)
      let task!: Promise<void>
      task = this.#resolveMedia(frame, abort).finally(() => { this.#media.delete(frame.request_id); this.#tasks.delete(task) })
      this.#tasks.add(task)
      return true
    }
    if (type === 'error' && this.#renew && (value as { request_id?: unknown }).request_id === this.#renew.id) {
      for (const id of this.#renew.ids) { const job = this.#jobs.get(id); if (job) this.#end(job) }
      this.#renew = undefined; return true
    }
    if (!inbound.has(type)) return false
    if (this.#closed) return true
    const frame = HostExportFrame.parse(value)
    if (frame.runtime_id !== this.runtimeId || frame.runtime_epoch !== this.epoch) throw new ProbeError('runtime.changed')
    if (frame.type === 'file.export.renewed') {
      const pending = this.#renew
      if (!pending || pending.id !== frame.request_id) throw new ProbeError('protocol.invalid')
      if (!this.#renewCurrent(pending)) { this.#expireRenewal(pending); return true }
      const seen = new Set<string>()
      for (const lease of frame.payload.leases) {
        if (!pending.ids.includes(lease.operation_id) || seen.has(lease.operation_id)) throw new ProbeError('protocol.invalid')
        seen.add(lease.operation_id)
        const job = this.#jobs.get(lease.operation_id)
        if (!job) continue
        if (!this.#valid(job) || !job.lease.renew(lease.lease_until, lease.expires_at)) this.#end(job)
      }
      for (const id of pending.ids) if (!seen.has(id)) { const job = this.#jobs.get(id); if (job) this.#end(job) }
      this.#renew = undefined; return true
    }
    if (frame.type === 'file.exports.list' || frame.type === 'file.export.request') {
      if (!this.registry.accepting || this.binding.signal.aborted) return true
      if (this.#count() >= 8 || this.#reserved(frame.request_id)) throw new ProbeError('rate.limited')
      let lease: FileOperationLease
      try { lease = new FileOperationLease(frame.payload.lease_until, frame.payload.expires_at, this.clock) }
      catch { throw new ProbeError('access.denied') }
      if (!lease.current()) throw new ProbeError('access.denied')
      const abort = new AbortController()
      const job: Job = { id: frame.request_id, session: frame.session_id, phase: frame.type === 'file.exports.list' ? 'listing' : 'preparing', abort,
        authority: { owner: { userId: frame.payload.user_id, deviceId: frame.payload.device_id }, keyVersion: frame.payload.key_version, grantVersion: frame.payload.grant_version,
          allowWorkspace: frame.payload.allow_workspace, expiresAt: lease.expires, signal: AbortSignal.any([abort.signal, this.binding.signal]), current: () => this.#current(job) },
        lease, active: true }
      this.#jobs.set(job.id, job)
      if (frame.type === 'file.exports.list') {
        const payload = frame.payload
        this.#task(job, async () => { const page = await this.#catalog.list(job.session, job.authority, payload.directory_id, payload.cursor); this.#current(job); await this.#send('file.exports.result', job, page) }, true)
      } else {
        job.source = frame.payload.source_id
        this.#task(job, async () => {
          job.entry = await this.#catalog.prepare(job.session, job.source!, job.authority)
          this.#current(job); job.phase = 'manifest'
          await this.#send('file.export.manifest', job, { source_id: job.source, origin: job.entry.origin, name: job.entry.name, size_bytes: job.entry.size_bytes, sha256: job.entry.sha256, source_expires_at: job.entry.expires_at })
        }, false)
      }
      return true
    }
    const job = this.#jobs.get(frame.request_id)
    if (!job) return true // A cancelled operation can still have a queued response.
    if (!('session_id' in frame) || frame.session_id !== job.session) throw new ProbeError('protocol.invalid')
    if (frame.type === 'file.export.abort' || frame.type === 'file.export.error') { this.#end(job); return true }
    this.#current(job)
    if (frame.type === 'file.export.upload') {
      const file = frame.payload.file
      if (job.phase !== 'manifest' || !job.entry || !frame.payload.upload || file.state !== 'uploading' || file.runtime_id !== this.runtimeId || file.session_id !== job.session
        || file.name !== job.entry.name || file.size_bytes !== job.entry.size_bytes || file.sha256 !== job.entry.sha256 || Date.parse(file.upload_expires_at) > Date.parse(job.entry.expires_at)
        || Date.parse(frame.payload.upload.expires_at) > Date.parse(file.upload_expires_at)) throw new ProbeError('protocol.invalid')
      job.phase = 'uploading'; job.file = file.file_id
      this.#task(job, async () => {
        await putExportSource(this.registry, job.entry!, job.session, job.authority, frame.payload.upload, job.authority.signal)
        this.#current(job); job.phase = 'committing'; await this.#send('file.export.uploaded', job, { file_id: job.file })
      }, false)
      return true
    }
    if (frame.type === 'file.export.committed') {
      if (job.phase !== 'committing' || frame.payload.file_id !== job.file) throw new ProbeError('protocol.invalid')
      this.#end(job); return true
    }
    throw new ProbeError('protocol.invalid')
  }
  async #resolveMedia(frame: HostMediaResolveFrame, abort: AbortController): Promise<void> {
    const payload = frame.payload
    const signal = AbortSignal.any([abort.signal, this.binding.signal])
    const timer = setTimeout(() => abort.abort(), Math.max(1, Math.min(5000, Date.parse(payload.lease_until) - this.clock.wall())))
    timer.unref()
    const reply = (type: string, body: unknown) => this.binding.send({ v: 1, type, request_id: frame.request_id, runtime_id: this.runtimeId,
      runtime_epoch: this.epoch, session_id: frame.session_id, subscription_id: frame.subscription_id, sync_id: payload.sync_id, payload: body })
    let lease: FileOperationLease
    const current = () => {
      signal.throwIfAborted()
      if (this.#closed || !this.registry.accepting || this.#media.get(frame.request_id) !== abort || !lease.current()) throw new ProbeError('access.denied')
    }
    try {
      try { lease = new FileOperationLease(payload.lease_until, payload.expires_at, this.clock) }
      catch { throw new ProbeError('access.denied') }
      current()
      const authority: CatalogAuthority = { owner: { userId: payload.user_id, deviceId: payload.device_id }, keyVersion: payload.key_version,
        grantVersion: payload.grant_version, allowWorkspace: false, expiresAt: lease.expires, signal, current }
      const source = await this.#catalog.resolveMedia(frame.session_id, payload.sync_id, payload.reference, authority)
      current()
      await reply('session.media.result', mediaResolveResult.parse({ reference: payload.reference, source }))
    } catch (error) {
      const code = error instanceof ProbeError && mediaErrors.safeParse(error.code).success ? error.code : 'file.export_unavailable'
      if (!this.#closed && this.registry.accepting && !this.binding.signal.aborted) await reply('session.media.error', { code }).catch(() => this.binding.fail())
    } finally { clearTimeout(timer); abort.abort() }
  }
  #renewCurrent(pending: { wall: number; mono: number }): boolean {
    const wall = this.clock.wall(), mono = this.clock.monotonic()
    return Number.isFinite(wall) && Number.isFinite(mono) && wall >= pending.wall && mono >= pending.mono && wall - pending.wall < 2500 && mono - pending.mono < 2500
  }
  #expireRenewal(pending: { ids: string[] }): void {
    for (const id of pending.ids) { const job = this.#jobs.get(id); if (job) this.#end(job) }
    this.#renew = undefined
  }
  #tick() {
    if (this.#closed) return
    for (const job of this.#jobs.values()) if (!this.#valid(job)) this.#end(job)
    if (this.#renew && !this.#renewCurrent(this.#renew)) this.#expireRenewal(this.#renew)
    if (this.#jobs.size && !this.#renew && this.clock.monotonic() - this.#lastRenew >= 1000) {
      const pending = { id: randomUUID(), ids: [...this.#jobs.keys()], wall: this.clock.wall(), mono: this.clock.monotonic() }
      this.#renew = pending; this.#lastRenew = this.clock.monotonic()
      void this.binding.send({ v: 1, type: 'file.export.renew', request_id: pending.id, runtime_id: this.runtimeId, runtime_epoch: this.epoch, payload: { operation_ids: pending.ids } }).catch(() => this.binding.fail())
    }
  }
  async close() { this.#closed = true; clearInterval(this.#timer); for (const abort of this.#media.values()) abort.abort(); this.#media.clear(); for (const job of this.#jobs.values()) this.#end(job); await this.#catalog.close(); await Promise.allSettled([...this.#tasks]) }
}
