import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'
import { Context } from '@deepseek-ai/cordis'
import { registerDesktopUI } from '../src/desktop-ui.js'

test('desktop status is owner-only, read-only, fresh and removed with its plugin', async t => {
  const ctx = new Context()
  let handler: ((req: IncomingMessage, res: ServerResponse) => void) | undefined
  const set = ctx.provide.bind(ctx) as (key: string, value: unknown) => void
  const retired = new AbortController(); retired.abort()
  set('connection', { admit(req: IncomingMessage) {
    const role = req.headers['x-test-role']
    if (role === 'denied') return { rejection: 403 }
    if (!role) return { rejection: 401 }
    return { peer: { principal: { kind: role }, signal: role === 'retired' ? retired.signal : new AbortController().signal } }
  } })
  set('webServer', { register(route: { handler: typeof handler }) {
    handler = route.handler
    return () => { handler = undefined }
  } })
  let phase = 'connecting'
  const plugin = ctx.plugin({ name: 'desktop-fixture', apply(scope) {
    registerDesktopUI(scope, () => ({ runtimeId: 'runtime', epoch: 'epoch', relay: { origin: 'https://relay.example', phase },
      localSocket: true, protectedJournal: true, development: false, workspaces: [{ id: 'project', label: 'Project' }], presets: [] }))
  } })
  await plugin.await()
  const server = createServer((req, res) => { if (handler) handler(req, res); else { res.writeHead(404); res.end() } })
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve))
  t.after(async () => { await plugin.dispose(); await new Promise<void>(resolve => server.close(() => resolve())) })
  const address = server.address()
  assert.ok(address && typeof address === 'object')
  const url = `http://127.0.0.1:${address.port}/ustc-remote-control/status`
  for (const [role, expected] of [['', 401], ['denied', 403], ['operator', 403], ['retired', 403]] as const) {
    const result = await fetch(url, { headers: { 'x-test-role': role } })
    assert.equal(result.status, expected)
    assert.equal(await result.text(), '')
  }
  const headers = { 'x-test-role': 'owner' }
  assert.equal((await fetch(url, { method: 'POST', headers })).status, 405)
  const response = await fetch(url, { headers })
  assert.equal(response.headers.get('cache-control'), 'no-store')
  assert.equal((await response.json()).relay.phase, 'connecting')
  phase = 'connected'
  const value = await (await fetch(url, { headers })).json()
  assert.equal(value.relay.phase, 'connected')
  assert.equal(value.hotReload, false)
  assert.deepEqual(Object.keys(value).sort(), ['development', 'epoch', 'hotReload', 'localSocket', 'presets', 'protectedJournal', 'relay', 'runtimeId', 'workspaces'])
  await plugin.dispose()
  assert.equal((await fetch(url, { headers })).status, 404)
})
