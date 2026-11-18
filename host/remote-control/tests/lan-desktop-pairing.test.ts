import test from 'node:test'
import assert from 'node:assert/strict'
import { Context } from '@deepseek-ai/cordis'
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'
import { execFileSync } from 'node:child_process'
import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { createCipheriv, createDecipheriv, randomBytes, randomUUID } from 'node:crypto'
import { once } from 'node:events'
import { host } from './harness.js'
import { registerDesktopUI } from '../src/desktop-ui.js'
import { LanPairing } from '../src/lan-pairing.js'
import { LanPairingStore } from '../src/lan-pairing-store.js'
import type { JournalKeyProtector } from '../src/journal-key-protection.js'

test('Desktop settings exposes real QR only to an owner and guards pairing mutations against CSRF', {
  timeout: 30_000, skip: process.platform !== 'linux' && 'requires the Linux private pairing store',
}, async t => {
  const f = await host({ probe: false }); t.after(() => f.close())
  const certPath = join(f.directory, 'desktop-cert.pem'), keyPath = join(f.directory, 'desktop-key.pem')
  execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', keyPath, '-out', certPath, '-days', '1', '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost'], { stdio: 'ignore' })
  const master = randomBytes(32)
  const protector: JournalKeyProtector = { id: 'desktop-real-test-key', async wrap(bytes, context) {
    const iv = randomBytes(12), cipher = createCipheriv('aes-256-gcm', master, iv); cipher.setAAD(context)
    return Buffer.concat([iv, cipher.update(bytes), cipher.final(), cipher.getAuthTag()])
  }, async unwrap(bytes, context) {
    const raw = Buffer.from(bytes), cipher = createDecipheriv('aes-256-gcm', master, raw.subarray(0, 12)); cipher.setAAD(context); cipher.setAuthTag(raw.subarray(-16))
    return Buffer.concat([cipher.update(raw.subarray(12, -16)), cipher.final()])
  } }
  const pairing = await LanPairing.open({ origin: 'https://localhost:8443', runtimeId: f.runtimeId, installationId: 'bbbbbbbb-2222-4222-8222-222222222222' },
    await readFile(certPath, 'utf8'), new LanPairingStore(join(f.directory, 'paired'), f.runtimeId, 'bbbbbbbb-2222-4222-8222-222222222222', protector), f.ctx.connection)
  t.after(() => pairing.close())
  const ctx = new Context(), routes = new Map<string, (req: IncomingMessage, res: ServerResponse) => void>()
  const provide = ctx.provide.bind(ctx) as (name: string, value: unknown) => void
  // Authentication is the existing browser-admission DI boundary; the pairing/network service above is real.
  provide('connection', { admit(req: IncomingMessage) {
    const kind = req.headers['x-test-role']; return kind ? { peer: { principal: { kind }, signal: new AbortController().signal } } : { rejection: 401 }
  } })
  provide('webServer', { register(route: { path: string; handler: (req: IncomingMessage, res: ServerResponse) => void }) {
    routes.set(route.path, route.handler); return () => routes.delete(route.path)
  } })
  const plugin = ctx.plugin({ name: 'lan-desktop-test', apply(scope) {
    registerDesktopUI(scope, () => ({ runtimeId: f.runtimeId, relay: null, lan: { host: '127.0.0.1', port: 8443, phase: 'listening', ...pairing.identity }, development: false }), pairing)
  } }); await plugin.await()
  const server = createServer((req, res) => { const route = routes.get(req.url!); if (route) route(req, res); else res.writeHead(404).end() })
  const listening = once(server, 'listening'); server.listen(0, '127.0.0.1'); await listening
  t.after(async () => { await plugin.dispose(); const closed = once(server, 'close'); server.close(); await closed })
  const address = server.address(); assert.ok(address && typeof address === 'object')
  const origin = `http://127.0.0.1:${address.port}`, url = origin + '/tokenworks-mobile/pairing'
  assert.equal((await fetch(url)).status, 401)
  assert.equal((await fetch(url, { headers: { 'x-test-role': 'operator' } })).status, 403)
  assert.equal((await fetch(url, { method: 'POST', headers: { 'x-test-role': 'owner', 'content-type': 'application/json', origin: 'https://evil.example' }, body: '{}' })).status, 403)
  assert.equal((await fetch(url, { method: 'POST', headers: { 'x-test-role': 'owner', 'content-type': 'application/json' }, body: '{}' })).status, 403)
  const response = await fetch(url, { method: 'POST', headers: { 'x-test-role': 'owner', 'content-type': 'application/json', origin }, body: '{}' })
  assert.equal(response.status, 201); assert.equal(response.headers.get('cache-control'), 'no-store')
  const offer = await response.json() as { descriptor: { runtimeId: string; origin: string; tlsPin: string; code: string }; qrSvg: string }
  assert.equal(offer.descriptor.runtimeId, f.runtimeId); assert.equal(offer.descriptor.origin, 'https://localhost:8443'); assert.match(offer.descriptor.code, /^\d{8}$/)
  assert.match(offer.qrSvg, /^<svg/); assert.match(offer.qrSvg, /<path/); assert.ok(offer.qrSvg.length > 1000)
  const status = await (await fetch(origin + '/tokenworks-mobile/status', { headers: { 'x-test-role': 'owner' } })).text()
  assert.equal(status.includes(offer.descriptor.code), false)
  const pairedStatus = await (await fetch(url, { headers: { 'x-test-role': 'owner' } })).json() as { devices: unknown[]; hostKeyEpoch: number }
  assert.deepEqual(pairedStatus.devices, []); assert.equal(pairedStatus.hostKeyEpoch, 1)
  const operationId = randomUUID(), rotateUrl = origin + '/tokenworks-mobile/pairing/rotate-host'
  assert.equal((await fetch(rotateUrl, { method: 'POST', headers: { 'x-test-role': 'owner', 'content-type': 'application/json', origin }, body: JSON.stringify({ operationId, confirm: 'wrong' }) })).status, 400)
  const rotated = await fetch(rotateUrl, { method: 'POST', headers: { 'x-test-role': 'owner', 'content-type': 'application/json', origin }, body: JSON.stringify({ operationId, confirm: 'rotate-and-revoke-all' }) })
  assert.equal(rotated.status, 200); assert.equal((await rotated.json() as { hostKeyEpoch: number }).hostKeyEpoch, 2)
  assert.equal(pairing.status().hostKeyEpoch, 2)
  await plugin.dispose(); assert.equal((await fetch(url, { headers: { 'x-test-role': 'owner' } })).status, 404)
})
