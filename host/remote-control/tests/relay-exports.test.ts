import test, { type TestContext } from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { mkdir, readdir, readlink, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { host } from './harness.js'
import { DshClient } from '../src/dsh-client.js'
import { FileExportRegistry } from '../src/file-export-registry.js'
import { PinnedExportSource } from '../src/file-export-source.js'
import { RelayExports } from '../src/relay-exports.js'
import { ProbeError } from '../src/protocol.js'

const supported = await PinnedExportSource.supported()
function gate() { let release!: () => void; const promise = new Promise<void>(resolve => { release = resolve }); return { promise, release } }
async function waitFor(read: () => boolean | Promise<boolean>) {
  for (let end = Date.now() + 5000; Date.now() < end;) { if (await read()) return; await new Promise(resolve => setTimeout(resolve, 10)) }
  assert.fail('timed out waiting for export fixture')
}
async function fixture(t: TestContext) {
  const h = await host({ probe: false })
  await mkdir(join(h.cwd, 'output')); await writeFile(join(h.cwd, 'output', 'result.txt'), 'export lease fixture')
  const client = new DshClient(h.ctx, h.runtimeId, [{ id: 'fixture', cwd: h.cwd, exportRoots: [{ id: 'output', directory: 'output' }] }], ['fixture'])
  const session = (await client.create('fixture', 'fixture')).session_id, registry = new FileExportRegistry(client), epoch = randomUUID()
  const lifetime = new AbortController(), state = { wall: Date.now(), mono: 0 }, sent: any[] = []
  const actor = { user_id: randomUUID(), device_id: randomUUID(), key_version: 1, grant_version: 1, allow_workspace: true }
  const peer = new RelayExports(registry, client, epoch, { installationId: randomUUID(), connectionId: randomUUID(), origin: 'https://relay.example.invalid',
    signer: { sign() { throw new Error('unused signer') } }, signal: lifetime.signal, async send(frame) { sent.push(frame) }, fail() { assert.fail('unexpected transport failure') } },
    () => true, { wall: () => state.wall, monotonic: () => state.mono })
  t.after(async () => { lifetime.abort(); await peer.close(); await registry.close(); await h.close() })
  const lease = () => ({ ...actor, lease_until: new Date(state.wall + 5000).toISOString(), expires_at: new Date(state.wall + 300_000).toISOString() })
  const frame = <T>(type: string, payload: T, id = randomUUID()) => ({ v: 1, type, request_id: id, runtime_id: client.runtimeId, runtime_epoch: epoch, session_id: session, payload })
  const list = async (directory?: string) => {
    const request = frame('file.exports.list', { ...lease(), ...(directory ? { directory_id: directory } : {}) })
    peer.handle(request); await waitFor(() => sent.some(row => row.type === 'file.exports.result' && row.request_id === request.request_id))
    return sent.find(row => row.type === 'file.exports.result' && row.request_id === request.request_id)
  }
  const prepare = async () => {
    const roots = await list(), directory = roots.payload.items.find((row: any) => row.name === 'output').source_id
    const page = await list(directory), source = page.payload.items.find((row: any) => row.name === 'result.txt').source_id
    const request = frame('file.export.request', { ...lease(), origin_request_id: randomUUID(), source_id: source })
    peer.handle(request); await waitFor(() => sent.some(row => row.type === 'file.export.manifest' && row.request_id === request.request_id))
    return sent.find(row => row.type === 'file.export.manifest' && row.request_id === request.request_id)
  }
  const upload = (manifest: any) => {
    const until = new Date(state.wall + 30_000).toISOString()
    return frame('file.export.upload', { file: { file_id: randomUUID(), runtime_id: client.runtimeId, session_id: session, direction: 'from_host',
      name: manifest.payload.name, size_bytes: manifest.payload.size_bytes, sha256: manifest.payload.sha256, state: 'uploading', upload_expires_at: until,
      expires_at: new Date(state.wall + 300_000).toISOString(), created_at: new Date(state.wall).toISOString() },
      upload: { url: 'https://objects.example.invalid/upload', method: 'PUT', headers: { 'Content-Type': 'application/octet-stream' }, expires_at: until } }, manifest.request_id)
  }
  const descriptors = async () => (await Promise.all((await readdir('/proc/self/fd')).map(fd => readlink(`/proc/self/fd/${fd}`).catch(() => '')))).filter(path => path === join(h.cwd, 'output', 'result.txt')).length
  return { h, client, session, registry, peer, state, sent, frame, lease, prepare, upload, descriptors }
}

test('a late export renewal cannot preserve a native source before the cleanup tick', { skip: !supported }, async t => {
  const f = await fixture(t), manifest = await f.prepare(), original = globalThis.fetch
  let requests = 0
  globalThis.fetch = (async () => { requests++; throw new Error('unexpected PUT') }) as typeof fetch
  try {
    assert.equal(await f.descriptors(), 1)
    await waitFor(() => f.sent.some(row => row.type === 'file.export.renew'))
    const renewal = f.sent.find(row => row.type === 'file.export.renew')
    f.state.mono = 2501 // The original five-second lease itself is still live.
    assert.equal(f.peer.handle({ ...renewal, type: 'file.export.renewed', payload: { leases: [{ operation_id: manifest.request_id,
      lease_until: f.lease().lease_until, expires_at: f.lease().expires_at }] } }), true)
    f.peer.handle(f.upload(manifest))
    assert.equal(requests, 0)
    await waitFor(async () => await f.descriptors() === 0)
    assert.equal(f.sent.some(row => row.type === 'file.export.uploaded'), false)
  } finally { globalThis.fetch = original }
})

test('clock reversal during an export is terminal even when wall time catches up again', { skip: !supported }, async t => {
  const f = await fixture(t), manifest = await f.prepare(), original = globalThis.fetch
  let requests = 0
  globalThis.fetch = (async () => { requests++; throw new Error('unexpected PUT') }) as typeof fetch
  try {
    await waitFor(() => f.sent.some(row => row.type === 'file.export.renew'))
    const renewal = f.sent.find(row => row.type === 'file.export.renew'), expires = f.lease().expires_at
    f.state.wall += 1000; f.state.mono += 1000
    f.peer.handle({ ...renewal, type: 'file.export.renewed', payload: { leases: [{ operation_id: manifest.request_id,
      lease_until: f.lease().lease_until, expires_at: expires }] } })
    f.state.wall--
    assert.throws(() => f.peer.handle(f.upload(manifest)), /access.denied/)
    f.state.wall += 1000; f.state.mono += 1000
    assert.throws(() => f.peer.handle(f.upload(manifest)), /access.denied/)
    assert.equal(requests, 0)
  } finally { globalThis.fetch = original }
})

test('closing an export registry fences late metadata and prevents a new native listing', { skip: !supported }, async t => {
  const f = await fixture(t), entered = gate(), resume = gate(), roots = f.client.workspaceExportRootIds.bind(f.client)
  let calls = 0
  f.client.workspaceExportRootIds = async (...args) => { calls++; const result = await roots(...args); entered.release(); await resume.promise; return result }
  const request = f.frame('file.exports.list', f.lease())
  f.peer.handle(request)
  try {
    await entered.promise
    await f.registry.close()
    f.peer.handle(f.frame('file.exports.list', f.lease()))
    assert.equal(calls, 1)
    resume.release(); await f.peer.close()
    assert.equal(f.sent.some(row => row.request_id === request.request_id), false)
  } finally { resume.release() }
})

test('media verification and export requests share the same eight-job budget and request namespace', { skip: !supported }, async t => {
  const f = await fixture(t), resume = gate()
  f.client.attachmentExportSource = async () => { await resume.promise; throw new ProbeError('file.export_unavailable') }
  const media = (id = randomUUID()) => ({ ...f.frame('session.media.resolve', { ...f.lease(), allow_workspace: false, sync_id: randomUUID(),
    reference: { v: 1, origin: 'session_attachment', native_seq: 0, part_index: 0, event_version_sha256: '0'.repeat(64),
      manifest: { name: 'fixture.png', size_bytes: 1, sha256: '0'.repeat(64) }, media_type: 'image/png', width: 1, height: 1 } }, id), subscription_id: randomUUID() })
  try {
    const first = media(); f.peer.handle(first)
    assert.throws(() => f.peer.handle(f.frame('file.exports.list', f.lease(), first.request_id)), /rate.limited/)
    for (let index = 1; index < 8; index++) f.peer.handle(media())
    assert.throws(() => f.peer.handle(f.frame('file.exports.list', f.lease())), /rate.limited/)
  } finally { resume.release(); await f.peer.close() }
})

test('a live renewed native export still streams one anonymous PUT and commits its original operation', { skip: !supported }, async t => {
  const f = await fixture(t), manifest = await f.prepare(), original = globalThis.fetch
  let requests = 0, received = Buffer.alloc(0)
  globalThis.fetch = (async (_url, options) => {
    requests++
    const headers = new Headers(options?.headers)
    assert.equal(headers.has('authorization'), false); assert.equal(headers.has('cookie'), false)
    assert.equal(options?.method, 'PUT'); assert.equal(options?.redirect, 'error')
    assert.ok(options?.body instanceof ReadableStream)
    const chunks: Uint8Array[] = [], reader = options.body.getReader()
    try { for (;;) { const next = await reader.read(); if (next.done) break; chunks.push(next.value) } }
    finally { reader.releaseLock() }
    received = Buffer.concat(chunks)
    return new Response(null, { status: 200 })
  }) as typeof fetch
  try {
    await waitFor(() => f.sent.some(row => row.type === 'file.export.renew'))
    const renewal = f.sent.find(row => row.type === 'file.export.renew'), expires = f.lease().expires_at
    f.state.wall += 1000; f.state.mono += 1000
    f.peer.handle({ ...renewal, type: 'file.export.renewed', payload: { leases: [{ operation_id: manifest.request_id,
      lease_until: f.lease().lease_until, expires_at: expires }] } })
    const upload = f.upload(manifest)
    f.peer.handle(upload)
    await waitFor(() => f.sent.some(row => row.type === 'file.export.uploaded' && row.request_id === manifest.request_id))
    f.peer.handle(f.frame('file.export.committed', { file_id: upload.payload.file.file_id }, manifest.request_id))
    assert.equal(received.toString(), 'export lease fixture'); assert.equal(requests, 1)
    assert.equal(await f.descriptors(), 0)
  } finally { globalThis.fetch = original }
})

test('cancelled exports retain capacity and their request IDs until native work actually settles', { skip: !supported }, async t => {
  const f = await fixture(t), entered = gate(), resume = gate(), roots = f.client.workspaceExportRootIds.bind(f.client)
  let calls = 0
  f.client.workspaceExportRootIds = async (...args) => { if (++calls === 8) entered.release(); await resume.promise; return roots(...args) }
  const requests = Array.from({ length: 8 }, () => f.frame('file.exports.list', f.lease()))
  for (const request of requests) f.peer.handle(request)
  try {
    await entered.promise
    for (const request of requests) f.peer.handle(f.frame('file.export.abort', { code: 'access.denied' }, request.request_id))
    assert.throws(() => f.peer.handle(f.frame('file.exports.list', f.lease())), /rate.limited/)
    assert.throws(() => f.peer.handle(f.frame('file.exports.list', f.lease(), requests[0]!.request_id)), /rate.limited/)
    resume.release()
    const fresh = f.frame('file.exports.list', f.lease())
    await waitFor(() => {
      try { f.peer.handle(fresh); return true }
      catch (error) { if (error instanceof ProbeError && error.code === 'rate.limited') return false; throw error }
    })
    await waitFor(() => f.sent.some(row => row.type === 'file.exports.result' && row.request_id === fresh.request_id))
    assert.equal(f.sent.some(row => requests.some(request => request.request_id === row.request_id)), false)
  } finally { resume.release(); await f.peer.close() }
})
