import { constants, type BigIntStats } from 'node:fs'
import { lstat, mkdir, mkdtemp, open, opendir, readFile, readlink, rename, rmdir, statfs, unlink, type FileHandle } from 'node:fs/promises'
import { basename, normalize } from 'node:path'
import { SESSION_FORMAT_VERSION, type SessionId } from '@deepseek-ai/dsh-session'
import type { SessionPersistence, SessionPersistenceSnapshot } from '@deepseek-ai/dsh-session-persistence'

export class SessionDeletionStorageError extends Error {
  constructor(readonly code: 'unavailable' | 'outcome-unknown') {
    super(code === 'unavailable' ? 'Session deletion storage is unavailable.' : 'The session deletion outcome could not be confirmed.')
    this.name = 'SessionDeletionStorageError'
  }
}

// The provider discovers logs only at root/project/session/log. The extra
// transaction level keeps the original directory out of discovery after rename.
const quarantineName = '!tokenworks-session-deletion'
const payloadName = 'payload'
const lockName = 'session.lock'
const procFds = '/proc/self/fd'
const directoryFlags = constants.O_RDONLY | constants.O_DIRECTORY | constants.O_NOFOLLOW
const pathFlags = 0o10000000 | constants.O_NOFOLLOW // Linux O_PATH: no device/FIFO I/O.
const nativeLogs = Array.from({ length: SESSION_FORMAT_VERSION + 1 }, (_, version) => version === 0 ? 'session.jsonl' : `session.v${version}.jsonl`)
const currentLog = nativeLogs[SESSION_FORMAT_VERSION]!

function unavailable(): never { throw new SessionDeletionStorageError('unavailable') }
function absent(error: unknown): boolean { return (error as NodeJS.ErrnoException | undefined)?.code === 'ENOENT' }
function fdPath(file: FileHandle): string { return `${procFds}/${file.fd}` }
async function exists(path: string): Promise<boolean> {
  try { await lstat(path); return true }
  catch (error) { if (absent(error)) return false; throw error }
}
async function empty(dir: FileHandle): Promise<boolean> {
  const entries = await opendir(fdPath(dir))
  try { return await entries.read() === null }
  finally { await entries.close() }
}
function entry(dir: FileHandle, name: string): string {
  if (!name || name === '.' || name === '..' || name.includes('/') || name.includes('\0') || Buffer.byteLength(name) > 255) unavailable()
  return `${fdPath(dir)}/${name}`
}
function mount(file: FileHandle): Promise<string> {
  return readFile(`/proc/self/fdinfo/${file.fd}`, 'ascii').then(info => /(?:^|\n)mnt_id:\s*([0-9]+)\n/.exec(info)?.[1] ?? unavailable())
}
function identity(left: BigIntStats, right: BigIntStats): boolean { return left.dev === right.dev && left.ino === right.ino }
function sameContents(left: BigIntStats, right: BigIntStats): boolean {
  return identity(left, right) && left.size === right.size && left.mtimeNs === right.mtimeNs && left.ctimeNs === right.ctimeNs
}
function managedDirectory(info: BigIntStats, uid: bigint, mode?: bigint): boolean {
  // Provider-created dirs are 0700. An operator may grant read/traverse, but
  // not write, to another UID; do not accept special permission bits.
  return info.isDirectory() && info.uid === uid && (mode === undefined
    ? (info.mode & 0o7722n) === 0o700n
    : (info.mode & 0o7777n) === mode)
}

type ManagedRoot = { file: FileHandle; uid: bigint; dev: bigint; mountId: string }
async function checkDirectory(file: FileHandle, root: ManagedRoot, mode?: bigint): Promise<BigIntStats> {
  const info = await file.stat({ bigint: true })
  if (!managedDirectory(info, root.uid, mode) || info.dev !== root.dev || await mount(file) !== root.mountId) unavailable()
  return info
}
async function openRoot(path: string, uid: bigint): Promise<FileHandle> {
  let parent = await open('/', directoryFlags)
  try {
    // Check every ancestor before any pathname-based provider operations. Sticky
    // directories (e.g. /tmp) are safe here because the next entry must itself
    // be owned by this UID or root. A privileged/same-UID host is trusted.
    for (const name of path.slice(1).split('/')) {
      const info = await parent.stat({ bigint: true })
      if ((info.uid !== 0n && info.uid !== uid) || ((info.mode & 0o022n) !== 0n && (info.mode & 0o1000n) === 0n)) unavailable()
      const previous = parent
      parent = await open(entry(previous, name), directoryFlags)
      await previous.close()
    }
    if (!managedDirectory(await parent.stat({ bigint: true }), uid) || await readlink(fdPath(parent)) !== path) unavailable()
    return parent
  } catch (error) {
    await parent.close()
    throw error
  }
}

