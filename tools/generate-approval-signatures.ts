/** Explicit public interoperability fixtures. Fresh private keys never leave memory. */
import { createHash, generateKeyPairSync, sign } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { calculateJwkThumbprint } from 'jose'
import { approvalBinding, approvalKeyID, APPROVAL_CONTEXT_DOMAIN, APPROVAL_DECISION_DOMAIN, APPROVAL_JWS_TYPE } from '../host/remote-control/src/approval-contract.js'
import { canonicalObject } from '../host/remote-control/src/canonical.js'

const now = '2026-10-24T12:00:02.000Z'
const context = {
  domain: APPROVAL_CONTEXT_DOMAIN, protocol_version: 1,
  host_id: '11111111-1111-4111-8111-111111111111', installation_id: '22222222-2222-4222-8222-222222222222',
  runtime_id: '33333333-3333-4333-8333-333333333333', runtime_epoch: '44444444-4444-4444-8444-444444444444',
  session_id: '55555555-5555-4555-8555-555555555555', approval_id: '66666666-6666-4666-8666-666666666666', execution_id: '77777777-7777-4777-8777-777777777777',
  dsh_session_id: 'native:workspace/opaque-session', call_id: 'native-call:fixture', tool: 'bash',
  arguments: { command: "printf '中文🙂' <>&\n", numbers: [333333333.3333333, 1e-7, 1e20], nested: { z: true, a: null } },
  workspace: { workspace_id: '88888888-8888-4888-8888-888888888888', root_sha256: 'a'.repeat(64) },
  resources: [{ kind: 'file', resource_id: 'artifact/patch', version_sha256: 'b'.repeat(64) }],
  nonce: Buffer.alloc(32, 7).toString('base64url'), policy_version: 2,
  required_assurance: { profile: 'owner-user-verification/v1', max_auth_age_seconds: 300 },
  created_at: '2026-10-24T12:00:00.000Z', expires_at: '2026-10-24T12:03:00.000Z',
}
const binding = approvalBinding(context)
const pair = generateKeyPairSync('ec', { namedCurve: 'prime256v1' })
const publicJwk = pair.publicKey.export({ format: 'jwk' })
const registered = { user_id: '99999999-9999-4999-8999-999999999999', device_id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  key_version: 2, approval_key_jkt: await calculateJwkThumbprint(publicJwk), public_jwk: publicJwk, active: true }
const decision: Record<string, unknown> = {
  domain: APPROVAL_DECISION_DOMAIN, protocol_version: 1,
  ...Object.fromEntries(['host_id', 'installation_id', 'runtime_id', 'runtime_epoch', 'session_id', 'approval_id', 'execution_id'].map(key => [key, context[key as keyof typeof context]])),
  binding_sha256: binding.sha256, decision: 'allow_once', user_id: registered.user_id, device_id: registered.device_id,
  key_version: 2, policy_version: 2, nonce: context.nonce, issued_at: '2026-10-24T12:00:01.000Z', expires_at: '2026-10-24T12:00:25.000Z',
}
const header = { alg: 'ES256', kid: approvalKeyID(registered.device_id, 2), typ: APPROVAL_JWS_TYPE }
const b64 = (bytes: string | Uint8Array) => Buffer.from(bytes).toString('base64url')
const raw = (head: Uint8Array | string, payload: Uint8Array | string, der = false) => {
  const input = `${b64(head)}.${b64(payload)}`
  return `${input}.${sign('sha256', Buffer.from(input), { key: pair.privateKey, dsaEncoding: der ? 'der' : 'ieee-p1363' }).toString('base64url')}`
}
const signed = (value: Record<string, unknown>, head: Record<string, unknown> = header) => raw(canonicalObject(head), canonicalObject(value))
type Vector = { name: string; compact: string; invalid?: boolean; decision?: string; payload_sha256?: string; registered?: unknown }
const vectors: Vector[] = []
const accepted = (name: string, value: Record<string, unknown>, compact = signed(value), key?: unknown) => {
  vectors.push({ name, compact, decision: value.decision as string, payload_sha256: createHash('sha256').update(canonicalObject(value)).digest('hex'), ...(key ? { registered: key } : {}) })
}
const denied = (name: string, compact: string, key?: unknown) => vectors.push({ name, compact, invalid: true, ...(key ? { registered: key } : {}) })
accepted('node-signed-allow', decision)
accepted('node-signed-reject', { ...decision, decision: 'reject' })
const valid = vectors[0]!.compact
const parts = valid.split('.')
denied('tampered-decision', `${parts[0]}.${b64(canonicalObject({ ...decision, decision: 'reject' }))}.${parts[2]}`)
const other = context.host_id
for (const [name, changes] of Object.entries({
  'wrong-user': { user_id: other }, 'wrong-device': { device_id: other }, 'wrong-key-version': { key_version: 3 }, 'wrong-policy': { policy_version: 3 },
  'wrong-nonce': { nonce: Buffer.alloc(32, 9).toString('base64url') }, 'wrong-binding': { binding_sha256: '0'.repeat(64) },
  'wrong-runtime': { runtime_id: other }, 'wrong-epoch': { runtime_epoch: other }, 'wrong-session': { session_id: other },
  'wrong-approval': { approval_id: other }, 'wrong-execution': { execution_id: other }, 'wrong-host': { host_id: context.session_id },
  'wrong-installation': { installation_id: other }, 'wrong-domain': { domain: 'another-purpose' }, 'extra-claim': { approval_public_jwk: publicJwk },
  expired: { issued_at: context.created_at, expires_at: '2026-10-24T12:00:01.000Z' }, 'future-issued': { issued_at: '2026-10-24T12:00:10.000Z' },
  'old-issued': { issued_at: '2026-10-24T11:59:30.000Z' }, 'after-request-deadline': { expires_at: '2026-10-24T12:03:01.000Z' },
  'wrong-utc-format': { issued_at: '2026-10-24T12:00:01Z' }, 'invalid-calendar': { issued_at: '2026-02-30T12:00:01.000Z' },
})) denied(name, signed({ ...decision, ...changes }))
for (const [name, changes] of Object.entries({ 'wrong-type': { typ: 'dpop+jwt' }, 'wrong-key-id': { kid: header.kid + '-other' },
  'caller-key': { jwk: publicJwk }, 'remote-key-url': { jku: 'https://example.invalid/keys' } })) denied(name, signed(decision, { ...header, ...changes }))
