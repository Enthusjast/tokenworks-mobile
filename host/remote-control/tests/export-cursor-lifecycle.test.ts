import test, { type TestContext } from 'node:test'
import assert from 'node:assert/strict'
import { mkdir, readdir, readlink, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { randomUUID } from 'node:crypto'
import { host } from './harness.js'
import { DshClient } from '../src/dsh-client.js'
import { FileExportRegistry } from '../src/file-export-registry.js'
import { PinnedExportSource } from '../src/file-export-source.js'
import { ExportCatalog, type CatalogAuthority } from '../src/export-catalog.js'
import { ProbeError } from '../src/protocol.js'

const supported = await PinnedExportSource.supported()
function gate() { let release!: () => void; const promise = new Promise<void>(resolve => { release = resolve }); return { promise, release } }
async function fixture(t: TestContext) {
  const h = await host({ probe: false }), output = join(h.cwd, 'output')
  await mkdir(output)
  await Promise.all(Array.from({ length: 17 }, (_, index) => writeFile(join(output, `item-${index}`), '')))
  const client = new DshClient(h.ctx, h.runtimeId, [{ id: 'fixture', cwd: h.cwd, exportRoots: [{ id: 'reports', directory: 'output' }] }], ['fixture'])
  const registry = new FileExportRegistry(client), catalog = new ExportCatalog(client, registry)
  const actor: CatalogAuthority = { owner: { userId: randomUUID(), deviceId: randomUUID() }, keyVersion: 1, grantVersion: 1,
    allowWorkspace: true, expiresAt: Date.now() + 290_000, signal: new AbortController().signal, current() {} }
  const session = (await client.create('fixture', 'fixture')).session_id
  const roots = await catalog.list(session, actor), directory = roots.items.find(row => row.name === 'reports')!.source_id
  t.after(async () => { await catalog.close(); await registry.close(); await h.close() })
  const descriptors = async () => (await Promise.all((await readdir('/proc/self/fd')).map(fd => readlink(`/proc/self/fd/${fd}`).catch(() => '')))).filter(path => path === output).length
  return { client, catalog, registry, actor, session, directory, descriptors }
}

test('a first page reserves the eighth cursor before awaiting native directory I/O', { skip: !supported }, async t => {
  const f = await fixture(t), entered = gate(), resume = gate()
  for (let index = 0; index < 7; index++) assert.ok((await f.catalog.list(f.session, f.actor, f.directory)).cursor)
  assert.equal(await f.descriptors(), 14)
  const page = f.client.workspaceExportPage.bind(f.client)
  f.client.workspaceExportPage = async (...args) => { const value = await page(...args); entered.release(); await resume.promise; return value }
  const pending = f.catalog.list(f.session, f.actor, f.directory)
  let extra: ReturnType<typeof f.catalog.list> | undefined
  try {
    await entered.promise
    extra = f.catalog.list(f.session, f.actor, f.directory)
    const outcome = extra.then(() => 'accepted', error => error instanceof ProbeError ? error.code : 'unknown')
    assert.equal(await Promise.race([outcome, new Promise(resolve => setImmediate(() => resolve('pending')))]), 'rate.limited')
    assert.equal(await f.descriptors(), 16)
    resume.release(); assert.ok((await pending).cursor)
    await f.catalog.close()
    assert.equal(await f.descriptors(), 0)
  } finally { resume.release(); await Promise.allSettled([pending, ...(extra ? [extra] : [])]) }
})

test('catalog close waits for a real directory reader returned by an in-flight first page', { skip: !supported }, async t => {
  const f = await fixture(t), entered = gate(), resume = gate(), page = f.client.workspaceExportPage.bind(f.client)
  f.client.workspaceExportPage = async (...args) => { const value = await page(...args); entered.release(); await resume.promise; return value }
  const pending = f.catalog.list(f.session, f.actor, f.directory)
  const rejected = assert.rejects(pending, /access.denied|aborted/i)
  let closing: Promise<void> | undefined
  try {
    await entered.promise
    assert.equal(await f.descriptors(), 2)
    let finished = false
    closing = f.catalog.close(); void closing.then(() => { finished = true })
    await new Promise(resolve => setImmediate(resolve))
    assert.equal(finished, false)
    assert.equal(await f.descriptors(), 2)
    resume.release(); await rejected; await closing
    assert.equal(await f.descriptors(), 0)
  } finally { resume.release(); await Promise.allSettled([pending, ...(closing ? [closing] : [])]) }
})

test('an owner mutation during a native page cannot publish metadata under a new identity', { skip: !supported }, async t => {
  const f = await fixture(t), entered = gate(), resume = gate(), page = f.client.workspaceExportPage.bind(f.client)
  f.client.workspaceExportPage = async (...args) => { const value = await page(...args); entered.release(); await resume.promise; return value }
  const pending = f.catalog.list(f.session, f.actor, f.directory), rejected = assert.rejects(pending, /access.denied/)
  try {
    await entered.promise
    f.actor.owner.deviceId = randomUUID()
    resume.release(); await rejected
    assert.equal(await f.descriptors(), 0)
  } finally { resume.release(); await Promise.allSettled([pending]) }
})

test('catalog close also waits for root metadata and suppresses its late result', { skip: !supported }, async t => {
  const f = await fixture(t), entered = gate(), resume = gate(), roots = f.client.workspaceExportRootIds.bind(f.client)
  f.client.workspaceExportRootIds = async (...args) => { const value = await roots(...args); entered.release(); await resume.promise; return value }
  const pending = f.catalog.list(f.session, f.actor), rejected = assert.rejects(pending, /access.denied|aborted/i)
  let closing: Promise<void> | undefined
  try {
    await entered.promise
    let finished = false
    closing = f.catalog.close(); void closing.then(() => { finished = true })
    await new Promise(resolve => setImmediate(resolve))
    assert.equal(finished, false)
    resume.release(); await rejected; await closing
    await assert.rejects(f.catalog.list(f.session, f.actor), /access.denied/)
  } finally { resume.release(); await Promise.allSettled([pending, ...(closing ? [closing] : [])]) }
})
