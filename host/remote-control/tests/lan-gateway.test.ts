import assert from 'node:assert/strict'
import test, { type TestContext } from 'node:test'
import { createCipheriv, createDecipheriv, createHash, randomBytes, randomUUID } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { mkdtemp, readFile, readdir, rm, chmod, open, stat, writeFile, type FileHandle } from 'node:fs/promises'
import { request as httpsRequest } from 'node:https'
import { request as httpRequest } from 'node:http'
import { createServer as reserveServer, type Socket } from 'node:net'
import { createServer as tlsServer } from 'node:https'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { once } from 'node:events'
import { Duplex } from 'node:stream'
import { generateKeyPair } from 'jose'
import WebSocket, { WebSocketServer, createWebSocketStream } from 'ws'
import { SignJWT, exportJWK } from 'jose'
import type { PeerScope } from '@deepseek-ai/dsh-typert-protocol'
import { createIsolatedNativeAssembly } from '../../../tools/capture-native-golden.js'
import { nativeVersion } from '../../../tools/native-contract.js'
import { LanGateway } from '../src/lan-gateway.js'
import { NativeGateway } from '../src/native-gateway.js'
import { NoiseCarrier } from '../src/noise-carrier.js'
import { NativeAccess } from '../src/native-access.js'
import { LanPairing } from '../src/lan-pairing.js'
import { LanPairingStore, bindingDigest } from '../src/lan-pairing-store.js'
import { PRODUCT_OWNER_BINDINGS } from '../src/product-access.generated.js'
import { generateNoiseStatic } from '../src/native-noise.js'
import type { JournalKeyProtector } from '../src/journal-key-protection.js'
import { pairNoiseClient, openNoiseClient, noiseDpop, noiseHttpRequest } from './noise-client.js'

const installationId = 'bbbbbbbb-2222-4222-8222-222222222222', runtimeId = 'aaaaaaaa-1111-4111-8111-111111111111'

