import type { Context } from '@deepseek-ai/cordis'
import type { SessionId } from '@deepseek-ai/dsh-session/types'
import type {} from '@deepseek-ai/dsh-typert-registry'
import type { Workspace } from '@deepseek-ai/dsh-workspace'
import { RemoteError, TypertRemoteService, type TypertLookup, type TypertRemoteCodecSpec } from '@deepseek-ai/dsh-typert-protocol'
import { createTypertRemoteInfo } from '@deepseek-ai/dsh-typert-protocol/remote-info'
import { createHash } from 'node:crypto'
import { constants, type BigIntStats } from 'node:fs'
import { lstat, open, opendir, statfs, type FileHandle } from 'node:fs/promises'
import { normalize } from 'node:path'
import { z } from 'zod'

const service = 'workspaceExports'
const lookup = 'workspaceExportScope'
const sessionSymbol = '@deepseek-ai/dsh-session/types#SessionId'
const scopeSymbol = 'tokenworks-mobile#WorkspaceExportScope'
const maxChunk = 256 * 1024
const maxFileBytes = 200_000_000
const maxEntries = 512
const maxDepth = 32
const versionSchema = z.string().length(64).regex(/^[a-f0-9]+$/)
const sizeSchema = z.number().int().min(0).max(Number.MAX_SAFE_INTEGER)
const unsafeSegment = /[/\\\u0000-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069\ufffd]/u
const segment = z.string().min(1).max(255).refine(value =>
  value !== '.' && value !== '..' && !unsafeSegment.test(value) && Buffer.byteLength(value) <= 255 && Buffer.from(value).toString() === value)
const partsSchema = z.array(segment).max(maxDepth)
const listResult = z.strictObject({
  entries: z.array(z.discriminatedUnion('kind', [
    z.strictObject({ name: segment, kind: z.literal('directory') }),
    z.strictObject({ name: segment, kind: z.literal('file'), size: sizeSchema, version: versionSchema }),
  ])).max(maxEntries),
  truncated: z.boolean(),
})
const readRequest = z.strictObject({
  parts: partsSchema.min(1), version: versionSchema, size: sizeSchema.max(maxFileBytes),
  offset: z.number().int().min(0).max(maxFileBytes), length: z.number().int().min(1).max(maxChunk),
})
const readResult = z.strictObject({ offset: sizeSchema, data: z.string().max(Math.ceil(maxChunk / 3) * 4), eof: z.boolean() })
type ExportScope = { sessionId: SessionId; workspace: Workspace }
type ListResult = z.infer<typeof listResult>
type ReadRequest = z.infer<typeof readRequest>
type ExportError = 'workspace-export/unavailable' | 'workspace-export/unsupported' | 'workspace-export/source-changed'

declare module '@deepseek-ai/dsh-typert-protocol' {
  interface TypertLookupMap {
    workspaceExportScope: TypertLookup<ExportScope, SessionId>
  }
  interface RemoteErrorDetailsMap {
    'workspace-export/unavailable': Record<string, never>
    'workspace-export/unsupported': Record<string, never>
    'workspace-export/source-changed': Record<string, never>
  }
}

function codec(typeSymbol: string, schema: z.ZodType): TypertRemoteCodecSpec {
  // Typert's Zod package has separate declarations; this codec uses the shared parse API.
  return { mode: 'strict', typeSymbol, create: () => schema as unknown as ReturnType<TypertRemoteCodecSpec['create']> }
}
const sessionCodec = codec(sessionSymbol, z.string().min(1).max(2048))
const scopeParameter = { name: lookup, wire: 'sessionId', source: 'lookup', lookup, codec: sessionCodec, strictCodec: sessionCodec } as const
const pathCodec = codec('tokenworks-mobile#WorkspaceExportParts', partsSchema)
const readCodec = codec('tokenworks-mobile#WorkspaceExportReadRequest', readRequest)
export const workspaceExportsRemote = createTypertRemoteInfo({
  package: 'tokenworks-mobile',
  methods: {
    'workspaceExports/list': {
      service, invocation: { kind: 'direct' }, cancellation: { parameter: 'signal' },
      parameters: [scopeParameter, { name: 'parts', source: 'json', codec: pathCodec, strictCodec: pathCodec }],
      result: codec('tokenworks-mobile#WorkspaceExportListing', listResult),
    },
    'workspaceExports/read': {
      service, invocation: { kind: 'direct' }, cancellation: { parameter: 'signal' },
      parameters: [scopeParameter, { name: 'request', source: 'json', codec: readCodec, strictCodec: readCodec }],
      result: codec('tokenworks-mobile#WorkspaceExportChunk', readResult),
    },
  },
}, z as unknown as Parameters<typeof createTypertRemoteInfo>[1])

export function installWorkspaceExports(ctx: Context): void {
  new WorkspaceExports(ctx)
  ctx.effect(() => {
    const disposeLookup = ctx.typert.lookups.register(lookup, {
      parameter: lookup, wire: 'sessionId', resource: 'session', hostTypeSymbol: scopeSymbol, wireTypeSymbol: sessionSymbol,
      resolve: sessionId => {
        // A caller-supplied session cwd is not a trusted export root. Membership
        // in the durable Host workspace registry is required on every call.
        const workspace = ctx.workspaceRegistry.list().find(item => item.sessionIds.includes(sessionId))
        return workspace ? { sessionId, workspace } : undefined
      },
    })
    try {
      const disposeContract = ctx.typert.register({ package: workspaceExportsRemote.package, face: 'host', schemas: [],
        model: { services: [], events: [], objects: [] }, invocations: workspaceExportsRemote.descriptors })
      return async () => { try { await disposeContract() } finally { await disposeLookup() } }
    } catch (error) { void disposeLookup(); throw error }
  }, 'remote-control.workspace-exports')
}

