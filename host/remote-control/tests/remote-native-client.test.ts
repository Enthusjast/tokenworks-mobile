import assert from 'node:assert/strict'
import test, { type TestContext } from 'node:test'
import { createCipheriv, createDecipheriv, createHash, generateKeyPairSync, randomBytes, randomUUID, sign } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { connect as connectSocket, createServer, type Socket } from 'node:net'
import { once } from 'node:events'
import { Duplex } from 'node:stream'
import { calculateJwkThumbprint } from 'jose'
import { createIsolatedNativeAssembly } from '../../../tools/capture-native-golden.js'
import { LanPairing } from '../src/lan-pairing.js'
import { LanPairingStore, bindingDigest, manifestSchema } from '../src/lan-pairing-store.js'
import { NativeGateway } from '../src/native-gateway.js'
import { NoiseCarrier } from '../src/noise-carrier.js'
import { LanGateway } from '../src/lan-gateway.js'
import { generateNoiseStatic, NativeNoiseSession, NOISE_SUITE } from '../src/native-noise.js'
import { canonicalObject, parseSigningObject } from '../src/canonical.js'
import { connectRemoteNative, nativeFileVersionPath } from '../src/remote-native-client.js'
import { dialNoiseCarrier } from '../src/remote-noise-initiator.js'
import { assertRemoteAddress, dialRemoteOuter, pinnedRequest, remoteOrigin, signPairingProof } from '../src/remote-network.js'
import type { InstallationPublicJwk } from '../src/host-connection-credential.js'
import type { JournalKeyProtector } from '../src/journal-key-protection.js'
import type { RemoteTarget, RemoteCredential } from '../src/remote-targets.js'
import type { SessionPromptRequest } from '@deepseek-ai/dsh-api-session-controller/types'

const linuxPairing = { skip: process.platform !== 'linux' && 'requires the Linux private pairing store', timeout: 90_000 }

