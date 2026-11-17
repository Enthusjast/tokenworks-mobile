import test from 'node:test'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createCipheriv, createDecipheriv, createHash, randomBytes, randomUUID } from 'node:crypto'
import { mkdtemp, readFile, readdir, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { RemoteOperationLedger, type RemoteOperationPrepare } from '../src/remote-operation-ledger.js'
import type { RemoteTarget } from '../src/remote-targets.js'
import type { JournalKeyProtector } from '../src/journal-key-protection.js'
import { NOISE_SUITE } from '../src/native-noise.js'

function protector(master: Buffer): JournalKeyProtector {
  return { id: 'remote-ledger-test-protector', async wrap(bytes, context) {
    const iv = randomBytes(12), cipher = createCipheriv('aes-256-gcm', master, iv); cipher.setAAD(context)
    return Buffer.concat([iv, cipher.update(bytes), cipher.final(), cipher.getAuthTag()])
  }, async unwrap(bytes, context) {
    const raw = Buffer.from(bytes), cipher = createDecipheriv('aes-256-gcm', master, raw.subarray(0, 12)); cipher.setAAD(context); cipher.setAuthTag(raw.subarray(-16))
    return Buffer.concat([cipher.update(raw.subarray(12, -16)), cipher.final()])
  } }
}
function target(): RemoteTarget {
  const origin = 'https://approved.example:9443', tlsPin = `sha256/${randomBytes(32).toString('base64')}`
  return { id: 'target', displayName: 'Target', route: 'direct', origin, tlsPin, expectedDshVersion: '0.2.2-rc.1',
    descriptor: { version: 2, carrierVersion: 1, suite: NOISE_SUITE, minimumEncrypted: true, origin, tlsPin, runtimeId: randomUUID(), installationId: randomUUID(), hostStaticPublicKey: randomBytes(32).toString('base64url'), hostKeyEpoch: 1 },
    allowedAddresses: ['192.0.2.10'], allowPrivate: false, allowLoopback: false, workspaces: [{ workspaceId: 'workspace', canonicalRoot: '/workspace' }], allowedActions: ['prompt'], allowedAgentPresets: ['preset'], allowedPaths: [], sessionBindings: [],
    authorizationEpoch: 1, status: 'paired', ownerSha256: randomBytes(32).toString('hex'), credentialRef: randomUUID() }
}

test('ledger preserves 256KiB prompt exact body and stable rpcId, fences duplicate dispatch and never replays after restart', async t => {
  const dataDir = await mkdtemp(join(tmpdir(), 'remote-ledger-')); t.after(() => rm(dataDir, { recursive: true, force: true }))
  const options = { dataDir, sourceInstallationId: randomUUID(), sourceRuntimeId: randomUUID(), protector: protector(randomBytes(32)) }
  const first = new RemoteOperationLedger(options); t.after(() => first.close())
  const remote = target(), content = 'private-prompt-'.repeat(18000).slice(0, 256 * 1024)
  const input: RemoteOperationPrepare = { ownerSha256: remote.ownerSha256, sourceAgentId: 'actual-agent', callId: 'actual-call', rootCallId: 'actual-root', target: remote, kind: 'prompt',
    body: requestId => ({ requestId, sessionId: 'actual-session', mode: 'queue', content: [{ type: 'text', text: content }] }) }
  const prepared = await first.prepare(input)
  assert.equal(prepared.created, true); assert.equal(prepared.operation.state, 'prepared')
  const exact = await first.body(prepared.operation.operationId), parsed = JSON.parse(exact.toString('utf8'))
  assert.equal(parsed.requestId, prepared.operation.requestId); assert.equal(parsed.content[0].text, content)
  assert.equal(createHash('sha256').update(exact).digest('hex'), prepared.operation.digest)
  const second = new RemoteOperationLedger(options); t.after(() => second.close())
  const duplicate = await second.prepare(input)
  assert.equal(duplicate.created, false); assert.equal(duplicate.operation.requestId, prepared.operation.requestId)
  await assert.rejects(second.markDispatchStarted(prepared.operation.operationId), /operation-dispatch-forbidden/)
  await assert.rejects(first.prepare({ ...input, body: { sessionId: 'changed' } }), /operation-conflict/)
  await first.record(prepared.operation.operationId, 'approval-pending', { nativeCallId: input.callId })
  await first.record(prepared.operation.operationId, 'approved', { decision: 'allowed-once' })
  assert.equal((await first.markDispatchStarted(prepared.operation.operationId)).state, 'dispatch-started')
  await assert.rejects(first.markDispatchStarted(prepared.operation.operationId), /operation-dispatch-forbidden/)
  first.close()
  const restarted = new RemoteOperationLedger(options); t.after(() => restarted.close())
  const unknown = await restarted.get(prepared.operation.operationId)
  assert.equal(unknown.state, 'outcome-unknown'); assert.equal(unknown.requestId, prepared.operation.requestId)
  assert.deepEqual(await restarted.body(unknown.operationId), exact)
  await assert.rejects(restarted.markDispatchStarted(unknown.operationId), /operation-dispatch-forbidden/)
  await restarted.record(unknown.operationId, 'accepted', { nativeEvidence: { rpcId: unknown.requestId, seq: 10 } })
  assert.equal((await restarted.get(unknown.operationId)).state, 'accepted')
  for (const name of await readdir(join(dataDir, 'remote-operations'))) {
    const raw = await readFile(join(dataDir, 'remote-operations', name))
    assert.equal(raw.includes(Buffer.from('private-prompt-')), false)
  }
  exact.fill(0)
})

test('ledger exact body rejects accessors and oversized input before any durable reservation', async t => {
  const dataDir = await mkdtemp(join(tmpdir(), 'remote-ledger-limits-')); t.after(() => rm(dataDir, { recursive: true, force: true }))
  const ledger = new RemoteOperationLedger({ dataDir, sourceInstallationId: randomUUID(), sourceRuntimeId: randomUUID(), protector: protector(randomBytes(32)) }); t.after(() => ledger.close())
  const remote = target(), input: RemoteOperationPrepare = { ownerSha256: remote.ownerSha256, sourceAgentId: 'agent', callId: 'call', rootCallId: 'root', target: remote, kind: 'prompt', body: {} }
  let touched = false
  await assert.rejects(ledger.prepare({ ...input, body: { get secret() { touched = true; return 'unsafe' } } }), /operation-body-invalid/)
  assert.equal(touched, false)
  await assert.rejects(ledger.prepare({ ...input, body: Buffer.alloc(1024 * 1024 + 1) }), /operation-body-too-large/)
  assert.deepEqual(await ledger.list(), [])
})

test('separate processes atomically reserve one exact call and only the winner may dispatch', { timeout: 60_000 }, async t => {
  const dataDir = await mkdtemp(join(tmpdir(), 'remote-ledger-process-')); t.after(() => rm(dataDir, { recursive: true, force: true }))
  const master = randomBytes(32), sourceInstallationId = randomUUID(), sourceRuntimeId = randomUUID(), remote = target()
  const input = { ownerSha256: remote.ownerSha256, sourceAgentId: 'actual-agent', callId: 'same-call', rootCallId: 'same-root', target: remote, kind: 'create_session', body: { workspaceId: 'workspace', agentPreset: 'preset' } }
  const script = `
    import { createCipheriv, createDecipheriv, randomBytes } from 'node:crypto';
    import { RemoteOperationLedger } from ${JSON.stringify(new URL('../src/remote-operation-ledger.js', import.meta.url).href)};
    const master = Buffer.from(process.env.REMOTE_TEST_MASTER, 'hex');
    const protector = (${protector.toString()})(master);
    const options = JSON.parse(process.env.REMOTE_TEST_OPTIONS);
    const ledger = new RemoteOperationLedger({ ...options, protector });
    const {operation,created} = await ledger.prepare(JSON.parse(process.env.REMOTE_TEST_INPUT));
    if (created) await ledger.markDispatchStarted(operation.operationId);
    console.log(JSON.stringify({operationId:operation.operationId,requestId:operation.requestId,created}));
    ledger.close();
  `
  const launch = () => {
    const { promise, resolve, reject } = Promise.withResolvers<{ operationId: string; requestId: string; created: boolean }>()
    const child = spawn(process.execPath, ['--import', 'tsx', '--input-type=module', '-e', script], { cwd: process.cwd(), env: { ...process.env, REMOTE_TEST_MASTER: master.toString('hex'), REMOTE_TEST_OPTIONS: JSON.stringify({ dataDir, sourceInstallationId, sourceRuntimeId }), REMOTE_TEST_INPUT: JSON.stringify(input) }, stdio: ['ignore', 'pipe', 'pipe'] })
    let output = '', error = ''
    child.stdout.on('data', chunk => { output += chunk }); child.stderr.on('data', chunk => { error += chunk })
    child.once('error', reject); child.once('exit', code => { if (code !== 0) reject(new Error(error)); else { try { resolve(JSON.parse(output)) } catch (cause) { reject(cause) } } })
    return promise
  }
  const results = await Promise.all([launch(), launch()])
  assert.equal(results.filter(value => value.created).length, 1)
  assert.equal(results[0]!.operationId, results[1]!.operationId); assert.equal(results[0]!.requestId, results[1]!.requestId)
  const ledger = new RemoteOperationLedger({ dataDir, sourceInstallationId, sourceRuntimeId, protector: protector(master) }); t.after(() => ledger.close())
  assert.equal((await ledger.get(results[0]!.operationId)).state, 'outcome-unknown')
  await assert.rejects(ledger.markDispatchStarted(results[0]!.operationId), /operation-dispatch-forbidden/)
})
