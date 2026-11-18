import assert from 'node:assert/strict'
import test, { type TestContext } from 'node:test'
import { createCipheriv, createDecipheriv, createHash, KeyObject, randomBytes, randomUUID, sign } from 'node:crypto'
import { execFileSync, spawn } from 'node:child_process'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { once } from 'node:events'
import { createServer, type Socket } from 'node:net'
import { request, type IncomingHttpHeaders } from 'node:http'
import { createServer as createHttpsServer } from 'node:https'
import { Duplex } from 'node:stream'
import { createSocket } from 'node:dgram'
import { setTimeout as platformExpiry } from 'node:timers/promises'
import { exportJWK } from 'jose'
import WebSocket, { WebSocketServer, createWebSocketStream } from 'ws'
import { RTCPeerConnection } from 'werift'
import { SessionId } from '@deepseek-ai/dsh-session'
import type { PeerScope } from '@deepseek-ai/dsh-typert-protocol'
import { REMOTE_EVENT_STREAM_ENDPOINT, parseRemoteStreamServerMessage } from '@deepseek-ai/dsh-api-gateway/stream-protocol'
import { WireDecoder } from '../src/wire-codec.js'
import { createIsolatedNativeAssembly } from '../../../tools/capture-native-golden.js'
import { nativeVersion } from '../../../tools/native-contract.js'
import { NativeGateway } from '../src/native-gateway.js'
import { NoiseCarrier } from '../src/noise-carrier.js'
import { LanGateway } from '../src/lan-gateway.js'
import { LanPairing, type RelayLeaseCredential } from '../src/lan-pairing.js'
import { LanPairingStore } from '../src/lan-pairing-store.js'
import type { JournalKeyProtector } from '../src/journal-key-protection.js'
import type { InstallationPublicJwk } from '../src/host-connection-credential.js'
import { dialNoiseCarrier } from '../src/remote-noise-initiator.js'
import { RtcCarrierHost, selectedRtcRoute } from '../src/rtc-carrier.js'
import { RtcDataChannelDuplex, guardRtcAssociation } from '../src/rtc-datachannel.js'
import { rtcConfigSchema, rtcPeerConfig, type RtcConfig, type RtcRouteKind } from '../src/rtc-config.js'
import { generateNoiseStatic } from '../src/native-noise.js'
import { NativeAccess, type NativeLease } from '../src/native-access.js'
import { PRODUCT_OWNER_BINDINGS } from '../src/product-access.generated.js'
import { pairNoiseClient, openNoiseClient, type NoiseClientCredential } from './noise-client.js'
import { nativeFileVersionPath } from '../src/remote-native-client.js'

