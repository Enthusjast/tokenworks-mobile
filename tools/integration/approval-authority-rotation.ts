import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { parsePinnedApprovalAuthorityDescription } from '../../host/remote-control/src/approval-authority.js'
import { ApprovalLeaseCache } from '../../host/remote-control/src/approval-lease.js'
import { canonicalObject } from '../../host/remote-control/src/canonical.js'
import type { ApprovalContext } from '../../host/remote-control/src/approval-contract.js'

const input = JSON.parse(readFileSync(0, 'utf8')) as {
  now: string; context: ApprovalContext; decision: string; connection: string
  documents: unknown[]; pins: string[]; leases: string[]
}
assert.equal(input.documents.length, 2); assert.equal(input.leases.length, 2); assert.equal(input.pins.length, 2)
const documents = await Promise.all(input.documents.map((value, index) => parsePinnedApprovalAuthorityDescription(JSON.stringify(value), input.pins[index]!)))
const anchors = documents.map(value => value.authority), now = Date.parse(input.now)
const connection = { host_id: input.context.host_id, installation_id: input.context.installation_id,
  runtime_id: input.context.runtime_id, runtime_epoch: input.context.runtime_epoch, connection_id: input.connection }
assert.equal(Object.isFrozen(documents[0]!.authority.public_jwk), true)
for (const document of documents) {
  assert.equal(document.policy.version, input.context.policy_version)
  assert.equal(document.policy.assurance_profile, input.context.required_assurance.profile)
  assert.equal(document.policy.max_auth_age_seconds, input.context.required_assurance.max_auth_age_seconds)
}
const overlap = new ApprovalLeaseCache(connection, anchors, () => now)
for (const lease of input.leases) await overlap.admit(input.context, input.decision, lease)
const decision = overlap.decision(input.context); assert.ok(decision)
assert.ok(overlap.authorization(input.context, decision))
overlap.close(); assert.equal(overlap.authorization(input.context, decision), undefined)
const current = new ApprovalLeaseCache(connection, [anchors[1]!], () => now)
await assert.rejects(current.admit(input.context, input.decision, input.leases[0]!))
await current.admit(input.context, input.decision, input.leases[1]!)
assert.ok(current.authorization(input.context, decision)); current.close()
// Retiring a trust set also fences a verification still in progress.
const closing = new ApprovalLeaseCache(connection, anchors, () => now)
const pending = closing.admit(input.context, input.decision, input.leases[0]!); closing.close()
await assert.rejects(pending)
for (const mutate of [(value: any) => { value.authority.key_id += '-changed' }, (value: any) => { value.authority.active = false },
  (value: any) => { value.policy.max_auth_age_seconds++ }, (value: any) => { value.authority.public_jwk.d = 'PRIVATE_INVALID' }]) {
  const value: any = structuredClone(input.documents[0]); mutate(value)
  const { sha256: _, ...body } = value as any
  value.sha256 = createHash('sha256').update(canonicalObject(body)).digest('hex')
  await assert.rejects(parsePinnedApprovalAuthorityDescription(JSON.stringify(value), input.pins[0]!))
}
await assert.rejects(parsePinnedApprovalAuthorityDescription(JSON.stringify(input.documents[1]), input.pins[0]!))
process.stdout.write(JSON.stringify({ overlap: true, old_rejected: true, closed: true, tampering_rejected: true }) + '\n')
