import { strict as assert } from 'node:assert'
import { test, type TestContext } from 'node:test'
import { EventEmitter, once, on } from 'node:events'
import { setImmediate } from 'node:timers/promises'
import { createServer } from 'node:net'
import { readFile } from 'node:fs/promises'
import { WebSocket, WebSocketServer } from 'ws'
import { Duplex } from 'node:stream'
import type { IncomingMessage } from 'node:http'
import { NativeGateway } from '../src/native-gateway.js'
import { WireDecoder, WireEncoder, WireError, WIRE_FRAME_BYTES, WIRE_LOGICAL_BYTES, WIRE_QUEUE_BYTES } from '../src/wire-codec.js'

type Open = (endpoint: string, payload: unknown, uplink: AsyncIterable<unknown>, peer: unknown, signal: AbortSignal) => Promise<AsyncIterable<unknown>>
async function connect(t: TestContext, open: Open, controller = new AbortController()) {
  const peer = { principal: { kind: 'owner' }, signal: controller.signal, async dispose() {} }
  const gateway = new NativeGateway({ createSharedFetchHandler: () => ({ fetch: async () => new Response('ok') }) } as never,
    { wireStream: { open, failure: () => ({ code: 'gateway/internal', message: 'failed', details: {} }) } } as never)
  const server = createServer(socket => gateway.accept(socket, peer as never))
  server.listen(0, '127.0.0.1'); await once(server, 'listening')
  const address = server.address()
  assert.ok(address && typeof address === 'object')
  const client = new WebSocket(`ws://127.0.0.1:${address.port}/api/remote.mux`)
  const messages = on(client, 'message', { signal: AbortSignal.timeout(10_000) })
  await once(client, 'open')
  const decoder = new WireDecoder(error => { throw error })
  const raw: string[] = []
  t.after(async () => {
    decoder.close(); await messages.return?.(); client.terminate(); gateway.close()
    const stopped = Promise.withResolvers<void>()
    server.close(() => stopped.resolve())
    await stopped.promise
  })
  return {
    client, controller, raw,
    send(value: unknown) { client.send(JSON.stringify(value)) },
    async next(): Promise<Record<string, any>> {
      while (true) {
        const event = await messages.next()
        assert.equal(event.done, false)
        const text = event.value![0].toString(); raw.push(text)
        const native = decoder.decode(text)
        if (native !== undefined) return JSON.parse(native)
      }
    },
  }
}

test('real HTTP upgrade preserves mixed durable frames and sequential deltas for a delayed consumer', async t => {
  const values = [{ type: 'snapshot', revision: 1 }, { type: 'assistant-stream', index: 0, text: '你' },
    { type: 'event', revision: 2 }, { type: 'assistant-stream', index: 1, text: '🙂' }]
  const produced = Promise.withResolvers<void>()
  const wire = await connect(t, async () => (async function* () {
    for (const value of values) yield value
    produced.resolve()
  })())
  wire.send({ type: 'open', streamId: '1', endpoint: 'session/follow', payload: {} })
  await produced.promise
  for (const value of values) assert.deepEqual(await wire.next(), { type: 'item', streamId: '1', value })
  assert.deepEqual(await wire.next(), { type: 'end', streamId: '1' })
  assert.equal(wire.raw.length, 5)
  assert.ok(wire.raw.every(text => JSON.parse(text).type !== 'wire.fragment'))
})

test('non-ASCII large native frames use ordered stable-id carrier fragments in both directions', async t => {
  const payload = '你🙂'.repeat(30_000)
  const wire = await connect(t, async (_endpoint, _payload, uplink) => (async function* () {
    for await (const value of uplink) yield value
  })())
  wire.send({ type: 'open', streamId: '1', endpoint: 'echo', payload: {} })
  const native = JSON.stringify({ type: 'item', streamId: '1', value: payload })
  for (const text of new WireEncoder().encode(native)) wire.client.send(text)
  wire.send({ type: 'end', streamId: '1' })
  assert.deepEqual(await wire.next(), JSON.parse(native))
  assert.equal(wire.raw.length, 4)
  for (const [index, text] of wire.raw.entries()) {
    const frame = JSON.parse(text)
    assert.equal(frame.type, 'wire.fragment'); assert.equal(frame.id, '1'); assert.equal(frame.index, index)
    assert.equal(frame.count, 4); assert.ok(Buffer.byteLength(text) <= WIRE_FRAME_BYTES)
  }
  assert.deepEqual(await wire.next(), { type: 'end', streamId: '1' })
})