async function exchange(stream: Duplex, path: string, body?: unknown, method = body === undefined ? 'GET' : 'POST') {
  const pending = Promise.withResolvers<{ status: number; body: Buffer; headers: IncomingHttpHeaders }>(), bytes = body === undefined ? undefined : Buffer.isBuffer(body) ? body : Buffer.from(JSON.stringify(body))
  const call = request({ hostname: 'native.invalid', path, method, createConnection: () => stream as Socket, headers: { Connection: 'close', ...(bytes ? { 'Content-Length': String(bytes.length), 'Content-Type': Buffer.isBuffer(body) ? 'application/octet-stream' : 'application/json' } : {}) } }, response => {
    const parts: Buffer[] = []; let length = 0
    response.on('data', (chunk: Buffer) => { length += chunk.length; if (length > 1024 * 1024) call.destroy(new Error('test/response-budget')); else parts.push(Buffer.from(chunk)) })
    response.on('error', pending.reject); response.once('end', () => pending.resolve({ status: response.statusCode!, body: Buffer.concat(parts), headers: response.headers }))
  })
  // A real-network response deadline, not a guessed gathering completion wait.
  const deadline = setTimeout(() => call.destroy(new Error(`test/native-response-timeout:${path.split('?')[0]}`)), 10_000); deadline.unref()
  call.once('close', () => clearTimeout(deadline))
  call.once('error', pending.reject); call.end(bytes); return pending.promise
}
async function fixture(t: TestContext, config: RtcConfig, installRtc = true) {
  const directory = await mkdtemp(join(tmpdir(), 'rtc-native-')), native = await createIsolatedNativeAssembly(directory, [
    { id: 'rtc-shell-env', name: '@deepseek-ai/dsh-shell-env' },
    { id: 'rtc-bash-local', name: '@deepseek-ai/dsh-bash-local', config: { cwd: join(directory, 'workspace') } },
    { id: 'rtc-bash-tool', name: '@deepseek-ai/dsh-tool-bash' },
    { id: 'rtc-api-remotes', name: '@deepseek-ai/dsh-api-remotes' },
  ])
  native.ctx.on('tools/pre-execute', async (execution, next) => {
    if (execution.name !== 'bash') return next()
    const args = execution.arguments as Record<string, unknown>
    if (args.command !== 'printf authorized >> approval-allow.txt' || Object.keys(args).some(key => !['command', 'description'].includes(key))) return { kind: 'deny', reason: 'isolated RTC command not allowlisted' }
    return { kind: 'ask', reason: 'real RTC approval proof' }
  }, { global: true })
  const certPath = join(directory, 'cert.pem'), keyPath = join(directory, 'key.pem')
  execFileSync('openssl', ['req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:P-256', '-nodes', '-keyout', keyPath, '-out', certPath, '-days', '1', '-subj', '/CN=localhost', '-addext', 'subjectAltName=IP:127.0.0.1,DNS:localhost'], { stdio: 'ignore' })
  const cert = await readFile(certPath, 'utf8'), key = await readFile(keyPath, 'utf8'), reserve = createServer()
  reserve.listen(0, '127.0.0.1'); await once(reserve, 'listening'); const address = reserve.address(); assert.ok(address && typeof address === 'object'); reserve.close(); await once(reserve, 'close')
  const origin = `https://127.0.0.1:${address.port}`, master = randomBytes(32), runtimeId = randomUUID(), installationId = randomUUID()
  const protector: JournalKeyProtector = { id: 'isolated-rtc-aes', async wrap(bytes, context) {
    const iv = randomBytes(12), cipher = createCipheriv('aes-256-gcm', master, iv); cipher.setAAD(context); return Buffer.concat([iv, cipher.update(bytes), cipher.final(), cipher.getAuthTag()])
  }, async unwrap(bytes, context) {
    const value = Buffer.from(bytes), cipher = createDecipheriv('aes-256-gcm', master, value.subarray(0, 12)); cipher.setAAD(context); cipher.setAuthTag(value.subarray(-16)); return Buffer.concat([cipher.update(value.subarray(12, -16)), cipher.final()])
  } }
  const pairing = await LanPairing.open({ origin, runtimeId, installationId }, cert, new LanPairingStore(join(directory, 'pairs'), runtimeId, installationId, protector), native.ctx.connection)
  const firstPeer = Promise.withResolvers<RTCPeerConnection>()
  const peers: PeerScope[] = []
  const rtcPeers: RTCPeerConnection[] = []
  class ObservedGateway extends NativeGateway {
    override accept(stream: Duplex, peer: PeerScope): void { if (peers.length >= 128) throw new Error('test/peer-observation-budget'); peers.push(peer); super.accept(stream, peer) }
  }
  const gateway = new ObservedGateway(native.ctx.connection, native.ctx.typertGateway), carrier = new NoiseCarrier(pairing, gateway, nativeVersion, origin), rtc = new RtcCarrierHost(pairing, carrier, config, pc => {
    if (rtcPeers.length >= 64) throw new Error('test/rtc-observation-budget')
    rtcPeers.push(pc); firstPeer.resolve(pc)
  })
  if (installRtc) carrier.setRtcControl(rtc)
  const lan = new LanGateway({ host: '127.0.0.1', port: address.port, certificate: { cert, key }, connection: native.ctx.connection, gateway, carrier, pairing }); await lan.listen()
  t.after(async () => { carrier.close(); rtc.close(); pairing.close(); gateway.close(); await rtc.drain(); await lan.close(); await native.peer.dispose(); await native.ctx.fiber.dispose(); master.fill(0); await rm(directory, { recursive: true, force: true }) })
  const descriptor = pairing.begin(), credential = await pairNoiseClient({ origin, ca: cert, descriptor })
  async function control(path: string, body?: unknown, method?: string, owner = credential, relayControlOrigin?: string, relayLease?: RelayLeaseCredential) {
    const origin = relayControlOrigin ?? owner.origin
    const client = await openNoiseClient({ origin, ca: cert, credential: relayControlOrigin ? { ...owner, origin } : owner, purpose: 'pairing-control',
      routeKind: relayControlOrigin ? 'relay' : 'lan', ...(relayLease ? { relayLease } : {}) })
    try { return await exchange(client.stream, path, body, method) } finally { client.close() }
  }
  async function rtcDial(routeKind: RtcRouteKind, options: { credential?: NoiseClientCredential; noiseCredential?: NoiseClientCredential; unordered?: boolean; raw?: boolean;
    relayControlOrigin?: string; signalLease?: RelayLeaseCredential; relayLease?: RelayLeaseCredential } = {}) {
    const owner = options.credential ?? credential, pc = new RTCPeerConnection(rtcPeerConfig(config, routeKind, owner.deviceId)), capture: Buffer[] = []
    let captured = 0
    const observe = (bytes: Buffer) => { if (captured + bytes.length > 1024 * 1024) throw new Error('test/ciphertext-capture-budget'); captured += bytes.length; capture.push(Buffer.from(bytes)) }
    const dc = pc.createDataChannel('tokenworks-native', { protocol: 'twm-noise.v1', ordered: !options.unordered })
    dc.onMessage.subscribe(data => { if (Buffer.isBuffer(data)) observe(data) })
    const offerId = randomUUID(), opened = dc.stateChanged.watch(state => state === 'open', 15_000)
    void opened.catch(() => {})
    t.after(() => pc.close())
    await pc.setLocalDescription(await pc.createOffer())
    assert.equal(pc.iceGatheringState, 'complete')
    guardRtcAssociation(pc, () => { void pc.close() })
    let outer: RtcDataChannelDuplex | undefined
    if (!options.raw) outer = new RtcDataChannelDuplex(dc, pc, (inbound, outbound) => {
      const queue = outer!.carrierQueuedBytes()
      if (outer!.readableLength + queue.inbound + inbound > 256 * 1024 || outer!.writableLength + queue.outbound + outbound > 256 * 1024) throw new Error('test/outer-budget')
    }, () => { void pc.close() })
    const send = dc.send.bind(dc); dc.send = data => { if (Buffer.isBuffer(data)) observe(data); send(data) }
    const result = await control('/pair/rtc/offer', { offerId, routeKind, sdp: pc.localDescription!.sdp }, 'POST', owner, options.relayControlOrigin, options.signalLease)
    assert.equal(result.status, 200, result.body.toString())
    const answer = JSON.parse(result.body.toString())
    assert.ok(answer.expiresAt > Date.now())
    await pc.setRemoteDescription({ type: 'answer', sdp: answer.sdp })
    await opened
    const facts = selectedRtcRoute(pc, routeKind)
    if (options.raw) return { pc, dc, offerId, capture, facts, noise: undefined }
    const noiseOwner = options.noiseCredential ?? owner
    const jwk = await exportJWK(noiseOwner.signer.publicKey) as InstallationPublicJwk
    const signingKey = KeyObject.from(noiseOwner.signer.privateKey)
    try {
      const noise = await dialNoiseCarrier({ expected: descriptor, credential: { pairId: noiseOwner.deviceId, token: noiseOwner.token, manifest: noiseOwner.manifest, bindingSha256: noiseOwner.bindingSha256, localPrivate: noiseOwner.privateKey, publicJwk: jwk,
        sign: async bytes => sign('sha256', bytes, { key: signingKey, dsaEncoding: 'ieee-p1363' }) }, purpose: 'native', expectedRouteKind: routeKind, requiredCapabilities: ['transport.webrtc.v1'],
        ...(options.relayLease ? { relayLease: options.relayLease } : {}), signal: new AbortController().signal, dialOuter: async () => outer! })
      t.after(noise.close)
      return { pc, dc, offerId, capture, facts, noise }
    } catch (error) { await pc.close(); throw error }
  }
  return { ...native, rtc, carrier, pairing, gateway, descriptor, credential, origin, cert, key, peers, rtcPeers, control, rtcDial, firstRtcPeer: firstPeer.promise }
}
async function isolatedTurn(t: TestContext) {
  const root = resolve('.local/coturn/root'), binary = process.env.TOKENWORKS_TEST_TURNSERVER ?? join(root, 'usr/bin/turnserver'), directory = await mkdtemp(join(tmpdir(), 'rtc-coturn-'))
  const socket = createSocket('udp4'); socket.bind(0, '127.0.0.1'); await once(socket, 'listening'); const port = socket.address().port; socket.close(); await once(socket, 'close')
  const sharedSecret = randomBytes(32).toString('base64url'), configPath = join(directory, 'turn.conf')
  await writeFile(configPath, `listening-ip=127.0.0.1\nrelay-ip=127.0.0.1\nlistening-port=${port}\nrealm=isolated.tokenworks.test\nuse-auth-secret\nstatic-auth-secret=${sharedSecret}\nfingerprint\nno-tcp\nno-tls\nno-dtls\nno-cli\nno-multicast-peers\nallow-loopback-peers\nrelay-threads=1\nmin-port=51000\nmax-port=51100\npidfile=${join(directory, 'pid')}\nlog-file=stdout\nsimple-log\n`, { mode: 0o600 })
  const server = spawn(binary, ['-c', configPath, `--userdb=${join(directory, 'turn.sqlite')}`], { env: { PATH: '/usr/bin:/bin', LD_LIBRARY_PATH: join(root, 'usr/lib/x86_64-linux-gnu') }, stdio: ['ignore', 'pipe', 'pipe'] })
  const ready = Promise.withResolvers<void>(), exited = once(server, 'exit'); let log = ''
  for (const stream of [server.stdout, server.stderr]) stream.on('data', (bytes: Buffer) => { log = (log + bytes.toString()).slice(-32768); if (log.includes('Total General servers:')) ready.resolve() })
  server.once('error', ready.reject); void exited.then(([code]) => ready.reject(new Error(`test/coturn-exit:${code}`)))
  const deadline = setTimeout(() => ready.reject(new Error('test/coturn-start-timeout')), 10_000)
  t.after(async () => { if (server.exitCode === null && server.signalCode === null) server.kill('SIGTERM'); await exited; await rm(directory, { recursive: true, force: true }) })
  try { await ready.promise } finally { clearTimeout(deadline) }
  return rtcConfigSchema.parse({ enabled: true, turn: { urls: [`turn:127.0.0.1:${port}?transport=udp`], sharedSecret } })
}
const rpc = (method: string, args: object) => ({ type: 'client-request', rpcId: randomUUID(), method, payload: { args } })
const linuxPairing = { skip: process.platform !== 'linux' && 'requires the Linux private pairing store' }

