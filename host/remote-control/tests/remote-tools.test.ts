import assert from 'node:assert/strict'
import test, { type TestContext } from 'node:test'
import { createCipheriv, createDecipheriv, createHash, randomBytes, randomUUID } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { once } from 'node:events'
import { Duplex } from 'node:stream'
import Gateway from '@deepseek-ai/dsh-api-gateway'
import * as ApiRemotes from '@deepseek-ai/dsh-api-remotes'
import { Peer } from '@deepseek-ai/dsh-client-connection'
import { SessionId } from '@deepseek-ai/dsh-session'
import type { ToolCallId } from '@deepseek-ai/dsh-llm'
import type { ToolExecutionResult } from '@deepseek-ai/dsh-tools'
import type { PeerScope } from '@deepseek-ai/dsh-typert-protocol'
import type { TypertContribution } from '@deepseek-ai/dsh-typert-registry'
import { TYPERT as filesHost } from '@deepseek-ai/dsh-api-workspace-files/typert'
import { createIsolatedNativeAssembly } from '../../../tools/capture-native-golden.js'
import { host } from './harness.js'
import { LanPairing } from '../src/lan-pairing.js'
import { LanPairingStore } from '../src/lan-pairing-store.js'
import { LanGateway } from '../src/lan-gateway.js'
import { NativeGateway } from '../src/native-gateway.js'
import { NoiseCarrier } from '../src/noise-carrier.js'
import { installRemoteTools } from '../src/remote-tools.js'
import { RemoteOperationLedger } from '../src/remote-operation-ledger.js'
import { pinnedRequest } from '../src/remote-network.js'
import { correlatePrompt } from '../src/remote-wait.js'
import { REMOTE_ACTIONS } from '../src/remote-targets.js'
import type { JournalKeyProtector } from '../src/journal-key-protection.js'

