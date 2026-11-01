import test, { type TestContext } from 'node:test'
import assert from 'node:assert/strict'
import { createHash, randomUUID } from 'node:crypto'
import { host } from './harness.js'
import { DshClient } from '../src/dsh-client.js'
import { NativeFileRegistry } from '../src/file-registry.js'
import type { FileOffer } from '../src/file-protocol.js'
import type { FileUploadValue } from '@deepseek-ai/dsh-client-file-upload'

function gate() {
  let release!: () => void
  const promise = new Promise<void>(resolve => { release = resolve })
  return { promise, release }
}
async function fixture(t: TestContext) {
  const h = await host({ probe: false })
  const client = new DshClient(h.ctx, h.runtimeId, [{ id: 'fixture', cwd: h.cwd }], ['fixture'])
  const session = (await client.create('fixture', 'fixture')).session_id
  const lifetime = new AbortController(), registry = new NativeFileRegistry(client, randomUUID())
  const owner = { userId: randomUUID(), deviceId: randomUUID() }, bytes = Buffer.from('registry lifecycle bytes')
  const offer = (): FileOffer => ({ file_id: randomUUID(), handoff_id: randomUUID(), user_id: owner.userId, device_id: owner.deviceId,
    key_version: 1, grant_version: 1, name: 'lifecycle.txt', size_bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex'), download_token: '',
    lease_until: new Date(Date.now() + 5000).toISOString(), expires_at: new Date(Date.now() + 60_000).toISOString() })
  const source = async function* () { yield bytes }
  t.after(async () => { registry.close(); lifetime.abort(); await h.close() })
  return { client, session, lifetime, registry, owner, bytes, offer, source }
}

test('closing a registry aborts pending work and retires a native receipt returned after close', async t => {
  const f = await fixture(t), entered = gate(), resume = gate(), file = f.offer()
  const upload = f.client.uploadFile.bind(f.client), native = await f.client.resolve(f.session, f.lifetime.signal)
  let result: FileUploadValue | undefined, observedSignal: AbortSignal | undefined, calls = 0
  f.client.uploadFile = async (...args) => {
    calls++; observedSignal = args[3]; result = await upload(...args)
    entered.release(); await resume.promise // Deliberately ignore cancellation after native storage.
    return result
  }
  const pending = f.registry.stage(f.session, file, f.source(), f.lifetime.signal, () => true)
  const denied = assert.rejects(pending, /file.stale/)
  try {
    await entered.promise
    assert.equal(f.client.fileStaged(native, result!.receiptId), true)
    f.registry.close(); f.registry.close()
    assert.equal(observedSignal!.aborted, true)
    assert.equal(f.registry.confirm(file.file_id, file.handoff_id), false)
    resume.release(); await denied
    assert.equal(f.client.fileStaged(native, result!.receiptId), false)
    await assert.rejects(f.registry.stage(f.session, f.offer(), f.source(), f.lifetime.signal, () => true), /file.stale/)
    assert.equal(calls, 1)
  } finally { resume.release(); await Promise.allSettled([pending]) }
})

test('a superseded native upload cannot replace or clean up a newer receipt for the same file', async t => {
  const f = await fixture(t), entered = gate(), resume = gate(), first = f.offer(), next = { ...f.offer(), file_id: first.file_id }
  const upload = f.client.uploadFile.bind(f.client), native = await f.client.resolve(f.session, f.lifetime.signal)
  let old: FileUploadValue | undefined, calls = 0
  f.client.uploadFile = async (...args) => {
    const result = await upload(...args)
    if (++calls === 1) { old = result; entered.release(); await resume.promise }
    return result
  }
  const pending = f.registry.stage(f.session, first, f.source(), f.lifetime.signal, () => true)
  const denied = assert.rejects(pending, /file.stale/)
  try {
    await entered.promise
    await f.registry.stage(f.session, next, f.source(), f.lifetime.signal, () => true)
    assert.equal(f.registry.confirm(next.file_id, first.handoff_id), false)
    f.registry.drop(next.file_id, first.handoff_id)
    assert.equal(f.registry.confirm(next.file_id, next.handoff_id), true)
    const current = f.registry.resolve(next.file_id, f.session, f.owner)
    resume.release(); await denied
    assert.equal(f.client.fileStaged(native, old!.receiptId), false)
    assert.deepEqual(f.registry.resolve(next.file_id, f.session, f.owner), current)
    if (current.type !== 'file') assert.fail('missing file part')
    assert.equal(f.client.fileStaged(native, current.receiptId), true)
  } finally { resume.release(); await Promise.allSettled([pending]) }
})