test('3MiB item returns explicit stream error without poisoning this socket or a reconnect', async t => {
  const open: Open = async endpoint => (async function* () { yield endpoint === 'large' ? 'x'.repeat(3 * 1024 * 1024) : 'healthy' })()
  const wire = await connect(t, open)
  wire.send({ type: 'open', streamId: '1', endpoint: 'large', payload: {} })
  assert.deepEqual(await wire.next(), { type: 'error', streamId: '1', error: { code: 'wire/logical-limit', message: 'wire/logical-limit', details: {} } })
  wire.send({ type: 'open', streamId: '2', endpoint: 'small', payload: {} })
  assert.equal((await wire.next()).value, 'healthy'); assert.equal((await wire.next()).type, 'end')
  const reconnect = await connect(t, open)
  reconnect.send({ type: 'open', streamId: '1', endpoint: 'small', payload: {} })
  assert.equal((await reconnect.next()).value, 'healthy'); assert.equal((await reconnect.next()).type, 'end')
})

test('cancel releases an uplink reader and subsequent streams remain healthy', async t => {
  const entered = Promise.withResolvers<void>(), exited = Promise.withResolvers<void>()
  const wire = await connect(t, async (endpoint, _payload, uplink) => (async function* () {
    if (endpoint === 'healthy') { yield 'ok'; return }
    entered.resolve()
    try { for await (const value of uplink) yield value } finally { exited.resolve() }
  })())
  wire.send({ type: 'open', streamId: '1', endpoint: 'wait', payload: {} }); await entered.promise
  wire.send({ type: 'cancel', streamId: '1' }); await exited.promise
  wire.send({ type: 'open', streamId: '2', endpoint: 'healthy', payload: {} })
  assert.equal((await wire.next()).value, 'ok'); assert.equal((await wire.next()).type, 'end')
})

test('peer revocation aborts the source and closes a real upgraded WebSocket', async t => {
  const entered = Promise.withResolvers<void>(), aborted = Promise.withResolvers<void>()
  const wire = await connect(t, async (_endpoint, _payload, _uplink, _peer, signal) => (async function* () {
    entered.resolve()
    const stopped = Promise.withResolvers<void>()
    if (!signal.aborted) { signal.addEventListener('abort', () => stopped.resolve(), { once: true }); await stopped.promise }
    aborted.resolve()
  })())
  wire.send({ type: 'open', streamId: '1', endpoint: 'wait', payload: {} }); await entered.promise
  const closed = once(wire.client, 'close'); wire.controller.abort()
  const [code, reason] = await closed
  assert.equal(code, 1008); assert.equal(reason.toString(), 'wire/revoked'); await aborted.promise
})

test('real malformed or oversized carrier frames close explicitly and reject partial replay', async t => {
  const native = JSON.stringify({ type: 'item', streamId: '1', value: '你🙂'.repeat(30_000) })
  const frames = [...new WireEncoder().encode(native)]
  for (const sequence of [[frames[1]!], [frames[0]!, frames[0]!], [frames[0]!, JSON.stringify({ ...JSON.parse(frames[1]!), id: '2' })],
    ['{bad'], ['x'.repeat(3 * 1024 * 1024)]]) {
    const wire = await connect(t, async () => (async function* () {})())
    const closed = once(wire.client, 'close')
    for (const frame of sequence) wire.client.send(frame)
    const [code] = await closed
    assert.ok(code === 1008 || code === 1009, `explicit protocol/size close: ${code}`)
  }
})

test('non-consuming uplink hits bounded count admission and aborts instead of accumulating', async t => {
  const entered = Promise.withResolvers<void>(), aborted = Promise.withResolvers<void>()
  const wire = await connect(t, async (_endpoint, _payload, _uplink, _peer, signal) => (async function* () {
    entered.resolve()
    const stopped = Promise.withResolvers<void>()
    if (!signal.aborted) { signal.addEventListener('abort', () => stopped.resolve(), { once: true }); await stopped.promise }
    aborted.resolve()
  })())
  wire.send({ type: 'open', streamId: '1', endpoint: 'blocked', payload: {} }); await entered.promise
  const closed = once(wire.client, 'close')
  for (let index = 0; index < 257; index++) wire.send({ type: 'item', streamId: '1', value: index })
  const [code, reason] = await closed
  assert.equal(code, 1013); assert.equal(reason.toString(), 'wire/slow-peer'); await aborted.promise
})

