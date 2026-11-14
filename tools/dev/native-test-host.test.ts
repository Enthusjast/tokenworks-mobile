import assert from 'node:assert/strict'
import { test } from 'node:test'
import { randomUUID, X509Certificate } from 'node:crypto'
import { readFile, stat } from 'node:fs/promises'
import { request } from 'node:https'
import { startNativeTestHost } from './native-test-host.js'
import { captureNativeFixture, checkNativeCapture } from '../capture-native-golden.js'
import { noiseDpop, noiseHttpRequest, openNoiseClient, pairNoiseClient } from '../../host/remote-control/tests/noise-client.js'
import { once } from 'node:events'
import type { Socket } from 'node:net'
import WebSocket from 'ws'
import { REMOTE_STREAM_MUX_PATH, REMOTE_EVENT_STREAM_ENDPOINT, parseRemoteStreamServerMessage, type RemoteStreamServerMessage } from '@deepseek-ai/dsh-api-gateway/stream-protocol'
import { WireDecoder, WireEncoder } from '../../host/remote-control/src/wire-codec.js'

test('private isolated native Host serves CA-verified pairing and encrypted installed session/files APIs and disposes artifacts', { timeout: 90_000 }, async t => {
  const host = await startNativeTestHost()
  t.after(() => host.close())
  const fixture = host.descriptor
  assert.equal((await stat(fixture.artifacts.root)).mode & 0o777, 0o700)
  assert.equal((await stat(host.path)).mode & 0o777, 0o600)
  assert.equal(fixture.artifacts.descriptor, host.path)
  assert.equal(fixture.pairing.origin.startsWith('https://localhost:'), true)
  assert.equal(new X509Certificate(fixture.operatorCaPem).ca, true)
  await assert.rejects(stat(fixture.image.uploadedPath), { code: 'ENOENT' })
  const origin = fixture.pairing.origin
  async function publicHttp(path: string, body?: unknown, headers: Record<string, string> = {}, trust = true) {
    const result = Promise.withResolvers<{ status: number; body: string }>()
    const call = request(origin + path, { method: body === undefined ? 'GET' : 'POST', ca: trust ? fixture.operatorCaPem : undefined,
      family: 4, agent: false, signal: AbortSignal.timeout(10_000), headers: { 'content-type': 'application/json', ...headers } }, response => {
      const chunks: Buffer[] = []
      response.on('data', bytes => chunks.push(Buffer.from(bytes)))
      response.once('end', () => result.resolve({ status: response.statusCode!, body: Buffer.concat(chunks).toString() }))
      response.once('error', result.reject)
    })
    call.once('error', result.reject)
    call.end(body === undefined ? undefined : JSON.stringify(body))
    return result.promise
  }
  await assert.rejects(publicHttp('/pair/tls', undefined, {}, false))
  assert.equal((await publicHttp('/pair/tls')).status, 204)
  const unauthorized = await publicHttp('/fixture.json')
  assert.equal(unauthorized.status, 404)
  assert.equal(unauthorized.body.includes(fixture.pairing.code), false)
  const credential = await pairNoiseClient({ origin, ca: fixture.operatorCaPem, descriptor: fixture.pairing, owner: fixture.owner })
  t.after(() => credential.privateKey.fill(0))
  await assert.rejects(pairNoiseClient({ origin, ca: fixture.operatorCaPem, descriptor: fixture.pairing, owner: fixture.owner }))
  // Even a valid paired DPoP capability cannot reach public plaintext business APIs.
  assert.equal((await publicHttp('/api/session/list', {}, { authorization: `DPoP ${credential.token}`,
    'x-tokenworks-device-id': credential.deviceId, dpop: await noiseDpop(credential, 'POST', '/api/session/list') })).status, 404)
  const outer: Buffer[] = []
  const client = { origin, ca: fixture.operatorCaPem, credential, capture: (_direction: 'send' | 'receive', bytes: Uint8Array) => outer.push(Buffer.from(bytes)) }
  async function native(endpoint: string, args: unknown) {
    const rpcId = randomUUID()
    const response = await noiseHttpRequest(client, 'POST', `/api/${endpoint}`,
      Buffer.from(JSON.stringify({ type: 'client-request', rpcId, method: endpoint, payload: { args } })), { 'content-type': 'application/json' })
    assert.equal(response.status, 200)
    const envelope = JSON.parse(response.body.toString())
    assert.equal(envelope.rpcId, rpcId)
    assert.equal(envelope.result.ok, true)
    return envelope.result.value
  }
  const listed = await native('session/list', { _request: {} })
  assert.ok(listed.items.some((item: { sessionId: string }) => item.sessionId === fixture.sessionId))
  assert.equal((await native('terminal/environment', { agentId: fixture.sessionId })).cwd, fixture.artifacts.workspace)
  const terminal = await native('terminal/create', { agentId: fixture.sessionId, request: { id: 'support-proof', cols: 80, rows: 24 } })
  assert.equal(terminal.id, 'support-proof')
  await native('terminal/close', { agentId: fixture.sessionId, id: terminal.id })
  const image = Buffer.from(fixture.image.data, 'base64')
  const upload = await noiseHttpRequest(client, 'POST', `/api/session/uploadFileBinary?sessionId=${encodeURIComponent(fixture.sessionId)}&name=${encodeURIComponent(fixture.image.name)}`,
    image, { 'content-type': 'application/octet-stream' })
  assert.equal(upload.status, 200)
  const receipt = JSON.parse(upload.body.toString())
  assert.equal(receipt.ok, true)
  assert.equal(receipt.value.file.attachmentId, `sha256:${fixture.image.sha256}`)
  assert.deepEqual(await readFile(fixture.image.uploadedPath), image)
  const marker = 'Native encrypted support proof 原样🙂'
  assert.ok(await native('session/prompt', { request: { sessionId: fixture.sessionId, requestId: randomUUID(), mode: 'queue',
    content: [{ type: 'text', text: marker }, { type: 'file', receiptId: receipt.value.receiptId }] } }))
  const download = await noiseHttpRequest(client, 'GET', `/api/file?path=${encodeURIComponent(fixture.image.uploadedPath)}`)
  assert.equal(download.status, 200)
  assert.deepEqual(download.body, image)
  assert.ok(await native('session/projections', { request: { sessionId: fixture.sessionId } }))
  const carrier = await openNoiseClient(client)
  const socket = new WebSocket(`ws://localhost${REMOTE_STREAM_MUX_PATH}`, { createConnection: () => carrier.stream as Socket, perMessageDeflate: false })
  const follow = Promise.withResolvers<RemoteStreamServerMessage>(), events = Promise.withResolvers<RemoteStreamServerMessage>()
  const decoder = new WireDecoder(error => { follow.reject(error); events.reject(error) })
  socket.on('error', error => { follow.reject(error); events.reject(error) })
  socket.on('message', (bytes, binary) => {
    if (binary) { follow.reject(new Error('native mux requires its original text carrier')); return }
    const text = decoder.decode(bytes.toString())
    if (text === undefined) return
    const frame = parseRemoteStreamServerMessage(text)
    if (frame.streamId === 'follow') follow.resolve(frame)
    if (frame.streamId === 'events') events.resolve(frame)
  })
  try {
    await once(socket, 'open')
    const encoder = new WireEncoder()
    for (const frame of encoder.encode(JSON.stringify({ type: 'open', streamId: 'follow', endpoint: 'session/follow',
      payload: { args: { request: { address: { kind: 'session', sessionId: fixture.sessionId }, assistantStream: true } } } }))) socket.send(frame)
    const baseline = await follow.promise
    assert.ok(baseline.type === 'item')
    const snapshot = baseline.value
    assert.ok(snapshot && typeof snapshot === 'object' && 'type' in snapshot && 'header' in snapshot)
    assert.equal(snapshot.type, 'snapshot')
    const header = snapshot.header
    assert.ok(header && typeof header === 'object' && 'id' in header)
    assert.equal(header.id, fixture.sessionId)
    for (const frame of encoder.encode(JSON.stringify({ type: 'open', streamId: 'events', endpoint: REMOTE_EVENT_STREAM_ENDPOINT, payload: { args: {} } }))) socket.send(frame)
    const opening = await events.promise
    assert.ok(opening.type === 'item')
    const ready = opening.value
    assert.ok(ready && typeof ready === 'object' && 'type' in ready)
    assert.equal(ready.type, 'ready')
  } finally { decoder.close(); socket.terminate(); carrier.close() }
  for (const record of outer) {
    assert.equal(record.includes(Buffer.from(marker)), false)
    assert.equal(record.includes(Buffer.from(credential.token)), false)
  }
  const root = fixture.artifacts.root
  await host.close()
  await assert.rejects(stat(root), { code: 'ENOENT' })
  await assert.rejects(readFile(host.path), { code: 'ENOENT' })
  await assert.rejects(publicHttp('/pair/tls'))
})

test('reusable assembly preserves all 43 original native codec captures and provenance', { timeout: 90_000 }, async () => {
  const capture = await captureNativeFixture()
  checkNativeCapture(capture)
  assert.equal(capture.calls.length, 43)
  assert.equal(capture.execution.networkAttempts, 0)
  assert.equal(capture.execution.providers.some(name => name.includes('dsh-bash-local')), false)
})
