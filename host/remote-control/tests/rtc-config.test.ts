import assert from 'node:assert/strict'
import test from 'node:test'
import { chmod, mkdtemp, rm, symlink, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { createHmac } from 'node:crypto'
import { loadRtcConfig, rtcConfigSchema, rtcCredentials, rtcPeerConfig } from '../src/rtc-config.js'

test('RTC is operator opt-in, never implicitly contacts public STUN, and P2P excludes TURN', async () => {
  const disabled = await loadRtcConfig()
  assert.equal(disabled.enabled, false)
  assert.throws(() => rtcPeerConfig(disabled, 'p2p', 'pair'), /unsupported/)
  const config = rtcConfigSchema.parse({ enabled: true, stunUrls: ['stun:127.0.0.1:3478'], turn: { urls: ['turn:127.0.0.1:3478?transport=udp'], sharedSecret: 's'.repeat(32) } })
  const p2p = rtcPeerConfig(config, 'p2p', 'pair')
  assert.equal(p2p.iceUseDefaultStunServer, false)
  assert.deepEqual(p2p.iceServers, [{ urls: 'stun:127.0.0.1:3478' }])
  const relay = rtcPeerConfig(config, 'turn', 'pair')
  assert.equal(relay.iceTransportPolicy, 'relay')
  assert.ok(relay.iceServers!.every(server => !String(server.urls).includes('stun:')))
  const credential = rtcCredentials(config, 'pair', 1_000_000)
  assert.equal(credential.turnServers[0]!.username, '1300:pair')
  assert.equal(credential.turnServers[0]!.credential, createHmac('sha1', 's'.repeat(32)).update('1300:pair').digest('base64'))
  assert.equal(credential.expiresAt, 1_300_000)
  assert.throws(() => rtcConfigSchema.parse({ enabled: true, stunUrls: ['https://undeclared.invalid'] }))
  assert.throws(() => rtcConfigSchema.parse({ turn: { urls: ['turns:localhost'], sharedSecret: 's'.repeat(32) } }))
  assert.throws(() => rtcConfigSchema.parse({ stunUrls: ['stun:turn.example.test:65536'] }))
  assert.throws(() => rtcConfigSchema.parse({ turn: { urls: ['turn:turn.example.test:0?transport=udp'], sharedSecret: 's'.repeat(32) } }))
  assert.doesNotThrow(() => rtcConfigSchema.parse({ stunUrls: ['stun:[2001:db8::1]', 'stun:[2001:db8::1]:65535'] }))
})

test('RTC secrets require a bounded owner-only regular file and refuse symlinks', async t => {
  const directory = await mkdtemp(join(tmpdir(), 'rtc-private-')); t.after(() => rm(directory, { recursive: true, force: true }))
  const path = join(directory, 'rtc.json'), alias = join(directory, 'alias')
  await writeFile(path, JSON.stringify({ enabled: true }), { mode: 0o600 })
  assert.equal((await loadRtcConfig(path)).enabled, true)
  await symlink(path, alias); await assert.rejects(loadRtcConfig(alias))
  await chmod(path, 0o644); await assert.rejects(loadRtcConfig(path), /invalid-private-config/)
  await chmod(path, 0o600); await writeFile(path, ' '.repeat(8193)); await assert.rejects(loadRtcConfig(path), /invalid-private-config/)
})
