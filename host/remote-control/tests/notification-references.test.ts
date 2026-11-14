import assert from 'node:assert/strict'
import { createHash, createPublicKey, generateKeyPairSync, randomUUID, sign, verify } from 'node:crypto'
import { once } from 'node:events'
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'
import { setTimeout as delay } from 'node:timers/promises'
import test, { type TestContext } from 'node:test'
import { Context } from '@deepseek-ai/cordis'
import { createUserMessage } from '@deepseek-ai/dsh-llm'
import { SessionId, SessionStore } from '@deepseek-ai/dsh-session'
import { NotificationReferences } from '../src/notification-references.js'
import type { HostConnectionCredential, HostCredentialProvider, InstallationPublicJwk } from '../src/host-connection-credential.js'
import { restEnvelopeHeader, restEnvelopeVersion } from '../src/rest-envelope.js'
import { host } from './harness.js'

const runtimeId = '32d8b403-5d6e-4fdc-8aa1-e3648a09b877'
const sensitive = 'PRIVATE-title-prompt-tool-arguments-audio-NEVER-PUSH'
async function until(condition: () => boolean): Promise<void> {
  const deadline = Date.now() + 5000
  while (!condition()) { assert.ok(Date.now() < deadline, 'reference operation did not settle'); await delay(5) }
}
function credentialFixture(): { credential: HostConnectionCredential; provider: HostCredentialProvider } {
  const keys = generateKeyPairSync('ec', { namedCurve: 'P-256' })
  const jwk = keys.publicKey.export({ format: 'jwk' })
  const credential: HostConnectionCredential = {
    installationId: randomUUID(), credential: Buffer.alloc(32, 7).toString('base64url'),
    publicJwk: { kty: 'EC', crv: 'P-256', x: jwk.x!, y: jwk.y! },
    signer: { sign: input => sign('sha256', input, { key: keys.privateKey, dsaEncoding: 'ieee-p1363' }) },
  }
  return { credential, provider: { async acquire(signal) { signal.throwIfAborted(); return credential } } }
}
interface Attempt { body: Record<string, unknown>; proof: Record<string, unknown> }
async function service(t: TestContext, credential: HostConnectionCredential, mode: 'nonce' | 'denied' | 'unsupported' | 'invalid-proof' = 'nonce') {
  const attempts: Attempt[] = [], deliveries: Record<string, unknown>[] = [], errors: unknown[] = []
  const seen = new Set<string>(), challenge = Buffer.alloc(32, 3).toString('base64url')
  const key = createPublicKey({ key: { ...credential.publicJwk }, format: 'jwk' })
  let origin = ''
  const reply = (res: ServerResponse, status: number, value: object, headers: Record<string, string> = {}) => {
    const requestId = randomUUID()
    res.writeHead(status, { 'content-type': 'application/json', [restEnvelopeHeader]: restEnvelopeVersion, 'x-request-id': requestId, ...headers })
    res.end(JSON.stringify({ ...value, request_id: requestId }))
  }
  const server = createServer((req: IncomingMessage, res: ServerResponse) => { void (async () => {
    assert.equal(req.method, 'POST'); assert.equal(req.url, '/v1/push/references')
    assert.equal(req.headers.authorization, `DPoP ${credential.credential}`)
    assert.equal(req.headers[restEnvelopeHeader], restEnvelopeVersion)
    const parts = String(req.headers.dpop).split('.')
    assert.equal(parts.length, 3)
    const header = JSON.parse(Buffer.from(parts[0]!, 'base64url').toString()) as { typ: string; alg: string; jwk: InstallationPublicJwk }
    const proof = JSON.parse(Buffer.from(parts[1]!, 'base64url').toString()) as Record<string, unknown>
    assert.equal(header.typ, 'dpop+jwt'); assert.equal(header.alg, 'ES256'); assert.deepEqual(header.jwk, credential.publicJwk)
    assert.equal(proof.htm, 'POST'); assert.equal(proof.htu, `${origin}/v1/push/references`)
    assert.equal(proof.ath, createHash('sha256').update(credential.credential).digest('base64url'))
    assert.equal(typeof proof.iat, 'number'); assert.ok(Math.abs(Date.now() / 1000 - Number(proof.iat)) < 5)
    assert.equal(typeof proof.jti, 'string'); assert.ok(!seen.has(String(proof.jti))); seen.add(String(proof.jti))
    const chunks: Buffer[] = []
    for await (const chunk of req) chunks.push(Buffer.from(chunk))
    const body = JSON.parse(Buffer.concat(chunks).toString()) as Record<string, unknown>
    attempts.push({ body, proof })
    const validSignature = verify('sha256', Buffer.from(`${parts[0]}.${parts[1]}`), { key, dsaEncoding: 'ieee-p1363' }, Buffer.from(parts[2]!, 'base64url'))
    if (mode === 'invalid-proof') {
      assert.equal(validSignature, false)
      reply(res, 401, { ok: false, error: { code: 'auth.proof_invalid' } }); return
    }
    assert.ok(validSignature)
    if (mode === 'denied') { reply(res, 403, { ok: false, error: { code: 'auth.forbidden' } }); return }
    if (mode === 'unsupported') { reply(res, 501, { ok: false, error: { code: 'push.unsupported' } }); return }
    if (proof.nonce !== challenge) {
      reply(res, 401, { ok: false, error: { code: 'auth.proof_invalid' } }, { 'dpop-nonce': challenge, 'www-authenticate': 'DPoP error="use_dpop_nonce"' }); return
    }
    assert.deepEqual(Object.keys(body).sort(), ['expires_at', 'kind', 'notification_id', 'runtime_id', 'session_id'])
    assert.match(String(body.notification_id), /^[0-9a-f-]{36}$/)
    assert.equal(body.runtime_id, runtimeId)
    assert.ok(Date.parse(String(body.expires_at)) > Date.now())
    assert.ok(Date.parse(String(body.expires_at)) <= Date.now() + 900_000)
    assert.ok(!JSON.stringify(body).includes(sensitive))
    deliveries.push(body)
    reply(res, 200, { ok: true, data: { accepted: true } })
  })().catch(error => { errors.push(error); reply(res, 500, { ok: false, error: { code: 'fixture.failure' } }) }) })
  server.listen(0, '127.0.0.1'); await once(server, 'listening')
  const address = server.address(); assert.ok(address && typeof address === 'object')
  origin = `http://127.0.0.1:${address.port}`
  t.after(async () => { server.closeAllConnections(); const closed = Promise.withResolvers<void>(); server.close(() => closed.resolve()); await closed.promise; assert.deepEqual(errors, []) })
  return { origin, attempts, deliveries }
}

