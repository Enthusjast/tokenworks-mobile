import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { NativeAccess, type InspectLease, type NativeLease } from '../src/native-access.js'

const RUNTIME = 'aaaaaaaa-1111-4111-8111-111111111111'
const INSTALL = 'bbbbbbbb-2222-4222-8222-222222222222'
const SECRET = 'A'.repeat(43)

function lease(overrides: Partial<NativeLease> = {}): NativeLease {
  return {
    active: true, sub: 'device-1', aud: RUNTIME, exp: Math.floor(Date.now() / 1000) + 300, client_id: 'test',
    device_id: 'cccccccc-3333-4333-8333-333333333333', runtime_id: RUNTIME, installation_id: INSTALL,
    grant_version: 1,
    bindings: [{ resource: { kind: 'session' }, actions: ['session/list', 'session/follow', 'session/prompt'] }],
    ...overrides,
  }
}

interface PeerRecord { principal: { kind: string }; interactions: string; disposed: boolean }
function connectionStub() {
  const peers: PeerRecord[] = []
  const handle = {
    createPeer(options: { principal: { kind: string }; interactions?: string }) {
      const record: PeerRecord = { principal: options.principal, interactions: options.interactions ?? 'none', disposed: false }
      peers.push(record)
      return { principal: options.principal, signal: new AbortController().signal, async dispose() { record.disposed = true } }
    },
  }
  return { peers, handle }
}

function accessStub() {
  return { registerPolicy: () => () => undefined, invalidate: () => undefined } as never
}

test('owner mode creates an owner peer with answer interactions', async () => {
  const { peers, handle: connection } = connectionStub()
  const inspect: InspectLease = async () => ({ ok: true, data: lease() })
  const access = new NativeAccess(connection as never, accessStub(), RUNTIME, INSTALL, inspect, () => ({ sessions: ['s1'], workspaces: [] }), 'owner')
  try {
    const admission = await access.admit('dddddddd-4444-4444-8444-444444444444', SECRET)
    const peer = peers.at(-1)!
    assert.equal(peer.principal.kind, 'owner')
    assert.equal(peer.interactions, 'answer')
    admission.release()
  } finally { access.close() }
})

test('device mode keeps the device principal with none interactions and a policy', async () => {
  const { peers, handle: connection } = connectionStub()
  let registered = 0
  const access = new NativeAccess(connection as never, { registerPolicy: () => { registered++; return () => undefined }, invalidate: () => undefined } as never,
    RUNTIME, INSTALL, async () => ({ ok: true, data: lease() }), () => ({ sessions: [], workspaces: [] }), 'device')
  try {
    const admission = await access.admit('dddddddd-4444-4444-8444-444444444444', SECRET)
    const peer = peers.at(-1)!
    assert.equal(peer.principal.kind, 'tokenworks-device')
    assert.equal(peer.interactions, 'none')
    assert.equal(registered, 1)
    admission.release()
  } finally { access.close() }
})

test('revoked or expired leases dispose the peer', async () => {
  const { peers, handle: connection } = connectionStub()
  const inspect: InspectLease = async () => ({ ok: true, data: lease({ exp: Math.floor(Date.now() / 1000) + 300 }) })
  const access = new NativeAccess(connection as never, accessStub(), RUNTIME, INSTALL, inspect, () => ({ sessions: [], workspaces: [] }), 'owner')
  try {
    const id = 'dddddddd-4444-4444-8444-444444444444'
    const admission = await access.admit(id, SECRET)
    const peer = peers.at(-1)!
    assert.equal(peer.disposed, false)
    // Releasing the only connection removes the entry and disposes the peer:
    // the paired device loses its surface at once (revocation trigger).
    admission.release()
    await new Promise(resolve => setImmediate(resolve))
    assert.equal(peer.disposed, true)
  } finally { access.close() }
})

test('admission refuses malformed lease ids and secrets without contacting the inspector', async () => {
  const { peers, handle: connection } = connectionStub()
  let inspected = 0
  const access = new NativeAccess(connection as never, accessStub(), RUNTIME, INSTALL,
    async () => { inspected++; return { ok: true, data: lease() } }, () => ({ sessions: [], workspaces: [] }), 'owner')
  try {
    await assert.rejects(access.admit('not-a-uuid', SECRET), /native lease refused/)
    await assert.rejects(access.admit('dddddddd-4444-4444-8444-444444444444', 'short'), /native lease refused/)
    assert.equal(inspected, 0)
    assert.equal(peers.length, 0)
  } finally { access.close() }
})

test('the same lease with a different secret is refused', async () => {
  const { peers, handle: connection } = connectionStub()
  const access = new NativeAccess(connection as never, accessStub(), RUNTIME, INSTALL,
    async () => ({ ok: true, data: lease() }), () => ({ sessions: [], workspaces: [] }), 'owner')
  try {
    const id = 'dddddddd-4444-4444-8444-444444444444'
    await access.admit(id, SECRET)
    await assert.rejects(access.admit(id, 'B'.repeat(43)), /native lease refused/)
  } finally { access.close() }
})

test('expired leases cannot admit a new connection', async () => {
  const { peers, handle: connection } = connectionStub()
  const access = new NativeAccess(connection as never, accessStub(), RUNTIME, INSTALL,
    async () => ({ ok: true, data: lease({ exp: Math.floor(Date.now() / 1000) - 10 }) }),
    () => ({ sessions: [], workspaces: [] }), 'owner')
  try {
    await assert.rejects(access.admit('dddddddd-4444-4444-8444-444444444444', SECRET), /native lease refused/)
    assert.equal(peers.length, 0)
  } finally { access.close() }
})
