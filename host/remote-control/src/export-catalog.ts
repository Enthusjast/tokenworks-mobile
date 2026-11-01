import { randomUUID } from 'node:crypto'
import type { DshClient, AttachmentExportCursor } from './dsh-client.js'
import type { ExportDirectory } from './export-directory.js'
import { FileExportRegistry, type ExportAuthority } from './file-export-registry.js'
import { MAX_EXPORT_FILE_BYTES } from './file-export-source.js'
import type { ExportNode, ExportPage } from './export-protocol.js'
import { ProbeError } from './protocol.js'
import type { VisibleMediaReference } from './media-protocol.js'
import { verifyVisibleMedia } from './session-media.js'

export interface CatalogAuthority extends ExportAuthority { keyVersion: number; grantVersion: number; allowWorkspace: boolean; expiresAt: number }
type Selector = { type: 'attachments' } | { type: 'attachment'; seq: number; index: number; media?: { reference: VisibleMediaReference; sync: string } }
  | { type: 'workspace'; root: string; parts: string[]; kind: 'directory' | 'file'; version?: string }
interface Item { node: ExportNode; selector: Selector; session: string; owner: string; start: bigint; wall: number; expires: number }
interface Cursor { item: Item; directory: string; session: string; owner: string; expires: number; busy: boolean; closed: boolean; abort: AbortController; reader?: ExportDirectory; native?: AttachmentExportCursor }

