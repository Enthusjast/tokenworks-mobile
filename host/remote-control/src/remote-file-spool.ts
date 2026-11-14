import { createHash, randomUUID } from 'node:crypto'
import { constants } from 'node:fs'
import { open, link, unlink, readdir, lstat, type FileHandle } from 'node:fs/promises'
import { openLinuxPrivateDirectory, assertLinuxPrivateDirectory, publishLinuxPrivateFile, readLinuxPrivateFile, type LinuxPrivateDirectory } from './linux-private-files.js'
import { RemoteToolError } from './remote-wait.js'

export const REMOTE_MAX_FILE_BYTES = 32 * 1024 * 1024
export interface RemoteFileHandle {
  handle: string
  ownerSha256: string
  sourceTargetId: string
  sourceTargetEpoch: number
  sourceInstallationId: string
  sourceRuntimeId: string
  sessionId: string
  path: string
  name: string
  bytes: number
  sha256: string
}
export interface StagedRemoteFile { readonly file: RemoteFileHandle; publish(signal: AbortSignal): Promise<RemoteFileHandle>; discard(): Promise<void> }

/** Source files are opaque private spool authorities, never model-supplied process paths. */
export class RemoteFileSpool {
  private directory: Promise<LinuxPrivateDirectory> | undefined
  private active = 0
  private reservedBytes = 0
  constructor(private readonly dataDir: string) {}
  private dir(): Promise<LinuxPrivateDirectory> { return this.directory ??= openLinuxPrivateDirectory(this.dataDir) }
  async close(): Promise<void> { await (await this.directory)?.file.close(); this.directory = undefined }
  async stage(response: Response, binding: Omit<RemoteFileHandle, 'handle' | 'bytes' | 'sha256'>, maxBytes: number, signal: AbortSignal): Promise<StagedRemoteFile> {
    if (this.active >= 2) throw new RemoteToolError('remote/file-concurrency')
    this.active++
    let directory: LinuxPrivateDirectory | undefined, file: FileHandle | undefined, retained = false, reservation = 0
    const id = randomUUID(), temporary = `.download-${id}.tmp`
    try {
      directory = await this.dir()
      if (!response.ok || response.status !== 200 || !response.body || response.headers.get('content-encoding')) throw new RemoteToolError('remote/file-response')
      const rawLength = response.headers.get('content-length')
      if (!rawLength || !/^(0|[1-9][0-9]*)$/.test(rawLength)) throw new RemoteToolError('remote/file-framing')
      const expected = Number(rawLength)
      if (!Number.isSafeInteger(expected) || expected > Math.min(maxBytes, REMOTE_MAX_FILE_BYTES)) throw new RemoteToolError('remote/file-size')
      reservation = expected; this.reservedBytes += reservation
      let total = this.reservedBytes, count = 0
      for (const entry of await readdir(directory.proc)) {
        if (++count > 2048) throw new RemoteToolError('remote/spool-capacity')
        if (!/^(?:[0-9a-f-]{36}\.bin|\.download-[0-9a-f-]{36}\.tmp)$/.test(entry)) continue
        const info = await lstat(`${directory.proc}/${entry}`)
        if (!info.isFile() || info.isSymbolicLink() || info.uid !== process.getuid?.() || (info.mode & 0o7777) !== 0o600) throw new RemoteToolError('remote/spool-policy')
        total += info.size
      }
      if (total > 128 * 1024 * 1024) throw new RemoteToolError('remote/spool-capacity')
      file = await open(`${directory.proc}/${temporary}`, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o600)
      const hash = createHash('sha256'), reader = response.body.getReader()
      let bytes = 0
      try {
        for (;;) {
          signal.throwIfAborted()
          const result = await reader.read()
          if (result.done) break
          bytes += result.value.byteLength
          if (bytes > expected || bytes > maxBytes || bytes > REMOTE_MAX_FILE_BYTES) throw new RemoteToolError('remote/file-size')
          hash.update(result.value)
          let offset = 0
          while (offset < result.value.length) { const written = await file.write(result.value, offset); if (!written.bytesWritten) throw new RemoteToolError('remote/spool-write'); offset += written.bytesWritten }
        }
      } finally { await reader.cancel().catch(() => {}); reader.releaseLock() }
      if (bytes !== expected) throw new RemoteToolError('remote/file-framing')
      await file.sync(); await file.close(); file = undefined
      const metadata: RemoteFileHandle = { ...binding, handle: id, bytes, sha256: hash.digest('hex') }
      const stagedDirectory = directory
      retained = true
      let finalized = false
      return {
        file: Object.freeze(metadata),
        publish: async publishSignal => {
          if (finalized) throw new RemoteToolError('remote/spool-already-finalized')
          publishSignal.throwIfAborted(); await assertLinuxPrivateDirectory(stagedDirectory)
          await link(`${stagedDirectory.proc}/${temporary}`, `${stagedDirectory.proc}/${id}.bin`)
          await stagedDirectory.file.sync()
          if (!await publishLinuxPrivateFile(stagedDirectory, `${id}.json`, Buffer.from(JSON.stringify(metadata)), 16 * 1024, publishSignal)) throw new RemoteToolError('remote/spool-conflict')
          await unlink(`${stagedDirectory.proc}/${temporary}`); await stagedDirectory.file.sync(); finalized = true
          return metadata
        },
        discard: async () => { if (!finalized) { await unlink(`${stagedDirectory.proc}/${temporary}`).catch(() => {}); await stagedDirectory.file.sync(); finalized = true } },
      }
    } finally {
      await file?.close(); this.active--; this.reservedBytes -= reservation
      if (!retained && directory) await unlink(`${directory.proc}/${temporary}`).catch(() => {})
    }
  }
  async metadata(handle: string, ownerSha256: string, signal: AbortSignal): Promise<RemoteFileHandle> {
    if (!/^[0-9a-f-]{36}$/.test(handle)) throw new RemoteToolError('remote/file-handle')
    const directory = await this.dir(), record = await readLinuxPrivateFile(directory, `${handle}.json`, 16 * 1024, signal)
    if (!record) throw new RemoteToolError('remote/file-handle')
    const value = JSON.parse(record.bytes.toString('utf8')) as RemoteFileHandle
    if (value.handle !== handle || value.ownerSha256 !== ownerSha256 || !Number.isSafeInteger(value.bytes) || value.bytes < 0 || value.bytes > REMOTE_MAX_FILE_BYTES || !/^[0-9a-f]{64}$/.test(value.sha256)) throw new RemoteToolError('remote/file-binding')
    return value
  }
  /** Verify exact bytes before the approved one-shot upload; descriptor remains pinned to its inode. */
  async read(handle: RemoteFileHandle, signal: AbortSignal): Promise<Buffer<ArrayBuffer>> {
    const directory = await this.dir()
    await assertLinuxPrivateDirectory(directory)
    const file = await open(`${directory.proc}/${handle.handle}.bin`, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK)
    try {
      const before = await file.stat()
      if (!before.isFile() || before.nlink !== 1 || before.uid !== process.getuid?.() || (before.mode & 0o7777) !== 0o600 || before.size !== handle.bytes) throw new RemoteToolError('remote/spool-policy')
      const bytes = Buffer.alloc(handle.bytes)
      try {
        let offset = 0
        while (offset < bytes.length) { signal.throwIfAborted(); const read = await file.read(bytes, offset, bytes.length - offset); if (!read.bytesRead) throw new RemoteToolError('remote/file-framing'); offset += read.bytesRead }
        const after = await file.stat()
        if (after.size !== before.size || after.mtimeMs !== before.mtimeMs || after.ctimeMs !== before.ctimeMs || createHash('sha256').update(bytes).digest('hex') !== handle.sha256) throw new RemoteToolError('remote/file-digest')
        return bytes
      } catch (error) { bytes.fill(0); throw error }
    } finally { await file.close() }
  }
}