async function fixture(t: TestContext) {
  const root = await mkdtemp(join(tmpdir(), 'remote-native-client-')), native = await createIsolatedNativeAssembly(root)
  const certPath = join(root, 'tls.pem'), keyPath = join(root, 'tls-key.pem')
  execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', keyPath, '-out', certPath, '-days', '1', '-subj', '/CN=localhost', '-addext', 'subjectAltName=IP:127.0.0.1,DNS:localhost'], { stdio: 'ignore' })
  const cert = await readFile(certPath, 'utf8'), key = await readFile(keyPath, 'utf8'), reserve = createServer()
  reserve.listen(0, '127.0.0.1'); await once(reserve, 'listening')
  const address = reserve.address(); assert.ok(address && typeof address === 'object')
  const reserved = once(reserve, 'close'); reserve.close(); await reserved
  const origin = `https://127.0.0.1:${address.port}`, runtimeId = randomUUID(), installationId = randomUUID(), master = randomBytes(32)
  const protector: JournalKeyProtector = { id: 'ephemeral-real-test-aes', async wrap(bytes, context) {
    const nonce = randomBytes(12), cipher = createCipheriv('aes-256-gcm', master, nonce); cipher.setAAD(context)
    return Buffer.concat([nonce, cipher.update(bytes), cipher.final(), cipher.getAuthTag()])
  }, async unwrap(bytes, context) {
    const input = Buffer.from(bytes), cipher = createDecipheriv('aes-256-gcm', master, input.subarray(0, 12)); cipher.setAAD(context); cipher.setAuthTag(input.subarray(-16))
    return Buffer.concat([cipher.update(input.subarray(12, -16)), cipher.final()])
  } }
  const pairing = await LanPairing.open({ origin, runtimeId, installationId }, cert, new LanPairingStore(join(root, 'pairs'), runtimeId, installationId, protector), native.ctx.connection)
  const gateway = new NativeGateway(native.ctx.connection, native.ctx.typertGateway), carrier = new NoiseCarrier(pairing, gateway, '0.2.2-rc.1', origin)
  const lan = new LanGateway({ host: '127.0.0.1', port: address.port, connection: native.ctx.connection, gateway, carrier, pairing, certificate: { cert, key } })
  await lan.listen()
  t.after(async () => { carrier.close(); pairing.close(); gateway.close(); await lan.close(); await native.peer.dispose(); await native.ctx.fiber.dispose(); master.fill(0); await rm(root, { recursive: true, force: true }) })
  const descriptor = pairing.begin(), { code, expiresAt: _expiresAt, ...expected } = descriptor
  const keys = generateNoiseStatic(), proof = generateKeyPairSync('ec', { namedCurve: 'prime256v1' }), publicJwk = proof.publicKey.export({ format: 'jwk' }) as InstallationPublicJwk
  const token = randomBytes(32).toString('base64url'), pairId = randomUUID()
  const owner = { account_id: randomUUID(), organization_id: randomUUID(), deployment_sha256: createHash('sha256').update(randomBytes(32)).digest('hex'), user_id: randomUUID(), device_id: randomUUID() }
  const manifest = manifestSchema.parse({ v: 2, runtimeId, installationId, pairId, tokenSha256: createHash('sha256').update(token).digest('base64url'), proofJkt: await calculateJwkThumbprint(publicJwk),
    deviceStaticPublicKey: Buffer.from(keys.publicKey).toString('base64url'), deviceKeyEpoch: 1, hostStaticPublicKey: expected.hostStaticPublicKey, hostKeyEpoch: expected.hostKeyEpoch, owner })
  const credential: RemoteCredential = { pairId, token, manifest, bindingSha256: bindingDigest(manifest), localPrivate: keys.privateKey, publicJwk,
    sign: bytes => sign('sha256', bytes, { key: proof.privateKey, dsaEncoding: 'ieee-p1363' }), close() { keys.privateKey.fill(0) } }
  t.after(() => credential.close())
  const target: RemoteTarget = { id: 'isolated-native', displayName: 'Isolated native', route: 'direct', expectedDshVersion: '0.2.2-rc.1', origin, tlsPin: expected.tlsPin, descriptor: expected,
    allowedAddresses: ['127.0.0.1'], allowPrivate: false, allowLoopback: true, trustedCertificate: cert, workspaces: [], allowedActions: ['discover', 'workspaces', 'create_session', 'prompt', 'wait'],
    allowedAgentPresets: ['golden'], allowedPaths: [], sessionBindings: [], authorizationEpoch: 1, status: 'pair-pending', ownerSha256: bindingDigest(owner) }
  const body = canonicalObject({ code, deviceId: pairId, deviceName: 'Production initiator proof', token, manifest })
  const response = await pinnedRequest(target, { method: 'POST', path: '/pair', body, headers: { 'content-type': 'application/json', dpop: await signPairingProof(target, credential, 'POST', '/pair', `${code}.${credential.bindingSha256}`) } }, AbortSignal.timeout(15_000))
  assert.equal(response.status, 201); assert.equal(parseSigningObject(response.body).bindingSha256, credential.bindingSha256)
  target.status = 'paired'
  return { ...native, target, credential, pairing, carrier, gateway }
}