// JSONL's on-disk name encoding. These are validation rules for the documented
// layout, not provider fields. Escape UTF-16 units (including lone surrogates).
function encodeUnit(unit: string): string {
  return /^[A-Za-z0-9._-]$/.test(unit) ? unit : `~${unit.charCodeAt(0).toString(16).toUpperCase().padStart(4, '0')}`
}
function sessionName(id: SessionId): string {
  if (!id || id.length > 255) unavailable()
  if (id === '.') return '~002E'
  if (id === '..') return '~002E~002E'
  return id.split('').map(encodeUnit).join('')
}
function projectName(cwd: string | undefined): string {
  if (cwd === undefined) return '_no-cwd'
  if (!cwd) unavailable()
  const key = cwd.replace(/[/\\:]+/g, '-').split('').map(encodeUnit).join('').replace(/^-+/, '')
  return `--${(key || 'root').slice(0, 251)}--`
}

type Selection = { project: string; session: string; log: string }
function selection(rootPath: string, snapshot: SessionPersistenceSnapshot, id: SessionId): Selection {
  if (snapshot.header.id !== id || snapshot.formatStatus !== 'current' || snapshot.header.version !== SESSION_FORMAT_VERSION || snapshot.location?.kind !== 'jsonl') unavailable()
  const log = basename(snapshot.location.path)
  if (log !== currentLog && log !== `${currentLog}.zstd`) unavailable()
  const project = projectName(snapshot.header.cwd)
  const session = sessionName(id)
  if (snapshot.location.path !== `${rootPath}/${project}/${session}/${log}`) unavailable()
  return { project, session, log }
}

async function checkedFile(dir: FileHandle, name: string, root: ManagedRoot): Promise<BigIntStats> {
  await using file = await open(entry(dir, name), pathFlags)
  const info = await file.stat({ bigint: true })
  // The provider's O_CREAT lock mode follows umask (often 0664). The
  // containing session directory is always 0700, so other UIDs cannot reach it.
  const disallowedMode = name === lockName ? 0o7111n : 0o7133n
  if (!info.isFile() || info.uid !== root.uid || info.dev !== root.dev || info.nlink !== 1n ||
      (info.mode & disallowedMode) !== 0n || await mount(file) !== root.mountId || (name === lockName ? info.size !== 0n : info.size === 0n)) unavailable()
  return info
}

async function inventory(dir: FileHandle, root: ManagedRoot, selectedLog: string): Promise<Map<string, BigIntStats>> {
  const suffix = selectedLog.endsWith('.zstd') ? '.zstd' : ''
  const contents = new Map<string, BigIntStats>()
  for await (const { name } of await opendir(fdPath(dir), { bufferSize: nativeLogs.length + 2 })) {
    if (name !== lockName && (!name.endsWith(suffix) || !nativeLogs.includes(name.slice(0, name.length - suffix.length)))) unavailable()
    contents.set(name, await checkedFile(dir, name, root))
  }
  if (!contents.has(lockName) || !contents.has(selectedLog)) unavailable()
  return contents
}

function compareInventory(before: Map<string, BigIntStats>, after: Map<string, BigIntStats>): void {
  if (before.size !== after.size) unavailable()
  for (const [name, info] of before) {
    const current = after.get(name)
    // JSONL truncates the zero-byte lock when it opens a writer. The lock inode
    // must remain the same, but this may change its timestamps.
    if (!current || !(name === lockName ? identity(info, current) : sameContents(info, current))) unavailable()
  }
}

export class SessionDeletionStore {
  readonly #pending = new Set<Promise<unknown>>()
  #closing?: Promise<void>
  #removing = false
  #unknown = false

  private constructor(private readonly rootPath: string, private readonly root: ManagedRoot, private readonly quarantine: FileHandle) {}

