import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { Ajv2020 } from 'ajv/dist/2020.js'
import { approvalBinding } from '../src/approval-contract.js'
import { verifyApprovalDecision, type RegisteredApprovalKey } from '../src/approval-signature.js'
import { generateKeyPairSync, sign } from 'node:crypto'
import { calculateJwkThumbprint } from 'jose'
import { canonicalObject } from '../src/canonical.js'
import { approvalKeyID, APPROVAL_JWS_TYPE } from '../src/approval-contract.js'

const fixture = JSON.parse(await readFile(new URL('../../../contracts/security/approval-signatures.json', import.meta.url), 'utf8')) as {
  now: string; context: Record<string, any>; binding_canonical: string; binding_sha256: string; registered: RegisteredApprovalKey;
  vectors: Array<{ name: string; compact: string; invalid?: boolean; decision?: string; payload_sha256?: string; registered?: RegisteredApprovalKey }>
}

test('Go/TypeScript approval vectors bind purpose, identities, context, decision, registered key and time', async () => {
  const kotlinFixture = JSON.parse(await readFile(new URL('../../../contracts/security/approval-kotlin-signatures.json', import.meta.url), 'utf8')) as typeof fixture
  const fixtures = [fixture, kotlinFixture]
  for (const fixture of fixtures) {
    const binding = approvalBinding(fixture.context)
    assert.equal(Buffer.from(binding.canonical).toString(), fixture.binding_canonical)
    assert.equal(binding.sha256, fixture.binding_sha256)
    const schema = JSON.parse(await readFile(new URL('../../../contracts/security/approval.schema.json', import.meta.url), 'utf8'))
    const validate = new Ajv2020({ strict: true }).compile(schema)
    assert.equal(validate(fixture.context), true)
    for (const vector of fixture.vectors) {
      const registered = vector.registered ?? fixture.registered
      if (vector.invalid) {
        await assert.rejects(verifyApprovalDecision(vector.compact, registered, fixture.context, Date.parse(fixture.now)), /approval: invalid signed decision/, vector.name)
      } else {
        const verified = await verifyApprovalDecision(vector.compact, registered, fixture.context, Date.parse(fixture.now))
        assert.equal(verified.claims.decision, vector.decision, vector.name)
        assert.equal(verified.payload_sha256, vector.payload_sha256, vector.name)
        assert.equal(validate(verified.claims), true, vector.name)
      }
    }
  }
})

test('approval verification rejects changed execution facts, unavailable keys and expired decisions without side effects', async () => {
  const valid = fixture.vectors.find(item => !item.invalid)!
  const now = Date.parse(fixture.now)
  for (const field of ['host_id', 'installation_id', 'runtime_id', 'runtime_epoch', 'session_id', 'approval_id', 'execution_id', 'dsh_session_id', 'call_id', 'tool', 'nonce', 'policy_version', 'arguments', 'workspace', 'resources', 'required_assurance']) {
    const changed = structuredClone(fixture.context)
    if (field === 'arguments') changed.arguments.command += ' changed'
    else if (field === 'workspace') changed.workspace.root_sha256 = 'c'.repeat(64)
    else if (field === 'resources') changed.resources[0].version_sha256 = 'c'.repeat(64)
    else if (field === 'required_assurance') changed.required_assurance.max_auth_age_seconds--
    else if (field === 'policy_version') changed[field]++
    else if (field === 'nonce') changed[field] = Buffer.alloc(32, 8).toString('base64url')
    else if (field.endsWith('_id') || field === 'runtime_epoch') changed[field] = field === 'host_id' ? fixture.context.session_id : fixture.context.host_id
    else changed[field] += '-changed'
    await assert.rejects(verifyApprovalDecision(valid.compact, fixture.registered, changed, now), /invalid signed decision/, field)
  }
  for (const registered of [
    { ...fixture.registered, active: false }, { ...fixture.registered, key_version: 3 },
    { ...fixture.registered, approval_key_jkt: 'A'.repeat(43) },
    { ...fixture.registered, public_jwk: { ...fixture.registered.public_jwk, d: 'private-material' } },
    { ...fixture.registered, public_jwk: { ...fixture.registered.public_jwk, jku: 'https://example.invalid/key' } },
  ]) await assert.rejects(verifyApprovalDecision(valid.compact, registered, fixture.context, now), /invalid signed decision/)
  await assert.rejects(verifyApprovalDecision(valid.compact, fixture.registered, fixture.context, Date.parse('2026-10-24T12:00:25.000Z')), /invalid signed decision/)
  await assert.rejects(verifyApprovalDecision(valid.compact, fixture.registered, fixture.context, Date.parse('2026-10-24T11:59:54.000Z')), /invalid signed decision/)
  const first = await verifyApprovalDecision(valid.compact, fixture.registered, fixture.context, now)
  const retry = await verifyApprovalDecision(valid.compact, fixture.registered, fixture.context, now)
  assert.equal(first.payload_sha256, retry.payload_sha256, 'pure verification must not pretend to consume pending state')
})

test('fresh signatures accept only the new decision domain, typ and kid', async () => {
  const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' })
  const public_jwk = publicKey.export({ format: 'jwk' })
  const registered = { ...fixture.registered, public_jwk, approval_key_jkt: await calculateJwkThumbprint(public_jwk) }
  const valid = fixture.vectors.find(item => !item.invalid)!
  const decision = JSON.parse(Buffer.from(valid.compact.split('.')[1]!, 'base64url').toString())
  const header = { alg: 'ES256', typ: APPROVAL_JWS_TYPE, kid: approvalKeyID(registered.device_id, registered.key_version) }
  const compact = (head: typeof header, body: typeof decision) => {
    const input = [head, body].map(value => Buffer.from(canonicalObject(value)).toString('base64url')).join('.')
    return input + '.' + sign('sha256', Buffer.from(input), { key: privateKey, dsaEncoding: 'ieee-p1363' }).toString('base64url')
  }
  await verifyApprovalDecision(compact(header, decision), registered, fixture.context, Date.parse(fixture.now))
  for (const [head, body] of [
    [{ ...header, typ: 'dsh-approval-decision+jws' }, decision],
    [{ ...header, kid: `dsh-approval/${registered.device_id}/${registered.key_version}` }, decision],
    [header, { ...decision, domain: 'dsh-mobile-ustc/approval-decision/v1' }],
  ] as const) await assert.rejects(verifyApprovalDecision(compact(head, body), registered, fixture.context, Date.parse(fixture.now)), /invalid signed decision/)
})