async function fixture(t: TestContext, bound = false) {
  const directory = await mkdtemp(join(tmpdir(), 'tokenworks-noise-host-'))
  const native = await createIsolatedNativeAssembly(directory)
  const certPath = join(directory, 'certificate.pem'), keyPath = join(directory, 'key.pem')
  execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', keyPath, '-out', certPath, '-days', '1', '-subj', '/CN=localhost', '-addext', 'subjectAltName=IP:127.0.0.1,DNS:localhost'], { stdio: 'ignore' })
  const cert = await readFile(certPath, 'utf8'), key = await readFile(keyPath, 'utf8'), reserve = reserveServer()
  reserve.listen(0, '127.0.0.1'); await once(reserve, 'listening'); const address = reserve.address(); assert.ok(address && typeof address === 'object')
  const ended = once(reserve, 'close'); reserve.close(); await ended
  const origin = `https://127.0.0.1:${address.port}`, master = randomBytes(32)
  const protector: JournalKeyProtector = { id: 'real-test-aes-key', async wrap(bytes, context) {
    const iv = randomBytes(12), cipher = createCipheriv('aes-256-gcm', master, iv); cipher.setAAD(context); return Buffer.concat([iv, cipher.update(bytes), cipher.final(), cipher.getAuthTag()])
  }, async unwrap(bytes, context) {
    const value = Buffer.from(bytes), cipher = createDecipheriv('aes-256-gcm', master, value.subarray(0, 12)); cipher.setAAD(context); cipher.setAuthTag(value.subarray(-16)); return Buffer.concat([cipher.update(value.subarray(12, -16)), cipher.final()])
  } }
  const storePath = join(directory, 'pairing'), store = new LanPairingStore(storePath, runtimeId, installationId, protector)
  const pairing = await LanPairing.open({ origin, runtimeId, installationId }, cert, store, native.ctx.connection)
  const peers: PeerScope[] = [], plaintext: Buffer[] = []
  let dropResponse = false
  class ObservedGateway extends NativeGateway {
    override accept(socket: Duplex, peer: PeerScope): void {
      peers.push(peer); socket.on('data', data => plaintext.push(Buffer.from(data)))
      if (!dropResponse) { super.accept(socket, peer); return }
      // Fault injection drops the first actual native response, after the write dispatched.
      const bridge = new Duplex({ read() {}, write(_bytes, _encoding, callback) { socket.destroy(); callback(new Error('test/lost-response')) }, destroy(error, callback) { socket.destroy(); callback(error) } })
      bridge.on('error', () => {}); socket.on('data', data => bridge.push(data)); socket.on('end', () => bridge.push(null)); socket.on('close', () => bridge.destroy())
      super.accept(bridge, peer)
    }
  }
  const gateway = new ObservedGateway(native.ctx.connection, native.ctx.typertGateway)
  const carrier = new NoiseCarrier(pairing, gateway, nativeVersion, origin)
  const lan = new LanGateway({ host: '127.0.0.1', port: address.port, certificate: { cert, key }, connection: native.ctx.connection, gateway, carrier, pairing })
  await lan.listen()
  t.after(async () => { carrier.close(); pairing.close(); gateway.close(); await lan.close(); await native.peer.dispose(); await native.ctx.fiber.dispose(); master.fill(0); await rm(directory, { recursive: true, force: true }) })
  const owner = { account_id: randomUUID(), organization_id: randomUUID(), deployment_sha256: 'a'.repeat(64), user_id: randomUUID(), device_id: randomUUID() }
  const relay = { issuer: origin, organizationId: owner.organization_id, deploymentSha256: owner.deployment_sha256, userId: owner.user_id, deviceId: owner.device_id }
  const credential = await pairNoiseClient({ origin, ca: cert, descriptor: pairing.begin(), owner, ...(bound ? { relay } : {}) })
  // Repository-only authoritative lease facts; the installed native Gateway and
  // production carrier still enforce them. This fixture does not claim SQL proof.
  const relayLease = { leaseId: randomUUID(), token: randomBytes(32).toString('base64url') }
  const facts = { active: true, sub: owner.user_id, aud: runtimeId, exp: Math.floor(Date.now() / 1000) + 120, client_id: owner.device_id,
    device_id: owner.device_id, runtime_id: runtimeId, installation_id: installationId, grant_version: 1,
    organization_id: owner.organization_id,
    role: 'owner', is_installation_owner: true, bindings: structuredClone(PRODUCT_OWNER_BINDINGS) }
  const access = new NativeAccess(native.ctx.connection, native.ctx.remoteAccess, runtimeId, installationId, async (id, token) => {
    if (id !== relayLease.leaseId || token !== relayLease.token) throw new Error('test/lease-refused')
    return { ok: true, data: facts }
  }, () => ({ sessions: native.ctx.sessions.list().map(session => session.id), workspaces: [] }))
  carrier.setRelayAuthority((id, token, pairedOwner) => access.admitFresh(id, token, pairedOwner))
  t.after(() => access.close())
  return { ...native, directory, storePath, cert, key, origin, store, pairing, gateway, carrier, lan, peers, plaintext, credential, owner, relay, protector, access, relayLease, loseResponse() { dropResponse = true }, restoreResponse() { dropResponse = false } }
}

async function exchange(stream: Duplex, path: string, body?: unknown, contentType = 'application/json', options: { method?: string; headers?: Record<string, string> } = {}) {
  const result = Promise.withResolvers<{ status: number; bytes: Buffer }>()
  const payload = body === undefined ? undefined : Buffer.isBuffer(body) ? body : Buffer.from(JSON.stringify(body))
  const request = httpRequest({ hostname: 'dsh-native.invalid', method: options.method ?? (payload ? 'POST' : 'GET'), path, createConnection: () => stream as Socket,
    headers: { Connection: 'close', ...(payload ? { 'Content-Type': contentType, 'Content-Length': String(payload.length) } : {}), ...options.headers } }, response => {
    const chunks: Buffer[] = []; response.on('data', chunk => chunks.push(Buffer.from(chunk))); response.on('error', result.reject)
    response.once('end', () => result.resolve({ status: response.statusCode!, bytes: Buffer.concat(chunks) }))
  })
  request.on('error', result.reject); request.end(payload); return result.promise
}
function rpc(method: string, args: object) { return { type: 'client-request', rpcId: randomUUID(), method, payload: { args } } }

