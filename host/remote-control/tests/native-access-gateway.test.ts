import assert from 'node:assert/strict'
import { test } from 'node:test'
import { randomUUID } from 'node:crypto'
import { setImmediate as nextTurn } from 'node:timers/promises'
import Gateway from '@deepseek-ai/dsh-api-gateway'
import * as ApiRemotes from '@deepseek-ai/dsh-api-remotes'
import WorkspaceFiles from '@deepseek-ai/dsh-api-workspace-files'
import Terminal from '@deepseek-ai/dsh-api-terminal-controller'
import Subprocess from '@deepseek-ai/dsh-subprocess-local'
import { TYPERT as sessionHost } from '@deepseek-ai/dsh-api-session-controller/typert'
import { TYPERT as terminalHost } from '@deepseek-ai/dsh-api-terminal-controller/typert'
import { TYPERT as filesHost } from '@deepseek-ai/dsh-api-workspace-files/typert'
import { TYPERT as uploadHost } from '@deepseek-ai/dsh-client-file-upload/typert'
import type { TypertContribution } from '@deepseek-ai/dsh-typert-registry'
import type { PeerScope } from '@deepseek-ai/dsh-typert-protocol'
import { SessionId } from '@deepseek-ai/dsh-session'
import { NativeAccess, type NativeLease } from '../src/native-access.js'
import { PRODUCT_OWNER_BINDINGS } from '../src/product-access.generated.js'
import { host } from './harness.js'

const INSTALLATION = 'bbbbbbbb-2222-4222-8222-222222222222'
const SECRET = 'A'.repeat(43)
const emptyUplink = async function* () {}