test('production pinned bootstrap and public Connection/Gateway clients perform actual native unary, streams and binary HTTP', linuxPairing, async t => {
  const f = await fixture(t), signal = AbortSignal.timeout(60_000)
  const identity = await pinnedRequest(f.target, { method: 'GET', path: '/pair/identity', headers: { authorization: `DPoP ${f.credential.token}`, 'x-tokenworks-device-id': f.credential.pairId,
    dpop: await signPairingProof(f.target, f.credential, 'GET', '/pair/identity') } }, signal)
  assert.equal(identity.status, 200); assert.equal(parseSigningObject(identity.body).bindingSha256, f.credential.bindingSha256)
  const client = await connectRemoteNative(f.target, f.credential, signal); t.after(() => client.close())
  assert.equal(client.ready.runtimeId, f.target.descriptor.runtimeId)
  const workspaces = client.remote.workspace.follow(signal), workspaceIterator = workspaces[Symbol.asyncIterator]()
  try { const first = await workspaceIterator.next(); assert.equal(first.done, false); assert.equal(first.value.type, 'baseline') }
  finally { await workspaceIterator.return?.() }
  const created = await client.remote.session.create({ cwd: f.cwd, agentPreset: 'golden' }); assert.equal(created.ok, true)
  if (!created.ok) throw new Error('native create failed')
  const sessionId = created.value.sessionId, canary = `production-${randomUUID()}`, content = `${canary}\n${'真实原生字节'.repeat(12_000)}`
  // The native nominal request identity is freshly generated here, not unvalidated input.
  const requestId = randomUUID() as SessionPromptRequest['requestId']
  const follow = client.remote.session.observeEvent({ address: { kind: 'session', sessionId } }, signal), iterator = follow[Symbol.asyncIterator]()
  const ready = await iterator.next(); assert.equal(ready.done, false); assert.equal(ready.value.type, 'ready')
  const prompted = await client.remote.session.prompt({ sessionId, requestId, mode: 'queue', content: [{ type: 'text', text: content }] }, signal)
  assert.equal(prompted.ok, true)
  try {
    let observed = false
    for (let count = 0; count < 64; count++) {
      const item = await iterator.next(); assert.equal(item.done, false)
      const text = JSON.stringify(item.value)
      if (text.includes(canary)) { assert.ok(Buffer.byteLength(text) > 128 * 1024); observed = true; break }
    }
    assert.equal(observed, true)
  } finally { await iterator.return?.() }
  const binary = Buffer.concat([Buffer.from(canary), randomBytes(180_000)])
  const uploaded = await client.fetch(`/api/session/uploadFileBinary?sessionId=${encodeURIComponent(sessionId)}&name=proof.bin`, { method: 'POST', body: binary, signal })
  const receipt = await uploaded.json() as { ok: boolean; value?: { receiptId: string } }; assert.equal(receipt.ok, true); assert.ok(receipt.value?.receiptId)
  const file = join(f.cwd, 'versioned-native.bin')
  await writeFile(file, binary)
  const filePath = `/api/file?path=${encodeURIComponent(file)}`
  for (const path of [filePath, `${filePath}&v=stale-version`]) {
    const downloaded = await client.fetch(path, { signal })
    assert.equal(downloaded.status, 200)
    assert.deepEqual(Buffer.from(await downloaded.arrayBuffer()), binary)
  }
  const head = await client.fetch(filePath, { method: 'HEAD', signal })
  assert.equal(head.status, 200); assert.equal(Number(head.headers.get('content-length')), binary.length)
  assert.equal(head.body, null)
  await assert.rejects(client.fetch('https://unapproved.example/api/file', { signal }), /remote\/native-route/)
  await assert.rejects(client.fetch('/api/session/downloadFileBinary', { signal }), /remote\/native-route/)
  await assert.rejects(client.fetch('/connect?token=not-a-pair-token', { signal }), /remote\/native-route/)
  const ended = Promise.withResolvers<void>()
  const live = await dialNoiseCarrier({ expected: f.target.descriptor, credential: f.credential, purpose: 'native', signal,
    dialOuter: outerSignal => dialRemoteOuter(f.target, f.credential, 'native', outerSignal) })
  live.stream.once('close', ended.resolve); await f.pairing.revoke(f.credential.pairId); await ended.promise
  await assert.rejects(connectRemoteNative(f.target, f.credential, signal))
})

test('native file version negotiation cannot redirect authority, paths or repeat a version', () => {
  const path = '/api/file?path=%2Fworkspace%2Fproof.bin&v=old'
  assert.equal(nativeFileVersionPath(path, '?path=%2Fworkspace%2Fproof.bin&v=new'), '/api/file?path=%2Fworkspace%2Fproof.bin&v=new')
  for (const location of [null, '', 'https://other.example/api/file?path=%2Fworkspace%2Fproof.bin&v=new', '//other.example/api/file?path=%2Fworkspace%2Fproof.bin&v=new',
    '/api/file?path=%2Fworkspace%2Fproof.bin&v=new', '?path=%2Fworkspace%2Fother.bin&v=new', '?path=%2Fworkspace%2Fproof.bin',
    '?path=%2Fworkspace%2Fproof.bin&v=', '?path=%2Fworkspace%2Fproof.bin&v=old', '?path=%2Fworkspace%2Fproof.bin&v=new&v=other',
    '?path=%2Fworkspace%2Fproof.bin&path=%2Fworkspace%2Fother.bin&v=new', '?path=%2Fworkspace%2Fproof.bin&v=new&extra=1', '?path=%2Fworkspace%2Fproof.bin&v=new#fragment']) {
    assert.throws(() => nativeFileVersionPath(path, location), /remote\/native-file-version/)
  }
})

