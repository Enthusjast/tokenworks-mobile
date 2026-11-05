import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { readFile, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { CompactSign } from 'jose'
import { approvalBinding, approvalKeyID, APPROVAL_JWS_TYPE, type ApprovalContext, type ApprovalDecision } from '../../host/remote-control/src/approval-contract.js'
import { verifyApprovalLease } from '../../host/remote-control/src/approval-lease.js'
import { canonicalObject } from '../../host/remote-control/src/canonical.js'
import type { approvalKeyFixtures } from './approval-key-fixture.js'

interface Collector { frames: any[]; send(value: object): void; wait(test: (value: any) => boolean, from?: number): Promise<any> }
type Fixture = Awaited<ReturnType<typeof approvalKeyFixtures>>
interface Options {
  fixture: Fixture; initial: Collector; runtimeId: string; workspaceId: string; workspace: string; userId: string
  device: { device_id: string; key_version: number }; phoneKey: any; oldKey: any
  phone(method: string, path: string, body?: object): Promise<any>
  connect(): Promise<Collector>; probe(): Promise<any>; restartGo(): Promise<void>; check(): void; query(sql: string): string
}
async function until<T>(read: () => Promise<T | undefined>, label: string, timeout = 15000): Promise<T> {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) { const value = await read(); if (value !== undefined) return value; await new Promise(resolve => setTimeout(resolve, 10)) }
  throw new Error(`approval rotation timed out: ${label}`)
}
export async function runApprovalKeyRuntime(options: Options) {
  const { fixture, runtimeId, workspaceId, workspace } = options
  let reader = options.initial, probe = await options.probe()
  const creation = randomUUID()
  reader.send({ v: 1, type: 'session.create', request_id: creation, runtime_id: runtimeId, runtime_epoch: probe.runtime_epoch, payload: { workspace_id: workspaceId, preset: 'standard' } })
  const created = await reader.wait(frame => frame.type === 'command.receipt' && frame.request_id === creation && frame.payload.state === 'settled')
  const sessionId = created.session_id as string, nativeId = options.query(`select dsh_session_id from sessions where id='${sessionId}'`)
  const markers: string[] = [], sentinels: string[] = []
  const subscribe = async () => {
    const subscription = randomUUID()
    reader.send({ v: 1, type: 'session.subscribe', request_id: subscription, runtime_id: runtimeId, session_id: sessionId, payload: { max_messages: 100 } })
    const snapshot = await reader.wait(frame => frame.type === 'session.snapshot' && frame.request_id === subscription)
    for (const marker of markers) assert.equal(snapshot.payload.records.filter((record: any) => record.type === 'user/message' && JSON.stringify(record.data).includes(marker)).length, 1)
    reader.send({ v: 1, type: 'approval.subscribe', request_id: randomUUID(), runtime_id: runtimeId, payload: {} })
  }
  await subscribe()
  const exercise = async (keyId: string, forge: boolean) => {
    const id = randomUUID(), marker = `REMOTE_APPROVAL_ROTATION_${id}`, from = reader.frames.length, request = randomUUID()
    const sentinel = join(workspace, `approval-rotation-${id}.txt`)
    reader.send({ v: 1, type: 'message.submit', request_id: request, runtime_id: runtimeId, session_id: sessionId,
      payload: { mode: 'queue', content: [{ type: 'text', text: marker }] } })
    const pending = await reader.wait(frame => frame.type === 'approval.reference' && frame.session_id === sessionId && frame.payload.status.state === 'pending' || frame.type === 'error' && frame.request_id === request, from)
    assert.equal(pending.type, 'approval.reference', `rotation prompt rejected: ${pending.payload?.code ?? 'unknown'}`)
    const approvalId = pending.payload.status.approval_id, details = randomUUID()
    reader.send({ v: 1, type: 'approval.details.get', request_id: details, runtime_id: runtimeId, runtime_epoch: probe.runtime_epoch, session_id: sessionId, payload: { approval_id: approvalId } })
    const context = (await reader.wait(frame => frame.type === 'approval.details' && frame.request_id === details, from)).payload.context as ApprovalContext
    assert.ok((context.arguments as { command: string }).command.includes(`approval-rotation-${id}.txt`))
    await assert.rejects(readFile(sentinel), { code: 'ENOENT' })
    const decision: ApprovalDecision = { domain: 'tokenworks-mobile/approval-decision/v1', protocol_version: 1,
      host_id: context.host_id, installation_id: context.installation_id, runtime_id: context.runtime_id, runtime_epoch: context.runtime_epoch,
      session_id: context.session_id, approval_id: context.approval_id, execution_id: context.execution_id, binding_sha256: approvalBinding(context).sha256,
      decision: 'allow_once', user_id: options.userId, device_id: options.device.device_id, key_version: options.device.key_version,
      policy_version: context.policy_version, nonce: context.nonce, issued_at: new Date().toISOString(), expires_at: context.expires_at }
    const proof = await new CompactSign(canonicalObject(decision)).setProtectedHeader({ alg: 'ES256', kid: approvalKeyID(options.device.device_id, options.device.key_version), typ: APPROVAL_JWS_TYPE }).sign(options.phoneKey)
    const phoneConnection = reader.frames.find(frame => frame.type === 'hello.ok').payload.connection_id
    const replacement = forge ? (async () => {
      const captured = await until(async () => { try { const value = JSON.parse(await readFile(fixture.paths.challenge, 'utf8')); return value.approval_id === approvalId ? value : undefined } catch { return undefined } }, 'captured public lease', 5000)
      const [head, body] = captured.lease.split('.') as string[], header = JSON.parse(Buffer.from(head!, 'base64url').toString()), lease = JSON.parse(Buffer.from(body!, 'base64url').toString())
      assert.equal(header.kid, fixture.next.authority.key_id)
      const retired = await new CompactSign(canonicalObject(lease)).setProtectedHeader({ ...header, kid: fixture.old.authority.key_id }).sign(options.oldKey)
      await verifyApprovalLease(retired, fixture.old.authority, context, proof, lease.connection_id)
      assert.ok(Date.parse(lease.expires_at) > Date.now() + 500)
      await writeFile(fixture.paths.forged, JSON.stringify({ approval_id: approvalId, lease: retired }), { mode: 0o600 })
    })() : Promise.resolve()
    await Promise.all([options.phone('POST', `/v1/approvals/${approvalId}/decisions`, { connection_id: phoneConnection, decision_jws: proof }), replacement])
    const settled = await reader.wait(frame => frame.type === 'approval.reference' && frame.payload.status.approval_id === approvalId && frame.payload.status.host_state === 'settled', from)
    assert.equal(settled.payload.status.execution_outcome, 'succeeded')
    const verified = await until(async () => { try { const value = JSON.parse(await readFile(fixture.paths.verified, 'utf8')); return value.approval_id === approvalId ? value : undefined } catch { return undefined } }, 'verified signer report')
    assert.equal(verified.key_id, keyId); if (forge) assert.equal(verified.old_key_rejected, true)
    assert.equal(await readFile(sentinel, 'utf8'), 'authorized')
    const retry = await options.phone('POST', `/v1/approvals/${approvalId}/decisions`, { connection_id: phoneConnection, decision_jws: proof })
    assert.equal(retry.delivery, 'recorded'); assert.equal(await readFile(sentinel, 'utf8'), 'authorized')
    await reader.wait(frame => frame.type === 'session.event' && frame.session_id === sessionId && frame.payload.type === 'turn/end', from)
    markers.push(marker); sentinels.push(sentinel)
  }
  await exercise(fixture.old.authority.key_id, false)
  await fixture.activateNext(); await options.restartGo(); options.check()
  assert.equal((await options.probe()).runtime_epoch, probe.runtime_epoch)
  reader = await options.connect(); await subscribe(); await exercise(fixture.next.authority.key_id, false)
  await fixture.retireOld(nativeId)
  const reload = await until(async () => { try { const value = JSON.parse(await readFile(fixture.paths.reload, 'utf8')); if (value.error) throw new Error('fixture scope reload failed'); return value.revision === 2 ? value : undefined } catch { return undefined } }, 'new-only Host trust')
  assert.equal(reload.same_agent, true); assert.deepEqual(reload.keys, [fixture.next.authority.key_id])
  const previousEpoch = probe.runtime_epoch
  probe = await until(async () => { try { const value = await options.probe(); return value.runtime_epoch !== previousEpoch && options.query(`select coalesce(epoch::text,'') from runtimes where id='${runtimeId}' and connection_id is not null`) === value.runtime_epoch ? value : undefined } catch { return undefined } }, 'reloaded Host attachment', 30000)
  reader = await options.connect(); await subscribe(); await exercise(fixture.next.authority.key_id, true)
  assert.equal(options.query(`select dsh_session_id from sessions where id='${sessionId}'`), nativeId)
  for (const sentinel of sentinels) assert.equal(await readFile(sentinel, 'utf8'), 'authorized')
  await subscribe(); options.check()
  return { phases: 3, native_session_preserved: true, retired_key_rejected_while_unexpired: true, each_body_executed_once: true }
}
