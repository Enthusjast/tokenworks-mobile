import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { jwksCacheTTL } from '../src/jwks-cache.js'

const fixture = JSON.parse(await readFile(new URL('../../../contracts/security/jwks-cache.json', import.meta.url), 'utf8')) as {
  base: string; cases: Array<{ name: string; headers: Record<string, string[]>; startedMs: number; receivedMs: number; readyMs: number; expectedMs: number }>
}
for (const row of fixture.cases) test(`JWKS freshness: ${row.name}`, () => {
  const base = Date.parse(fixture.base), headers = new Headers()
  for (const [name, values] of Object.entries(row.headers)) for (const value of values) headers.append(name, value)
  assert.equal(jwksCacheTTL(headers, { startedWall: base + row.startedMs, receivedWall: base + row.receivedMs, readyWall: base + row.readyMs,
    startedMono: row.startedMs, receivedMono: row.receivedMs, readyMono: row.readyMs }), row.expectedMs)
})
test('JWKS freshness rejects a wall-clock or monotonic reversal independently', () => {
  const time = { startedWall: 100, receivedWall: 101, readyWall: 102, startedMono: 0, receivedMono: 1, readyMono: 2 }
  for (const patch of [{ readyWall: 99 }, { readyMono: 0 }, { receivedMono: -1 }, { readyMono: NaN }]) {
    assert.equal(jwksCacheTTL(new Headers(), { ...time, ...patch }), 0)
  }
})