test('real native Gateway enforces viewer/operator resources and owner-only waterfall answers in owner mode', { timeout: 30_000 }, async t => {
  const f = await host({ probe: false, filesystem: 'sandbox' })
  t.after(() => f.close())
  await f.ctx.plugin(Gateway).await()
  await f.ctx.plugin(WorkspaceFiles).await()
  await f.ctx.plugin(Subprocess).await()
  await f.ctx.plugin(Terminal).await()
  await f.ctx.plugin(ApiRemotes).await()
  for (const contribution of [sessionHost, terminalHost, filesHost, uploadHost]) f.ctx.typert.register(contribution as TypertContribution)
  const leases = new Map<string, NativeLease>()
  const access = new NativeAccess(f.ctx.connection, f.ctx.remoteAccess, f.runtimeId, INSTALLATION,
    async id => ({ ok: true, data: leases.get(id) }), () => ({ sessions: ['permitted', 'forbidden'], workspaces: [] }), 'owner')
  t.after(() => access.close())
  async function admit(role: NativeLease['role'], actualOwner: boolean, bindings: NativeLease['bindings']) {
    const id = randomUUID()
    leases.set(id, { active: true, sub: randomUUID(), aud: f.runtimeId, exp: Math.floor(Date.now() / 1000) + 300,
      client_id: 'test', device_id: randomUUID(), runtime_id: f.runtimeId, installation_id: INSTALLATION,
      organization_id: 'ffffffff-6666-4666-8666-666666666666',
      grant_version: 1, role, is_installation_owner: actualOwner, bindings })
    return (await access.admitFresh(id, SECRET)).peer
  }
  const scopedActions = ['session/selectModel', 'fileReferences/list', 'fileUploads/upload', 'terminal/list', 'terminal/attach', 'event:api-session/status']
  const viewer = await admit('viewer', false, [{ resource: { kind: 'session', id: 'permitted' }, actions: ['session/follow', 'event:api-session/status'] }])
  const operator = await admit('operator', true, [{ resource: { kind: 'session', id: 'permitted' }, actions: [...scopedActions, 'session/prompt'] }])
  const ownerRoleNotOwner = await admit('owner', false, [{ resource: { kind: 'session', id: 'permitted' }, actions: scopedActions }])
  const owner = await admit('owner', true, structuredClone(PRODUCT_OWNER_BINDINGS) as NativeLease['bindings'])
  assert.equal(viewer.principal.kind, 'tokenworks-device')
  assert.equal(operator.principal.kind, 'tokenworks-device')
  assert.equal(ownerRoleNotOwner.principal.kind, 'tokenworks-device')
  assert.equal(owner.principal.kind, 'owner')
  const gateway = f.ctx.typertGateway
  const denied = (error: unknown) => typeof error === 'object' && error !== null && 'code' in error && error.code === 'remote-access/denied'
  for (const peer of [viewer, operator, ownerRoleNotOwner]) {
    for (const [namespace, method, args] of [
      ['session', 'prompt', { request: { requestId: randomUUID(), sessionId: 'forbidden', mode: 'queue', content: [{ type: 'text', text: 'unauthorized' }] } }],
      ['session', 'selectModel', { request: { sessionId: 'forbidden', provider: 'fixture', model: 'fixture' } }],
      ['fileReferences', 'list', { agentId: 'forbidden', query: '' }],
      ['fileUploads', 'upload', { agentId: 'forbidden', request: { data: 'c2VjcmV0', name: 'unauthorized.txt' } }],
      ['workspaceFiles', 'read', { workspaceFileScopeId: 'forbidden', path: 'secret.txt', range: {} }],
      ['workspaceFiles', 'list', { workspaceFileScopeId: 'forbidden', path: '.' }],
      ['terminal', 'list', { sessionId: 'forbidden' }],
    ] as const) await assert.rejects(gateway.invoke({ namespace, method, args, peer }), denied, `${peer.principal.kind}: ${namespace}/${method}`)
    await assert.rejects(gateway.stream({ namespace: 'terminal', method: 'attach', args: { agentId: 'forbidden', id: 'terminal-id' }, peer }), denied)
  }
  // The same real endpoint succeeds on its allowed resource; no UI gate is involved.
  assert.deepEqual(await gateway.invoke({ namespace: 'terminal', method: 'list', args: { sessionId: 'permitted' }, peer: operator }), [])
  await assert.rejects(gateway.invoke({ namespace: 'session', method: 'selectModel', args: { request: { sessionId: 'permitted', provider: 'fixture', model: 'fixture' } }, peer: viewer }), denied)
  await assert.rejects(gateway.invoke({ namespace: 'session', method: 'prompt', args: { request: { requestId: randomUUID(), sessionId: 'permitted', mode: 'queue', content: [{ type: 'text', text: 'viewer cannot prompt' }] } }, peer: viewer }), denied)

  const handle = await f.ctx.agents.create({ sessionId: SessionId('permitted'), meta: { cwd: f.cwd }, agentOptions: { provider: 'fixture', model: 'fixture' } })
  t.after(() => handle.dispose())
  await gateway.invoke({ namespace: 'session', method: 'selectModel', args: { request: { sessionId: 'permitted', provider: 'fixture', model: 'fixture' } }, peer: operator })
  const held = f.model.holdNext()
  t.after(() => held.release())
  await gateway.invoke({ namespace: 'session', method: 'prompt', args: { request: { requestId: randomUUID(), sessionId: 'permitted', mode: 'queue', content: [{ type: 'text', text: 'authorized native prompt' }] } }, peer: operator })
  await held.started
  const streams: { peer: PeerScope; iterator: AsyncIterator<unknown>; clientId: string; control: AbortController }[] = []
  for (const peer of [viewer, operator, ownerRoleNotOwner, owner]) {
    const control = new AbortController()
    const source = await gateway.wireStream.open('$events', { args: {} }, emptyUplink(), peer, control.signal)
    const iterator = source[Symbol.asyncIterator]()
    const ready = (await iterator.next()).value as { type: string; clientId: string }
    assert.equal(ready.type, 'ready')
    streams.push({ peer, iterator, clientId: ready.clientId, control })
  }
  t.after(async () => { for (const stream of streams) stream.control.abort(); await Promise.all(streams.map(stream => stream.iterator.return?.())) })
  assert.equal(gateway.hasLiveClient(), true)
  const approval = f.ctx.approval.request({ agent: handle.agent, toolName: 'native-access-proof', reason: 'owner answer required' })
  const waterfall = (await streams.at(-1)!.iterator.next()).value as { type: string; event: string; eventId: string }
  assert.equal(waterfall.type, 'waterfall')
  assert.equal(waterfall.event, 'approval/request')
  const fetchHandler = f.ctx.connection.createSharedFetchHandler('/api')
  async function answer(peer: PeerScope, clientId: string, eventId: string, value: unknown) {
    const response = await fetchHandler.fetch(new Request('http://localhost/api/$events/result', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({
      type: 'client-request', rpcId: randomUUID(), method: '$events/result', payload: { args: { clientId, eventId, outcome: { kind: 'result', value } } },
    }) }), peer)
    return (await response.json()) as { result: { ok: boolean; error?: { code: string } } }
  }
  for (const stream of streams.slice(0, -1)) {
    const reply = await answer(stream.peer, streams.at(-1)!.clientId, waterfall.eventId, 'allowed-once')
    assert.equal(reply.result.ok, false)
    assert.equal(reply.result.error?.code, 'gateway/invocation-unavailable')
  }
  // The actual native source sends a session-scoped broadcast. Restricted peers
  // receive it as emit, never the preceding approval waterfall.
  f.ctx.emit('api-session/status', SessionId('permitted'), false)
  for (const stream of streams.slice(0, -1)) {
    const frame = (await stream.iterator.next()).value as { type: string; event: string }
    assert.equal(frame.type, 'emit')
    assert.equal(frame.event, 'api-session/status')
  }
  assert.equal((await answer(owner, streams.at(-1)!.clientId, waterfall.eventId, 'allowed-once')).result.ok, true)
  assert.equal(await approval, 'allowed-once')
  // Drain the owner's copy of the same broadcast before the next waterfall.
  const ownerBroadcast = (await streams.at(-1)!.iterator.next()).value as { type: string }
  assert.equal(ownerBroadcast.type, 'emit')
  const question = f.ctx.userQuestions.ask({ agent: handle.agent, questions: [{ id: 'native-question', question: 'Owner only?', header: 'Authorization' }] })
  const questionFrame = (await streams.at(-1)!.iterator.next()).value as { type: string; event: string; eventId: string }
  assert.equal(questionFrame.type, 'waterfall')
  assert.equal(questionFrame.event, 'user-questions/request')
  const questionAnswer = { answers: [{ id: 'native-question', selected: [] }] }
  for (const stream of streams.slice(0, -1)) assert.equal((await answer(stream.peer, streams.at(-1)!.clientId, questionFrame.eventId, questionAnswer)).result.ok, false)
  assert.equal((await answer(owner, streams.at(-1)!.clientId, questionFrame.eventId, questionAnswer)).result.ok, true)
  assert.deepEqual(await question, questionAnswer)
  streams.at(-1)!.control.abort()
  await nextTurn()
  assert.equal(gateway.hasLiveClient(), false)
  held.release()
  await handle.agent.whenIdle()
})