test('shared carrier cases preserve exact Unicode bytes and reject order, replay and integrity failures', async () => {
  const spec = JSON.parse(await readFile(new URL('../../../contracts/golden/wire-fragment.json', import.meta.url), 'utf8'))
  const native = JSON.stringify({ type: 'item', streamId: '1', value: spec.text.repeat(spec.repeat) })
  const encoder = new WireEncoder(), frames = [...encoder.encode(native)]
  assert.equal(frames.length, spec.count)
  for (const text of frames) {
    const frame = JSON.parse(text)
    assert.equal(frame.bytes, spec.bytes); assert.equal(frame.sha256, spec.sha256)
  }
  for (const scenario of spec.cases) {
    const decoder = new WireDecoder(() => {}), results: string[] = []
    const decode = () => {
      for (const index of scenario.indices) {
        const text = scenario.checksum ? JSON.stringify({ ...JSON.parse(frames[index]!), sha256: scenario.checksum }) : frames[index]!
        const result = decoder.decode(text); if (result !== undefined) results.push(result)
      }
    }
    try {
      if (scenario.error) assert.throws(decode, { code: scenario.error }, scenario.name)
      else { decode(); assert.deepEqual(results, [native]); assert.equal(results.length, scenario.deliveries) }
      assert.equal(decoder.bufferedBytes, 0)
    } finally { decoder.close() }
  }
  assert.equal(JSON.parse([...encoder.encode(native)][0]!).id, '2')
})

test('decoder rejects duplicate, order, metadata, checksum, interruption and clears memory', () => {
  const native = JSON.stringify({ type: 'item', streamId: '1', value: '你🙂'.repeat(30_000) })
  const frames = [...new WireEncoder().encode(native)]
  const cases = [[frames[1]!], [frames[0]!, frames[0]!],
    [frames[0]!, JSON.stringify({ ...JSON.parse(frames[1]!), bytes: 210042 })],
    frames.map(text => JSON.stringify({ ...JSON.parse(text), sha256: '0'.repeat(64) })),
    [frames[0]!, '{"type":"end","streamId":"1"}']]
  for (const sequence of cases) {
    const decoder = new WireDecoder(() => {})
    assert.throws(() => sequence.forEach(text => decoder.decode(text)), WireError)
    assert.equal(decoder.bufferedBytes, 0)
  }
})

test('fragment timeout and explicit revocation release memory and reject remainder', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const spec = JSON.parse(await readFile(new URL('../../../contracts/golden/wire-fragment.json', import.meta.url), 'utf8'))
  const native = JSON.stringify({ type: 'item', streamId: '1', value: spec.text.repeat(spec.repeat) })
  const frames = [...new WireEncoder().encode(native)]
  const expired = Promise.withResolvers<WireError>()
  const decoder = new WireDecoder(expired.resolve, spec.timeoutMs)
  try {
    decoder.decode(frames[0]!)
    assert.equal(decoder.bufferedBytes, spec.bytes)
    t.mock.timers.tick(spec.timeoutMs)
    assert.equal((await expired.promise).code, 'wire/fragment-timeout'); assert.equal(decoder.bufferedBytes, 0)
    assert.throws(() => decoder.decode(frames[0]!), { code: 'wire/closed' })
    const disconnected = new WireDecoder(() => {})
    for (const text of frames.slice(0, spec.partialDisconnectAfter)) disconnected.decode(text)
    disconnected.close(); disconnected.close()
    assert.equal(disconnected.bufferedBytes, 0)
    assert.throws(() => disconnected.decode(frames[1]!), { code: 'wire/closed' })
    const reconnect = new WireDecoder(() => {})
    assert.deepEqual(frames.map(text => reconnect.decode(text)).filter(text => text !== undefined), [native])
    reconnect.close()
  } finally { decoder.close() }
})

test('exact frame and logical limits preserve large ASCII frames without exceeding carrier limits', () => {
  for (const size of [WIRE_FRAME_BYTES, WIRE_FRAME_BYTES + 1, WIRE_LOGICAL_BYTES]) {
    const prefix = '{"type":"item","streamId":"1","value":"'
    const native = prefix + 'x'.repeat(size - prefix.length - 2) + '"}'
    const frames = [...new WireEncoder().encode(native)], decoder = new WireDecoder(() => {})
    assert.equal(frames.length === 1, size <= WIRE_FRAME_BYTES)
    assert.ok(frames.every(text => Buffer.byteLength(text) <= WIRE_FRAME_BYTES))
    assert.deepEqual(frames.map(text => decoder.decode(text)).filter(text => text !== undefined), [native])
    decoder.close()
  }
  assert.throws(() => [...new WireEncoder().encode('x'.repeat(WIRE_LOGICAL_BYTES + 1))], { code: 'wire/logical-limit' })
})