async function publicRequest(origin: string, ca: string, path: string) {
  const pending = Promise.withResolvers<number>(); const request = httpsRequest(origin + path, { ca, agent: false }, response => { response.resume(); pending.resolve(response.statusCode!) }); request.on('error', pending.reject); request.end(); return pending.promise
}

test('real TLS + fixed Noise passes exact native unary/binary/mux bytes, stable paired Peer, and hides postTLS Relay canaries', { timeout: 90_000 }, async t => {
  const f = await fixture(t, true)
  assert.equal((await publicRequest(f.origin, f.cert, '/pair/tls')), 204)
  await assert.rejects(openNoiseClient({ origin: f.origin, ca: f.cert, credential: f.credential }))
  assert.equal(f.peers.length, 0, 'a bound LAN pairing cannot bypass current Relay authority')
  assert.equal((await publicRequest(f.origin, f.cert, '/api/session/list')), 404)
  const capture: Buffer[] = [], first = await openNoiseClient({ origin: f.origin, ca: f.cert, credential: f.credential, relayLease: f.relayLease, capture: (_direction, bytes) => capture.push(Buffer.from(bytes)) })
  t.after(first.close)
  const envelope = rpc('session/create', { request: { cwd: f.cwd, agentPreset: 'golden' } })
  const created = await exchange(first.stream, '/api/session/create', envelope)
  assert.equal(created.status, 200)
  const answer = JSON.parse(created.bytes.toString()); assert.equal(answer.type, 'server-response'); assert.equal(answer.rpcId, envelope.rpcId); assert.equal(answer.result.ok, true)
  const sessionId = answer.result.value.sessionId as string, canary = `NOISE-SECRET-${randomUUID()}`, upload = Buffer.concat([Buffer.from(canary), Buffer.from([0, 255, 128, 10, 0])])
  const second = await openNoiseClient({ origin: f.origin, ca: f.cert, credential: f.credential, relayLease: f.relayLease, capture: (_direction, bytes) => capture.push(Buffer.from(bytes)) }); t.after(second.close)
  const path = `/api/session/uploadFileBinary?sessionId=${encodeURIComponent(sessionId)}&name=${canary}.bin`
  const uploaded = await exchange(second.stream, path, upload, 'application/octet-stream')
  assert.equal(uploaded.status, 200); assert.equal(JSON.parse(uploaded.bytes.toString()).ok, true)
  assert.strictEqual(f.peers[0], f.peers[1]); assert.equal(first.ready.peerGeneration, second.ready.peerGeneration)
  assert.ok(Buffer.concat(f.plaintext).includes(upload)); assert.ok(Buffer.concat(f.plaintext).includes(Buffer.from(path)))
  assert.ok(Buffer.concat(f.plaintext).includes(Buffer.from(JSON.stringify(envelope))))
  assert.equal(Buffer.concat(capture).includes(Buffer.from(canary)), false)
  const mux = await openNoiseClient({ origin: f.origin, ca: f.cert, credential: f.credential, relayLease: f.relayLease }); t.after(mux.close)
  const ws = new WebSocket('ws://dsh-native.invalid/api/remote.mux', { createConnection: () => mux.stream as Socket, perMessageDeflate: false }); t.after(() => ws.terminate()); await once(ws, 'open')
  assert.strictEqual(f.peers[2], f.peers[0])
  const frames: unknown[] = []; ws.on('message', data => frames.push(JSON.parse(data.toString())))
  const nextFrame = once(ws, 'message')
  ws.send(JSON.stringify({ type: 'open', streamId: 'list', endpoint: 'session/list', payload: { args: { _request: {} } } }))
  await nextFrame
  assert.ok(frames.length > 0)
  const other = await pairNoiseClient({ origin: f.origin, ca: f.cert, descriptor: f.pairing.begin(), owner: { ...f.owner, device_id: randomUUID() } })
  const otherConnection = await openNoiseClient({ origin: f.origin, ca: f.cert, credential: other }); t.after(otherConnection.close)
  assert.notStrictEqual(f.peers[3], f.peers[0])
  // A TLS-terminating malicious Relay sees these real WS bytes, not decrypted HTTP.
  const postTLS: Buffer[] = [], relayTLS = tlsServer({ cert: f.cert, key: f.key }), relayWS = new WebSocketServer({ server: relayTLS, perMessageDeflate: false, maxPayload: 256 * 1024 })
  relayWS.on('connection', socket => { socket.on('message', (bytes, binary) => { assert.equal(binary, true); postTLS.push(Buffer.from(bytes as Buffer)) }); void f.carrier.accept(createWebSocketStream(socket), { routeKind: 'relay', purpose: 'native', relayLease: f.relayLease }) })
  relayTLS.listen(0, '127.0.0.1'); await once(relayTLS, 'listening'); const bound = relayTLS.address(); assert.ok(bound && typeof bound === 'object')
  t.after(() => { for (const ws of relayWS.clients) ws.terminate(); relayWS.close(); relayTLS.close() })
  const relayOrigin = `https://127.0.0.1:${bound.port}`, relayCredential = { ...f.credential, origin: relayOrigin }, localDispatches = f.peers.length
  // A malicious Relay may fabricate owner lease facts; neither fact grants a missing local key/P256 proof.
  await assert.rejects(openNoiseClient({ origin: relayOrigin, ca: f.cert, credential: { ...relayCredential, privateKey: generateNoiseStatic().privateKey }, relayLease: f.relayLease, routeKind: 'relay' }))
  await assert.rejects(openNoiseClient({ origin: relayOrigin, ca: f.cert, credential: { ...relayCredential, signer: await generateKeyPair('ES256', { extractable: true }) }, relayLease: f.relayLease, routeKind: 'relay' }))
  await assert.rejects(openNoiseClient({ origin: relayOrigin, ca: f.cert, credential: { ...relayCredential, deviceId: randomUUID() }, relayLease: f.relayLease, routeKind: 'relay' }))
  assert.equal(f.peers.length, localDispatches)
  const relayed = await openNoiseClient({ origin: relayOrigin, ca: f.cert, credential: relayCredential, relayLease: f.relayLease, routeKind: 'relay' }); t.after(relayed.close)
  const listing = await exchange(relayed.stream, `/api/session/list?canary=${canary}`, rpc('session/list', { _request: {} }))
  assert.equal(listing.status, 200); assert.equal(JSON.parse(listing.bytes.toString()).result.ok, true)
  assert.strictEqual(f.peers.at(-1), f.peers[0]); assert.equal(relayed.ready.peerGeneration, first.ready.peerGeneration)
  assert.equal(Buffer.concat(postTLS).includes(Buffer.from(canary)), false); assert.equal(Buffer.concat(postTLS).includes(Buffer.from('client-request')), false); assert.equal(Buffer.concat(postTLS).includes(Buffer.from('/api/')), false)
})