function protector(): JournalKeyProtector {
  const master = randomBytes(32)
  return { id: `remote-proof-${randomUUID()}`, async wrap(bytes, context) {
    const iv = randomBytes(12), cipher = createCipheriv('aes-256-gcm', master, iv); cipher.setAAD(context)
    return Buffer.concat([iv, cipher.update(bytes), cipher.final(), cipher.getAuthTag()])
  }, async unwrap(bytes, context) {
    const value = Buffer.from(bytes), cipher = createDecipheriv('aes-256-gcm', master, value.subarray(0, 12)); cipher.setAAD(context); cipher.setAuthTag(value.subarray(-16))
    return Buffer.concat([cipher.update(value.subarray(12, -16)), cipher.final()])
  } }
}
async function target(t: TestContext) {
  const directory = await mkdtemp(join(tmpdir(), 'remote-tools-target-')), native = await createIsolatedNativeAssembly(directory, [{ id: 'workspace-files', name: '@deepseek-ai/dsh-api-workspace-files' }])
  // The installed descriptor export is typed unknown; it is the same native contribution
  // registered by the production package, not caller-controlled protocol data.
  native.ctx.typert.register(filesHost as TypertContribution)
  const certPath = join(directory, 'cert.pem'), keyPath = join(directory, 'key.pem')
  execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', keyPath, '-out', certPath, '-days', '1', '-subj', '/CN=localhost', '-addext', 'subjectAltName=IP:127.0.0.1,DNS:localhost'], { stdio: 'ignore' })
  const cert = await readFile(certPath, 'utf8'), key = await readFile(keyPath, 'utf8'), reserve = createServer()
  reserve.listen(0, '127.0.0.1'); await once(reserve, 'listening'); const address = reserve.address(); assert.ok(address && typeof address === 'object')
  const ended = once(reserve, 'close'); reserve.close(); await ended
  const runtimeId = randomUUID(), installationId = randomUUID(), origin = `https://127.0.0.1:${address.port}`
  const pairing = await LanPairing.open({ origin, runtimeId, installationId }, cert, new LanPairingStore(join(directory, 'pairing'), runtimeId, installationId, protector()), native.ctx.connection)
  let lose: string | undefined, dispatches = 0
  class ObservedNativeGateway extends NativeGateway {
    override accept(socket: Duplex, peer: PeerScope): void {
      dispatches++
      if (!lose) { super.accept(socket, peer); return }
      let prefix = '', drop = false, classified = false
      const bridge = new Duplex({ read() {}, write(bytes, _encoding, callback) { if (drop) { socket.destroy(); callback(new Error('fixture/lost-response')) } else socket.write(bytes, callback) }, destroy(error, callback) { socket.destroy(); callback(error) } })
      bridge.on('error', () => {}); socket.on('data', data => {
        if (!classified) { prefix += data.toString('latin1'); assert.ok(prefix.length <= 32768); if (prefix.includes('\r\n')) { classified = true; drop = prefix.startsWith(`POST /api/${lose}`) } }
        bridge.push(data)
      }); socket.on('end', () => bridge.push(null)); socket.on('close', () => bridge.destroy())
      super.accept(bridge, peer)
    }
  }
  const gateway = new ObservedNativeGateway(native.ctx.connection, native.ctx.typertGateway), carrier = new NoiseCarrier(pairing, gateway, '0.2.1-rc.5', origin)
  const lan = new LanGateway({ host: '127.0.0.1', port: address.port, certificate: { cert, key }, connection: native.ctx.connection, gateway, carrier, pairing }); await lan.listen()
  const workspace = await native.ctx.typertGateway.invoke({ namespace: 'workspace', method: 'create', args: { request: { path: native.cwd } }, peer: native.peer }) as { workspace: { workspaceId: string } }
  const filePath = join(native.cwd, 'native-bytes.bin'), bytes = Buffer.concat([Buffer.from('real native cross-host bytes'), randomBytes(1024), Buffer.from([0, 255, 128])]); await writeFile(filePath, bytes)
  t.after(async () => { carrier.close(); pairing.close(); gateway.close(); await lan.close(); await native.peer.dispose(); await native.ctx.fiber.dispose(); await rm(directory, { recursive: true, force: true }) })
  return { ...native, cert, origin, pairing, runtimeId, installationId, workspaceId: workspace.workspace.workspaceId, filePath, bytes, dispatches: () => dispatches, lose(endpoint?: string) { lose = endpoint } }
}