const head = Buffer.from(canonicalObject(header)).toString(), payload = Buffer.from(canonicalObject(decision)).toString()
denied('noncanonical-header', raw(JSON.stringify({ typ: header.typ, kid: header.kid, alg: header.alg }), payload))
denied('noncanonical-payload', raw(head, JSON.stringify(decision, null, 1)))
denied('duplicate-header', raw(head.replace('{', '{"alg":"ES256",'), payload))
denied('duplicate-decision', raw(head, payload.replace('{', '{"decision":"allow_once",')))
denied('der-signature', raw(head, payload, true))
denied('padded-signature', valid + '=')
const order = BigInt('0xffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551')
const signature = Buffer.from(parts[2]!, 'base64url')
const complement = order - BigInt('0x' + signature.subarray(32).toString('hex'))
const equivalent = Buffer.concat([signature.subarray(0, 32), Buffer.from(complement.toString(16).padStart(64, '0'), 'hex')])
accepted('equivalent-es256-signature', decision, `${parts[0]}.${parts[1]}.${b64(equivalent)}`)
const go = process.env.REMOTE_TEST_GO || '/opt/go1.26.10/bin/go'
const goResult = JSON.parse(execFileSync(go, ['run', './internal/approval/testdata/generate-decisions'], {
  cwd: fileURLToPath(new URL('../services/remote', import.meta.url)), input: canonicalObject(decision), encoding: 'utf8', stdio: ['pipe', 'pipe', 'inherit'],
})) as { compact: string; registered: unknown }
accepted('go-jose-signed-allow', decision, goResult.compact, goResult.registered)
denied('noncanonical-registered-coordinate', valid, { ...registered, public_jwk: { ...publicJwk, x: publicJwk.x + '=' } })
denied('oversized-key-id-utf8', valid, { ...registered, device_id: '🙂'.repeat(300) })
const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_'
const sigText = parts[2]!, last = alphabet.indexOf(sigText.at(-1)!)
denied('noncanonical-signature-pad-bits', `${parts[0]}.${parts[1]}.${sigText.slice(0, -1)}${alphabet[(last & 48) | 1]}`)
const { domain: _domain, ...noDomain } = decision
denied('uppercase-domain-claim', signed({ ...noDomain, DOMAIN: APPROVAL_DECISION_DOMAIN }), registered)
denied('case-alias-decision', signed({ ...decision, Decision: 'reject' }), registered)
denied('case-alias-header', signed(decision, { ...header, ALG: 'ES256' }), registered)
await writeFile(new URL('../contracts/security/approval-signatures.json', import.meta.url), JSON.stringify({
  now, context, binding_canonical: Buffer.from(binding.canonical).toString(), binding_sha256: binding.sha256, registered, vectors,
}, null, 2) + '\n')
console.log(`Generated ${vectors.length} public approval vectors; private keys were not persisted.`)