test('installed native approval, question and completed-turn events emit authenticated content-free references', async t => {
  const fixture = await host({ probe: false })
  t.after(() => fixture.close())
  const { ctx } = fixture
  const credentials = credentialFixture(), endpoint = await service(t, credentials.credential)
  // A pre-existing connected answerer must still see the exact original request.
  ctx.on('user-questions/request', async request => ({ answers: request.questions.map(question => ({ id: question.id, selected: [] })) }))
  ctx.on('approval/request', async () => 'allowed-once')
  const references = new NotificationReferences(ctx, { enabled: true, origin: endpoint.origin, runtimeId, credentials: credentials.provider, allowLoopbackHttp: true })
  t.after(() => references.close())
  const handle = await ctx.agents.create({ sessionId: SessionId('opaque-native-session'), meta: { cwd: fixture.cwd }, agentOptions: { provider: 'fixture', model: 'fixture' } })
  t.after(() => handle.dispose())
  const held = fixture.model.holdNext()
  handle.agent.followup(createUserMessage({ source: { kind: 'user' }, content: [{ type: 'text', text: sensitive }] }))
  await held.started
  assert.equal(await ctx.approval.request({ agent: handle.agent, toolName: sensitive, reason: sensitive }), 'allowed-once')
  const answer = await ctx.userQuestions.ask({ agent: handle.agent, questions: [{ id: 'question-1', question: sensitive, header: sensitive, detail: sensitive }] })
  assert.deepEqual(answer, { answers: [{ id: 'question-1', selected: [] }] })
  await until(() => endpoint.deliveries.length === 2)
  held.release(); await handle.agent.whenIdle()
  await until(() => endpoint.deliveries.length === 3 && references.status() === 'accepted')
  assert.deepEqual(endpoint.deliveries.map(body => body.kind).sort(), ['completed', 'interaction', 'interaction'])
  assert.ok(endpoint.deliveries.every(body => body.session_id === 'opaque-native-session'))
  assert.equal(new Set(endpoint.deliveries.map(body => body.notification_id)).size, 3)
  assert.equal(endpoint.attempts.length, 6)
  for (const delivery of endpoint.deliveries) {
    const attempts = endpoint.attempts.filter(attempt => attempt.body.notification_id === delivery.notification_id)
    assert.equal(attempts.length, 2); assert.deepEqual(attempts[0]!.body, attempts[1]!.body)
  }
  // Non-completion and content events do not manufacture another push.
  const session = ctx.sessions.create(SessionId('not-completed'))
  session.append('turn/start', { turn: 1 })
  session.append('turn/end', { turn: 1, reason: { kind: 'blocked' } })
  await delay(20)
  assert.equal(endpoint.deliveries.length, 3)
  assert.deepEqual(fixture.errors, [])
})

