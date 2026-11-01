import test, { type TestContext } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, readlink, readdir, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { randomUUID } from 'node:crypto'
import { FileExportRegistry, type ExportAuthority } from '../src/file-export-registry.js'
import { PinnedExportSource } from '../src/file-export-source.js'
import { ProbeError } from '../src/protocol.js'

const supported = await PinnedExportSource.supported()
function gate() { let release!: () => void; const promise = new Promise<void>(resolve => { release = resolve }); return { promise, release } }
async function descriptors(root: string): Promise<number> {
  const paths = await Promise.all((await readdir('/proc/self/fd')).map(fd => readlink(`/proc/self/fd/${fd}`).catch(() => '')))
  return paths.filter(path => path.startsWith(root + '/')).length
}
async function fixture(t: TestContext, customize: (source: PinnedExportSource) => void, awaitCleanup = true) {
  const root = await mkdtemp(join(tmpdir(), 'dsh-export-cleanup-'))
  await writeFile(join(root, 'artifact'), 'small source')
  const sources: PinnedExportSource[] = []
  const registry = new FileExportRegistry({
    async workspaceExportSource(_session, _root, _parts, lifetime, current) {
      const source = await PinnedExportSource.open({ root, parts: ['artifact'] }, lifetime, current)
      sources.push(source); customize(source)
      return source
    },
    async attachmentExportSource() { throw new ProbeError('file.export_unavailable') },
  })
  const actor: ExportAuthority = { owner: { userId: randomUUID(), deviceId: randomUUID() }, signal: new AbortController().signal, current() {} }
  const session = randomUUID()
  t.after(async () => { const closing = registry.close().catch(() => {}); if (awaitCleanup) await closing; await rm(root, { recursive: true, force: true }) })
  return { registry, root, sources, actor, session, register: (authority = actor) => registry.registerWorkspaceArtifact(session, 'root', ['artifact'], authority) }
}

test('registry close waits for a released source whose real descriptor is still closing', { skip: !supported }, async t => {
  const entered = gate(), resume = gate()
  const f = await fixture(t, source => {
    const close = source.close.bind(source); let closing: Promise<void> | undefined
    source.close = () => closing ??= (async () => { entered.release(); await resume.promise; await close() })()
  })
  const entry = await f.register(), release = f.registry.release(entry.export_id, f.session, f.actor)
  let closing: Promise<void> | undefined
  try {
    await entered.promise
    assert.equal(await descriptors(f.root), 1)
    assert.throws(() => f.registry.describe(entry.export_id, f.session, f.actor), /file.export_unavailable/)
    let finished = false
    closing = f.registry.close(); void closing.then(() => { finished = true })
    await new Promise(resolve => setImmediate(resolve))
    assert.equal(finished, false, 'shutdown forgot cleanup detached from the public entry map')
    assert.equal(await descriptors(f.root), 1)
    resume.release(); await release; await closing
    assert.equal(await descriptors(f.root), 0)
  } finally { resume.release(); await Promise.allSettled([release, ...(closing ? [closing] : [])]) }
})

test('pruned descriptors consume the 32-source budget until actual close completes', { skip: !supported }, async t => {
  const resume = gate()
  const f = await fixture(t, source => {
    const close = source.close.bind(source); let closing: Promise<void> | undefined
    source.close = () => closing ??= (async () => { await resume.promise; await close() })()
  })
  const lifetime = new AbortController(), actor = { ...f.actor, signal: lifetime.signal }
  try {
    for (let index = 0; index < 32; index++) await f.register(actor)
    assert.equal(await descriptors(f.root), 32)
    lifetime.abort()
    await assert.rejects(f.register(), /rate.limited/)
    assert.equal(f.sources.length, 32, 'pruning freed capacity before descriptor close')
    assert.equal(await descriptors(f.root), 32)
    resume.release(); await Promise.all(f.sources.map(source => source.close()))
    await new Promise(resolve => setImmediate(resolve))
    assert.equal(await descriptors(f.root), 0)
    const fresh = await f.register()
    assert.equal(f.sources.length, 33)
    await f.registry.release(fresh.export_id, f.session, f.actor)
    assert.equal(await descriptors(f.root), 0)
  } finally { resume.release() }
})

test('a failed close cannot strand preparation accounting or silently report successful shutdown', { skip: !supported, timeout: 5000 }, async t => {
  const f = await fixture(t, source => {
    const close = source.close.bind(source)
    source.prepare = async () => { throw new ProbeError('file.export_changed') }
    source.close = async () => { await close(); throw new Error('PRIVATE_CLOSE_PATH') }
  }, false)
  const unavailable = (error: unknown) => error instanceof ProbeError && error.code === 'file.export_unavailable' && !String(error).includes('PRIVATE_CLOSE_PATH')
  await assert.rejects(f.register(), unavailable)
  assert.equal(await descriptors(f.root), 0)
  await assert.rejects(f.register(), unavailable)
  assert.equal(f.sources.length, 1)
  await assert.rejects(f.registry.close(), unavailable)
  await assert.rejects(f.registry.close(), unavailable)
})