for (const routeKind of ['p2p', 'turn'] as const) test(`real ${routeKind} selected-candidate + SAME Noise + native unary/binary/mux/approval canary and cleanup`, { ...linuxPairing, timeout: 90_000 }, async t => {
  const config = routeKind === 'turn' ? await isolatedTurn(t) : rtcConfigSchema.parse({ enabled: true })
  const f = await fixture(t, config), configResponse = await f.control('/pair/rtc/config')
  assert.equal(configResponse.status, 200); assert.equal(JSON.parse(configResponse.body.toString()).enabled, true)
  assert.equal(configResponse.body.includes(Buffer.from(config.turn?.sharedSecret ?? 'never-a-config-secret')), false)
  const first = await f.rtcDial(routeKind), canary = `RTC-NATIVE-CANARY-${randomUUID()}`
  assert.ok(first.noise)
  t.diagnostic(`${routeKind}: selected ICE + production Noise READY`)
  assert.equal(first.facts.routeKind, routeKind)
  assert.equal(first.facts.localCandidateType === 'relay', routeKind === 'turn')
  assert.equal(first.facts.remoteCandidateType === 'relay', routeKind === 'turn')
  assert.equal(first.pc.getTransceivers().length, 0)
  assert.equal(f.rtc.activeCount, 1, 'normal control response must not retire handed-off PC')
  const created = await exchange(first.noise.stream, '/api/session/create', rpc('session/create', { request: { cwd: f.cwd, agentPreset: 'golden' } }))
  assert.equal(created.status, 200); const sessionId = JSON.parse(created.body.toString()).result.value.sessionId
  t.diagnostic(`${routeKind}: native session/create replied`)
  const second = await f.rtcDial(routeKind); assert.ok(second.noise); assert.equal(second.noise.ready.peerGeneration, first.noise.ready.peerGeneration)
  const bytes = Buffer.concat([Buffer.from(canary), Buffer.from([0, 255, 128, 10])])
  const uploaded = await exchange(second.noise.stream, `/api/session/uploadFileBinary?sessionId=${sessionId}&name=${canary}.bin`, bytes)
  assert.equal(uploaded.status, 200); assert.equal(JSON.parse(uploaded.body.toString()).ok, true)
  t.diagnostic(`${routeKind}: native binary upload replied`)
  const largeBytes = Buffer.alloc(400003), largePath = join(f.cwd, 'rtc-large-native.bin')
  for (let index = 0; index < largeBytes.length; index++) largeBytes[index] = (index * 37 + 11) & 255
  await writeFile(largePath, largeBytes, { mode: 0o600, flag: 'wx' })
  const versionCarrier = await f.rtcDial(routeKind); assert.ok(versionCarrier.noise)
  const path = `/api/file?path=${encodeURIComponent(largePath)}`, version = await exchange(versionCarrier.noise.stream, path)
  assert.equal(version.status, 302)
  assert.equal(versionCarrier.noise.ready.peerGeneration, first.noise.ready.peerGeneration)
  const downloadCarrier = await f.rtcDial(routeKind); assert.ok(downloadCarrier.noise)
  assert.equal(downloadCarrier.noise.ready.peerGeneration, versionCarrier.noise.ready.peerGeneration)
  const downloaded = await exchange(downloadCarrier.noise.stream, nativeFileVersionPath(path, version.headers.location ?? null))
  assert.equal(downloaded.status, 200); assert.deepEqual(downloaded.body, largeBytes)
  assert.equal(createHash('sha256').update(downloaded.body).digest('hex'), createHash('sha256').update(largeBytes).digest('hex'))
  t.diagnostic(`${routeKind}: actual 400003-byte multi-record native download matched bytes and digest`)
  const third = await f.rtcDial(routeKind); assert.ok(third.noise)
  const ws = new WebSocket('ws://native.invalid/api/remote.mux', { createConnection: () => third.noise!.stream as Socket, perMessageDeflate: false }); t.after(() => ws.terminate()); await once(ws, 'open')
  const response = once(ws, 'message'); ws.send(JSON.stringify({ type: 'open', streamId: 'list', endpoint: 'session/list', payload: { args: { _request: {} } } })); await response
  t.diagnostic(`${routeKind}: native mux session/list replied`)
  const eventsReady = Promise.withResolvers<{ clientId: string }>(), waterfall = Promise.withResolvers<{ eventId: string }>()
  const decoder = new WireDecoder(error => { eventsReady.reject(error); waterfall.reject(error) }); t.after(() => decoder.close())
  void eventsReady.promise.catch(() => {}); void waterfall.promise.catch(() => {})
  ws.on('message', (data, binary) => {
    assert.equal(binary, false)
    const text = decoder.decode(data.toString()); if (text === undefined) return
    const frame = parseRemoteStreamServerMessage(text)
    if (frame.streamId !== 'events') return
    if (frame.type === 'error' || frame.type === 'end') { const error = new Error(`test/native-events-${frame.type}`); eventsReady.reject(error); waterfall.reject(error); return }
    if (frame.type !== 'item' || !frame.value || typeof frame.value !== 'object') return
    const value = frame.value as Record<string, unknown>
    if (value.type === 'ready' && typeof value.clientId === 'string') eventsReady.resolve({ clientId: value.clientId })
    if (value.type === 'waterfall' && value.event === 'approval/request' && typeof value.eventId === 'string') waterfall.resolve({ eventId: value.eventId })
  })
  ws.send(JSON.stringify({ type: 'open', streamId: 'events', endpoint: REMOTE_EVENT_STREAM_ENDPOINT, payload: { args: {} } }))
  const live = await eventsReady.promise, agent = f.ctx.agents.get(SessionId(sessionId)); assert.ok(agent)
  t.diagnostic(`${routeKind}: native event stream ready`)
  const fourth = await f.rtcDial(routeKind); assert.ok(fourth.noise)
  const prompted = await exchange(fourth.noise.stream, '/api/session/prompt', rpc('session/prompt', { request: { sessionId, requestId: randomUUID(), mode: 'queue', content: [{ type: 'text', text: `REMOTE_APPROVAL_ALLOW ${canary}` }] } }))
  assert.equal(prompted.status, 200); assert.equal(JSON.parse(prompted.body.toString()).result.ok, true)
  const pendingApproval = await waterfall.promise, fifth = await f.rtcDial(routeKind); assert.ok(fifth.noise)
  const answered = await exchange(fifth.noise.stream, '/api/$events/result', rpc('$events/result', { clientId: live.clientId, eventId: pendingApproval.eventId, outcome: { kind: 'result', value: 'allowed-once' } }))
  assert.equal(answered.status, 200); assert.equal(JSON.parse(answered.body.toString()).result.ok, true)
  await agent.whenIdle(); assert.equal(await readFile(join(f.cwd, 'approval-allow.txt'), 'utf8'), 'authorized')
  t.diagnostic(`${routeKind}: actual native approval executed allowlisted sentinel`)
  const postDtls = Buffer.concat([...first.capture, ...second.capture, ...versionCarrier.capture, ...downloadCarrier.capture, ...third.capture, ...fourth.capture, ...fifth.capture])
  for (const plaintext of [canary, '/api/', 'client-request', 'session/create', 'approval/request', 'allowed-once']) assert.equal(postDtls.includes(Buffer.from(plaintext)), false, plaintext)
  assert.equal(postDtls.includes(largeBytes.subarray(0, 64)), false)
  assert.ok(postDtls.includes(Buffer.from('tokenworks-noise')))
  await f.pairing.revoke(f.credential.deviceId)
  assert.equal(f.rtc.activeCount, 0)
})