  static async open(root: string): Promise<SessionDeletionStore> {
    const uid = process.getuid?.()
    try {
      if (process.platform !== 'linux' || uid === undefined || !root.startsWith('/') || root.endsWith('/') || normalize(root) !== root ||
          (await statfs(procFds)).type !== 0x9fa0 || (await statfs('/proc/self/fdinfo')).type !== 0x9fa0) unavailable()
      const file = await openRoot(root, BigInt(uid))
      try {
        const rootDir = { file, uid: BigInt(uid), dev: (await file.stat({ bigint: true })).dev, mountId: await mount(file) }
        const path = entry(file, quarantineName)
        await mkdir(path, { mode: 0o700 }).catch(error => { if (error?.code !== 'EEXIST') throw error })
        const quarantine = await open(path, directoryFlags)
        try {
          await checkDirectory(quarantine, rootDir, 0o700n)
          // Without a cross-process lifetime proof, a transaction here may be
          // live or orphaned. Never reclaim it automatically or guess success.
          if (!await empty(quarantine)) throw new SessionDeletionStorageError('outcome-unknown')
          await file.sync()
          return new SessionDeletionStore(root, rootDir, quarantine)
        } catch (error) {
          await quarantine.close()
          throw error
        }
      } catch (error) {
        await file.close()
        throw error
      }
    } catch (error) {
      if (error instanceof SessionDeletionStorageError) throw error
      unavailable()
    }
  }

  async canDelete(snapshot: SessionPersistenceSnapshot): Promise<boolean> {
    if (this.#closing || this.#unknown) return false
    return this.#track(this.#canDelete(snapshot))
  }

  async #canDelete(snapshot: SessionPersistenceSnapshot): Promise<boolean> {
    try {
      await this.#checkRoot()
      if (!await empty(this.quarantine)) return false
      const selected = selection(this.rootPath, snapshot, snapshot.header.id)
      await using project = await open(entry(this.root.file, selected.project), directoryFlags)
      await checkDirectory(project, this.root)
      await using session = await open(entry(project, selected.session), directoryFlags)
      await checkDirectory(session, this.root, 0o700n)
      const files = await inventory(session, this.root, selected.log)
      return !this.#closing && !this.#unknown && (snapshot.sizeBytes === undefined || snapshot.sizeBytes === Number(files.get(selected.log)!.size))
    } catch { return false }
  }