test('wrong local key/P256, peer mix, replay and downgrade never reach NativeGateway; revoke closes native streams durably', { timeout: 90_000 }, async t => {
  const f = await fixture(t), before = f.peers.length
  const wrong = generateNoiseStatic()
  await assert.rejects(openNoiseClient({ origin: f.origin, ca: f.cert, credential: { ...f.credential, privateKey: wrong.privateKey } }))
  await assert.rejects(openNoiseClient({ origin: f.origin, ca: f.cert, credential: { ...f.credential, signer: await generateKeyPair('ES256', { extractable: true }) } }))
  await assert.rejects(openNoiseClient({ origin: f.origin, ca: f.cert, credential: { ...f.credential, manifest: { ...f.credential.manifest, hostStaticPublicKey: Buffer.from(wrong.publicKey).toString('base64url') } } }))
  assert.equal(f.peers.length, before)
  const records: Buffer[] = [], live = await openNoiseClient({ origin: f.origin, ca: f.cert, credential: f.credential, capture: (direction, bytes) => { if (direction === 'send') records.push(Buffer.from(bytes)) } }); t.after(live.close)
  const peer = f.peers.at(-1)!; assert.equal(peer.principal.kind, 'owner')
  const outerHeaders = async () => ({ Authorization: `DPoP ${f.credential.token}`, 'X-TokenWorks-Device-Id': f.credential.deviceId, 'X-TokenWorks-Carrier-Purpose': 'native',
    DPoP: await new SignJWT({ htm: 'GET', htu: f.origin + '/carrier', ath: createHash('sha256').update(f.credential.token).digest('base64url') }).setProtectedHeader({ typ: 'dpop+jwt', alg: 'ES256', jwk: await exportJWK(f.credential.signer.publicKey) }).setIssuedAt().setJti(randomUUID()).sign(f.credential.signer.privateKey) })
  assert.ok(records.length >= 2)
  const replay = new WebSocket(f.origin.replace('https:', 'wss:') + '/carrier', { ca: f.cert, headers: await outerHeaders(), perMessageDeflate: false }); replay.on('error', () => {})
  const replayClosed = once(replay, 'close'); replay.once('message', () => { for (const record of records) replay.send(record) }); await replayClosed
  assert.equal(f.peers.length, before + 1)
  const partial = new WebSocket(f.origin.replace('https:', 'wss:') + '/carrier', { ca: f.cert, headers: await outerHeaders(), perMessageDeflate: false }); partial.on('error', () => {})
  const partialClosed = once(partial, 'close'); partial.once('message', () => { partial.send(Buffer.from([0])); partial.close() }); await partialClosed
  assert.equal(f.peers.length, before + 1)
  const secondPair = await pairNoiseClient({ origin: f.origin, ca: f.cert, descriptor: f.pairing.begin(), owner: f.owner })
  await assert.rejects(openNoiseClient({ origin: f.origin, ca: f.cert, credential: secondPair, headers: await outerHeaders() }))
  assert.equal(f.peers.length, before + 1)
  const liveClosed = Promise.withResolvers<void>(); live.stream.once('close', liveClosed.resolve)
  await f.pairing.revoke(f.credential.deviceId)
  assert.equal(peer.signal.aborted, true); await liveClosed.promise
  await assert.rejects(openNoiseClient({ origin: f.origin, ca: f.cert, credential: f.credential }))
  assert.deepEqual((await f.store.devices()).map(record => record.deviceId), [secondPair.deviceId])
  const reopened = new LanPairingStore(f.storePath, runtimeId, installationId, f.protector); assert.deepEqual((await reopened.devices()).map(record => record.deviceId), [secondPair.deviceId])
  for (const name of await readdir(f.storePath)) assert.equal((await readFile(join(f.storePath, name))).includes(Buffer.from(f.credential.token)), false)
})

