import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { Duplex, PassThrough, Writable } from 'node:stream'
import { get } from 'node:http'
import { createServer } from 'node:net'
import { once } from 'node:events'
import { NativeGateway } from '../src/native-gateway.js'
import { REMOTE_STREAM_MUX_PATH } from '@deepseek-ai/dsh-api-gateway/stream-protocol'

const OWNER_PEER = {
  principal: { kind: 'owner' },
  signal: new AbortController().signal,
  async dispose() {},
} as never

function gatewayStub() {
  const fetches: { url: string; method: string; peer: unknown }[] = []
  const handle = {
    createSharedFetchHandler() {
      return {
        requestBodyMode: () => 'buffer' as const,
        async fetch(request: Request, peer: unknown) {
          fetches.push({ url: request.url, method: request.method, peer })
          return new Response(JSON.stringify({ echoed: request.method }), {
            status: 200, headers: { 'content-type': 'application/json', 'set-cookie': 'session=1; Path=/' },
          })
        },
      }
    },
  }
  const gatewayModel = {
    wireStream: {
      async open(_endpoint: string, _payload: unknown, _uplink: AsyncIterable<unknown>, _peer: unknown) {
        return (async function* () { yield { hello: true } })()
      },
      failure: (error: unknown) => ({ code: 'gateway/internal', message: String(error), details: {} }),
    },
  }
  return { fetches, gateway: new NativeGateway(handle as never, gatewayModel as never) }
}

function pair(): { socket: Duplex; client: Writable; toClient: PassThrough } {
  const toClient = new PassThrough()
  const socket = new Duplex({
    read() {},
    write(chunk, _enc, cb) { toClient.push(chunk); cb() },
  })
  const client = new Writable({
    write(chunk, _enc, cb) { socket.push(chunk); cb() },
    final(cb) { socket.push(null); cb() },
  })
  return { socket, client, toClient }
}

async function roundTrip(gateway: NativeGateway, raw: string): Promise<string> {
  const server = createServer(socket => gateway.accept(socket, OWNER_PEER))
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  const address = server.address()
  assert.ok(address && typeof address === 'object')
  const result = Promise.withResolvers<string>()
  const path = raw.split(' ')[1]!
  get({ hostname: '127.0.0.1', port: address.port, path, agent: false }, response => {
    const chunks: Buffer[] = []
    response.on('data', chunk => chunks.push(chunk))
    response.on('error', result.reject)
    response.on('end', () => result.resolve(`HTTP/1.1 ${response.statusCode} ${response.statusMessage}\\r\\n${
      response.rawHeaders.reduce((text, header, index) => text + header + (index % 2 ? '\\r\\n' : ': '), '')
    }\\r\\n${Buffer.concat(chunks).toString()}`))
  }).on('error', result.reject)
  try { return await result.promise }
  finally {
    gateway.close()
    const stopped = Promise.withResolvers<void>()
    server.close(() => stopped.resolve())
    await stopped.promise
  }
}

test('an admitted socket answers /api requests through sharedFetch', async () => {
  const { fetches, gateway } = gatewayStub()
  const response = await roundTrip(gateway, 'GET /api/session/list HTTP/1.1\r\nHost: dsh-api\r\n\r\n')
  assert.match(response, /HTTP\/1\.1 200 OK/)
  assert.match(response, /"echoed":"GET"/)
  assert.match(response, /set-cookie: session=1/i)
  assert.equal(fetches.length, 1)
  assert.equal((fetches[0]!.peer as { principal: { kind: string } }).principal.kind, 'owner')
  gateway.close()
})

test('requests outside /api are 404', async () => {
  const { fetches, gateway } = gatewayStub()
  const response = await roundTrip(gateway, 'GET /other HTTP/1.1\r\nHost: dsh-api\r\n\r\n')
  assert.match(response, /HTTP\/1\.1 404/)
  assert.equal(fetches.length, 0)
  gateway.close()
})

test('fetches always carry the admitted peer, never a default', async () => {
  const { fetches, gateway } = gatewayStub()
  await roundTrip(gateway, 'GET /api/session/list HTTP/1.1\r\nHost: dsh-api\r\n\r\n')
  assert.equal(fetches.length, 1)
  assert.equal((fetches[0]!.peer as { principal: { kind: string } }).principal.kind, 'owner')
  gateway.close()
})

test('a socket whose peer is aborted is destroyed on accept', () => {
  const { gateway } = gatewayStub()
  const controller = new AbortController()
  const peer = { principal: { kind: 'owner' }, signal: controller.signal, async dispose() {} } as never
  const { socket } = pair()
  controller.abort()
  gateway.accept(socket, peer)
  assert.equal(socket.destroyed, true)
  gateway.close()
})

test('close destroys all admitted sockets', () => {
  const { gateway } = gatewayStub()
  const { socket } = pair()
  gateway.accept(socket, OWNER_PEER)
  gateway.close()
  assert.equal(socket.destroyed, true)
})

test('the mux upgrade path is the reserved stream path', () => {
  assert.equal(REMOTE_STREAM_MUX_PATH, '/api/remote.mux')
})