test('address, target identity, TLS chain and SPKI failures are rejected without alternate routing', linuxPairing, async t => {
  const policy = { allowedAddresses: ['192.168.4.10'], allowPrivate: false, allowLoopback: false }
  assert.throws(() => assertRemoteAddress(policy, '192.168.4.10'), /address-policy/)
  assert.doesNotThrow(() => assertRemoteAddress({ ...policy, allowPrivate: true }, '192.168.4.10'))
  assert.doesNotThrow(() => assertRemoteAddress({ allowedAddresses: ['127.0.0.1/32'], allowPrivate: false, allowLoopback: true }, '127.0.0.1'))
  assert.doesNotThrow(() => assertRemoteAddress({ allowedAddresses: ['203.0.113.7/32'], allowPrivate: false, allowLoopback: false }, '203.0.113.7'))
  for (const ip of ['::ffff:127.0.0.1', '::ffff:192.168.4.10', '::ffff:cb00:7107']) assert.throws(() => assertRemoteAddress({ allowedAddresses: [ip], allowPrivate: true, allowLoopback: true }, ip), /address-policy/)
  assert.throws(() => assertRemoteAddress({ ...policy, allowPrivate: true }, '192.168.4.11'), /address-policy/)
  for (const ip of ['127.0.0.1', '169.254.169.254', '100.100.100.200', '::ffff:127.0.0.1', '::', 'fe80::1', '224.0.0.1']) {
    assert.throws(() => assertRemoteAddress({ allowedAddresses: [ip], allowPrivate: true, allowLoopback: false }, ip))
  }
  assert.throws(() => assertRemoteAddress({ allowedAddresses: ['10.0.0.0/8'], allowPrivate: true, allowLoopback: false }, '10.1.1.1'), /address-allowlist/)
  for (const origin of ['http://host', 'https://host/', 'https://user@host', 'https://host/path', 'https://host#x', 'https://host./']) assert.throws(() => remoteOrigin({ route: 'direct', origin }))
  const f = await fixture(t), input = { method: 'GET' as const, path: '/pair/identity' as const, headers: {} }
  const wrongPin = `sha256/${randomBytes(32).toString('base64')}`
  await assert.rejects(pinnedRequest({ ...f.target, tlsPin: wrongPin, descriptor: { ...f.target.descriptor, tlsPin: wrongPin } }, input, AbortSignal.timeout(15_000)))
  await assert.rejects(pinnedRequest({ ...f.target, trustedCertificate: undefined }, input, AbortSignal.timeout(15_000)))
  await assert.rejects(pinnedRequest({ ...f.target, allowedAddresses: ['127.0.0.2'] }, input, AbortSignal.timeout(15_000)), /address-policy/)
  await assert.rejects(connectRemoteNative({ ...f.target, ownerSha256: '0'.repeat(64) }, f.credential, AbortSignal.timeout(15_000)), /target-binding/)
  const wrong = generateNoiseStatic(); t.after(() => wrong.privateKey.fill(0))
  await assert.rejects(dialNoiseCarrier({ expected: f.target.descriptor, credential: { ...f.credential, localPrivate: wrong.privateKey }, purpose: 'native', signal: AbortSignal.timeout(15_000),
    dialOuter: signal => dialRemoteOuter(f.target, f.credential, 'native', signal) }))
})

test('production admission accepts the supported family without a default pin and preserves explicit exact pins', linuxPairing, async t => {
  const f = await fixture(t)
  for (const [version, expectedDshVersion, accepted] of [
    ['0.2.2-rc.1', undefined, true], ['0.2.2-rc.2', undefined, true], ['0.2.2', undefined, true], ['0.2.2-rc.2+build.7', undefined, true],
    ['0.2.2-rc.2', '0.2.2-rc.2', true], ['0.2.2-rc.2', '0.2.2-rc.1', false], ['0.2.2+build.7', '0.2.2', false],
    ['0.2.1-rc.5', undefined, false], ['0.2.3-rc.1', undefined, false], ['0.2.2-rc.01', undefined, false],
  ] as const) {
    const carrier = new NoiseCarrier(f.pairing, f.gateway, version, f.target.origin)
    const { initiator, responder } = await carrierSocketPair(t)
    const server = carrier.accept(responder, { routeKind: 'lan', purpose: 'native', pairId: f.credential.pairId })
    try {
      const attempt = dialNoiseCarrier({ expected: f.target.descriptor, credential: f.credential, purpose: 'native', signal: AbortSignal.timeout(15_000),
        ...(expectedDshVersion === undefined ? {} : { expectedDshVersion }), dialOuter: async () => initiator })
      if (accepted) {
        const client = await attempt
        assert.equal(client.ready.dshVersion, version)
        client.close()
      } else await assert.rejects(attempt, /e2e\/hello-binding/)
    } finally { carrier.close(); initiator.destroy(); responder.destroy(); await server }
  }
})