test('actual source registry/open turn/owner approval and independently paired native targets exercise B19', { timeout: 180_000 }, async t => {
  const [a, b] = await Promise.all([target(t), target(t)]), source = await host({ probe: false })
  t.after(() => source.close())
  await source.ctx.plugin(Gateway).await(); await source.ctx.plugin(ApiRemotes).await()
  const options = { dataDir: join(source.directory, 'outbound'), sourceInstallationId: randomUUID(), sourceRuntimeId: source.runtimeId, protector: protector() }
  const tools = await installRemoteTools(source.ctx, options); t.after(() => tools.close())
  assert.equal((await tools.targets.status()).enabled, false)
  for (const [id, native] of [['a', a], ['b', b]] as const) {
    const full = native.pairing.begin(), { code: _code, expiresAt: _expiresAt, ...descriptor } = full
    await tools.targets.configure({ id, displayName: `Independent ${id}`, route: 'direct', descriptor, origin: native.origin, tlsPin: descriptor.tlsPin, allowedAddresses: ['127.0.0.1/32'], allowLoopback: true, allowPrivate: true, trustedCertificate: native.cert,
      workspaces: [{ workspaceId: native.workspaceId, canonicalRoot: native.cwd }], allowedActions: [...REMOTE_ACTIONS], allowedAgentPresets: ['golden'], allowedPaths: [{ path: native.filePath, actions: ['read', 'stat', 'download'], maxBytes: 1024 * 1024 }, { path: native.cwd, actions: ['list'], maxBytes: 1024 * 1024 }], sessionBindings: [] })
    await tools.targets.pair(id, full, pinnedRequest, AbortSignal.timeout(15_000))
  }
  await tools.targets.setEnabled(true)
  const ac = await tools.targets.credential('a', AbortSignal.timeout(1000)), bc = await tools.targets.credential('b', AbortSignal.timeout(1000))
  assert.notEqual(ac.pairId, bc.pairId); assert.notDeepEqual(ac.publicJwk, bc.publicJwk); assert.notDeepEqual(ac.localPrivate, bc.localPrivate); assert.equal(ac.manifest.relay, undefined)
  assert.notEqual(ac.manifest.owner.device_id, options.sourceInstallationId); ac.close(); bc.close()
  const handle = await source.ctx.agents.create({ sessionId: SessionId(randomUUID()), meta: { cwd: source.cwd }, agentOptions: { provider: 'fixture', model: 'fixture' } }); t.after(() => handle.dispose())
  const held = source.model.holdNext(); t.after(() => held.release())
  const owner = new Peer(source.ctx, { principal: { kind: 'owner' }, interactions: 'answer' }); t.after(() => owner.dispose())
  await source.ctx.typertGateway.invoke({ namespace: 'session', method: 'prompt', args: { request: { sessionId: handle.agent.id, requestId: randomUUID(), mode: 'queue', content: [{ type: 'text', text: 'hold actual source native turn' }] } }, peer: owner })
  await held.started
  const control = new AbortController(), events = (await source.ctx.typertGateway.wireStream.open('$events', { args: {} }, (async function* () {})(), owner, control.signal))[Symbol.asyncIterator]()
  t.after(async () => { control.abort(); await events.return?.() })
  const ready = (await events.next()).value as { type: string; clientId: string }; assert.equal(ready.type, 'ready')
  const fetchHandler = source.ctx.connection.createSharedFetchHandler('/api')
  const call = async (name: string, args: object, approval?: 'allowed-once' | 'rejected', callId = randomUUID()) => {
    const executing = source.ctx.tools.execute({ name, arguments: args, callId: callId as ToolCallId, agent: handle.agent, signal: AbortSignal.timeout(45_000) })
    if (approval) {
      let question: { type: string; event?: string; eventId?: string; args?: unknown } | undefined
      for (let index = 0; index < 64; index++) { const frame = (await events.next()).value as typeof question; if (frame?.type === 'waterfall' && frame.event === 'approval/request') { question = frame; break } }
      assert.ok(question?.eventId, 'actual source native approval waterfall')
      const response = await fetchHandler.fetch(new Request('http://localhost/api/$events/result', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ type: 'client-request', rpcId: randomUUID(), method: '$events/result', payload: { args: { clientId: ready.clientId, eventId: question.eventId, outcome: { kind: 'result', value: approval } } } }) }), owner)
      const answer = await response.json() as { result: { ok: boolean } }
      assert.equal(answer.result.ok, true)
    }
    return executing
  }
  const value = async (pending: Promise<ToolExecutionResult>) => {
    const result = await pending
    if (result.isError) assert.fail(JSON.stringify(result))
    // The actual registry has already validated the closed tool output schema.
    const checked = result.value as { state: string; operationId: string; data: Record<string, unknown> }
    return checked
  }
  assert.equal((await value(call('remote_discover', { targetId: 'a' }))).state, 'authenticated')
  assert.ok(Array.isArray((await value(call('remote_workspaces', { targetId: 'a' }))).data))
  const createdA = await value(call('remote_create_session', { targetId: 'a', workspaceId: a.workspaceId, agentPreset: 'golden' }, 'allowed-once'))
  const createdB = await value(call('remote_create_session', { targetId: 'b', workspaceId: b.workspaceId, agentPreset: 'golden' }, 'allowed-once'))
  const sessionA = createdA.data.sessionId as string, sessionB = createdB.data.sessionId as string
  const deniedBefore = a.ctx.sessions.list().length
  assert.equal((await call('remote_create_session', { targetId: 'a', workspaceId: a.workspaceId, agentPreset: 'golden' }, 'rejected')).isError, true); assert.equal(a.ctx.sessions.list().length, deniedBefore)
  assert.equal((await call('remote_prompt', { targetId: 'a', sessionId: sessionA, text: 'deny injected approval', approved: true })).isError, true)
  const targetMessagesBeforeNever = a.ctx.agents.get(SessionId(sessionA))!.session.snapshotEvents().filter(event => event.type === 'user/message').length
  source.ctx.approval.setPolicy(handle.agent, 'never')
  assert.equal((await call('remote_prompt', { targetId: 'a', sessionId: sessionA, text: 'native never policy must deny' })).isError, true)
  assert.equal(a.ctx.agents.get(SessionId(sessionA))!.session.snapshotEvents().filter(event => event.type === 'user/message').length, targetMessagesBeforeNever)
  source.ctx.approval.setPolicy(handle.agent, 'ask')
  const allowGate = source.ctx.on('tools/pre-execute', async () => ({ kind: 'allow' }), { global: true })
  t.after(allowGate)
  assert.equal((await call('remote_prompt', { targetId: 'b', sessionId: sessionA, text: 'wrong target' })).isError, true)
  const prompt = await value(call('remote_prompt', { targetId: 'a', sessionId: sessionA, text: 'native remote prompt' }, 'allowed-once'))
  const waited = await value(call('remote_wait', { operationId: prompt.operationId, timeoutMs: 10_000 }))
  assert.equal(waited.state, 'turn-ended'); assert.deepEqual(waited.data.reason, { kind: 'completed' })
  assert.equal((await tools.ledger.get(prompt.operationId)).requestId, prompt.data.requestId)
  const nativeEvents = a.ctx.agents.get(SessionId(sessionA))!.session.snapshotEvents()
  assert.ok(nativeEvents.some(event => event.type === 'user/message' && 'rpcId' in event.data.source && event.data.source.rpcId === prompt.data.requestId))
  const listed = await value(call('remote_files', { targetId: 'a', sessionId: sessionA, action: 'list', path: a.cwd })); assert.equal(listed.state, 'observed')
  const download = await value(call('remote_files', { targetId: 'a', sessionId: sessionA, action: 'download', path: a.filePath }, 'allowed-once'))
  assert.equal(download.data.sha256, createHash('sha256').update(a.bytes).digest('hex'))
  const metadata = await tools.spool.metadata(download.data.handle as string, (await tools.targets.target('a')).ownerSha256, AbortSignal.timeout(1000))
  assert.deepEqual(await tools.spool.read(metadata, AbortSignal.timeout(1000)), a.bytes)
  const upload = await value(call('remote_files', { targetId: 'b', sessionId: sessionB, action: 'upload', fileHandle: metadata.handle, name: 'cross-host.bin' }, 'allowed-once'))
  assert.ok(upload.data.receiptId)
  assert.equal((await call('remote_prompt', { targetId: 'a', sessionId: sessionA, text: 'reject target-bound receipt', uploads: [upload.operationId] })).isError, true)
  const attachmentPrompt = await value(call('remote_prompt', { targetId: 'b', sessionId: sessionB, text: 'actual receiving native file bytes', uploads: [upload.operationId] }, 'allowed-once'))
  assert.equal((await value(call('remote_wait', { operationId: attachmentPrompt.operationId, timeoutMs: 10_000 }))).state, 'turn-ended')
  const beforeUnknown = a.ctx.sessions.list().length
  const lostCreateCallId = randomUUID()
  a.lose('session/create ')
  assert.equal((await call('remote_create_session', { targetId: 'a', workspaceId: a.workspaceId, agentPreset: 'golden' }, 'allowed-once', lostCreateCallId)).isError, true)
  a.lose()
  const unknown = (await tools.ledger.list()).find(operation => operation.kind === 'create_session' && operation.state === 'outcome-unknown')!
  assert.ok(unknown); assert.equal(a.ctx.sessions.list().length, beforeUnknown + 1)
  const afterUnknown = a.ctx.sessions.list().length
  const reconciled = await value(call('remote_reconcile', { operationId: unknown.operationId })); assert.equal(reconciled.state, 'outcome-unknown'); assert.equal(a.ctx.sessions.list().length, afterUnknown)
  assert.equal((await value(call('remote_create_session', { targetId: 'a', workspaceId: a.workspaceId, agentPreset: 'golden' }, undefined, lostCreateCallId))).state, 'outcome-unknown')
  assert.equal(a.ctx.sessions.list().length, afterUnknown)
  const lostPromptCallId = randomUUID(), lostText = 'native prompt ACK lost exactly once'
  a.lose('session/prompt ')
  assert.equal((await call('remote_prompt', { targetId: 'a', sessionId: sessionA, text: lostText }, 'allowed-once', lostPromptCallId)).isError, true)
  a.lose()
  const unknownPrompt = (await tools.ledger.list()).find(operation => operation.callId === lostPromptCallId)!
  assert.equal(unknownPrompt.state, 'outcome-unknown')
  assert.equal((await value(call('remote_reconcile', { operationId: unknownPrompt.operationId }))).state, 'turn-ended')
  const lostUploadCallId = randomUUID()
  b.lose('session/uploadFileBinary?')
  assert.equal((await call('remote_files', { targetId: 'b', sessionId: sessionB, action: 'upload', fileHandle: metadata.handle, name: 'lost-receipt.bin' }, 'allowed-once', lostUploadCallId)).isError, true)
  b.lose()
  const unknownUpload = (await tools.ledger.list()).find(operation => operation.callId === lostUploadCallId)!
  assert.equal(unknownUpload.state, 'outcome-unknown')
  assert.equal((await value(call('remote_reconcile', { operationId: unknownUpload.operationId }))).state, 'outcome-unknown')
  assert.equal((await value(call('remote_files', { targetId: 'b', sessionId: sessionB, action: 'upload', fileHandle: metadata.handle, name: 'lost-receipt.bin' }, undefined, lostUploadCallId))).state, 'outcome-unknown')
  const reopened = new RemoteOperationLedger(options)
  assert.equal((await reopened.get(unknown.operationId)).state, 'outcome-unknown'); reopened.close()
  const sourceAudit = handle.agent.session.snapshotEvents()
  assert.ok(sourceAudit.some(event => event.type === 'approval/asked')); assert.ok(sourceAudit.some(event => event.type === 'approval/decided' && event.data.outcome === 'allowed-once'))
  await tools.targets.revoke('a')
  assert.equal((await call('remote_discover', { targetId: 'a' })).isError, true)
  assert.equal((await value(call('remote_discover', { targetId: 'b' }))).state, 'authenticated')
  const bad = await tools.targets.target('b')
  await assert.rejects(pinnedRequest({ ...bad, allowedAddresses: ['192.0.2.1/32'] }, { method: 'GET', path: '/pair/identity', headers: {} }, AbortSignal.timeout(1000)), /remote\//)
  await assert.rejects(tools.targets.configure({ ...bad, origin: 'http://127.0.0.1' } as never))
})

test('exact rpcId must be consumed within a contiguous identified native turn', () => {
  const event = (seq: number, type: string, data: object) => ({ seq, type, data, time: 0 })
  const events = [event(0, 'turn/start', { turn: 1 }), event(1, 'user/message', { source: { kind: 'user', rpcId: 'other' } }), event(2, 'turn/end', { turn: 1, reason: { kind: 'completed' } }), event(3, 'turn/start', { turn: 2 }), event(4, 'user/message', { source: { kind: 'user', rpcId: 'wanted' } }), event(5, 'turn/end', { turn: 2, reason: { kind: 'blocked' } })]
  assert.equal(correlatePrompt(events.slice(0, 3) as never, 'wanted', 'session').state, 'not-observed')
  assert.deepEqual(correlatePrompt(events as never, 'wanted', 'session').reason, { kind: 'blocked' })
  assert.equal(correlatePrompt([events[3]!, events[4]!, { ...events[5]!, seq: 7 }] as never, 'wanted', 'session').state, 'consumed')
})
