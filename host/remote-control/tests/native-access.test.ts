import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { setImmediate as nextTurn } from 'node:timers/promises'
import { NativeAccess, type InspectLease, type NativeLease } from '../src/native-access.js'
import { PRODUCT_OWNER_BINDINGS } from '../src/product-access.generated.js'

const RUNTIME = 'aaaaaaaa-1111-4111-8111-111111111111'
const INSTALL = 'bbbbbbbb-2222-4222-8222-222222222222'
const SECRET = 'A'.repeat(43)

function lease(overrides: Partial<NativeLease> = {}): NativeLease {
  return {
    active: true, sub: 'device-1', aud: RUNTIME, exp: Math.floor(Date.now() / 1000) + 300, client_id: 'test',
    device_id: 'cccccccc-3333-4333-8333-333333333333', runtime_id: RUNTIME, installation_id: INSTALL,
    organization_id: 'ffffffff-6666-4666-8666-666666666666',
    grant_version: 1, role: 'owner', is_installation_owner: true,
    bindings: structuredClone(PRODUCT_OWNER_BINDINGS) as NativeLease['bindings'],
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
    const admission = await access.admitFresh('dddddddd-4444-4444-8444-444444444444', SECRET)
    const peer = peers.at(-1)!
    assert.equal(peer.principal.kind, 'owner')
    assert.equal(peer.interactions, 'answer')
    admission.release()
  } finally { access.close() }
})

test('device mode keeps the restricted principal with native broadcast participation and a policy', async () => {
  const { peers, handle: connection } = connectionStub()
  let registered = 0
  const access = new NativeAccess(connection as never, { registerPolicy: () => { registered++; return () => undefined }, invalidate: () => undefined } as never,
    RUNTIME, INSTALL, async () => ({ ok: true, data: lease() }), () => ({ sessions: [], workspaces: [] }), 'device')
  try {
    const admission = await access.admitFresh('dddddddd-4444-4444-8444-444444444444', SECRET)
    const peer = peers.at(-1)!
    assert.equal(peer.principal.kind, 'tokenworks-device')
    assert.equal(peer.interactions, 'answer')
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
    const admission = await access.admitFresh(id, SECRET)
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
    await assert.rejects(access.admitFresh('not-a-uuid', SECRET), /native lease refused/)
    await assert.rejects(access.admitFresh('dddddddd-4444-4444-8444-444444444444', 'short'), /native lease refused/)
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
    await access.admitFresh(id, SECRET)
    await assert.rejects(access.admitFresh(id, 'B'.repeat(43)), /native lease refused/)
  } finally { access.close() }
})

test('expired leases cannot admit a new connection', async () => {
  const { peers, handle: connection } = connectionStub()
  const access = new NativeAccess(connection as never, accessStub(), RUNTIME, INSTALL,
    async () => ({ ok: true, data: lease({ exp: Math.floor(Date.now() / 1000) - 10 }) }),
    () => ({ sessions: [], workspaces: [] }), 'owner')
  try {
    await assert.rejects(access.admitFresh('dddddddd-4444-4444-8444-444444444444', SECRET), /native lease refused/)
    assert.equal(peers.length, 0)
  } finally { access.close() }
})

for (const facts of [
  { role: 'viewer' as const, is_installation_owner: false },
  { role: 'operator' as const, is_installation_owner: true },
  { role: 'owner' as const, is_installation_owner: false },
]) test(`owner preference cannot escalate ${JSON.stringify(facts)}`, async () => {
  const { peers, handle } = connectionStub()
  let registrations = 0
  const access = new NativeAccess(handle as never,
    { registerPolicy: () => { registrations++; return () => undefined }, invalidate() {} } as never,
    RUNTIME, INSTALL, async () => ({ ok: true, data: lease(facts) }), () => ({ sessions: [], workspaces: [] }), 'owner')
  try {
    const admitted = await access.admitFresh('dddddddd-4444-4444-8444-444444444444', SECRET)
    assert.equal(peers.at(-1)!.principal.kind, 'tokenworks-device')
    assert.equal(registrations, 1)
    admitted.release()
  } finally { access.close() }
})

