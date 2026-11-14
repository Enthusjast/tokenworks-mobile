import assert from 'node:assert/strict'
import test from 'node:test'
import { createHash } from 'node:crypto'
import { mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { startNativeTestHost } from '../../../tools/dev/native-test-host.js'
import { noiseHttpRequest, pairNoiseClient } from './noise-client.js'

test('explicit private RTC fixture creates an actual 400003-byte workspace file served by the installed native file provider', { timeout: 45_000 }, async t => {
  const directory = await mkdtemp(join(tmpdir(), 'rtc-fixture-config-')), config = join(directory, 'rtc-private.json')
  t.after(() => rm(directory, { recursive: true, force: true }))
  await writeFile(config, JSON.stringify({ enabled: true }), { mode: 0o600, flag: 'wx' })
  const host = await startNativeTestHost({ rtcConfigFile: config, rtcInterfaceAddress: '127.0.0.1' }); t.after(() => host.close())
  const descriptor = host.descriptor, rtc = descriptor.rtc; assert.ok(rtc)
  assert.equal(rtc.enabled, true); assert.equal(rtc.interfaceAddress, '127.0.0.1')
  assert.equal(rtc.largeFile.bytes, 400003)
  assert.equal(rtc.largeFile.path, join(descriptor.artifacts.workspace, 'rtc-large-native.bin'))
  for (const path of [rtc.largeFile.path, rtc.capturePath, rtc.selectedRoutesPath]) assert.equal((await stat(path)).mode & 0o777, 0o600)
  const expected = Buffer.alloc(400003)
  for (let index = 0; index < expected.length; index++) expected[index] = (index * 37 + 11) & 255
  assert.deepEqual(await readFile(rtc.largeFile.path), expected)
  assert.equal(rtc.largeFile.sha256, createHash('sha256').update(expected).digest('hex'))
  const credential = await pairNoiseClient({ origin: descriptor.pairing.origin, ca: descriptor.operatorCaPem, descriptor: descriptor.pairing, owner: descriptor.owner })
  t.after(() => credential.privateKey.fill(0))
  const response = await noiseHttpRequest({ origin: descriptor.pairing.origin, ca: descriptor.operatorCaPem, credential }, 'GET', `/api/file?path=${encodeURIComponent(rtc.largeFile.path)}`)
  assert.equal(response.status, 200); assert.deepEqual(response.body, expected)
  assert.equal(createHash('sha256').update(response.body).digest('hex'), rtc.largeFile.sha256)
})
