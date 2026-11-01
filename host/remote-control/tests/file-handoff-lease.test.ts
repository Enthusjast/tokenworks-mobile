import test from 'node:test'
import assert from 'node:assert/strict'
import { FileOperationLease } from '../src/file-operation-lease.js'

const base = Date.UTC(2026, 10, 4, 12)
const at = (ms: number) => new Date(base + ms).toISOString()
function clock() {
  const state = { wall: base, mono: 0 }
  return { state, source: { wall: () => state.wall, monotonic: () => state.mono } }
}

test('file handoff expiry is terminal and cannot be revived by a late renewal', () => {
  for (const advance of ['wall', 'mono'] as const) {
    const { state, source } = clock(), lease = new FileOperationLease(at(1000), at(60_000), source)
    assert.equal(lease.current(), true)
    if (advance === 'wall') state.wall += 901; else state.mono += 901
    assert.equal(lease.renew(at(5000), at(60_000)), false)
    state.wall = base; state.mono = 0
    assert.equal(lease.current(), false)
    assert.equal(lease.renew(at(5000), at(60_000)), false)
  }
})

test('file handoff renewal cannot move the initial hard deadline, even with a frozen wall clock', () => {
  const { state, source } = clock(), lease = new FileOperationLease(at(5000), at(8000), source)
  state.mono = 4000
  assert.equal(lease.renew(at(5000), at(8000)), true)
  state.mono = 7000
  assert.equal(lease.renew(at(5000), at(8000)), true)
  state.mono = 7900
  assert.equal(lease.current(), false)
  assert.equal(lease.renew(at(5000), at(8000)), false)
})

test('file handoff observes clock reversals after renewal and never reopens after recovery', () => {
  for (const field of ['wall', 'mono'] as const) {
    const { state, source } = clock(), lease = new FileOperationLease(at(5000), at(60_000), source)
    state.wall += 1000; state.mono += 1000
    assert.equal(lease.renew(at(6000), at(60_000)), true)
    state[field]--
    assert.equal(lease.current(), false)
    state.wall += 1000; state.mono += 1000
    assert.equal(lease.current(), false)
  }
})

test('file handoff rejects oversized or malformed authority and renewal cannot change hard expiry', () => {
  const { state, source } = clock()
  for (const [until, expiry] of [[at(5501), at(60_000)], [at(5000), at(330_001)], [at(5000), at(4000)], ['invalid', at(60_000)]]) {
    assert.throws(() => new FileOperationLease(until!, expiry!, source), /file.stale/)
  }
  for (const [until, expiry] of [[at(5501), at(60_000)], [at(5000), at(60_001)]]) {
    assert.throws(() => new FileOperationLease(at(5000), at(60_000), source).renew(until!, expiry!), /protocol.invalid/)
  }
  const capped = new FileOperationLease(at(5500), at(330_000), source)
  state.mono = 4900
  assert.equal(capped.current(), false, 'server clock skew must not extend the local five-second lease')
  for (const field of ['wall', 'mono'] as const) {
    const current = clock(), lease = new FileOperationLease(at(5000), at(60_000), current.source)
    current.state[field] = NaN
    assert.equal(lease.current(), false)
    assert.throws(() => new FileOperationLease(at(5000), at(60_000), current.source), /file.stale/)
  }
})