for (const field of ['role', 'is_installation_owner', 'organization_id'] as const) test(`missing authenticated ${field} never defaults to owner authority`, async () => {
  const { peers, handle } = connectionStub()
  const data: Record<string, unknown> = lease()
  delete data[field]
  const access = new NativeAccess(handle as never, accessStub(), RUNTIME, INSTALL,
    async () => ({ ok: true, data }), () => ({ sessions: [], workspaces: [] }), 'owner')
  try {
    await assert.rejects(access.admitFresh('dddddddd-4444-4444-8444-444444444444', SECRET))
    assert.equal(peers.length, 0)
  } finally { access.close() }
})

for (const changes of [{ role: 'viewer' as const }, { is_installation_owner: false }]) test(`refresh retires an existing peer when ${JSON.stringify(changes)} changes without grant-version bump`, async t => {
  const { peers, handle } = connectionStub()
  let refresh!: () => void
  const interval = globalThis.setInterval
  t.mock.method(globalThis, 'setInterval', (callback: () => void) => {
    refresh = callback
    return interval(callback, 1_000_000)
  })
  let data = lease()
  const access = new NativeAccess(handle as never, accessStub(), RUNTIME, INSTALL,
    async () => ({ ok: true, data }), () => ({ sessions: [], workspaces: [] }), 'owner')
  try {
    await access.admitFresh('dddddddd-4444-4444-8444-444444444444', SECRET)
    data = lease(changes)
    refresh()
    await nextTurn()
    assert.equal(peers.at(-1)!.disposed, true)
  } finally { access.close() }
})

test('an actual owner with a partial grant remains subject to the device policy', async () => {
  const { peers, handle } = connectionStub()
  const access = new NativeAccess(handle as never, accessStub(), RUNTIME, INSTALL,
    async () => ({ ok: true, data: lease({ bindings: [{ resource: { kind: 'session', id: 'only-this-session' }, actions: ['session/follow'] }] }) }),
    () => ({ sessions: [], workspaces: [] }), 'owner')
  try {
    await access.admitFresh('dddddddd-4444-4444-8444-444444444444', SECRET)
    assert.equal(peers.at(-1)!.principal.kind, 'tokenworks-device')
  } finally { access.close() }
})

for (const bindings of [
  [{ resource: { kind: 'global' as const }, actions: ['terminal/attach'] }],
  [{ resource: { kind: 'session' as const }, actions: ['session/unknownFutureMethod'] }],
]) test(`unknown or incorrectly scoped native grants fail closed: ${JSON.stringify(bindings)}`, async () => {
  const { peers, handle } = connectionStub()
  const access = new NativeAccess(handle as never, accessStub(), RUNTIME, INSTALL,
    async () => ({ ok: true, data: lease({ bindings }) }), () => ({ sessions: [], workspaces: [] }), 'owner')
  try {
    await assert.rejects(access.admitFresh('dddddddd-4444-4444-8444-444444444444', SECRET), /native lease refused/)
    assert.equal(peers.length, 0)
  } finally { access.close() }
})

test('every retained admission introspects freshly and shares only an identical live Peer generation', async () => {
  const { peers, handle } = connectionStub(), data = lease({ role: 'viewer', is_installation_owner: false })
  let inspected = 0
  const access = new NativeAccess(handle as never, accessStub(), RUNTIME, INSTALL,
    async () => { inspected++; return { ok: true, data } }, () => ({ sessions: [], workspaces: [] }))
  try {
    const id = 'dddddddd-4444-4444-8444-444444444444'
    const request = await access.admitFresh(id, SECRET), pendingPc = await access.admitFresh(id, SECRET)
    assert.equal(inspected, 2)
    assert.strictEqual(request.peer, pendingPc.peer)
    assert.equal(request.peerGeneration, pendingPc.peerGeneration)
    request.release(); request.release()
    assert.equal(pendingPc.signal.aborted, false)
    assert.equal(peers[0]!.disposed, false)
    const native = await access.admitFresh(id, SECRET)
    pendingPc.release()
    assert.equal(native.signal.aborted, false)
    native.release()
    assert.equal(native.signal.aborted, true)
    assert.equal(peers[0]!.disposed, true)
  } finally { access.close() }
})

