import assert from 'node:assert/strict'
import { test } from 'node:test'
import { generateKeyPairSync, randomUUID, sign } from 'node:crypto'
import { setImmediate as nextTurn } from 'node:timers/promises'
import { RelayTunnel, type NativeTunnelOptions } from '../src/relay-tunnel.js'
import type { HostConnectionCredential } from '../src/host-connection-credential.js'
import type { NativeLease } from '../src/native-access.js'

for (const ending of ['owner-abort', 'changed-installation', 'credential-refused', 'host-close'] as const) test(`Host-lifetime authority survives reverse tunnel unavailability and fences ${ending}`, async t => {
  const installationId = randomUUID(), runtimeId = randomUUID(), owner = new AbortController()
  const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' })
  const credential: HostConnectionCredential = { installationId, credential: 'H'.repeat(43),
    publicJwk: publicKey.export({ format: 'jwk' }) as HostConnectionCredential['publicJwk'],
    signer: { sign: bytes => sign('sha256', bytes, { key: privateKey, dsaEncoding: 'ieee-p1363' }) } }
  let acquired = 0, changed = false, refused = false, inspected = 0, unregisters = 0
  const peers: { disposed: boolean }[] = []
  // Repository-only introspection fixture facts, not a SQL/service-state proof.
  // The reverse tunnel intentionally cannot acquire its initial credential.
  const data: NativeLease = { active: true, sub: randomUUID(), aud: runtimeId, client_id: 'test',
    exp: Math.floor(Date.now() / 1000) + 300, device_id: randomUUID(), runtime_id: runtimeId,
    installation_id: installationId, organization_id: randomUUID(), grant_version: 1,
    role: 'viewer', is_installation_owner: false,
    bindings: [{ resource: { kind: 'session', id: 'permitted' }, actions: ['session/follow'] }] }
  t.mock.method(globalThis, 'fetch', async (input: string | URL | Request, init?: RequestInit) => {
    inspected++
    assert.equal(String(input), `https://relay.invalid/v2/tunnel-leases/${id}`)
    assert.equal((init?.headers as Record<string, string>).Authorization, `DPoP ${credential.credential}`)
    assert.equal((init?.headers as Record<string, string>)['Proxy-Authorization'], `Bearer ${secret}`)
    return new Response(JSON.stringify({ ok: true, data }), { headers: { 'content-type': 'application/json' } })
  })
  const options: NativeTunnelOptions = { origin: 'https://relay.invalid', runtimeId, installationId,
    versions: { dsh: 'test', application: 'test', plugin: 'test' },
    credentials: { origin: 'https://relay.invalid', signal: owner.signal, async acquire(signal) {
      signal.throwIfAborted()
      acquired++
      if (acquired === 1) throw new Error('test/reverse-unavailable')
      if (refused) throw new Error('test/credential-refused')
      return changed ? { ...credential, installationId: randomUUID() } : credential
    } },
    connection: { createPeer({ principal }: { principal: object }) {
      const record = { disposed: false }, cancel = new AbortController(); peers.push(record)
      return { principal, signal: cancel.signal, async dispose() { record.disposed = true; cancel.abort() } }
    } } as never,
    remoteAccess: { registerPolicy: () => () => { unregisters++ }, invalidate() {} } as never,
    gateway: {} as never, carrier: {} as never, resources: () => ({ sessions: ['permitted'], workspaces: [] }) }
  const tunnel = new RelayTunnel(options)
  t.after(() => tunnel.close())
  const id = randomUUID(), secret = 'S'.repeat(43)
  await nextTurn()
  assert.equal(tunnel.status(), 'offline')
  const first = await tunnel.admitFresh(id, secret), retained = await tunnel.admitFresh(id, secret)
  assert.strictEqual(first.peer, retained.peer)
  assert.equal(inspected, 2)
  assert.equal(acquired, 3, 'every introspection acquires current scoped Host credentials')
  first.release()
  assert.equal(retained.signal.aborted, false, 'a retained admission is not an HTTP request lifetime')
  if (ending === 'owner-abort') owner.abort()
  else if (ending === 'changed-installation') { changed = true; await assert.rejects(tunnel.admitFresh(id, secret), /installation changed/) }
  else if (ending === 'credential-refused') { refused = true; await assert.rejects(tunnel.admitFresh(id, secret), /test\/credential-refused/) }
  else await tunnel.close()
  assert.equal(retained.signal.aborted, true)
  assert.equal(peers[0]!.disposed, true)
  retained.release()
  await tunnel.close()
  assert.equal(unregisters, 1)
})