test('production initiator rekeys at the real record boundary and validates authenticated CLOSE in both directions', { ...linuxPairing, timeout: 180_000 }, async t => {
  const f = await fixture(t), signal = AbortSignal.timeout(150_000)
  // A generic byte consumer is a test fixture, not a native RPC or readiness stand-in.
  class EchoGateway extends NativeGateway {
    override accept(stream: Duplex): void { stream.pipe(stream) }
  }
  const gateway = new EchoGateway(f.ctx.connection, f.ctx.typertGateway)
  t.after(() => gateway.close())
  const echo = new NoiseCarrier(f.pairing, gateway, '0.2.2-rc.1', f.target.origin)
  t.after(() => echo.close())
  const { initiator, responder } = await carrierSocketPair(t)
  const server = echo.accept(responder, { routeKind: 'lan', purpose: 'native', pairId: f.credential.pairId })
  const client = await dialNoiseCarrier({ expected: f.target.descriptor, credential: f.credential, purpose: 'native', signal, dialOuter: async () => initiator })
  t.after(client.close)
  let received = 0; client.stream.on('data', (bytes: Buffer) => { for (const byte of bytes) assert.equal(byte, 37); received += bytes.length })
  const byte = Buffer.from([37])
  // AUTH is record one; this crosses the fixed 2^20 boundary in both directions.
  for (let index = 0; index < 2 ** 20; index++) {
    const written = Promise.withResolvers<void>()
    client.stream.write(byte, error => error ? written.reject(error) : written.resolve()); await written.promise
  }
  const ended = once(client.stream, 'end'); client.stream.end(); await ended; await server
  assert.equal(received, 2 ** 20)
  assert.equal(client.stream.readableEnded, true); assert.equal(client.stream.writableFinished, true)
})