function refused(code: ExportError): never {
  throw new RemoteError(code, 'Workspace export is unavailable for this source.', {})
}
function version(workspace: Workspace, info: BigIntStats): string {
  return createHash('sha256').update([workspace.id, info.dev, info.ino, info.size, info.mode, info.mtimeNs, info.ctimeNs, info.nlink].join(':')).digest('hex')
}

const linuxPathOnly = 0o10000000
let procFdSupport: Promise<boolean> | undefined

/** Every component is kernel-relative to a pinned directory and must not be a symlink. */
async function openSource(root: string, parts: readonly string[], directory: boolean, check: () => void): Promise<FileHandle> {
  if (process.platform !== 'linux' || !(await (procFdSupport ??= statfs('/proc/self/fd').then(info => info.type === 0x9fa0, () => false)))) {
    refused('workspace-export/unsupported')
  }
  if (!root.startsWith('/') || normalize(root) !== root) refused('workspace-export/unavailable')
  const flags = constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK
  const components = root === '/' ? parts : [...root.slice(1).split('/'), ...parts]
  let current: FileHandle | undefined = await open('/', flags | constants.O_DIRECTORY)
  try {
    for (const [index, component] of components.entries()) {
      check()
      const previous: FileHandle = current!
      const isFileLeaf = !directory && index + 1 === components.length
      // O_PATH lets stat reject symlinks and devices before opening for I/O.
      current = await open(`/proc/self/fd/${previous.fd}/${component}`,
        isFileLeaf ? linuxPathOnly | constants.O_NOFOLLOW : flags | constants.O_DIRECTORY)
      await previous.close()
    }
    check()
    const pinned = current
    current = undefined
    return pinned!
  } finally { await current?.close() }
}

class WorkspaceExports extends TypertRemoteService {
  constructor(ctx: Context) { super(ctx, service) }

  check(scope: ExportScope, signal: AbortSignal): void {
    signal.throwIfAborted()
    this.ctx.invocation?.peer.signal.throwIfAborted()
    if (!this.ctx.workspaceRegistry.get(scope.workspace.id)?.sessionIds.includes(scope.sessionId)) refused('workspace-export/unavailable')
  }

  async list(scope: ExportScope, parts: string[], signal: AbortSignal): Promise<ListResult> {
    const check = () => this.check(scope, signal)
    try {
      check()
      const entries: ListResult['entries'] = []
      let truncated = false
      const handle = await openSource(scope.workspace.path, parts, true, check)
      try {
        const fdPath = `/proc/self/fd/${handle.fd}`
        const dir = await opendir(fdPath, { bufferSize: 32 })
        try {
          for (let scanned = 0; ; scanned++) {
            check()
            const entry = await dir.read()
            if (!entry) break
            if (scanned === maxEntries || parts.length === maxDepth) { truncated = true; break }
            if (!segment.safeParse(entry.name).success) continue
            if (entry.isDirectory()) {
              entries.push({ kind: 'directory', name: entry.name })
            } else if (entry.isFile()) {
              const info = await lstat(`${fdPath}/${entry.name}`, { bigint: true }).catch(error => {
                if (error?.code === 'ENOENT') return undefined
                throw error
              })
              if (info?.isFile() && info.nlink === 1n && info.size <= BigInt(Number.MAX_SAFE_INTEGER)) {
                entries.push({ kind: 'file', name: entry.name, size: Number(info.size), version: version(scope.workspace, info) })
              }
            }
          }
        } finally { await dir.close() }
      } finally { await handle.close() }
      check()
      entries.sort((a, b) => (a.kind === b.kind ? a.name.localeCompare(b.name) : a.kind === 'directory' ? -1 : 1))
      return { entries, truncated }
    } catch (error) {
      check()
      if (error instanceof RemoteError) throw error
      refused('workspace-export/unavailable')
    }
  }

  async read(scope: ExportScope, request: ReadRequest, signal: AbortSignal): Promise<z.infer<typeof readResult>> {
    const { parts, size, offset, length } = request
    const check = () => this.check(scope, signal)
    if (offset > size) refused('workspace-export/source-changed')
    try {
      check()
      const handle = await openSource(scope.workspace.path, parts, false, check)
      let data: Buffer
      try {
        const validate = async () => {
          check()
          const info = await handle.stat({ bigint: true })
          if (!info.isFile() || info.nlink !== 1n || info.size !== BigInt(size) || version(scope.workspace, info) !== request.version) {
            refused('workspace-export/source-changed')
          }
        }
        await validate()
        data = Buffer.allocUnsafe(Math.min(length, size - offset))
        if (data.length > 0) {
          const reader = await open(`/proc/self/fd/${handle.fd}`, constants.O_RDONLY | constants.O_NONBLOCK)
          try {
            for (let copied = 0; copied < data.length;) {
              check()
              const { bytesRead } = await reader.read(data, copied, data.length - copied, offset + copied)
              if (!bytesRead) refused('workspace-export/source-changed')
              copied += bytesRead
            }
          } finally { await reader.close() }
        }
        await validate()
      } finally { await handle.close() }
      check()
      return { offset, data: data.toString('base64'), eof: offset + data.length === size }
    } catch (error) {
      check()
      if (error instanceof RemoteError) throw error
      refused('workspace-export/source-changed')
    }
  }
}