test('authenticated offer ownership, strict body, pair/Host PC caps, real TTL, revoke and Host cleanup', { ...linuxPairing, timeout: 90_000 }, async t => {
  const f = await fixture(t, rtcConfigSchema.parse({ enabled: true })), pc = new RTCPeerConnection(rtcPeerConfig(rtcConfigSchema.parse({ enabled: true }), 'p2p', f.credential.deviceId))
  t.after(() => pc.close()); pc.createDataChannel('tokenworks-native', { protocol: 'twm-noise.v1', ordered: true }); await pc.setLocalDescription(await pc.createOffer())
  const other = await pairNoiseClient({ origin: f.origin, ca: f.cert, descriptor: f.pairing.begin() })
  const offerIds: string[] = []
  for (let index = 0; index < 8; index++) { const offerId = randomUUID(); offerIds.push(offerId); assert.equal((await f.control('/pair/rtc/offer', { offerId, routeKind: 'p2p', sdp: pc.localDescription!.sdp })).status, 200) }
  assert.equal(f.rtc.activeCount, 8)
  assert.equal((await f.control('/pair/rtc/offer', { offerId: randomUUID(), routeKind: 'p2p', sdp: pc.localDescription!.sdp })).status, 429)
  assert.equal((await f.control(`/pair/rtc/offer/${offerIds[0]}`, undefined, 'DELETE', other)).status, 403)
  assert.equal(f.rtc.activeCount, 8)
  assert.equal((await f.control(`/pair/rtc/offer/${offerIds[0]}`, undefined, 'DELETE')).status, 200)
  assert.equal((await f.control(`/pair/rtc/offer/${offerIds[0]}`, undefined, 'DELETE')).status, 200)
  assert.equal(f.rtc.activeCount, 7)
  assert.equal((await f.control('/pair/rtc/offer', { offerId: randomUUID(), pairId: other.deviceId, routeKind: 'p2p', sdp: pc.localDescription!.sdp })).status, 409)
  const noTurn = await f.control('/pair/rtc/offer', { offerId: randomUUID(), routeKind: 'turn', sdp: pc.localDescription!.sdp })
  assert.equal(noTurn.status, 503); assert.deepEqual(JSON.parse(noTurn.body.toString()), { code: 'rtc/unavailable' })
  await f.rtc.drain()
  for (let index = 0; index < 8; index++) assert.equal((await f.control('/pair/rtc/offer', { offerId: randomUUID(), routeKind: 'p2p', sdp: pc.localDescription!.sdp }, 'POST', other)).status, 200)
  assert.equal((await f.control('/pair/rtc/offer', { offerId: randomUUID(), routeKind: 'p2p', sdp: pc.localDescription!.sdp })).status, 200)
  assert.equal(f.rtc.activeCount, 16)
  const third = await pairNoiseClient({ origin: f.origin, ca: f.cert, descriptor: f.pairing.begin() })
  assert.equal((await f.control('/pair/rtc/offer', { offerId: randomUUID(), routeKind: 'p2p', sdp: pc.localDescription!.sdp }, 'POST', third)).status, 429)
  // Deliberately exercise the actual 20-second wall-clock expiry against real
  // ICE sockets; mocked timers cannot prove UDP/TURN allocation cleanup.
  await platformExpiry(20_000)
  assert.equal(f.rtc.activeCount, 0); await f.rtc.drain()
  assert.equal((await f.control('/pair/rtc/offer', { offerId: randomUUID(), routeKind: 'p2p', sdp: pc.localDescription!.sdp })).status, 200)
  await f.pairing.revoke(f.credential.deviceId); assert.equal(f.rtc.activeCount, 0); await f.rtc.drain()
  f.rtc.close(); assert.equal(f.rtc.enabled, false)
})

