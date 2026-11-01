import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parse } from 'yaml'
import { Ajv2020 } from 'ajv/dist/2020.js'

const api = parse(await readFile(new URL('../../../contracts/openapi/remote.yaml', import.meta.url), 'utf8')) as {
  components: { schemas: object }
  paths: Record<string, Record<string, unknown>>
}
const defs = JSON.parse(JSON.stringify(api.components.schemas).replaceAll('#/components/schemas/', '#/$defs/')) as object
const ajv = new Ajv2020({ strict: true })
ajv.addFormat('uuid', /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
ajv.addFormat('date-time', value => Number.isFinite(Date.parse(value)))
ajv.addFormat('uri', value => { try { return Boolean(new URL(value).protocol) } catch { return false } })
const validator = (name: string) => ajv.compile({ $defs: defs, $ref: `#/$defs/${name}` })
const id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', digest = 'a'.repeat(64), timestamp = '2026-11-02T12:00:00Z'

test('administrator directory, exact decisions and terminal receipt have strict public REST contracts', () => {
  const decision = validator('EnrollmentAdminDecisionRequest')
  assert.equal(decision({ review_sha256: digest }), true)
  for (const body of [{}, { review_sha256: null }, { review_sha256: digest, reason: 'PRIVATE_REASON' }, { Review_SHA256: digest }]) assert.equal(decision(body), false)
  const denial = { enrollment_id: id, host_id: id, installation_id: id, runtime_id: id, state: 'denied', denied_by: id,
    device_id: id, device_key_version: 1, review_sha256: digest, denied_at: timestamp }
  const receipt = validator('EnrollmentAdminDenialResponse')
  assert.equal(receipt({ ok: true, data: denial }), true)
  assert.equal(receipt({ ok: true, data: denial, request_id: id }), true)
  assert.equal(receipt({ ok: true, data: { ...denial, state: 'approved' } }), false)
  assert.equal(receipt({ ok: true, data: { ...denial, code: 'PRIVATE_PAIRING_CODE' } }), false)
  const item = { enrollment_id: id, organization_id: id, host_id: id, host_alias: 'Fixture', installation_id: id, runtime_id: id,
    profile_ref: 'fixture', owner_user_id: id, confirmed_user_id: null, user_confirmed: false, host_confirmed: false, state: 'pending', created_at: '2026-11-02T11:59:00Z', expires_at: timestamp }
  const directory = validator('EnrollmentApprovalDirectoryResponse')
  const page = { ok: true, data: { organization_id: id, items: [item], next_cursor: null } }
  assert.equal(directory(page), true)
  assert.equal(directory({ ...page, data: { ...page.data, items: [{ ...item, request_sha256: digest }] } }), false)
  assert.equal(directory({ ...page, data: { ...page.data, items: [{ ...item, state: 'denied' }] } }), false)
  const review = validator('EnrollmentAdminReviewResponse')
  const participant = { user_id: id, issuer: 'https://fixture.example', subject: 'fixture', eligible: true }
  const value = { enrollment_id: id, organization_id: id, host_id: id, host_alias: 'Fixture', installation_id: id, installation_key_jkt: 'A'.repeat(43), runtime_id: id,
    profile_ref: 'fixture', request_sha256: digest, owner: participant, confirmed_user: null, confirmed_device_id: null, confirmed_device_key_version: null,
    user_confirmed_at: null, host_confirmed_at: null, admin_approval_required: true, state: 'pending', expires_at: timestamp, workspaces: [], review_sha256: digest }
  assert.equal(review({ ok: true, data: value }), true)
  assert.equal(review({ ok: true, data: { ...value, state: 'denied', denial } }), true)
  assert.equal(review({ ok: true, data: { ...value, pairing_code: 'PRIVATE_PAIRING_CODE' } }), false)
  assert.ok(api.paths['/v1/enrollments']?.get)
  for (const [suffix, method] of [['admin-review', 'get'], ['admin-approve', 'post'], ['admin-deny', 'post']] as const) assert.ok(api.paths[`/v1/enrollments/{id}/${suffix}`]?.[method])
})