/** IDs are connection-local metadata references, never filesystem selectors from a phone. */
export class ExportCatalog {
  #items = new Map<string, Item>()
  #cursors = new Map<string, Cursor>()
  #owned = new Set<Cursor>()
  #disposing = new Map<Cursor, Promise<void>>()
  #pending = new Set<Promise<void>>()
  #lifetime = new AbortController()
  #cleanupFailed = false
  #closing: Promise<void> | undefined
  #closed = false
  #timer: NodeJS.Timeout
  constructor(private readonly client: DshClient, private readonly registry: FileExportRegistry,
    private readonly mediaCurrent?: (session: string, sync: string) => boolean) {
    this.#timer = setInterval(() => this.#prune(), 1000); this.#timer.unref()
  }
  #owner(scope: CatalogAuthority) { return `${scope.owner.userId}:${scope.owner.deviceId}:${scope.keyVersion}:${scope.grantVersion}` }
  #current(scope: CatalogAuthority) {
    if (this.#closed || !this.registry.accepting || Date.now() >= scope.expiresAt) throw new ProbeError('access.denied')
    if (this.#cleanupFailed) throw new ProbeError('file.export_unavailable')
    scope.signal.throwIfAborted(); scope.current(); scope.signal.throwIfAborted()
    if (this.#closed || !this.registry.accepting) throw new ProbeError('access.denied')
  }
  async #run<T>(scope: CatalogAuthority, work: (current: CatalogAuthority) => Promise<T>): Promise<T> {
    this.#current(scope)
    if (this.#pending.size >= 8) throw new ProbeError('rate.limited')
    const owner = this.#owner(scope), allowWorkspace = scope.allowWorkspace, expiresAt = scope.expiresAt
    let finish!: () => void
    const done = new Promise<void>(resolve => { finish = resolve }); this.#pending.add(done)
    try {
      const signal = AbortSignal.any([scope.signal, this.#lifetime.signal])
      const current = () => {
        this.#current(scope); signal.throwIfAborted()
        if (owner !== this.#owner(scope) || allowWorkspace !== scope.allowWorkspace || expiresAt !== scope.expiresAt) throw new ProbeError('access.denied')
      }
      const active = { ...scope, owner: { ...scope.owner }, signal, current }
      const result = await work(active)
      this.#current(active)
      return result
    } finally { this.#pending.delete(done); finish() }
  }
  #retire(cursor: Cursor): void {
    cursor.closed = true; cursor.abort.abort()
    for (const [id, value] of this.#cursors) if (value === cursor) this.#cursors.delete(id)
    if (!cursor.busy) void this.#dispose(cursor).catch(() => {})
  }
  #dispose(cursor: Cursor): Promise<void> {
    const closing = this.#disposing.get(cursor)
    if (closing) return closing
    if (!this.#owned.has(cursor)) return Promise.resolve()
    cursor.closed = true; cursor.abort.abort()
    const task = Promise.resolve().then(() => cursor.reader?.close()).catch(() => {
      this.#cleanupFailed = true
      throw new ProbeError('file.export_unavailable')
    }).finally(() => { this.#disposing.delete(cursor); this.#owned.delete(cursor) })
    this.#disposing.set(cursor, task); void task.catch(() => {})
    return task
  }
  #prune() {
    for (const [id, item] of this.#items) if (Date.now() < item.wall || Date.now() >= item.expires || process.hrtime.bigint() - item.start >= 300_000_000_000n) this.#items.delete(id)
    for (const cursor of this.#owned) if (this.#cleanupFailed || Date.now() >= cursor.expires || !this.#items.has(cursor.directory)) this.#retire(cursor)
  }
  #add(session: string, scope: CatalogAuthority, selector: Selector, name: string, size?: number): ExportNode {
    this.#current(scope); this.#prune()
    if (this.#items.size >= 1024) throw new ProbeError('rate.limited')
    const source_id = randomUUID(), expires = Math.min(scope.expiresAt, Date.now() + 300_000)
    const origin = selector.type === 'workspace' ? 'workspace_artifact' as const : 'session_attachment' as const
    const node: ExportNode = size === undefined ? { source_id, kind: 'directory', origin, name, expires_at: new Date(expires).toISOString() }
      : { source_id, kind: 'file', origin, name, size_bytes: size, expires_at: new Date(expires).toISOString(), ...(size > MAX_EXPORT_FILE_BYTES ? { unavailable: 'file.too_large' as const } : {}) }
    this.#items.set(source_id, { node, selector, session, owner: this.#owner(scope), start: process.hrtime.bigint(), wall: Date.now(), expires })
    return { ...node }
  }
  #find(id: string, session: string, scope: CatalogAuthority): Item {
    this.#current(scope); this.#prune()
    const item = this.#items.get(id)
    if (!item || item.session !== session || item.owner !== this.#owner(scope)) throw new ProbeError('file.export_unavailable')
    if (item.selector.type === 'workspace' && !scope.allowWorkspace) throw new ProbeError('access.denied')
    if (item.selector.type === 'attachment' && item.selector.media && !this.mediaCurrent?.(session, item.selector.media.sync)) throw new ProbeError('file.export_unavailable')
    return item
  }
  resolveMedia(session: string, sync: string, reference: VisibleMediaReference, scope: CatalogAuthority): Promise<ExportNode> {
    return this.#run(scope, current => this.#resolveMedia(session, sync, reference, current))
  }
  async #resolveMedia(session: string, sync: string, reference: VisibleMediaReference, scope: CatalogAuthority): Promise<ExportNode> {
    this.#current(scope)
    const current = () => {
      this.#current(scope)
      if (!this.mediaCurrent?.(session, sync)) throw new ProbeError('protocol.resync_required')
    }
    current()
    const verified = await verifyVisibleMedia(this.client, session, reference, scope.signal, current)
    current()
    return this.#add(session, scope, { type: 'attachment', seq: verified.native_seq, index: verified.part_index,
      media: { reference: verified, sync } }, verified.manifest.name, verified.manifest.size_bytes)
  }
  list(session: string, scope: CatalogAuthority, directory?: string, continuation?: string): Promise<ExportPage> {
    return this.#run(scope, current => this.#list(session, current, directory, continuation))
  }
  async #list(session: string, scope: CatalogAuthority, directory?: string, continuation?: string): Promise<ExportPage> {
    this.#current(scope)
    if (!directory) {
      if (continuation) throw new ProbeError('file.export_unavailable')
      const roots = await this.client.workspaceExportRootIds(session, scope.signal, () => this.#current(scope))
      const items = [this.#add(session, scope, { type: 'attachments' }, '会话附件')]
      if (scope.allowWorkspace) for (const root of roots) items.push(this.#add(session, scope, { type: 'workspace', root, parts: [], kind: 'directory' }, root))
      this.#current(scope)
      return { items, directory_id: null, cursor: null }
    }
    const item = this.#find(directory, session, scope)
    if (item.node.kind !== 'directory') throw new ProbeError('file.export_unavailable')
    let cursor = continuation ? this.#cursors.get(continuation) : undefined
    if (continuation && (!cursor || cursor.owner !== this.#owner(scope) || cursor.session !== session || cursor.directory !== directory)) throw new ProbeError('file.export_unavailable')
    if (cursor?.busy) throw new ProbeError('rate.limited')
    if (!cursor) {
      if (this.#owned.size >= 8) throw new ProbeError('rate.limited')
      cursor = { item, directory, session, owner: this.#owner(scope), expires: item.expires, busy: false, closed: false, abort: new AbortController() }
      this.#owned.add(cursor)
    }
    const selected = cursor
    selected.busy = true
    const signal = AbortSignal.any([scope.signal, selected.abort.signal])
    const current = () => {
      this.#current(scope); signal.throwIfAborted()
      if (selected.closed || this.#items.get(directory) !== selected.item || selected.owner !== this.#owner(scope)) throw new ProbeError('file.export_unavailable')
    }
    let keep = false
    try {
      const items: ExportNode[] = []
      let more = false
      const selector = item.selector
      if (selector.type === 'attachments') {
        const page = await this.client.attachmentExportPage(session, signal, current, selected.native)
        current()
        for (const candidate of page.items) items.push(this.#add(session, scope, { type: 'attachment', seq: candidate.seq, index: candidate.index }, candidate.manifest.name, candidate.manifest.size_bytes))
        if (page.cursor) { selected.native = page.cursor; more = true }
      } else if (selector.type === 'workspace' && selector.kind === 'directory') {
        const page = await this.client.workspaceExportPage(session, selector.root, selector.parts, signal, current, selected.reader)
        selected.reader = page.reader
        current()
        for (const child of page.items) {
          if (selector.parts.length >= 15 && child.kind === 'directory') continue
          items.push(this.#add(session, scope, { type: 'workspace', root: selector.root, parts: [...selector.parts, child.name], kind: child.kind, ...(child.version ? { version: child.version } : {}) }, child.name, child.kind === 'file' ? child.size : undefined))
        }
        more = !page.done
      } else throw new ProbeError('file.export_unavailable')
      current()
      if (continuation) this.#cursors.delete(continuation)
      if (!more) return { items, directory_id: directory, cursor: null }
      const next = randomUUID(); this.#cursors.set(next, selected); keep = true
      return { items, directory_id: directory, cursor: next }
    } finally {
      selected.busy = false
      if (!keep || selected.closed || this.#closed) {
        for (const [id, value] of this.#cursors) if (value === selected) this.#cursors.delete(id)
        await this.#dispose(selected)
      }
    }
  }
  prepare(session: string, source: string, scope: CatalogAuthority) {
    return this.#run(scope, current => this.#prepare(session, source, current))
  }
  async #prepare(session: string, source: string, scope: CatalogAuthority) {
    const item = this.#find(source, session, scope), selector = item.selector
    if (item.node.kind !== 'file') throw new ProbeError('file.export_unavailable')
    if (item.node.unavailable) throw new ProbeError(item.node.unavailable)
    if (selector.type === 'attachment' && selector.media) await verifyVisibleMedia(this.client, session, selector.media.reference, scope.signal,
      () => { this.#current(scope); this.#find(source, session, scope) })
    const authority: ExportAuthority = { owner: { ...scope.owner }, signal: scope.signal, current: () => { this.#current(scope); this.#find(source, session, scope) } }
    const entry = selector.type === 'attachment' ? await this.registry.registerSessionAttachment(session, selector.seq, selector.index, authority)
      : selector.type === 'workspace' && selector.kind === 'file' ? await this.registry.registerWorkspaceArtifact(session, selector.root, selector.parts, authority, selector.version)
        : undefined
    if (!entry) throw new ProbeError('file.export_unavailable')
    if (selector.type === 'attachment' && selector.media && (entry.sha256 !== selector.media.reference.manifest.sha256 || entry.size_bytes !== selector.media.reference.manifest.size_bytes || entry.name !== selector.media.reference.manifest.name)) {
      await this.registry.release(entry.export_id, session, authority)
      throw new ProbeError('file.export_changed')
    }
    return { ...entry, expires_at: new Date(Math.min(item.expires, Date.parse(entry.expires_at))).toISOString() }
  }
  async close(): Promise<void> {
    if (this.#closing) return this.#closing
    this.#closed = true; clearInterval(this.#timer); this.#lifetime.abort()
    this.#cursors.clear(); this.#items.clear()
    this.#closing = (async () => {
      while (this.#pending.size || this.#owned.size || this.#disposing.size) {
        for (const cursor of this.#owned) this.#retire(cursor)
        await Promise.allSettled([...this.#pending, ...this.#disposing.values()])
      }
      if (this.#cleanupFailed) throw new ProbeError('file.export_unavailable')
    })()
    return this.#closing
  }
  toJSON() { return {} }
  [Symbol.for('nodejs.util.inspect.custom')]() { return 'ExportCatalog' }
}