test('unordered, text and extra-channel ingress retire physical RTC carriers without fallback', { ...linuxPairing, timeout: 90_000 }, async t => {
  const f = await fixture(t, rtcConfigSchema.parse({ enabled: true }))
  await assert.rejects(f.rtcDial('p2p', { raw: true, unordered: true })); await f.rtc.drain(); assert.equal(f.rtc.activeCount, 0)
  const text = await f.rtcDial('p2p', { raw: true }), textClosed = text.dc.stateChanged.watch(state => state === 'closed', 5000)
  text.dc.send('plaintext must never enter Noise'); await textClosed
  assert.equal(f.rtc.activeCount, 0)
  const extra = await f.rtcDial('p2p', { raw: true }), extraClosed = extra.dc.stateChanged.watch(state => state === 'closed', 5000)
  extra.pc.createDataChannel('extra', { protocol: 'twm-noise.v1', ordered: true }); await extraClosed
  assert.equal(f.rtc.activeCount, 0)
})

test('RTC pair proof is independent of encrypted signaling owner and wrong Noise key never admits native', { ...linuxPairing, timeout: 90_000 }, async t => {
  const f = await fixture(t, rtcConfigSchema.parse({ enabled: true }))
  const other = await pairNoiseClient({ origin: f.origin, ca: f.cert, descriptor: f.pairing.begin() })
  await assert.rejects(f.rtcDial('p2p', { noiseCredential: other }))
  await f.rtc.drain(); assert.equal(f.rtc.activeCount, 0)
  const wrong = generateNoiseStatic()
  try { await assert.rejects(f.rtcDial('p2p', { noiseCredential: { ...f.credential, privateKey: wrong.privateKey } })) } finally { wrong.privateKey.fill(0) }
  await f.rtc.drain(); assert.equal(f.rtc.activeCount, 0)
})

test('aborting encrypted signaling during real ICE gathering closes its pending PC and tasks', { ...linuxPairing, timeout: 30_000 }, async t => {
  // An actual bound UDP sink deliberately never replies: this is a declared
  // path-failure test, not fake STUN/TURN readiness or a timing sleep.
  const sink = createSocket('udp4'); sink.bind(0, '127.0.0.1'); await once(sink, 'listening'); t.after(() => sink.close())
  const f = await fixture(t, rtcConfigSchema.parse({ enabled: true, stunUrls: [`stun:127.0.0.1:${sink.address().port}`] }))
  const pc = new RTCPeerConnection(rtcPeerConfig(rtcConfigSchema.parse({ enabled: true }), 'p2p', f.credential.deviceId)); t.after(() => pc.close())
  pc.createDataChannel('tokenworks-native', { protocol: 'twm-noise.v1', ordered: true }); await pc.setLocalDescription(await pc.createOffer())
  const control = await openNoiseClient({ origin: f.origin, ca: f.cert, credential: f.credential, purpose: 'pairing-control' }); t.after(control.close)
  const request = exchange(control.stream, '/pair/rtc/offer', { offerId: randomUUID(), routeKind: 'p2p', sdp: pc.localDescription!.sdp })
  void request.catch(() => {})
  const hostPeer = await f.firstRtcPeer, closed = hostPeer.connectionStateChange.watch(state => state === 'closed', 5000)
  control.close(); await assert.rejects(request); await closed; await f.rtc.drain()
  assert.equal(f.rtc.activeCount, 0)
  assert.deepEqual(hostPeer.sctpTransport?.getCarrierQueueBytes(), { inbound: 0, outbound: 0, pendingWriteBytes: 0 })
})