  async remove(persistence: SessionPersistence, id: SessionId, assertCurrent: () => void, signal: AbortSignal): Promise<void> {
    if (this.#unknown) throw new SessionDeletionStorageError('outcome-unknown')
    if (this.#closing || this.#removing) unavailable()
    this.#removing = true
    try { await this.#track(this.#remove(persistence, id, assertCurrent, signal)) }
    finally { this.#removing = false }
  }

  async #remove(persistence: SessionPersistence, id: SessionId, assertCurrent: () => void, signal: AbortSignal): Promise<void> {
    let attempted = false
    let transactionName: string | undefined
    const checkpoint = () => {
      if (this.#closing) unavailable()
      signal.throwIfAborted()
      assertCurrent()
      signal.throwIfAborted()
      if (this.#closing) unavailable()
    }
    try {
      try {
        signal.throwIfAborted()
        await this.#checkRoot()
        if (!await empty(this.quarantine)) unavailable()
        checkpoint()
        // The provider reads by pathname and opens its lock with O_TRUNC. Before
        // calling it, reject symlinks/special files and ambiguous directories its
        // exact ID lookup could encounter. Mutations below never use its path.
        const discovered = await this.#discover(id, signal)
        await using project = await open(entry(this.root.file, discovered.project), directoryFlags)
        const projectInfo = await checkDirectory(project, this.root)
        await using session = await open(entry(project, discovered.session), directoryFlags)
        const sessionInfo = await checkDirectory(session, this.root, 0o700n)
        const before = await inventory(session, this.root, discovered.log)
        checkpoint()
        const initial = await persistence.stat(id, { signal })
        if (!initial) unavailable()
        const expect = (snapshot: SessionPersistenceSnapshot) => {
          const selected = selection(this.rootPath, snapshot, id)
          if (selected.project !== discovered.project || selected.session !== discovered.session || selected.log !== discovered.log ||
              (snapshot.sizeBytes !== undefined && snapshot.sizeBytes !== Number(before.get(selected.log)!.size))) unavailable()
        }
        expect(initial)
        await this.#checkAttached(project, session, discovered, projectInfo, sessionInfo)
        compareInventory(before, await inventory(session, this.root, discovered.log))
        checkpoint()

        await using writer = await persistence.open(id, 'write', { signal })
        if (writer.access !== 'write' || writer.id !== id || writer.header.id !== id || writer.header.version !== SESSION_FORMAT_VERSION ||
            writer.header.cwd !== initial.header.cwd) unavailable()
        checkpoint()
        const current = await persistence.stat(id, { signal })
        if (!current || initial.revision !== current.revision) unavailable()
        expect(current)
        checkpoint()

        transactionName = basename(await mkdtemp(entry(this.quarantine, '!transaction-')))
        await using transaction = await open(entry(this.quarantine, transactionName), directoryFlags)
        await checkDirectory(transaction, this.root, 0o700n)
        // Persist the transaction slot before it can receive a directory.
        await this.quarantine.sync()
        await transaction.sync()
        await this.#checkAttached(project, session, discovered, projectInfo, sessionInfo)
        const held = await inventory(session, this.root, discovered.log)
        compareInventory(before, held)
        checkpoint()

        // Never unlink generations while the provider can discover the session.
        // On a rename I/O error, the absence of a confirmation is not proof it
        // didn't take effect. Preserve the transaction and report uncertainty.
        attempted = true
        await rename(entry(project, discovered.session), entry(transaction, payloadName))
        await project.sync()
        await transaction.sync()

        await using moved = await open(entry(transaction, payloadName), directoryFlags)
        if (!identity(sessionInfo, await checkDirectory(moved, this.root, 0o700n))) unavailable()
        compareInventory(held, await inventory(moved, this.root, discovered.log))
        for (const [name, info] of held) {
          const checked = await checkedFile(moved, name, this.root)
          if (!(name === lockName ? identity(info, checked) : sameContents(info, checked))) unavailable()
          await unlink(entry(moved, name))
        }
        await moved.sync()
        await rmdir(entry(transaction, payloadName))
        await transaction.sync()
      } catch (error) {
        // `await using` releases the writer first. Once rename is issued,
        // preserve the slot on any failure for operator inspection.
        if (!attempted && transactionName !== undefined) {
          await rmdir(entry(this.quarantine, transactionName))
          await this.quarantine.sync()
        }
        throw error
      }
      await rmdir(entry(this.quarantine, transactionName))
      await this.quarantine.sync()
    } catch {
      if (attempted) {
        this.#unknown = true
        throw new SessionDeletionStorageError('outcome-unknown')
      }
      unavailable()
    }
  }

  async #discover(id: SessionId, signal: AbortSignal): Promise<Selection> {
    const session = sessionName(id)
    let selected: Selection | undefined
    for await (const dir of await opendir(fdPath(this.root.file))) {
      signal.throwIfAborted()
      if (!dir.isDirectory() || dir.name === quarantineName) continue
      await using project = await open(entry(this.root.file, dir.name), directoryFlags)
      await checkDirectory(project, this.root)
      for (const suffix of ['.jsonl', '.jsonl.zstd']) {
        // If the encoded ID nearly fills NAME_MAX, longer flat names cannot exist.
        if (session.length + suffix.length <= 255 && await exists(entry(project, session + suffix))) unavailable()
      }
      await using target = await open(entry(project, session), directoryFlags).catch(error => absent(error) ? undefined : Promise.reject(error))
      if (!target) continue
      await checkDirectory(target, this.root, 0o700n)
      const log = await exists(entry(target, currentLog)) ? currentLog
        : await exists(entry(target, `${currentLog}.zstd`)) ? `${currentLog}.zstd` : undefined
      if (!log || selected) unavailable()
      selected = { project: dir.name, session, log }
    }
    return selected ?? unavailable()
  }

  async #checkRoot(): Promise<void> {
    const root = this.root.file
    await checkDirectory(root, this.root)
    if (await readlink(fdPath(root)) !== this.rootPath) unavailable()
    await using quarantine = await open(entry(root, quarantineName), directoryFlags)
    if (!identity(await checkDirectory(quarantine, this.root, 0o700n), await this.quarantine.stat({ bigint: true }))) unavailable()
  }

  async #checkAttached(project: FileHandle, session: FileHandle, selected: Selection, projectInfo: BigIntStats, sessionInfo: BigIntStats): Promise<void> {
    await this.#checkRoot()
    await using linkedProject = await open(entry(this.root.file, selected.project), directoryFlags)
    if (!identity(projectInfo, await checkDirectory(linkedProject, this.root))) unavailable()
    await using linkedSession = await open(entry(project, selected.session), directoryFlags)
    if (!identity(sessionInfo, await checkDirectory(linkedSession, this.root, 0o700n)) ||
        !identity(sessionInfo, await checkDirectory(session, this.root, 0o700n))) unavailable()
  }

  #track<T>(work: Promise<T>): Promise<T> {
    this.#pending.add(work)
    return work.finally(() => { this.#pending.delete(work) })
  }

  close(): Promise<void> {
    return this.#closing ??= (async () => {
      await Promise.allSettled(this.#pending)
      try { await this.quarantine.close() }
      finally { await this.root.file.close() }
    })()
  }
}
