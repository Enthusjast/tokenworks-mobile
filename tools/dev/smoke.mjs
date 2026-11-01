/** Authenticate one local fixture phone; no model prompt or tool execution. */
import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { randomUUID, sign } from 'node:crypto'
import WebSocket from 'ws'
import { origin, state, phoneClient } from './fixture-host.mjs'
import { hostHelloDigest } from '../../host/remote-control/lib/relay-client.js'

async function main() {
  const runtime = JSON.parse(await readFile(resolve(state, 'enrolled-runtime.json'), 'utf8'))
  assert.equal(runtime.purpose, 'dsh-local-enrolled-runtime/v1')
  const phone = await phoneClient()
  const device = await phone.request('GET', '/v1/devices/current')
  assert.equal(device.state, 'active')
  const directory = await phone.request('GET', '/v1/runtimes')
  const visible = directory.items.find(item => item.id === runtime.runtimeId)
  assert.ok(visible)
  assert.equal(visible.access.role, 'owner')
  const sessions = await phone.request('GET', `/v1/runtimes/${runtime.runtimeId}/sessions`)
  assert.ok(Array.isArray(sessions.items))
  const ticket = await phone.request('POST', '/v1/realtime/tickets', { runtime_id: runtime.runtimeId,
    scope: { actions: ['session.read'], workspaces: [runtime.workspaces[0].workspaceId], sessions: [] } }, 201)
  const caps = ['command.relay.v1', 'connection.heartbeat.v1', 'session.read.v1']
  const socket = new WebSocket(origin.replace('http:', 'ws:') + '/v1/realtime', 'dsh.remote.v1', {
    headers: { authorization: 'DSH-Ticket ' + ticket.ticket }, perMessageDeflate: false, maxPayload: 65536,
  })
  let challengeSeen = false
  try {
    await new Promise((accept, reject) => {
      const timer = setTimeout(() => reject(new Error('local fixture handshake timeout')), 10000)
      const failure = () => { clearTimeout(timer); reject(new Error('local fixture handshake unavailable')) }
      socket.once('error', failure)
      socket.once('close', failure)
      socket.on('message', bytes => {
        try {
          const frame = JSON.parse(bytes.toString())
          if (frame.type === 'challenge') {
            assert.equal(challengeSeen, false)
            challengeSeen = true
            const claims = { iss: device.device.device_id, aud: 'urn:dsh-mobile-ustc:realtime',
              challenge_id: frame.payload.challenge_id, connection_id: frame.payload.connection_id, nonce: frame.payload.nonce,
              runtime_id: runtime.runtimeId, hello_sha256: hostHelloDigest(['device', device.device.device_id, '1', caps.join('\n')]),
              iat: Math.floor(Date.now() / 1000), jti: randomUUID() }
            const header = { alg: 'ES256', typ: 'dsh-relay-handshake-proof+jwt' }
            const input = [header, claims].map(value => Buffer.from(JSON.stringify(value)).toString('base64url')).join('.')
            const proof = input + '.' + sign('sha256', Buffer.from(input), { key: phone.proof.privateKey, dsaEncoding: 'ieee-p1363' }).toString('base64url')
            socket.send(JSON.stringify({ v: 1, type: 'hello.proof', payload: { device_id: device.device.device_id,
              protocols: [1], capabilities: caps, proof } }))
          } else if (frame.type === 'hello.ok') {
            assert.equal(challengeSeen, true)
            clearTimeout(timer)
            socket.removeListener('error', failure)
            socket.removeListener('close', failure)
            accept()
          } else if (frame.type === 'error') failure()
        } catch { failure() }
      })
    })
  } finally { socket.terminate() }
  const proof = { runtime_id: runtime.runtimeId, device_id: device.device.device_id,
    scoped_rest_directory: true, read_only_websocket_handshake: true, session_count: sessions.items.length }
  await writeFile(resolve(state, 'relay-smoke-proof.json'), JSON.stringify(proof, null, 2) + '\n', { mode: 0o600 })
  console.log(JSON.stringify(proof))
}
main().catch(() => { console.error('local development relay smoke failed; no operation was replayed'); process.exitCode = 1 })