test('opt-in is disabled by default and binds the authenticated installation origin', async t => {
  const ctx = new Context(), credentials = credentialFixture()
  t.after(() => ctx.fiber.dispose())
  let acquired = 0
  const references = new NotificationReferences(ctx, { origin: 'https://service.example', runtimeId, credentials: { async acquire() { acquired++; return credentials.credential } } })
  references.emit('session', 'completed')
  assert.equal(references.status(), 'disabled'); assert.equal(acquired, 0)
  await references.close()
  for (const options of [
    { origin: 'http://service.example' }, { origin: 'https://service.example/path' },
    { origin: 'https://service.example', credentials: { ...credentials.provider, origin: 'https://other.example' } },
    { origin: 'https://service.example', ttlSeconds: 901 }, { origin: 'https://service.example', runtimeId: 'not-a-runtime-uuid' },
  ]) assert.throws(() => new NotificationReferences(ctx, { enabled: true, runtimeId, credentials: credentials.provider, ...options }), /configuration invalid/)
})

for (const mode of ['denied', 'unsupported', 'invalid-proof'] as const) test(`provider ${mode} remains truthful and is not replayed`, async t => {
  const ctx = new Context(), credentials = credentialFixture(), endpoint = await service(t, credentials.credential, mode)
  t.after(() => ctx.fiber.dispose())
  const other = credentialFixture()
  const provider = mode === 'invalid-proof' ? { async acquire() { return { ...credentials.credential, signer: other.credential.signer } } } : credentials.provider
  const references = new NotificationReferences(ctx, { enabled: true, origin: endpoint.origin, runtimeId, credentials: provider, allowLoopbackHttp: true })
  t.after(() => references.close())
  references.emit('session', 'interaction')
  await until(() => references.status() === (mode === 'unsupported' ? 'unsupported' : 'auth_required'))
  assert.equal(endpoint.attempts.length, 1); assert.equal(endpoint.deliveries.length, 0)
})

test('expired native references never reach HTTP, including expiry during credential acquisition', async t => {
  const ctx = new Context(), credentials = credentialFixture(), endpoint = await service(t, credentials.credential)
  t.after(() => ctx.fiber.dispose())
  await ctx.plugin(SessionStore).await()
  const waiting = Promise.withResolvers<HostConnectionCredential>(), entered = Promise.withResolvers<void>()
  let acquired = 0
  const references = new NotificationReferences(ctx, { enabled: true, origin: endpoint.origin, runtimeId, ttlSeconds: 1, allowLoopbackHttp: true,
    credentials: { async acquire() { acquired++; entered.resolve(); return waiting.promise } } })
  t.after(() => references.close())
  references.emit('session', 'completed', Date.now() - 2000)
  assert.equal(references.status(), 'expired'); assert.equal(acquired, 0)
  const session = ctx.sessions.create(SessionId('expires-during-auth'))
  session.append('turn/start', { turn: 1 }); session.append('turn/end', { turn: 1, reason: { kind: 'completed' } })
  await entered.promise
  const now = Date.now(), clock = t.mock.method(Date, 'now', () => now + 2000)
  waiting.resolve(credentials.credential)
  await until(() => acquired === 1 && references.status() === 'expired')
  // Let the acquisition continuation run even though an earlier rejection set expired.
  await delay(10); clock.mock.restore()
  assert.equal(endpoint.attempts.length, 0)
})

test('close removes native listeners and suppresses future wakeups', async t => {
  const ctx = new Context(), credentials = credentialFixture(), endpoint = await service(t, credentials.credential)
  t.after(() => ctx.fiber.dispose())
  await ctx.plugin(SessionStore).await()
  const references = new NotificationReferences(ctx, { enabled: true, origin: endpoint.origin, runtimeId, credentials: credentials.provider, allowLoopbackHttp: true })
  await references.close()
  const session = ctx.sessions.create(SessionId('closed-session'))
  session.append('turn/start', { turn: 1 }); session.append('turn/end', { turn: 1, reason: { kind: 'completed' } })
  references.emit('closed-session', 'interaction')
  await delay(20)
  assert.equal(endpoint.attempts.length, 0)
})