class ControlledSocket extends EventEmitter {
  readonly OPEN = 1
  readyState = 1
  bufferedAmount = 0
  readonly sent: { text: string; done: (error?: Error) => void }[] = []
  readonly closings: { code: number; reason?: string }[] = []
  send(text: string, done: (error?: Error) => void) { this.sent.push({ text, done }) }
  close(code: number, reason?: string) {
    if (this.readyState !== this.OPEN) return
    this.closings.push({ code, ...(reason === undefined ? {} : { reason }) }); this.readyState = 3; this.emit('close')
  }
  terminate() { this.close(1006) }
  message(value: unknown) { this.emit('message', Buffer.from(JSON.stringify(value)), false) }
}
async function controlled(t: TestContext, open: Open) {
  const socket = new ControlledSocket(), controller = new AbortController()
  const gateway = new NativeGateway({ createSharedFetchHandler: () => ({ fetch: async () => new Response('ok') }) } as never,
    { wireStream: { open, failure: () => ({ code: 'gateway/internal', message: 'failed', details: {} }) } } as never)
  const accepted = Promise.withResolvers<void>()
  const upgrade = t.mock.method(WebSocketServer.prototype, 'handleUpgrade', (_request: IncomingMessage, _socket: Duplex, _head: Buffer, callback: (client: WebSocket, request: IncomingMessage) => void) => { callback(socket as never, _request); accepted.resolve() })
  const carrier = new Duplex({ read() {}, write(_chunk, _encoding, callback) { callback() } })
  carrier.on('error', () => {})
  gateway.accept(carrier, { principal: { kind: 'owner' }, signal: controller.signal } as never)
  carrier.push(Buffer.from('GET /api/remote.mux HTTP/1.1\r\nHost: dsh-native.invalid\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n'))
  try { await accepted.promise } finally { upgrade.mock.restore() }
  t.after(() => { gateway.close(); socket.terminate(); carrier.destroy() })
  return socket
}

test('stream cancellation completes an admitted fragmented write without poisoning other streams', async t => {
  const payload = '你🙂'.repeat(30_000)
  const socket = await controlled(t, async endpoint => (async function* () { yield endpoint === 'large' ? payload : 'healthy' })())
  socket.message({ type: 'open', streamId: '1', endpoint: 'large', payload: {} })
  await setImmediate(); assert.equal(socket.sent.length, 1)
  socket.message({ type: 'cancel', streamId: '1' })
  socket.message({ type: 'open', streamId: '2', endpoint: 'small', payload: {} })
  for (let index = 0; index < 6; index++) { assert.ok(socket.sent[index]); socket.sent[index]!.done(); await setImmediate() }
  assert.equal(socket.closings.length, 0)
  const decoder = new WireDecoder(() => {})
  const native = socket.sent.map(frame => decoder.decode(frame.text)).filter(text => text !== undefined).map(text => JSON.parse(text!))
  assert.deepEqual(native, [{ type: 'item', streamId: '1', value: payload }, { type: 'item', streamId: '2', value: 'healthy' }, { type: 'end', streamId: '2' }])
  decoder.close()
})

test('a stalled write explicitly fails after its deadline and disposes the source', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  let aborted = false
  const socket = await controlled(t, async (_endpoint, _payload, _uplink, _peer, signal) => {
    signal.addEventListener('abort', () => { aborted = true }, { once: true })
    return (async function* () { yield 'blocked' })()
  })
  socket.message({ type: 'open', streamId: '1', endpoint: 'x', payload: {} }); await setImmediate()
  assert.equal(socket.sent.length, 1)
  t.mock.timers.tick(30_000); await setImmediate()
  assert.deepEqual(socket.closings, [{ code: 1013, reason: 'wire/slow-peer' }]); assert.equal(aborted, true)
})

test('socket byte pressure and write failures are explicit rather than dropping frames', async t => {
  for (const mode of ['pressure', 'failure'] as const) {
    const socket = await controlled(t, async () => (async function* () { yield 'kept-or-failed' })())
    if (mode === 'pressure') socket.bufferedAmount = WIRE_QUEUE_BYTES
    socket.message({ type: 'open', streamId: '1', endpoint: 'x', payload: {} }); await setImmediate()
    if (mode === 'failure') { socket.sent[0]!.done(new Error('private')); await setImmediate() }
    assert.deepEqual(socket.closings, [{ code: mode === 'pressure' ? 1013 : 1011, reason: mode === 'pressure' ? 'wire/slow-peer' : 'wire/write-failed' }])
  }
})

test('disconnecting a real partial assembly never leaks bytes or ids into a reconnect', async t => {
  const payload = '你🙂'.repeat(30_000)
  const frames = [...new WireEncoder().encode(JSON.stringify({ type: 'item', streamId: '1', value: payload }))]
  const open: Open = async (_endpoint, _payload, uplink) => (async function* () { for await (const value of uplink) yield value })()
  const partial = await connect(t, open)
  partial.send({ type: 'open', streamId: '1', endpoint: 'echo', payload: {} }); partial.client.send(frames[0]!)
  partial.client.terminate(); await once(partial.client, 'close')
  const fresh = await connect(t, open)
  fresh.send({ type: 'open', streamId: '1', endpoint: 'echo', payload: {} })
  for (const text of frames) fresh.client.send(text)
  fresh.send({ type: 'end', streamId: '1' })
  assert.equal((await fresh.next()).value, payload); assert.equal((await fresh.next()).type, 'end')
})