test('initiator rejects noncanonical HELLO, wrong READY, invalid CLOSE totals, trailing records and unauthenticated truncation', { timeout: 90_000 }, async t => {
  const f = await fixture(t)
  for (const scenario of ['hello-extra', 'hello-capabilities', 'ready-binding', 'ready-version', 'ready-build', 'close-counter', 'after-close', 'truncated'] as const) {
    const { initiator: clientOuter, responder: serverOuter } = await carrierSocketPair(t)
    clientOuter.on('error', () => {}); serverOuter.on('error', () => {})
    const server = (async () => {
      const scratch = Buffer.alloc(65535), encoded = Buffer.alloc(65535)
      let session: NativeNoiseSession | undefined
      const exact = async (length: number) => {
        const bytes = Buffer.alloc(length); let offset = 0
        while (offset < length) {
          const chunk = serverOuter.read(Math.min(length - offset, Math.max(1, serverOuter.readableLength))) as Buffer | null
          if (chunk) { bytes.set(chunk, offset); offset += chunk.length }
          else { if (serverOuter.destroyed || serverOuter.readableEnded) throw new Error('fixture/truncated'); await once(serverOuter, 'readable') }
        }
        return bytes
      }
      const readRecord = async () => exact((await exact(2)).readUInt16BE())
      const writeRecord = async (bytes: Uint8Array) => {
        const wire = Buffer.alloc(bytes.length + 2); wire.writeUInt16BE(bytes.length); wire.set(bytes, 2)
        const written = Promise.withResolvers<void>(); serverOuter.write(wire, error => error ? written.reject(error) : written.resolve()); await written.promise
      }
      try {
        const hostKey = f.pairing.hostKey
        const hello = canonicalObject({ magic: 'tokenworks-noise', v: 1, suite: NOISE_SUITE, runtimeId: f.target.descriptor.runtimeId, installationId: f.target.descriptor.installationId,
          hostKeyEpoch: hostKey.epoch, hostStaticPublicKey: hostKey.publicKey, purpose: 'native', routeKind: 'lan', dshVersion: '0.2.2-rc.1',
          capabilities: scenario === 'hello-capabilities' ? ['native.http.v1'] : ['native.http.v1', 'noise.ik.v1'], ...(scenario === 'hello-extra' ? { unexpected: true } : {}) })
        await writeRecord(hello)
        if (scenario.startsWith('hello-')) return
        const privateKey = Buffer.from(hostKey.privateKey, 'base64url')
        try { session = new NativeNoiseSession(false, privateKey, null, hello) } finally { privateKey.fill(0) }
        assert.equal(session.handshakeRead(await readRecord(), scratch), 0)
        await writeRecord(encoded.subarray(0, session.handshakeWrite(new Uint8Array(), encoded)))
        const binding = session.finishHandshake(), authLength = session.decrypt(await readRecord(), scratch)
        const authenticated = await f.pairing.authenticate(parseSigningObject(scratch.subarray(0, authLength)), binding.remoteStatic, binding.handshakeHash, 'native')
        const ready = canonicalObject({ type: 'ready', v: 1, suite: NOISE_SUITE, dshVersion: scenario === 'ready-version' ? '0.2.2-rc.2' : scenario === 'ready-build' ? '0.2.2-rc.1+changed' : '0.2.2-rc.1', runtimeId: f.target.descriptor.runtimeId, installationId: f.target.descriptor.installationId,
          pairId: f.credential.pairId, hostKeyEpoch: hostKey.epoch, deviceKeyEpoch: f.credential.manifest.deviceKeyEpoch, ownerSha256: f.target.ownerSha256,
          bindingSha256: scenario === 'ready-binding' ? '0'.repeat(64) : f.credential.bindingSha256, peerGeneration: f.pairing.peers.generation(authenticated.record) })
        await writeRecord(encoded.subarray(0, session.encrypt(ready, encoded)))
        if (scenario.startsWith('ready-') || scenario === 'truncated') return
        const close = Buffer.alloc(17); close[0] = 1; close.writeBigUInt64BE(scenario === 'close-counter' ? 99n : 2n, 9)
        await writeRecord(encoded.subarray(0, session.encrypt(close, encoded)))
        if (scenario === 'after-close') await writeRecord(encoded.subarray(0, session.encrypt(Buffer.from([0, 37]), encoded)))
      } finally { session?.close(); scratch.fill(0); encoded.fill(0); serverOuter.end() }
    })()
    const serverFailure = Promise.withResolvers<never>()
    void server.catch(error => { serverFailure.reject(error); serverOuter.destroy() })
    const attempt = Promise.race([serverFailure.promise, dialNoiseCarrier({ expected: f.target.descriptor, credential: f.credential, purpose: 'native', signal: AbortSignal.timeout(15_000), dialOuter: async () => clientOuter })])
    if (scenario.startsWith('hello-') || scenario.startsWith('ready-')) await assert.rejects(attempt, /e2e\/(?:hello-binding|noncanonical|ready-binding)/)
    else {
      const client = await attempt, failed = Promise.withResolvers<Error>()
      if (client.stream.errored) failed.resolve(client.stream.errored as Error)
      else client.stream.once('error', failed.resolve)
      client.stream.resume()
      assert.match((await failed.promise).message, /e2e\/(?:record-kind|after-close|truncated)/); client.close()
    }
    await server
  }
})

/** Real sockets are byte-mode and release write buffers before their callback.
 * Duplex.from({readable,writable}) instead defaults to object mode, and a
 * PassThrough may still retain a buffer after its write callback. Neither is
 * a valid substitute for the carrier's retained-write lifetime contract. */
async function carrierSocketPair(t: TestContext): Promise<{ initiator: Socket; responder: Socket }> {
  const listener = createServer({ allowHalfOpen: true })
  listener.listen(0, '127.0.0.1'); await once(listener, 'listening')
  const address = listener.address(); assert.ok(address && typeof address === 'object')
  const accepted = once(listener, 'connection')
  const initiator = connectSocket({ host: '127.0.0.1', port: address.port, allowHalfOpen: true })
  const connected = once(initiator, 'connect')
  const [responder] = await accepted as [Socket]; await connected
  initiator.setNoDelay(true); responder.setNoDelay(true)
  initiator.on('error', () => {}); responder.on('error', () => {})
  t.after(async () => {
    initiator.destroy(); responder.destroy()
    const stopped = Promise.withResolvers<void>(); listener.close(() => stopped.resolve()); await stopped.promise
  })
  return { initiator, responder }
}