test('lost native write response dispatches once, Host rotate-and-revoke-all commits one operation and does not restore old keys', { timeout: 90_000 }, async t => {
  const f = await fixture(t)
  f.loseResponse()
  const connection = await openNoiseClient({ origin: f.origin, ca: f.cert, credential: f.credential }); t.after(connection.close)
  const request = rpc('session/create', { request: { cwd: f.cwd, agentPreset: 'golden' } })
  await assert.rejects(exchange(connection.stream, '/api/session/create', request)); f.restoreResponse()
  assert.equal(f.ctx.sessions.list().length, 1)
  const reconnect = await openNoiseClient({ origin: f.origin, ca: f.cert, credential: f.credential }); t.after(reconnect.close)
  const list = JSON.parse((await exchange(reconnect.stream, '/api/session/list', rpc('session/list', { _request: {} }))).bytes.toString()); assert.equal(list.result.value.items.length, 1)
  await assert.rejects(f.pairing.rotateHost(f.pairing.status().operationId), /already used/)
  const op = randomUUID(), old = f.pairing.hostKey.publicKey, result = await f.pairing.rotateHost(op)
  assert.equal(result.hostKeyEpoch, 2); assert.notEqual(result.hostStaticPublicKey, old); assert.deepEqual(await f.pairing.rotateHost(op), result)
  await assert.rejects(openNoiseClient({ origin: f.origin, ca: f.cert, credential: f.credential }))
  const reopened = await LanPairing.open({ origin: f.origin, runtimeId, installationId }, f.cert, new LanPairingStore(f.storePath, runtimeId, installationId, f.protector), f.ctx.connection); t.after(() => reopened.close())
  assert.equal(reopened.hostKey.epoch, 2); assert.equal(reopened.hostKey.publicKey, result.hostStaticPublicKey); assert.deepEqual(reopened.list(), [])
  // A real storage publication failure latches deny; no successful revocation is fabricated.
  const next = await pairNoiseClient({ origin: f.origin, ca: f.cert, descriptor: f.pairing.begin(), owner: f.owner })
  await chmod(f.storePath, 0o500)
  try { await assert.rejects(f.pairing.revoke(next.deviceId)); await assert.rejects(openNoiseClient({ origin: f.origin, ca: f.cert, credential: next })) }
  finally { await chmod(f.storePath, 0o700) }
  await f.pairing.revoke(next.deviceId)
  const finalPair = await pairNoiseClient({ origin: f.origin, ca: f.cert, descriptor: f.pairing.begin(), owner: f.owner })
  const directoryInfo = await stat(f.storePath), directoryHandle = await open(f.storePath, 'r')
  const prototype = Object.getPrototypeOf(directoryHandle) as { sync(this: FileHandle): Promise<void> }, originalSync = prototype.sync
  await directoryHandle.close()
  // Inject the exact platform directory-fsync failure; all file writes, encryption and native dispatch remain real.
  const failure = t.mock.method(prototype, 'sync', async function(this: FileHandle) {
    const info = await this.stat(); if (info.dev === directoryInfo.dev && info.ino === directoryInfo.ino) throw new Error('test/directory-fsync-failure')
    return originalSync.call(this)
  })
  try { await assert.rejects(f.pairing.revoke(finalPair.deviceId), /fsync/); await assert.rejects(openNoiseClient({ origin: f.origin, ca: f.cert, credential: finalPair })) }
  finally { failure.mock.restore() }
  await f.pairing.revoke(finalPair.deviceId)
  const rotationFailure = t.mock.method(prototype, 'sync', async function(this: FileHandle) {
    const info = await this.stat(); if (info.dev === directoryInfo.dev && info.ino === directoryInfo.ino) throw new Error('test/directory-fsync-failure')
    return originalSync.call(this)
  })
  const nextOperation = randomUUID()
  try { await assert.rejects(f.pairing.rotateHost(nextOperation), /fsync/); assert.equal(f.pairing.status().denied, true); assert.throws(() => f.pairing.begin(), /unavailable/) }
  finally { rotationFailure.mock.restore() }
  const recoveredRotation = await f.pairing.rotateHost(nextOperation)
  assert.equal(recoveredRotation.hostKeyEpoch, 3); assert.equal(f.pairing.status().denied, false)
  assert.equal((await new LanPairingStore(f.storePath, runtimeId, installationId, f.protector).hostKey()).epoch, 3)
  await assert.rejects(f.pairing.rotateHost(op), /superseded/)
  assert.equal(f.pairing.status().hostKeyEpoch, 3)
  const marker = join(f.storePath, 'host-key-active.3'), corrupted = await readFile(marker); corrupted[corrupted.length - 1]! ^= 1; await writeFile(marker, corrupted)
  await assert.rejects(new LanPairingStore(f.storePath, runtimeId, installationId, f.protector).hostKey())
  await writeFile(join(f.storePath, `device.${randomUUID()}`), Buffer.from('TWMLAN01legacy-needs-operator-repair'), { mode: 0o600 })
  await assert.rejects(new LanPairingStore(f.storePath, runtimeId, installationId, f.protector).devices(), /upgrade-required/)
})

