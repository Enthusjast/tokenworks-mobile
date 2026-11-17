import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile, stat } from 'node:fs/promises'
import { dirname } from 'node:path'
import { z } from 'zod'
import { parseRemoteEventResult, parseRemoteStreamClientMessage, parseRemoteStreamServerMessage } from '@deepseek-ai/dsh-api-gateway/stream-protocol'
import { assertNativePackages, carrier, nativeCodec, nativeCompatibility, nativeMethods, nativeRoundTrip, nativeVersion, uncarry, type NativeSide } from '../../../tools/native-contract.js'
import { captureNativeFixture, checkNativeCapture, type NativeCapture } from '../../../tools/capture-native-golden.js'
import type { NativeVector } from '../../../tools/native-golden-vectors.js'
import type { WireVector } from '../../../tools/native-wire-vectors.js'

const golden = new URL('../../../contracts/typert/golden/', import.meta.url)
const vectors = (await readFile(new URL('native-vectors.jsonl', golden), 'utf8')).trimEnd().split('\n').map(line => JSON.parse(line) as NativeVector)
const wire = JSON.parse(await readFile(new URL('wire-vectors.json', golden), 'utf8')) as { vectors: WireVector[] }

test('golden coverage includes the input and result of every installed selected native method', () => {
  assertNativePackages()
  assert.equal(nativeMethods.length, 38)
  const covered = new Set(vectors.map(vector => `${vector.endpoint}:${vector.side}`))
  assert.deepEqual([...covered].sort(), nativeMethods.flatMap(([endpoint, method]) => [`${endpoint}:input`, `${endpoint}:result`, ...(method.uplink ? [`${endpoint}:uplink`] : [])]).sort())
  assert.ok(vectors.every(vector => vector.source === 'schema-generated'))
})

for (const [endpoint, method] of nativeMethods) for (const side of (method.uplink ? ['input', 'result', 'uplink'] : ['input', 'result']) as NativeSide[]) {
  test(`native codecs agree with shared golden ${endpoint} ${side}`, () => {
    const selected = vectors.filter(vector => vector.endpoint === endpoint && vector.side === side)
    const codec = nativeCodec(endpoint, side)
    assert.ok(selected.some(vector => vector.accepted))
    assert.ok(selected.some(vector => !vector.accepted))
    for (const vector of selected) {
      const raw = uncarry(vector.value)
      if (vector.accepted) {
        assert.deepEqual(nativeRoundTrip(endpoint, side, vector.value), vector.expected, vector.id)
        assert.deepEqual(carrier(z.encode(codec, raw)), vector.expected, `${vector.id}: encode`)
      } else {
        assert.throws(() => z.decode(codec, raw), `${vector.id}: decode`)
        assert.throws(() => z.encode(codec, raw), `${vector.id}: encode`)
      }
    }
  })
}

test('installed stream and event-result wire parsers agree, preserving missing values and explicit null', () => {
  for (const vector of wire.vectors) {
    const run = () => vector.side === 'client' ? parseRemoteStreamClientMessage(JSON.stringify(vector.value))
      : vector.side === 'server' ? parseRemoteStreamServerMessage(JSON.stringify(vector.value)) : parseRemoteEventResult(vector.value)
    if (vector.accepted) assert.deepEqual(run(), vector.expected, vector.id)
    else assert.throws(run, vector.id)
  }
  for (const side of ['client', 'server', 'event-result']) {
    assert.ok(wire.vectors.some(vector => vector.side === side && vector.accepted))
    assert.ok(wire.vectors.some(vector => vector.side === side && !vector.accepted))
  }
})

test('compatibility decision accepts the supported SemVer family and rejects unknown runtime versions', async () => {
  const manifest = JSON.parse(await readFile(new URL('compatibility.json', golden), 'utf8')) as {
    dshVersion: string; endpoints: string[]; cases: { advertisedVersion: unknown; decision: string }[]
  }
  assert.equal(manifest.dshVersion, nativeVersion)
  assert.deepEqual(manifest.endpoints, nativeMethods.map(([endpoint]) => endpoint))
  for (const vector of manifest.cases) assert.equal(nativeCompatibility(vector.advertisedVersion), vector.decision)
  assert.equal(nativeCompatibility(undefined), 'unsupported-version')
})

test('native numeric codecs retain every finite IEEE-754 number and reject nonfinite runtime values', () => {
  for (const value of [Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY]) {
    assert.throws(() => nativeRoundTrip('terminal/create', 'input', carrier({ agentId: 'fixture', request: { id: 'fixture', cols: value, rows: 24 } })))
  }
  for (const value of [Number.MIN_VALUE, -Number.MIN_VALUE, Number.MAX_VALUE, -Number.MAX_VALUE, Number.MAX_SAFE_INTEGER + 1, 0.5]) {
    const input = carrier({ agentId: 'fixture', request: { id: 'fixture', cols: value, rows: 24 } })
    assert.deepEqual(nativeRoundTrip('terminal/create', 'input', input), input)
  }
})

test('recorded installed-native fixture calls are separate from schema-generated coverage', async () => {
  const capture = JSON.parse(await readFile(new URL('native-captured.json', golden), 'utf8')) as NativeCapture
  checkNativeCapture(capture)
  assert.equal(new Set(capture.calls.map(call => call.endpoint)).size, 38)
  assert.equal(capture.notCaptured.length, 0)
  assert.equal(capture.execution.networkAttempts, 0)
  assert.ok(capture.calls.some(call => call.endpoint === 'userQuestions/answer' && call.results.some(result => result.present && result.value === true)))
  assert.deepEqual(capture.calls.filter(call => call.streaming).map(call => call.endpoint).sort(), nativeMethods.filter(([, method]) => method.mode === 'stream').map(([endpoint]) => endpoint).sort())
  for (const call of capture.calls) for (const result of call.results) assert.deepEqual(nativeRoundTrip(call.endpoint, 'result', result), result)
})

test('fresh isolated native providers execute the recorded endpoint matrix without user state', async () => {
  const capture = await captureNativeFixture()
  checkNativeCapture(capture)
  assert.equal(new Set(capture.calls.map(call => call.endpoint)).size, 38)
  assert.equal(capture.notCaptured.length, 0)
  assert.ok(capture.calls.some(call => call.endpoint === 'workspace/initializeDefault' && call.results.some(result => !result.present)))
  assert.ok(capture.calls.some(call => call.endpoint === 'session/projections' && call.results.some(result => result.present && result.value === null)))
  assert.ok(!capture.execution.environmentKeys.some(key => /TOKEN|PASSWORD|SECRET|API_KEY|SSH|PROFILE/.test(key)))
  const directoryCall = capture.calls.find(call => call.endpoint === 'workspace/create')!
  assert.ok(directoryCall.input.present)
  const value = directoryCall.input.value
  assert.ok(value && typeof value === 'object' && 'request' in value)
  assert.ok(value.request && typeof value.request === 'object' && 'path' in value.request)
  assert.equal(typeof value.request.path, 'string')
  const root = dirname(value.request.path as string)
  await assert.rejects(stat(root), { code: 'ENOENT' })
})