test('the 32-entry bound includes pending and superseded operations until they actually settle', async t => {
  const f = await fixture(t), resume = gate(), native = await f.client.resolve(f.session, f.lifetime.signal)
  const resolve = f.client.resolve.bind(f.client), upload = f.client.uploadFile.bind(f.client)
  let resolving = 0, uploading = 0, read = 0
  f.client.resolve = async () => { resolving++; await resume.promise; return native }
  f.client.uploadFile = async (...args) => { uploading++; return upload(...args) }
  const files = Array.from({ length: 32 }, () => f.offer())
  const source = async function* () { read++; yield f.bytes }
  const tasks = files.map(file => f.registry.stage(f.session, file, source(), f.lifetime.signal, () => true))
  const outcomes = Promise.allSettled(tasks)
  try {
    assert.equal(resolving, 32)
    await assert.rejects(f.registry.stage(f.session, f.offer(), source(), f.lifetime.signal, () => true), /file.staging_limit/)
    for (const file of files) f.registry.drop(file.file_id, file.handoff_id)
    await assert.rejects(f.registry.stage(f.session, f.offer(), source(), f.lifetime.signal, () => true), /file.staging_limit/)
    assert.equal(uploading, 0); assert.equal(read, 0)
    resume.release()
    assert.equal((await outcomes).every(result => result.status === 'rejected'), true)
    f.client.resolve = resolve
    const next = f.offer()
    await f.registry.stage(f.session, next, f.source(), f.lifetime.signal, () => true)
    assert.equal(f.registry.confirm(next.file_id, next.handoff_id), true)
    assert.equal(f.registry.available(next.file_id), true)
    assert.equal(uploading, 1)
  } finally { resume.release(); await outcomes }
})

test('caller abort retires receipts immediately and failed authority checks prune stale entries', async t => {
  const f = await fixture(t), caller = new AbortController(), first = f.offer()
  const native = await f.client.resolve(f.session, f.lifetime.signal)
  await f.registry.stage(f.session, first, f.source(), caller.signal, () => true)
  f.registry.confirm(first.file_id, first.handoff_id)
  const receipt = f.registry.resolve(first.file_id, f.session, f.owner)
  if (receipt.type !== 'file') assert.fail('missing file part')
  caller.abort()
  assert.equal(f.client.fileStaged(native, receipt.receiptId), false)
  let active = true
  const next = f.offer()
  await f.registry.stage(f.session, next, f.source(), f.lifetime.signal, () => active)
  f.registry.confirm(next.file_id, next.handoff_id)
  const second = f.registry.resolve(next.file_id, f.session, f.owner)
  if (second.type !== 'file') assert.fail('missing file part')
  active = false
  assert.equal(f.registry.available(next.file_id), false)
  assert.equal(f.client.fileStaged(native, second.receiptId), false)
})

test('metadata is captured before asynchronous staging and cannot redirect receipt ownership', async t => {
  const f = await fixture(t), entered = gate(), resume = gate(), input = f.offer(), original = { ...input }
  const upload = f.client.uploadFile.bind(f.client)
  f.client.uploadFile = async (...args) => { const value = await upload(...args); entered.release(); await resume.promise; return value }
  const pending = f.registry.stage(f.session, input, f.source(), f.lifetime.signal, () => true)
  try {
    await entered.promise
    input.file_id = randomUUID(); input.user_id = randomUUID(); input.device_id = randomUUID(); input.handoff_id = randomUUID(); input.name = 'changed.txt'
    resume.release(); await pending
    assert.equal(f.registry.confirm(original.file_id, original.handoff_id), true)
    assert.equal(f.registry.confirm(input.file_id, input.handoff_id), false)
    assert.equal(f.registry.resolve(original.file_id, f.session, f.owner).type, 'file')
    assert.throws(() => f.registry.resolve(original.file_id, f.session, { userId: input.user_id, deviceId: input.device_id }), /file.staging_required/)
  } finally { resume.release(); await Promise.allSettled([pending]) }
})