test('old-key authenticated device rotation commits before ACK, new-key read reconciles, and control cannot enter native APIs', { timeout: 90_000 }, async t => {
  const f = await fixture(t), live = await openNoiseClient({ origin: f.origin, ca: f.cert, credential: f.credential }); t.after(live.close)
  const oldPeer = f.peers.at(-1)!, oldClosed = Promise.withResolvers<void>(); live.stream.once('close', oldClosed.resolve)
  const denied = await noiseHttpRequest({ origin: f.origin, ca: f.cert, credential: f.credential, purpose: 'pairing-control' }, 'GET', '/api/session/list')
  assert.equal(denied.status, 404); assert.equal(f.peers.length, 1)
  const control = await openNoiseClient({ origin: f.origin, ca: f.cert, credential: f.credential, purpose: 'pairing-control' }); t.after(control.close)
  const keys = generateNoiseStatic(), body = { operationId: randomUUID(), expectedDeviceKeyEpoch: 1, newDeviceStaticPublicKey: Buffer.from(keys.publicKey).toString('base64url'), newDeviceKeyEpoch: 2 }
  const nonce = bindingDigest({ ...body, handshakeHash: control.handshakeHash, oldDeviceStaticPublicKey: f.credential.manifest.deviceStaticPublicKey })
  const rotated = await exchange(control.stream, '/pair/noise-key', body, 'application/json', { method: 'PUT', headers: { DPoP: await noiseDpop(f.credential, 'PUT', '/pair/noise-key', { nonce }) } })
  assert.equal(rotated.status, 200); await oldClosed.promise; assert.equal(oldPeer.signal.aborted, true)
  const identity = JSON.parse(rotated.bytes.toString())
  const next = { ...f.credential, privateKey: keys.privateKey, manifest: identity.manifest, bindingSha256: identity.bindingSha256 }
  await assert.rejects(openNoiseClient({ origin: f.origin, ca: f.cert, credential: f.credential }))
  const reconciled = await noiseHttpRequest({ origin: f.origin, ca: f.cert, credential: next, purpose: 'pairing-control' }, 'GET', '/pair/identity')
  assert.equal(reconciled.status, 200); assert.equal(JSON.parse(reconciled.body.toString()).manifest.deviceKeyEpoch, 2)
  const durable = await new LanPairingStore(f.storePath, runtimeId, installationId, f.protector).devices()
  assert.equal(durable[0]!.manifest.deviceKeyEpoch, 2); assert.equal(durable[0]!.bindingSha256, next.bindingSha256)
  const nextNative = await openNoiseClient({ origin: f.origin, ca: f.cert, credential: next }); t.after(nextNative.close)
  assert.notStrictEqual(f.peers.at(-1), oldPeer)
  const revoked = await noiseHttpRequest({ origin: f.origin, ca: f.cert, credential: next, purpose: 'pairing-control' }, 'DELETE', '/pair')
  assert.equal(revoked.status, 200); assert.deepEqual(JSON.parse(revoked.body.toString()), { ok: true })
  assert.deepEqual(await f.store.devices(), [])
})

test('native large file bodies cross multiple Noise records without exceeding carrier write credits', { timeout: 90_000 }, async t => {
  const f = await fixture(t), client = { origin: f.origin, ca: f.cert, credential: f.credential }
  const created = await noiseHttpRequest(client, 'POST', '/api/session/create', Buffer.from(JSON.stringify(rpc('session/create', { request: { cwd: f.cwd, agentPreset: 'golden' } }))), { 'Content-Type': 'application/json' })
  assert.equal(created.status, 200); assert.equal(JSON.parse(created.body.toString()).result.ok, true)
  const file = join(f.cwd, `noise-large-${randomUUID()}.bin`), expected = randomBytes(400_003)
  await writeFile(file, expected)
  const downloaded = await noiseHttpRequest(client, 'GET', `/api/file?path=${encodeURIComponent(file)}`)
  assert.equal(downloaded.status, 200); assert.deepEqual(downloaded.body, expected)
  assert.equal(Number(downloaded.headers['content-length']), expected.length)
  assert.strictEqual(f.peers[0], f.peers[1])
})
