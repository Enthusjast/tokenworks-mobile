import test from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { createCipheriv, createDecipheriv, randomBytes, randomUUID } from 'node:crypto'
import { mkdtemp, readFile, readdir, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { host } from './harness.js'
import { LanPairing } from '../src/lan-pairing.js'
import { LanPairingStore, bindingDigest } from '../src/lan-pairing-store.js'
import { RemoteTargetStore, REMOTE_PAIRING_RISK, type RemotePairRequest, type RemoteTargetConfig } from '../src/remote-targets.js'
import { NOISE_SUITE } from '../src/native-noise.js'
import type { JournalKeyProtector } from '../src/journal-key-protection.js'

function protector(master: Buffer): JournalKeyProtector {
  return { id: 'remote-target-test-protector', async wrap(bytes, context) {
    const iv = randomBytes(12), cipher = createCipheriv('aes-256-gcm', master, iv); cipher.setAAD(context)
    return Buffer.concat([iv, cipher.update(bytes), cipher.final(), cipher.getAuthTag()])
  }, async unwrap(bytes, context) {
    const raw = Buffer.from(bytes), cipher = createDecipheriv('aes-256-gcm', master, raw.subarray(0, 12)); cipher.setAAD(context); cipher.setAuthTag(raw.subarray(-16))
    return Buffer.concat([cipher.update(raw.subarray(12, -16)), cipher.final()])
  } }
}
function config(targetId = 'target'): RemoteTargetConfig {
  const origin = 'https://localhost:9443', tlsPin = `sha256/${randomBytes(32).toString('base64')}`
  return { id: targetId, displayName: 'Native target', route: 'direct', origin, tlsPin,
    descriptor: { version: 2, carrierVersion: 1, suite: NOISE_SUITE, minimumEncrypted: true, origin, tlsPin, runtimeId: randomUUID(), installationId: randomUUID(), hostStaticPublicKey: randomBytes(32).toString('base64url'), hostKeyEpoch: 1 },
    allowedAddresses: ['127.0.0.1'], allowLoopback: true, workspaces: [{ workspaceId: 'workspace', canonicalRoot: '/approved' }], allowedActions: ['discover', 'create_session', 'prompt', 'files.read'], allowedAgentPresets: ['approved-preset'],
    allowedPaths: [{ path: '/approved/exact.txt', actions: ['read', 'download'], maxBytes: 4096 }] }
}
const linuxPrivateFiles = { skip: process.platform !== 'linux' && 'requires the Linux private pairing store' }

test('targets default disabled and local caller is protected, source-bound and direct-only', linuxPrivateFiles, async t => {
  const dataDir = await mkdtemp(join(tmpdir(), 'remote-targets-')); t.after(() => rm(dataDir, { recursive: true, force: true }))
  const options = { dataDir, sourceInstallationId: randomUUID(), sourceRuntimeId: randomUUID(), protector: protector(randomBytes(32)) }
  const store = new RemoteTargetStore(options); t.after(() => store.close())
  assert.equal((await store.status()).enabled, false)
  assert.match(REMOTE_PAIRING_RISK, /full native owner/)
  const owner = await store.owner(), target = await store.configure(config())
  assert.notEqual(owner.localCallerId, options.sourceInstallationId)
  assert.equal(owner.kind, 'host-local-outbound'); assert.equal(owner.relayDeviceId, null)
  assert.equal(target.ownerSha256, owner.ownerSha256)
  const reopened = new RemoteTargetStore(options); t.after(() => reopened.close())
  assert.deepEqual(await reopened.owner(), owner)
  const wrongRuntime = new RemoteTargetStore({ ...options, sourceRuntimeId: randomUUID() }); t.after(() => wrongRuntime.close())
  await assert.rejects(wrongRuntime.owner())
  const raw = await readFile(join(dataDir, 'remote-targets', 'local-caller'))
  assert.equal(raw.includes(Buffer.from(owner.localCallerId)), false)
  await assert.rejects(store.configure({ ...config('unsafe'), origin: 'http://localhost:9443' }), /target-config-invalid/)
  await assert.rejects(store.configure({ ...config('unsafe'), allowedAddresses: ['10.0.0.0/8'] }), /target-address-invalid/)
  await assert.rejects(store.configure({ ...config('unsafe'), allowedPaths: [{ path: '/approved/../escape', actions: ['read'], maxBytes: 10 }] }))
})

test('real native pairing persists independent P256/Noise/token intent before POST and reconciles a lost reply without replay', { ...linuxPrivateFiles, timeout: 60_000 }, async t => {
  const f = await host({ probe: false }); t.after(() => f.close())
  const master = randomBytes(32), protection = protector(master), sourceDir = join(f.directory, 'source')
  const certPath = join(f.directory, 'target-cert.pem'), keyPath = join(f.directory, 'target-key.pem')
  execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', keyPath, '-out', certPath, '-days', '1', '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost'], { stdio: 'ignore' })
  const installationId = randomUUID(), pairing = await LanPairing.open({ origin: 'https://localhost:9443', runtimeId: f.runtimeId, installationId }, await readFile(certPath, 'utf8'), new LanPairingStore(join(f.directory, 'target-pairs'), f.runtimeId, installationId, protection), f.ctx.connection)
  t.after(() => pairing.close())
  const options = { dataDir: sourceDir, sourceInstallationId: randomUUID(), sourceRuntimeId: randomUUID(), protector: protection }
  let store = new RemoteTargetStore(options); t.after(() => store.close())
  const offer = pairing.begin(), { code: _code, expiresAt: _expiresAt, ...descriptor } = offer
  await store.configure({ ...config(), origin: descriptor.origin, tlsPin: descriptor.tlsPin, descriptor })
  let posts = 0, loseReply = true
  const request: RemotePairRequest = async (_target, request, signal) => {
    signal.throwIfAborted()
    if (request.method === 'POST') {
      posts++
      const diskStore = new RemoteTargetStore(options)
      try { const pending = await diskStore.target(_target.id); assert.equal(pending.status, 'pair-pending'); assert.ok(pending.credentialRef) } finally { diskStore.close() }
      const body = JSON.parse(Buffer.from(request.body!).toString('utf8'))
      const paired = await pairing.pair(body.code, body.deviceName, request.headers.dpop!, body.deviceId, body.token, body.manifest)
      if (loseReply) { loseReply = false; throw new Error('fixture lost response') }
      return { status: 201, body: Buffer.from(JSON.stringify(paired)) }
    }
    const paired = await pairing.verifyCredential(request.headers['x-tokenworks-device-id']!, request.headers.authorization!, request.headers.dpop!, 'GET', '/pair/identity')
    return { status: 200, body: Buffer.from(JSON.stringify(pairing.identityFor(paired))) }
  }
  await assert.rejects(store.pair('target', offer, request, new AbortController().signal), /lost response/)
  assert.equal(posts, 1)
  store.close(); store = new RemoteTargetStore(options)
  await assert.rejects(store.pair('target', offer, request, new AbortController().signal), /pair-reconcile-required/)
  const paired = await store.reconcilePair('target', request, new AbortController().signal)
  assert.equal(paired.status, 'paired'); assert.equal(posts, 1)
  await assert.rejects(store.credential('target', new AbortController().signal), /target-fenced/)
  await store.setEnabled(true)
  const first = await store.credential('target', new AbortController().signal)
  assert.equal(first.manifest.relay, undefined)
  assert.equal(first.bindingSha256, bindingDigest(first.manifest))
  assert.notEqual(first.manifest.owner.device_id, options.sourceInstallationId)
  const nextOffer = pairing.begin(), { code: _code2, expiresAt: _expiresAt2, ...nextDescriptor } = nextOffer
  await store.configure({ ...config('second'), origin: nextDescriptor.origin, tlsPin: nextDescriptor.tlsPin, descriptor: nextDescriptor })
  await store.pair('second', nextOffer, request, new AbortController().signal)
  const second = await store.credential('second', new AbortController().signal)
  assert.notEqual(first.manifest.proofJkt, second.manifest.proofJkt)
  assert.notEqual(first.manifest.deviceStaticPublicKey, second.manifest.deviceStaticPublicKey)
  assert.notEqual(first.token, second.token); assert.notEqual(first.pairId, second.pairId)
  second.close()
  await assert.rejects(store.bindSession('target', { sessionId: 'native-session', workspaceId: 'workspace', sourceAgentId: 'actual-source-agent' }, paired.authorizationEpoch + 1), /target-fenced/)
  await store.bindSession('target', { sessionId: 'native-session', workspaceId: 'workspace', sourceAgentId: 'actual-source-agent' }, paired.authorizationEpoch)
  assert.equal((await store.assertSession('target', 'native-session', 'actual-source-agent')).workspaceId, 'workspace')
  await assert.rejects(store.assertSession('target', 'native-session', 'other-agent'), /session-denied/)
  const before = await store.target('target'), rotated = await store.rotate('target')
  assert.equal(rotated.authorizationEpoch, before.authorizationEpoch + 1); assert.equal(rotated.status, 're-pair-required')
  await assert.rejects(store.assertCurrent(before), /target-fenced/)
  first.close(); assert.equal(first.localPrivate.every(byte => byte === 0), true)
  const reopened = new RemoteTargetStore(options); t.after(() => reopened.close())
  assert.equal((await reopened.target('target')).status, 're-pair-required')
  await assert.rejects(reopened.credential('target', new AbortController().signal), /target-fenced/)
  const secondBeforeRevoke = await reopened.target('second'), revoked = await reopened.revoke('second')
  assert.equal(revoked.status, 'revoked'); assert.equal(revoked.authorizationEpoch, secondBeforeRevoke.authorizationEpoch + 1)
  await assert.rejects(reopened.assertCurrent(secondBeforeRevoke), /target-fenced/)
  for (const name of await readdir(join(sourceDir, 'remote-targets'))) {
    const bytes = await readFile(join(sourceDir, 'remote-targets', name))
    assert.equal(bytes.includes(Buffer.from(first.token)), false)
  }
})