test('operator-disabled RTC returns explicit unsupported through encrypted pair control without creating a PC', { ...linuxPairing, timeout: 30_000 }, async t => {
  const f = await fixture(t, rtcConfigSchema.parse({}))
  const config = await f.control('/pair/rtc/config')
  assert.equal(config.status, 200)
  const status = JSON.parse(config.body.toString())
  assert.equal(status.enabled, false); assert.deepEqual(status.capabilities, []); assert.deepEqual(status.stunUrls, []); assert.deepEqual(status.turnServers, [])
  assert.equal((await f.control('/pair/rtc/offer', { offerId: randomUUID(), routeKind: 'p2p', sdp: 'disabled must not gather' })).status, 404)
  assert.equal(f.rtc.activeCount, 0)
})

test('actual TURN failure returns exact availability without undeclared TCP; invalid SDP stays terminal', { ...linuxPairing, timeout: 30_000 }, async t => {
  const clientConfig = await isolatedTurn(t)
  const sink = createSocket('udp4'); sink.bind(0, '127.0.0.1'); await once(sink, 'listening'); t.after(() => sink.close())
  let tcpAttempts = 0
  const tcp = createServer(socket => { tcpAttempts++; socket.destroy() }); tcp.listen(sink.address().port, '127.0.0.1'); await once(tcp, 'listening')
  t.after(async () => { const closed = once(tcp, 'close'); tcp.close(); await closed })
  const config = rtcConfigSchema.parse({ enabled: true, turn: { urls: [`turn:127.0.0.1:${sink.address().port}?transport=udp`], sharedSecret: randomBytes(32).toString('base64url') } })
  const f = await fixture(t, config), pc = new RTCPeerConnection(rtcPeerConfig(clientConfig, 'turn', f.credential.deviceId)); t.after(() => pc.close())
  pc.createDataChannel('tokenworks-native', { protocol: 'twm-noise.v1', ordered: true }); await pc.setLocalDescription(await pc.createOffer())
  const unavailable = await f.control('/pair/rtc/offer', { offerId: randomUUID(), routeKind: 'turn', sdp: pc.localDescription!.sdp })
  assert.equal(unavailable.status, 503); assert.deepEqual(JSON.parse(unavailable.body.toString()), { code: 'rtc/unavailable' })
  await f.rtc.drain(); assert.equal(f.rtc.activeCount, 0)
  const hostPeer = await f.firstRtcPeer
  assert.deepEqual(hostPeer.sctpTransport?.getCarrierQueueBytes(), { inbound: 0, outbound: 0, pendingWriteBytes: 0 })
  assert.equal(tcpAttempts, 0, 'explicit UDP TURN must not silently retry TCP')
  const invalid = await f.control('/pair/rtc/offer', { offerId: randomUUID(), routeKind: 'p2p', sdp: pc.localDescription!.sdp })
  assert.equal(invalid.status, 409); assert.equal(f.rtc.activeCount, 0)
})

test('absent optional RTC module returns authenticated exact unsupported JSON', { ...linuxPairing, timeout: 30_000 }, async t => {
  const f = await fixture(t, rtcConfigSchema.parse({}), false)
  const result = await f.control('/pair/rtc/config')
  assert.equal(result.status, 404); assert.deepEqual(JSON.parse(result.body.toString()), { code: 'rtc/unsupported' })
  assert.equal(f.rtc.activeCount, 0)
})

async function nativeEvents(t: TestContext, stream: Duplex) {
  const ready = Promise.withResolvers<{ clientId: string }>(), waterfall = Promise.withResolvers<Record<string, unknown>>()
  void ready.promise.catch(() => {}); void waterfall.promise.catch(() => {})
  const frames: Record<string, unknown>[] = []
  let marker: { sessionId: string; pending: PromiseWithResolvers<void> } | undefined
  let follow: PromiseWithResolvers<Record<string, unknown>> | undefined
  let fault: Error | undefined
  const fail = (error: Error) => { fault = error; ready.reject(error); waterfall.reject(error); marker?.pending.reject(error); follow?.reject(error) }
  const decoder = new WireDecoder(fail), socket = new WebSocket('ws://native.invalid/api/remote.mux', { createConnection: () => stream as Socket, perMessageDeflate: false })
  t.after(() => { decoder.close(); socket.terminate() })
  socket.on('error', fail); socket.once('close', () => fail(new Error('test/native-events-closed')))
  socket.on('message', (data, binary) => {
    try {
      if (binary) throw new Error('test/native-event-text-required')
      const text = decoder.decode(data.toString()); if (text === undefined) return
      const frame = parseRemoteStreamServerMessage(text)
      if (frame.type === 'error' || frame.type === 'end') throw new Error(`test/native-event-${frame.type}`)
      if (frame.type !== 'item' || !frame.value || typeof frame.value !== 'object' || Array.isArray(frame.value)) return
      const value = frame.value as Record<string, unknown>
      if (frame.streamId === 'follow') { if (value.type === 'snapshot') follow?.resolve(value); return }
      if (frame.streamId !== 'events') return
      if (frames.length >= 64) throw new Error('test/native-events-budget')
      frames.push(value)
      if (value.type === 'ready' && typeof value.clientId === 'string') ready.resolve({ clientId: value.clientId })
      if (value.type === 'waterfall' && value.event === 'approval/request') waterfall.resolve(value)
      if (marker && value.type === 'emit' && value.event === 'api-session/status' && Array.isArray(value.args) && value.args[0] === marker.sessionId) marker.pending.resolve()
    } catch (error) { fail(error instanceof Error ? error : new Error('test/native-event-invalid')) }
  })
  await once(socket, 'open')
  socket.send(JSON.stringify({ type: 'open', streamId: 'events', endpoint: REMOTE_EVENT_STREAM_ENDPOINT, payload: { args: {} } }))
  const opened = await ready.promise
  return { socket, clientId: opened.clientId, frames, waterfall: waterfall.promise, get fault() { return fault },
    broadcast(sessionId: string) { const pending = Promise.withResolvers<void>(); marker = { sessionId, pending }; return pending.promise },
    async follow(sessionId: string) {
      follow = Promise.withResolvers<Record<string, unknown>>()
      socket.send(JSON.stringify({ type: 'open', streamId: 'follow', endpoint: 'session/follow', payload: { args: { request: { address: { kind: 'session', sessionId }, assistantStream: true } } } }))
      return follow.promise
    },
  }
}

