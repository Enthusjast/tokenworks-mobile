import type { SessionId } from '@deepseek-ai/dsh-session'
import type { FileUploadValue } from '@deepseek-ai/dsh-client-file-upload'
import type { DshClient, DshPromptPart } from './dsh-client.js'
import type { FileOffer } from './file-protocol.js'
import { ProbeError } from './protocol.js'

interface StagedFile { file: FileOffer; session: string; native: SessionId; upload: FileUploadValue; signal: AbortSignal; abort: AbortController; valid: () => boolean; confirmed: boolean; onAbort: () => void }
interface PendingFile { file: FileOffer; abort: AbortController }

/** Native receipts stay in this process and are fenced by the exact socket lifetime. */
export class NativeFileRegistry {
  private readonly entries = new Map<string, StagedFile>()
  private readonly pending = new Map<string, PendingFile>()
  // Superseded work still consumes capacity until its actual I/O settles.
  private readonly active = new Set<PendingFile>()
  private readonly lifetime = new AbortController()
  private closed = false
  constructor(readonly client: DshClient, readonly epoch: string) {}
  get accepting(): boolean { return !this.closed }

  async stage(session: string, file: FileOffer, source: AsyncIterable<Uint8Array>, signal: AbortSignal, valid: () => boolean): Promise<string> {
    if (this.closed) throw new ProbeError('file.stale')
    signal.throwIfAborted()
    if (!valid() || this.closed || signal.aborted) throw new ProbeError('file.stale')
    for (const id of this.entries.keys()) this.current(id)
    const snapshot = { ...file, download_token: '' }
    if (this.entries.size + this.active.size - (this.entries.has(snapshot.file_id) ? 1 : 0) >= 32) throw new ProbeError('file.staging_limit')
    this.drop(snapshot.file_id)
    if (this.closed || signal.aborted) throw new ProbeError('file.stale')
    const attempt: PendingFile = { file: snapshot, abort: new AbortController() }
    const lifetime = AbortSignal.any([signal, attempt.abort.signal, this.lifetime.signal])
    this.pending.set(snapshot.file_id, attempt); this.active.add(attempt)
    const onAbort = () => { if (this.pending.get(snapshot.file_id) === attempt) this.pending.delete(snapshot.file_id) }
    lifetime.addEventListener('abort', onAbort, { once: true })
    const check = () => {
      const current = () => !this.closed && !lifetime.aborted && this.pending.get(snapshot.file_id) === attempt
      if (!current() || !valid() || !current()) throw new ProbeError('file.stale')
    }
    let native: SessionId | undefined, upload: FileUploadValue | undefined, published = false
    try {
      check()
      native = await this.client.resolve(session, lifetime)
      check()
      const guarded = async function* () {
        check()
        for await (const chunk of source) { check(); yield chunk; check() }
        check()
      }
      upload = await this.client.uploadFile(session, { name: snapshot.name, size_bytes: snapshot.size_bytes, sha256: snapshot.sha256 }, guarded(), lifetime)
      check()
      if (!this.client.fileStaged(native, upload.receiptId)) throw new ProbeError('file.stale')
      const name = upload.file.name
      check()
      const staged: StagedFile = { file: snapshot, session, native, upload, signal: lifetime, abort: attempt.abort, valid, confirmed: false,
        onAbort: () => { if (this.entries.get(snapshot.file_id) === staged) this.drop(snapshot.file_id, snapshot.handoff_id) } }
      this.pending.delete(snapshot.file_id)
      this.entries.set(snapshot.file_id, staged)
      lifetime.addEventListener('abort', staged.onAbort, { once: true })
      published = true
      upload = undefined // The published entry now owns receipt cleanup.
      return name
    } finally {
      lifetime.removeEventListener('abort', onAbort)
      if (this.pending.get(snapshot.file_id) === attempt) this.pending.delete(snapshot.file_id)
      this.active.delete(attempt)
      if (!published) attempt.abort.abort()
      if (upload && native !== undefined) { try { this.client.releaseFile(native, upload.receiptId) } catch { /* Failed cleanup cannot publish authority. */ } }
    }
  }
  private current(fileId: string): StagedFile | undefined {
    const staged = this.entries.get(fileId)
    if (!staged) return undefined
    let active = !this.closed && !staged.signal.aborted
    if (active) { try { active = staged.valid() && this.client.fileStaged(staged.native, staged.upload.receiptId) } catch { active = false } }
    if (!active || this.entries.get(fileId) !== staged || this.closed || staged.signal.aborted) {
      if (this.entries.get(fileId) === staged) this.drop(fileId, staged.file.handoff_id)
      return undefined
    }
    return staged
  }
  confirm(fileId: string, handoffId?: string): boolean {
    const staged = this.current(fileId)
    if (!staged || handoffId !== undefined && staged.file.handoff_id !== handoffId) return false
    staged.confirmed = true
    return true
  }
  available(fileId: string): boolean { return this.current(fileId)?.confirmed === true }

  resolve(fileId: string, session: string, owner: { userId: string; deviceId: string }): DshPromptPart {
    const staged = this.current(fileId)
    if (!staged || !staged.confirmed || staged.session !== session || staged.file.user_id !== owner.userId || staged.file.device_id !== owner.deviceId) throw new ProbeError('file.staging_required')
    return { type: 'file', receiptId: staged.upload.receiptId }
  }
  async resolveImage(fileId: string, session: string, owner: { userId: string; deviceId: string }, signal: AbortSignal): Promise<DshPromptPart> {
    this.resolve(fileId, session, owner) // The ordinary scanner/native-ready/ownership gate is never bypassed.
    const staged = this.entries.get(fileId)!
    const current = () => {
      signal.throwIfAborted()
      if (this.entries.get(fileId) !== staged || !this.available(fileId) || staged.file.user_id !== owner.userId || staged.file.device_id !== owner.deviceId) throw new ProbeError('file.staging_required')
    }
    try {
      const image = await this.client.resolveImageFile(staged.native, staged.upload.receiptId,
        { name: staged.file.name, size_bytes: staged.file.size_bytes, sha256: staged.file.sha256 }, AbortSignal.any([signal, staged.signal]), current)
      current()
      return image
    } catch (error) {
      // Local receipt retirement is a definite pre-admission rejection. Caller
      // cancellation keeps its own meaning instead of inheriting that error.
      current()
      throw error
    }
  }
  drop(fileId: string, handoffId?: string): void {
    const pending = this.pending.get(fileId)
    if (pending && (handoffId === undefined || pending.file.handoff_id === handoffId)) { this.pending.delete(fileId); pending.abort.abort() }
    const staged = this.entries.get(fileId)
    if (staged && (handoffId === undefined || staged.file.handoff_id === handoffId)) {
      this.entries.delete(fileId)
      staged.signal.removeEventListener('abort', staged.onAbort)
      staged.abort.abort()
      try { this.client.releaseFile(staged.native, staged.upload.receiptId) } catch { /* Closing authority does not rely on cleanup success. */ }
    }
  }
  close(): void {
    if (this.closed) return
    this.closed = true; this.lifetime.abort()
    for (const id of this.pending.keys()) this.drop(id)
    for (const id of this.entries.keys()) this.drop(id)
  }
}