for (const changes of [
  { role: 'viewer' as const }, { is_installation_owner: false }, { grant_version: 2 },
  { device_id: 'eeeeeeee-5555-4555-8555-555555555555' }, { sub: 'another-user' },
  { runtime_id: 'eeeeeeee-5555-4555-8555-555555555555' },
  { installation_id: 'eeeeeeee-5555-4555-8555-555555555555' },
  { organization_id: 'eeeeeeee-5555-4555-8555-555555555555' },
  { aud: 'another-runtime' }, { client_id: 'another-client' },
  { exp: Math.floor(Date.now() / 1000) - 1 },
  { bindings: [{ resource: { kind: 'session' as const, id: 'only' }, actions: ['session/follow'] }] },
]) test(`fresh admission fences immutable drift ${JSON.stringify(changes)}`, async () => {
  const { peers, handle } = connectionStub()
  let data = lease()
  const access = new NativeAccess(handle as never, accessStub(), RUNTIME, INSTALL,
    async () => ({ ok: true, data }), () => ({ sessions: [], workspaces: [] }))
  try {
    const id = 'dddddddd-4444-4444-8444-444444444444', admitted = await access.admitFresh(id, SECRET)
    data = { ...data, ...changes }
    await assert.rejects(access.admitFresh(id, SECRET))
    assert.equal(admitted.signal.aborted, true)
    assert.equal(peers[0]!.disposed, true)
    admitted.release()
  } finally { access.close() }
})

for (const withExisting of [false, true]) test(`concurrent stale introspection cannot resurrect a fenced generation (existing=${withExisting})`, async () => {
  const { peers, handle } = connectionStub(), data = lease(), stale = Promise.withResolvers<unknown>()
  let calls = 0
  const access = new NativeAccess(handle as never, accessStub(), RUNTIME, INSTALL, async () => {
    calls++
    if (withExisting && calls === 1) return { ok: true, data }
    if (calls === (withExisting ? 2 : 1)) return stale.promise
    throw new Error('authority revoked')
  }, () => ({ sessions: [], workspaces: [] }))
  try {
    const id = 'dddddddd-4444-4444-8444-444444444444'
    const admitted = withExisting ? await access.admitFresh(id, SECRET) : undefined
    const oldAdmission = access.admitFresh(id, SECRET), refused = assert.rejects(oldAdmission)
    await assert.rejects(access.admitFresh(id, SECRET), /authority revoked/)
    stale.resolve({ ok: true, data })
    await refused
    assert.equal(admitted?.signal.aborted ?? true, true)
    assert.equal(peers.length, withExisting ? 1 : 0)
    admitted?.release()
  } finally { access.close() }
})

test('fresh owner admissions preserve the operator-approved stable paired Peer without owning its lifetime', async () => {
  const { handle } = connectionStub(), data = lease(), owner = handle.createPeer({ principal: { kind: 'owner' } })
  let acquired = 0, disposed = false
  const pairedOwner = { ...owner, async dispose() { disposed = true } }
  const access = new NativeAccess(handle as never, accessStub(), RUNTIME, INSTALL,
    async () => ({ ok: true, data }), () => ({ sessions: [], workspaces: [] }))
  try {
    const id = 'dddddddd-4444-4444-8444-444444444444', factory = () => { acquired++; return pairedOwner as never }
    const first = await access.admitFresh(id, SECRET, factory), second = await access.admitFresh(id, SECRET, factory)
    assert.strictEqual(first.peer, pairedOwner)
    assert.strictEqual(second.peer, pairedOwner)
    assert.equal(acquired, 1)
    first.release(); second.release()
    assert.equal(second.signal.aborted, true)
    assert.equal(disposed, false)
  } finally { access.close() }
})

test('lease reference accounting retains eight RTC signal pins and eight native admissions independently of physical budgets', async () => {
  const { peers, handle } = connectionStub(), data = lease({ role: 'viewer', is_installation_owner: false })
  const access = new NativeAccess(handle as never, accessStub(), RUNTIME, INSTALL,
    async () => ({ ok: true, data }), () => ({ sessions: [], workspaces: [] }))
  try {
    const references = []
    for (let index = 0; index < 16; index++) references.push(await access.admitFresh('dddddddd-4444-4444-8444-444444444444', SECRET))
    assert.equal(peers.length, 1)
    for (const reference of references.slice(0, -1)) reference.release()
    assert.equal(references.at(-1)!.signal.aborted, false)
    references.at(-1)!.release()
    assert.equal(references.at(-1)!.signal.aborted, true)
  } finally { access.close() }
})
