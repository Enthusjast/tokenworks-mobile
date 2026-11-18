import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { link, mkdir, statfs, symlink, unlink, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { test } from 'node:test'
import Gateway from '@deepseek-ai/dsh-api-gateway'
import { SessionId } from '@deepseek-ai/dsh-session'
import type { PeerScope } from '@deepseek-ai/dsh-typert-protocol'
import { NativeAccess, type NativeLease } from '../src/native-access.js'
import { installWorkspaceExports } from '../src/workspace-exports.js'
import { host } from './harness.js'

const code = (name: string) => (error: unknown) => error instanceof Error && 'code' in error && error.code === name

test('registered workspace exports pin chunks and never elevate a scoped Peer', { timeout: 30_000 }, async t => {
  if (process.platform !== 'linux' || !(await statfs('/proc/self/fd').then(info => info.type === 0x9fa0, () => false))) {
    t.skip('kernel-relative workspace export requires Linux procfs'); return
  }
  const fixture = await host({ probe: false, filesystem: 'sandbox' })
  t.after(() => fixture.close())
  const { ctx, cwd, directory, runtimeId } = fixture
  await ctx.plugin(Gateway).await()
  installWorkspaceExports(ctx)
  const privateRoot = join(directory, 'private')
  const looseRoot = join(directory, 'unregistered')
  await Promise.all([mkdir(join(cwd, 'nested')), mkdir(privateRoot), mkdir(looseRoot)])
  const bytes = Buffer.from([0, 1, 255, 253, 6])
  const path = join(cwd, 'nested', 'report %.bin')
  await Promise.all([
    writeFile(path, bytes), writeFile(join(cwd, 'empty.bin'), ''), writeFile(join(privateRoot, 'secret'), 'private'),
    symlink(privateRoot, join(cwd, 'linked-directory')), symlink(join(privateRoot, 'secret'), join(cwd, 'linked-file')),
  ])
  await link(join(privateRoot, 'secret'), join(cwd, 'linked-hard'))
  const permitted = ctx.sessions.create(SessionId('permitted'), { meta: { cwd } }).id
  const forbidden = ctx.sessions.create(SessionId('forbidden'), { meta: { cwd: privateRoot } }).id
  const unregistered = ctx.sessions.create(SessionId('unregistered'), { meta: { cwd: looseRoot } }).id
  await (await ctx.workspaceRegistry.create(cwd)).attachSession(permitted)
  await (await ctx.workspaceRegistry.create(privateRoot)).attachSession(forbidden)
  const installationId = randomUUID()
  const leases = new Map<string, NativeLease>()
  const access = new NativeAccess(ctx.connection, ctx.remoteAccess, runtimeId, installationId,
    async id => ({ ok: true, data: leases.get(id) }), () => ({ sessions: [permitted, forbidden, unregistered], workspaces: [] }))
  t.after(() => access.close())
  async function admit(actions: string[], sessionId = permitted) {
    const id = randomUUID()
    const bindings: NativeLease['bindings'] = [{ resource: { kind: 'session', id: sessionId }, actions }]
    leases.set(id, { active: true, sub: randomUUID(), aud: runtimeId, exp: Math.floor(Date.now() / 1000) + 300,
      client_id: 'test', device_id: randomUUID(), runtime_id: runtimeId, installation_id: installationId,
      organization_id: randomUUID(), grant_version: 1, role: 'owner', is_installation_owner: true, bindings })
    const admission = await access.admitFresh(id, 'A'.repeat(43), () => { throw new Error('scoped Peer reached owner fallback') })
    assert.equal(admission.peer.principal.kind, 'tokenworks-device')
    assert.equal(admission.ownerEligible, false)
    t.after(() => admission.release())
    return admission.peer
  }
  const reader = await admit(['workspaceExports/list', 'workspaceExports/read'])
  const browser = await admit(['workspaceExports/list'])
  const unregisteredBrowser = await admit(['workspaceExports/list'], unregistered)
  const gateway = ctx.typertGateway
  function call(peer: PeerScope, method: string, args: Record<string, unknown>) {
    return gateway.invoke({ namespace: 'workspaceExports', method, args, peer })
  }
  type Listing = { entries: { name: string; kind: string; size?: number; version?: string }[]; truncated: boolean }
  const root = await call(browser, 'list', { sessionId: permitted, parts: [] }) as Listing
  assert.deepEqual(root.entries.map(({ name, kind }) => ({ name, kind })), [{ name: 'nested', kind: 'directory' }, { name: 'empty.bin', kind: 'file' }])
  assert.equal(root.truncated, false)
  const rootFile = root.entries[1]!
  assert.deepEqual(await call(reader, 'read', { sessionId: permitted, request: { parts: [rootFile.name], version: rootFile.version, size: 0, offset: 0, length: 1 } }),
    { offset: 0, data: '', eof: true })
  const directoryList = await call(reader, 'list', { sessionId: permitted, parts: ['nested'] }) as Listing
  const file = directoryList.entries[0]!
  assert.equal(file.size, bytes.length)
  const request = { parts: ['nested', file.name], size: file.size, version: file.version, offset: 0, length: 3 }
  assert.deepEqual(await call(reader, 'read', { sessionId: permitted, request }), { offset: 0, data: bytes.subarray(0, 3).toString('base64'), eof: false })
  assert.deepEqual(await call(reader, 'read', { sessionId: permitted, request: { ...request, offset: 3 } }),
    { offset: 3, data: bytes.subarray(3).toString('base64'), eof: true })
  await assert.rejects(call(browser, 'read', { sessionId: permitted, request }), code('remote-access/denied'))
  await assert.rejects(call(reader, 'list', { sessionId: forbidden, parts: [] }), code('remote-access/denied'))
  await assert.rejects(call(reader, 'read', { sessionId: forbidden, request }), code('remote-access/denied'))
  await assert.rejects(call(unregisteredBrowser, 'list', { sessionId: unregistered, parts: [] }), code('gateway/lookup-not-found'))
  await assert.rejects(call(reader, 'list', { sessionId: permitted, parts: ['linked-directory'] }), code('workspace-export/unavailable'))
  await assert.rejects(call(reader, 'read', { sessionId: permitted, request: { ...request, parts: ['linked-file'] } }), code('workspace-export/source-changed'))
  await assert.rejects(call(reader, 'read', { sessionId: permitted, request: { ...request, parts: ['linked-hard'] } }), code('workspace-export/source-changed'))
  await assert.rejects(call(reader, 'list', { sessionId: permitted, parts: ['..'] }), code('gateway/input-invalid'))
  await assert.rejects(call(reader, 'read', { sessionId: permitted, request: { ...request, length: 256 * 1024 + 1 } }), code('gateway/input-invalid'))
  const secondLink = join(privateRoot, 'linked-report')
  await link(path, secondLink)
  assert.deepEqual(await call(reader, 'list', { sessionId: permitted, parts: ['nested'] }), { entries: [], truncated: false })
  await assert.rejects(call(reader, 'read', { sessionId: permitted, request }), code('workspace-export/source-changed'))
  await unlink(secondLink)
  await writeFile(path, Buffer.from([1, 2, 3, 4, 5]))
  await assert.rejects(call(reader, 'read', { sessionId: permitted, request }), code('workspace-export/source-changed'))
  const fetchHandler = ctx.connection.createSharedFetchHandler('/api')
  const rpcId = randomUUID()
  const response = await fetchHandler.fetch(new Request('http://localhost/api/workspaceExports/list', { method: 'POST',
    headers: { 'content-type': 'application/json' }, body: JSON.stringify({ type: 'client-request', rpcId, method: 'workspaceExports/list',
      payload: { args: { sessionId: permitted, parts: [] } } }) }), browser)
  assert.match(response.headers.get('content-type') ?? '', /^application\/json/)
  assert.deepEqual(await response.json(), { type: 'server-response', rpcId, result: { ok: true, value: root } })
})
