import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { calculateJwkThumbprint } from 'jose'
import { Ajv2020 } from 'ajv/dist/2020.js'
import { canonicalObject } from '../src/canonical.js'
import { parsePinnedApprovalAuthorityDescription, APPROVAL_AUTHORITY_DESCRIPTION_FORMAT } from '../src/approval-authority.js'

const fixture = JSON.parse(await readFile(new URL('../../../contracts/security/approval-leases.json', import.meta.url), 'utf8'))
function seal(body: object) {
  const sha256 = createHash('sha256').update(canonicalObject(body)).digest('hex')
  return { ...body, sha256 }
}
const body = () => ({ format: APPROVAL_AUTHORITY_DESCRIPTION_FORMAT, authority: structuredClone(fixture.authorities[0]),
  policy: { version: fixture.context.policy_version, assurance_profile: fixture.context.required_assurance.profile,
    max_auth_age_seconds: fixture.context.required_assurance.max_auth_age_seconds } })

test('public authority descriptions require an independent exact digest and retain immutable values', async () => {
  const value = seal(body())
  const parsed = await parsePinnedApprovalAuthorityDescription(JSON.stringify(value, null, 2), value.sha256)
  assert.deepEqual(parsed, value)
  assert.ok(Object.isFrozen(parsed)); assert.ok(Object.isFrozen(parsed.authority)); assert.ok(Object.isFrozen(parsed.authority.public_jwk)); assert.ok(Object.isFrozen(parsed.policy))
  for (const mutate of [(value: any) => { value.authority.active = false }, (value: any) => { value.authority.key_id += '-new' },
    (value: any) => { value.policy.version++ }, (value: any) => { value.authority.valid_until = '2040-01-01T00:00:00.000Z' }]) {
    const changed = body(); mutate(changed)
    await assert.rejects(parsePinnedApprovalAuthorityDescription(JSON.stringify(seal(changed)), value.sha256), /^Error: approval: invalid pinned authority description$/)
  }
  await assert.rejects(parsePinnedApprovalAuthorityDescription(JSON.stringify(value), 'A'.repeat(64)))
  await assert.rejects(parsePinnedApprovalAuthorityDescription(JSON.stringify(value).replace('"sha256":', '"sha256":"' + value.sha256 + '","sha256":'), value.sha256))
})

test('a correctly hashed description still rejects invalid keys, shape, policy and intervals', async () => {
  for (const mutate of [(value: any) => { value.authority.public_jwk.d = 'PRIVATE_INVALID' }, (value: any) => { value.authority.public_jwk.jku = 'https://untrusted.example/key' },
    (value: any) => { value.authority.valid_until = value.authority.valid_from }, (value: any) => { value.policy.max_auth_age_seconds = 901 },
    (value: any) => { value.policy.Version = value.policy.version }, (value: any) => { value.authority.key_jkt = 'A'.repeat(43) },
    (value: any) => { value.private_key_file = 'PRIVATE_PATH' }]) {
    const candidate = body(); mutate(candidate)
    const value = seal(candidate)
    await assert.rejects(parsePinnedApprovalAuthorityDescription(JSON.stringify(value), value.sha256), /^Error: approval: invalid pinned authority description$/)
  }
  const invalidPoint = body(); invalidPoint.authority.public_jwk.x = 'A'.repeat(43); invalidPoint.authority.public_jwk.y = 'A'.repeat(43)
  invalidPoint.authority.key_jkt = await calculateJwkThumbprint(invalidPoint.authority.public_jwk)
  const point = seal(invalidPoint)
  await assert.rejects(parsePinnedApprovalAuthorityDescription(JSON.stringify(point), point.sha256))
  await assert.rejects(parsePinnedApprovalAuthorityDescription(' '.repeat(16 * 1024 + 1), 'a'.repeat(64)))
})

test('public inspection may stage future or inactive anchors without claiming current approval authority', async () => {
  const candidate = body(); candidate.authority.active = false
  candidate.authority.valid_from = '2040-01-01T00:00:00.000Z'; candidate.authority.valid_until = '2041-01-01T00:00:00.000Z'
  const value = seal(candidate)
  assert.equal((await parsePinnedApprovalAuthorityDescription(JSON.stringify(value), value.sha256)).authority.active, false)
})

test('published authority metadata schema is strict and carries no private deployment material', async () => {
  const schema = JSON.parse(await readFile(new URL('../../../contracts/security/approval-authority.schema.json', import.meta.url), 'utf8'))
  const validate = new Ajv2020({ strict: true }).compile(schema)
  const document = seal(body())
  assert.equal(validate(document), true)
  assert.equal(validate({ ...document, private_key_file: 'PRIVATE_PATH' }), false)
  assert.equal(validate({ ...document, authority: { ...body().authority, public_jwk: { ...body().authority.public_jwk, d: 'PRIVATE_SCALAR' } } }), false)
})
