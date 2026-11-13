/** Authenticate one local fixture phone against the native relay path; no model prompt or tool execution. */
import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { state, phoneClient } from './fixture-host.mjs'

async function main() {
  const runtime = JSON.parse(await readFile(resolve(state, 'enrolled-runtime.json'), 'utf8'))
  assert.equal(runtime.purpose, 'tokenworks-mobile-local-enrolled-runtime/v1')
  const phone = await phoneClient()
  const device = await phone.request('GET', '/v1/devices/current')
  assert.equal(device.state, 'active')
  const directory = await phone.request('GET', '/v1/runtimes')
  const visible = directory.items.find(item => item.id === runtime.runtimeId)
  assert.ok(visible, 'enrolled runtime is not visible to this phone')
  assert.equal(visible.access.role, 'owner')

  // A lease is the only credential the relay accepts for the native tunnel.
  const lease = await phone.request('POST', `/v2/runtimes/${runtime.runtimeId}/leases`, {}, 201)
  assert.equal(lease.token_type, 'Bearer')
  assert.equal(lease.expires_in, 300)
  assert.match(lease.access_token, /^[A-Za-z0-9_-]{43}$/)

  const listed = await phone.native('session/list', { _request: {} }, runtime.runtimeId, lease.access_token)
  assert.equal(listed.ok, true, `session/list rejected: ${JSON.stringify(listed.error ?? null)}`)
  assert.ok(Array.isArray(listed.value?.items), 'session/list returned no items')

  const catalog = await phone.native('session/modelCatalog', {}, runtime.runtimeId, lease.access_token)
  assert.equal(catalog.ok, true, `session/modelCatalog rejected: ${JSON.stringify(catalog.error ?? null)}`)
  assert.ok(Array.isArray(catalog.value?.groups), 'session/modelCatalog returned no groups')

  const proof = { runtime_id: runtime.runtimeId, device_id: device.device.device_id, lease_id: lease.lease_id,
    lease_ttl_seconds: lease.expires_in, native_session_list: true, native_model_catalog: true,
    session_count: listed.value.items.length, model_groups: catalog.value.groups.length }
  await writeFile(resolve(state, 'relay-smoke-proof.json'), JSON.stringify(proof, null, 2) + '\n', { mode: 0o600 })
  console.log(JSON.stringify(proof))
}
main().catch(error => { console.error('local development relay smoke failed; no operation was replayed:', error?.message ?? error); process.exitCode = 1 })