test('fresh native authority fences real Gateway policy generations on grant drift, revoke and concurrent stale admission', { timeout: 30_000 }, async t => {
  const f = await host({ probe: false, filesystem: 'sandbox' })
  t.after(() => f.close())
  await f.ctx.plugin(Gateway).await()
  await f.ctx.plugin(Subprocess).await()
  await f.ctx.plugin(Terminal).await()
  f.ctx.typert.register(terminalHost as TypertContribution)
  const facts = new Map<string, NativeLease>(), delayed = new Map<string, Promise<unknown>>()
  // Test-only repository facts; production NativeAccess and installed native
  // Gateway enforce the result. No PostgreSQL state is simulated as proof.
  const access = new NativeAccess(f.ctx.connection, f.ctx.remoteAccess, f.runtimeId, INSTALLATION, async id => {
    const pending = delayed.get(id)
    if (pending) { delayed.delete(id); return pending }
    return { ok: true, data: facts.get(id) }
  }, () => ({ sessions: ['permitted'], workspaces: [] }))
  t.after(() => access.close())
  const disposed = (error: unknown) => typeof error === 'object' && error !== null && 'code' in error && error.code === 'gateway/peer-disposed'
  for (const change of ['role', 'bindings', 'device', 'organization', 'version', 'revoke', 'concurrent'] as const) {
    const id = randomUUID(), initial: NativeLease = { active: true, sub: randomUUID(), aud: f.runtimeId,
      exp: Math.floor(Date.now() / 1000) + 300, client_id: 'fixture', device_id: randomUUID(), runtime_id: f.runtimeId,
      installation_id: INSTALLATION, grant_version: 1, role: 'operator', is_installation_owner: false,
      organization_id: 'ffffffff-6666-4666-8666-666666666666',
      bindings: [{ resource: { kind: 'session', id: 'permitted' }, actions: ['terminal/list'] }] }
    facts.set(id, initial)
    const request = await access.admitFresh(id, SECRET), pendingPc = await access.admitFresh(id, SECRET)
    request.release()
    assert.equal(pendingPc.signal.aborted, false, 'normal signaling response must not release the PC pin')
    const native = await access.admitFresh(id, SECRET)
    pendingPc.release()
    assert.equal(native.signal.aborted, false)
    assert.deepEqual(await f.ctx.typertGateway.invoke({ namespace: 'terminal', method: 'list', args: { sessionId: 'permitted' }, peer: native.peer }), [])
    const stale = Promise.withResolvers<unknown>()
    let refused: Promise<void> | undefined
    if (change === 'concurrent') {
      delayed.set(id, stale.promise)
      refused = assert.rejects(access.admitFresh(id, SECRET))
    }
    if (change === 'role') facts.set(id, { ...initial, role: 'owner', is_installation_owner: true })
    else if (change === 'bindings') facts.set(id, { ...initial, bindings: structuredClone(PRODUCT_OWNER_BINDINGS) as NativeLease['bindings'] })
    else if (change === 'device') facts.set(id, { ...initial, device_id: randomUUID() })
    else if (change === 'organization') facts.set(id, { ...initial, organization_id: randomUUID() })
    else if (change === 'version') facts.set(id, { ...initial, grant_version: 2 })
    else facts.delete(id)
    await assert.rejects(access.admitFresh(id, SECRET), change)
    assert.equal(native.signal.aborted, true, change)
    assert.equal(native.peer.signal.aborted, true, change)
    if (change === 'concurrent') { stale.resolve({ ok: true, data: initial }); await refused }
    await assert.rejects(f.ctx.typertGateway.invoke({ namespace: 'terminal', method: 'list', args: { sessionId: 'permitted' }, peer: native.peer }), disposed, change)
    native.release()
  }
})