for (const spec of [
  { name: 'viewer', role: 'viewer', installationOwner: false },
  { name: 'operator', role: 'operator', installationOwner: false },
  { name: 'partial-owner', role: 'owner', installationOwner: true },
] as const) test(`Relay ${spec.name} signaling retains restricted RTC authority, cannot answer owner waterfall, and revocation fences PC`, { ...linuxPairing, timeout: 45_000 }, async t => {
  const f = await fixture(t, rtcConfigSchema.parse({ enabled: true }))
  async function ownerRpc(method: string, args: object) {
    const carrier = await f.rtcDial('p2p'); assert.ok(carrier.noise)
    return exchange(carrier.noise.stream, `/api/${method}`, rpc(method, args))
  }
  const created = await ownerRpc('session/create', { request: { cwd: f.cwd, agentPreset: 'golden' } })
  const sessionId = JSON.parse(created.body.toString()).result.value.sessionId as string
  const sentinel = await ownerRpc('session/create', { request: { cwd: f.cwd, agentPreset: 'golden' } })
  const markerSession = JSON.parse(sentinel.body.toString()).result.value.sessionId as string
  const owner = { account_id: randomUUID(), organization_id: randomUUID(), deployment_sha256: 'a'.repeat(64), user_id: randomUUID(), device_id: randomUUID() }
  const bound = await pairNoiseClient({ origin: f.origin, ca: f.cert, descriptor: f.pairing.begin(), owner,
    relay: { issuer: f.origin, organizationId: owner.organization_id, deploymentSha256: owner.deployment_sha256, userId: owner.user_id, deviceId: owner.device_id } })
  // Repository-owned introspection facts only; real NativeAccess, installed SDK
  // policy/Gateway, TLS, Noise and ICE execute below. This is not a Go/PG claim.
  const bindings: NativeLease['bindings'] = [{ resource: { kind: 'session' }, actions: ['session/follow', 'event:api-session/status', ...(spec.role === 'operator' ? ['session/prompt'] : [])] }]
  const lease: NativeLease = { active: true, sub: owner.user_id, aud: f.descriptor.runtimeId, exp: Math.floor(Date.now() / 1000) + 120,
    client_id: 'rtc-authority-fixture', device_id: owner.device_id, runtime_id: f.descriptor.runtimeId, installation_id: f.descriptor.installationId,
    organization_id: owner.organization_id, grant_version: 1, role: spec.role, is_installation_owner: spec.installationOwner, bindings }
  const records = new Map<string, { credential: RelayLeaseCredential; lease: NativeLease; active: boolean }>()
  const issue = (facts: NativeLease) => { const credential = { leaseId: randomUUID(), token: randomBytes(32).toString('base64url') }; const record = { credential, lease: facts, active: true }; records.set(credential.leaseId, record); return record }
  const signalRecord = issue(lease), nativeRecord = issue({ ...lease, client_id: 'rtc-native-fixture' })
  let inspections = 0, signalPin: AbortSignal | undefined
  const access = new NativeAccess(f.ctx.connection, f.ctx.remoteAccess, f.descriptor.runtimeId, f.descriptor.installationId, async (id, token) => {
    inspections++
    const current = records.get(id)
    return current?.active && current.credential.token === token ? { ok: true, data: current.lease } : { ok: true, data: { active: false } }
  }, () => ({ sessions: [sessionId, markerSession], workspaces: [] }))
  t.after(() => access.close())
  f.carrier.setRelayAuthority(async (id, token, pairedOwner) => { const admitted = await access.admitFresh(id, token, pairedOwner); if (id === signalRecord.credential.leaseId) signalPin = admitted.signal; return admitted })
  const relay = createHttpsServer({ cert: f.cert, key: f.key }), wss = new WebSocketServer({ server: relay, perMessageDeflate: false, maxPayload: 65537 })
  wss.on('connection', (socket, request) => {
    const purpose = request.headers['x-tokenworks-carrier-purpose']
    if (purpose !== 'native' && purpose !== 'pairing-control') { socket.terminate(); return }
    void f.carrier.accept(createWebSocketStream(socket), { routeKind: 'relay', purpose, relayLease: signalRecord.credential })
  })
  relay.listen(0, '127.0.0.1'); await once(relay, 'listening'); const address = relay.address(); assert.ok(address && typeof address === 'object')
  t.after(async () => { for (const socket of wss.clients) socket.terminate(); wss.close(); const closed = once(relay, 'close'); relay.close(); await closed })
  const relayOrigin = `https://127.0.0.1:${address.port}`
  const options = { credential: bound, relayControlOrigin: relayOrigin, signalLease: signalRecord.credential, relayLease: nativeRecord.credential }
  const restricted = await f.rtcDial('p2p', options); assert.ok(restricted.noise)
  const events = await nativeEvents(t, restricted.noise.stream), restrictedPeer = f.peers.at(-1); assert.ok(restrictedPeer)
  assert.equal(restrictedPeer.principal.kind, 'tokenworks-device')
  assert.ok(inspections >= 3, 'control, independent retained fork and native AUTH each freshly introspect')
  const baseline = await events.follow(sessionId)
  const header = baseline.header
  assert.ok(header && typeof header === 'object' && 'id' in header)
  assert.equal(header.id, sessionId)
  const deniedCarrier = await f.rtcDial('p2p', options); assert.ok(deniedCarrier.noise)
  assert.equal(deniedCarrier.noise.ready.peerGeneration, restricted.noise.ready.peerGeneration)
  const denied = await exchange(deniedCarrier.noise.stream, '/api/session/list', rpc('session/list', { _request: {} }))
  assert.equal(JSON.parse(denied.body.toString()).result.ok, false); assert.strictEqual(f.peers.at(-1), restrictedPeer)
  const pendingPc = new RTCPeerConnection(rtcPeerConfig(rtcConfigSchema.parse({ enabled: true }), 'p2p', bound.deviceId)); t.after(() => pendingPc.close())
  pendingPc.createDataChannel('tokenworks-native', { protocol: 'twm-noise.v1', ordered: true }); await pendingPc.setLocalDescription(await pendingPc.createOffer())
  const pending = await f.control('/pair/rtc/offer', { offerId: randomUUID(), routeKind: 'p2p', sdp: pendingPc.localDescription!.sdp }, 'POST', bound, relayOrigin, signalRecord.credential)
  assert.equal(pending.status, 200)
  const pendingHost = f.rtcPeers.at(-1); assert.ok(pendingHost)
  assert.notEqual(pendingHost.connectionState, 'closed', 'normal signaling response must preserve the independent pending admission')
  const agent = f.ctx.agents.get(SessionId(sessionId)); assert.ok(agent)
  if (spec.role === 'operator') {
    const scoped = await f.rtcDial('p2p', options); assert.ok(scoped.noise)
    const prompted = await exchange(scoped.noise.stream, '/api/session/prompt', rpc('session/prompt', { request: { sessionId, requestId: randomUUID(), mode: 'queue', content: [{ type: 'text', text: 'Operator keeps its actual scoped prompt permission' }] } }))
    assert.equal(JSON.parse(prompted.body.toString()).result.ok, true); await agent.whenIdle()
  }
  const ownerCarrier = await f.rtcDial('p2p'); assert.ok(ownerCarrier.noise)
  const ownerEvents = await nativeEvents(t, ownerCarrier.noise.stream)
  const prompted = await ownerRpc('session/prompt', { request: { sessionId, requestId: randomUUID(), mode: 'queue', content: [{ type: 'text', text: 'REMOTE_APPROVAL_ALLOW' }] } })
  assert.equal(JSON.parse(prompted.body.toString()).result.ok, true)
  const waterfall = await ownerEvents.waterfall
  const barrier = events.broadcast(markerSession); f.ctx.emit('api-session/status', SessionId(markerSession), false); await barrier
  assert.equal(events.fault, undefined); assert.equal(events.frames.some(frame => frame.type === 'waterfall'), false)
  const answerCarrier = await f.rtcDial('p2p', options); assert.ok(answerCarrier.noise)
  const blocked = await exchange(answerCarrier.noise.stream, '/api/$events/result', rpc('$events/result', { clientId: ownerEvents.clientId, eventId: waterfall.eventId, outcome: { kind: 'result', value: 'allowed-once' } }))
  const refusal = JSON.parse(blocked.body.toString()); assert.equal(refusal.result.ok, false); assert.equal(refusal.result.error.code, 'gateway/invocation-unavailable')
  await assert.rejects(readFile(join(f.cwd, 'approval-allow.txt')), { code: 'ENOENT' })
  const answered = await ownerRpc('$events/result', { clientId: ownerEvents.clientId, eventId: waterfall.eventId, outcome: { kind: 'result', value: 'allowed-once' } })
  assert.equal(JSON.parse(answered.body.toString()).result.ok, true); await agent.whenIdle(); assert.equal(await readFile(join(f.cwd, 'approval-allow.txt'), 'utf8'), 'authorized')
  if (spec.role === 'viewer') {
    const stronger = issue({ ...lease, role: 'owner', is_installation_owner: true, grant_version: 2,
      bindings: PRODUCT_OWNER_BINDINGS.map(binding => ({ resource: { ...binding.resource }, actions: [...binding.actions] })) })
    const before = f.peers.length
    await assert.rejects(f.rtcDial('p2p', { ...options, relayLease: stronger.credential }))
    assert.equal(f.peers.length, before, 'fresh AUTH cannot upgrade the retained viewer signal cap')
    assert.equal(signalPin?.aborted, false)
  }
  const closed = once(events.socket, 'close')
  signalRecord.active = false
  await assert.rejects(openNoiseClient({ origin: relayOrigin, ca: f.cert, credential: { ...bound, origin: relayOrigin }, purpose: 'pairing-control', routeKind: 'relay', relayLease: signalRecord.credential }))
  await closed; await f.rtc.drain()
  assert.equal(signalPin?.aborted, true)
  assert.equal(pendingHost.connectionState, 'closed')
  assert.deepEqual(pendingHost.sctpTransport?.getCarrierQueueBytes(), { inbound: 0, outbound: 0, pendingWriteBytes: 0 })
  if (!restrictedPeer.signal.aborted) await once(restrictedPeer.signal, 'abort')
  assert.equal(restrictedPeer.signal.aborted, true)
})
