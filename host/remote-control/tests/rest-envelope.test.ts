import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parse } from 'yaml'
import { Ajv2020 } from 'ajv/dist/2020.js'
import { parseSigningObject } from '../src/canonical.js'
import { normalizeRestEnvelope, restEnvelopeHeader, restEnvelopeVersion } from '../src/rest-envelope.js'

test('REST envelope metadata is verified before strict business parsing and never replaces command identity', () => {
  const id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
  const headers = new Headers({ [restEnvelopeHeader]: restEnvelopeVersion, 'x-request-id': id })
  const value = parseSigningObject(JSON.stringify({ ok: true, data: { request_id: 'PRIVATE_COMMAND_ID' }, request_id: id }))
  assert.deepEqual(normalizeRestEnvelope(value, headers), { ok: true, data: { request_id: 'PRIVATE_COMMAND_ID' } })
  for (const [raw, responseHeaders] of [
    [{ ok: true, data: null, request_id: id, extra: true }, headers],
    [{ ok: true, error: null, request_id: id }, headers],
    [{ ok: true, data: null, request_id: 'PRIVATE_INVALID_ID' }, headers],
    [{ ok: true, data: null }, headers],
    [value, new Headers()],
    [value, new Headers({ [restEnvelopeHeader]: restEnvelopeVersion, 'x-request-id': `${id}, ${id}` })],
    [value, new Headers({ [restEnvelopeHeader]: 'request-id.v2', 'x-request-id': id })],
  ] as const) {
    assert.throws(() => normalizeRestEnvelope(raw, responseHeaders), /^Error: remote response envelope invalid$/)
  }
  const legacy = { ok: false, error: { code: 'auth.proof_invalid' } }
  assert.equal(normalizeRestEnvelope(legacy, new Headers()), legacy)
  assert.throws(() => parseSigningObject(`{"ok":true,"data":null,"request_id":"${id}","request_id":"${id}"}`))
})

test('published REST envelope contract distinguishes negotiated metadata from business identities', async () => {
  const api = parse(await readFile(new URL('../../../contracts/openapi/remote.yaml', import.meta.url), 'utf8')) as {
    components: { schemas: Record<string, { required?: string[]; properties?: Record<string, unknown> }> }
    paths: Record<string, { parameters: Array<{ $ref: string }> }>
  }
  const defs = JSON.parse(JSON.stringify(api.components.schemas).replaceAll('#/components/schemas/', '#/$defs/')) as object
  const ajv = new Ajv2020({ strict: true })
  ajv.addFormat('uuid', /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  ajv.addFormat('date-time', value => Number.isFinite(Date.parse(value)))
  ajv.addFormat('uri', value => { try { return Boolean(new URL(value).protocol) } catch { return false } })
  const negotiated = ajv.compile({ $defs: defs, $ref: '#/$defs/NegotiatedResponseEnvelope' })
  const id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
  assert.equal(negotiated({ ok: true, data: { request_id: 'PRIVATE_BUSINESS_ID' }, request_id: id }), true)
  assert.equal(negotiated({ ok: false, error: { code: 'auth.proof_invalid' }, request_id: id }), true)
  for (const value of [{ ok: true, data: null }, { ok: true, error: { code: 'internal' }, request_id: id },
    { ok: false, error: { code: 'internal', extra: true }, request_id: id }, { ok: true, data: null, request_id: 'PRIVATE_INVALID_ID' }]) {
    assert.equal(negotiated(value), false)
  }
  const visit = (value: unknown): void => {
    if (!value || typeof value !== 'object') return
    const schema = value as { required?: unknown; properties?: Record<string, unknown> }
    if (Array.isArray(schema.required) && schema.required.includes('ok')) assert.ok(schema.properties?.request_id, 'business envelope omitted negotiated representation')
    for (const child of Object.values(value)) visit(child)
  }
  visit(api)
  for (const path of Object.values(api.paths)) {
    assert.equal(path.parameters.filter(parameter => parameter.$ref === '#/components/parameters/ResponseEnvelope').length, 1)
  }
})
