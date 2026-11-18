import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { lstat, statfs, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { test } from 'node:test'
import Gateway from '@deepseek-ai/dsh-api-gateway'
import { SessionId } from '@deepseek-ai/dsh-session'
import type {} from '@deepseek-ai/dsh-session-persistence'
import type { PeerScope } from '@deepseek-ai/dsh-typert-protocol'
import { PRODUCT_NATIVE_VERSION } from '../src/product-access.generated.js'
import '../src/native-access.js'
import { sessionDeletionPlugin } from '../src/session-deletion.js'
import { host } from './harness.js'

test('only a configured native owner can permanently delete an archived, cold history', { timeout: 30_000 }, async t => {
  if (process.platform !== 'linux' || await statfs('/proc/self/fd').then(info => info.type !== 0x9fa0, () => true)) {
    t.skip('managed session deletion requires Linux procfs'); return
  }
  const fixture = await host({ probe: false })
  t.after(() => fixture.close())
  const { ctx, cwd, directory } = fixture
  await ctx.plugin(Gateway).await()
  const owner = ctx.connection.createPeer({ principal: { kind: 'owner' } })
  const device = ctx.connection.createPeer({ principal: { kind: 'tokenworks-device', leaseId: randomUUID(), generation: 1 } })
  t.after(() => Promise.all([owner.dispose(), device.dispose()]))

  async function store(id: SessionId) {
    const pending = ctx.sessions.prepare(id, { meta: { cwd } })
    const handle = await ctx.sessionPersistence.create(pending.header)
    try { await handle.flush() } finally { await handle.close() }
    const stored = await ctx.sessionPersistence.stat(id)
    assert.equal(stored?.header.id, id)
    return stored!.location!.path
  }
  const archived = SessionId(`session-${randomUUID()}`)
  const other = SessionId(`session-${randomUUID()}`)
  const path = await store(archived)
  const untouched = await store(other)
  const workspaceFile = join(cwd, 'keep.txt')
  await writeFile(workspaceFile, 'The workspace is not deleted.')
  await ctx.workspaceRegistry.archiveSession(archived)

  async function request(peer: PeerScope, method: string, args: Record<string, unknown>) {
    const rpcId = randomUUID()
    const endpoint = `sessionDeletion/${method}`
    const response = await ctx.connection.createSharedFetchHandler('/api').fetch(new Request(`http://localhost/api/${endpoint}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ type: 'client-request', rpcId, method: endpoint, payload: { args } }),
    }), peer)
    return { rpcId, response }
  }
  async function call(peer: PeerScope, method: string, args: Record<string, unknown> = { sessionId: archived }) {
    const { rpcId, response } = await request(peer, method, args)
    assert.equal(response.status, 200)
    const envelope = await response.json() as { type: string; rpcId: string; result: { ok: boolean; value?: unknown; error?: { code: string } } }
    assert.equal(envelope.type, 'server-response')
    assert.equal(envelope.rpcId, rpcId)
    return envelope.result
  }

  assert.equal((await request(owner, 'delete', { sessionId: archived })).response.status, 404, 'no operator opt-in')
  assert.equal((await lstat(path)).isFile(), true)
  await ctx.plugin(sessionDeletionPlugin(join(directory, 'sessions'), PRODUCT_NATIVE_VERSION)).await()
  assert.deepEqual(await call(owner, 'status'), { ok: true, value: { available: true } })
  assert.deepEqual(await call(owner, 'status', { sessionId: other }), { ok: true, value: { available: false } })
  assert.equal((await call(device, 'delete')).error?.code, 'remote-access/denied')
  assert.equal((await call(owner, 'delete', { sessionId: archived, path: untouched })).ok, false, 'caller cannot select disk paths')
  const live = ctx.sessions.enter(ctx.sessions.prepare(archived, { meta: { cwd } }))
  try {
    assert.deepEqual(await call(owner, 'status'), { ok: true, value: { available: false } })
    assert.equal((await call(owner, 'delete')).error?.code, 'session-deletion/unavailable')
  } finally { live() }
  const writer = await ctx.sessionPersistence.open(archived, 'write')
  try {
    assert.equal((await call(owner, 'delete')).error?.code, 'session-deletion/unavailable')
    assert.equal((await lstat(path)).isFile(), true, 'exclusive native writer must prevent deletion')
  } finally { await writer.close() }
  assert.deepEqual(await call(owner, 'delete'), { ok: true, value: { deleted: true } })
  assert.equal(await ctx.sessionPersistence.stat(archived), undefined)
  await assert.rejects(lstat(dirname(path)), { code: 'ENOENT' })
  assert.equal((await lstat(untouched)).isFile(), true)
  assert.equal((await lstat(workspaceFile)).isFile(), true)
  assert.deepEqual(await call(owner, 'status'), { ok: true, value: { available: false } })
})
