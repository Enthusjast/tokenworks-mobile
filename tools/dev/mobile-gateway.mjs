/** Explicit local-development OIDC authority and TLS proxy; never production code.
 * No models, fake relay peers, school credentials or persistent browser sessions. */
import { readFile } from 'node:fs/promises'
import { createHash, createPrivateKey, createPublicKey, randomBytes, randomUUID, sign, timingSafeEqual, verify } from 'node:crypto'
import https from 'node:https'
import http from 'node:http'
import { resolve } from 'node:path'
import { homedir } from 'node:os'

const root = resolve(import.meta.dirname, '../..'), state = resolve(root, '.local/tokenworks-mobile-dev')
const secureState = resolve(homedir(), '.local/state/tokenworks-mobile-development', createHash('sha256').update(root).digest('hex').slice(0, 16))
const origin = 'https://localhost:8443', issuer = origin + '/issuer', audience = 'urn:tokenworks-mobile:local-dev'
const authority = JSON.parse(await readFile(resolve(secureState, 'issuer.json'), 'utf8'))
const privateKey = createPrivateKey({ format: 'jwk', key: authority.private })
const exportedPublic = createPublicKey(privateKey).export({ format: 'jwk' })
authority.public = { kty: exportedPublic.kty, crv: exportedPublic.crv, x: exportedPublic.x, y: exportedPublic.y,
  kid: 'local-dev-only', use: 'sig', alg: 'ES256' }
const clients = new Map([
  ['local-dev-phone', { redirect: 'cn.edu.ustc.tokenworks:/oauth2redirect', subject: 'local-dev-fixture-phone', label: 'phone' }],
  ['local-dev-host', { redirect: origin + '/issuer/host-callback', subject: 'local-dev-host-owner', label: 'Host' }],
])
const transactions = new Map(), codes = new Map(), nonces = new Map(), replays = new Map()
const now = () => Math.floor(Date.now() / 1000)
const random = () => randomBytes(32).toString('base64url')
const hash = value => createHash('sha256').update(value).digest('base64url')
const equal = (a, b) => typeof a === 'string' && typeof b === 'string' && a.length === b.length && timingSafeEqual(Buffer.from(a), Buffer.from(b))
const escaped = value => value.replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char])
function jwt(header, claims) {
  const input = [header, claims].map(value => Buffer.from(JSON.stringify(value)).toString('base64url')).join('.')
  return input + '.' + sign('sha256', Buffer.from(input), { key: privateKey, dsaEncoding: 'ieee-p1363' }).toString('base64url')
}
function reply(response, status, body, headers = {}) {
  const bytes = Buffer.from(typeof body === 'string' ? body : JSON.stringify(body))
  response.writeHead(status, { 'content-type': typeof body === 'string' ? 'text/html; charset=utf-8' : 'application/json',
    'content-length': bytes.length, 'cache-control': 'no-store', 'pragma': 'no-cache', 'referrer-policy': 'same-origin', 'connection': 'close',
    'x-content-type-options': 'nosniff', 'content-security-policy': "default-src 'none'; form-action 'self' cn.edu.ustc.tokenworks:; frame-ancestors 'none'; base-uri 'none'", ...headers })
  response.end(bytes)
}
function parameters(raw) {
  const result = Object.create(null)
  for (const [key, value] of new URLSearchParams(raw)) {
    if (key in result || key.length > 128 || value.length > 8192) throw Error('invalid_request')
    result[key] = value
  }
  return result
}
async function form(request) {
  if (request.headers['content-type']?.split(';')[0] !== 'application/x-www-form-urlencoded') throw Error('invalid_request')
  let body = ''
  for await (const chunk of request) {
    body += chunk.toString('utf8')
    if (Buffer.byteLength(body) > 16384) throw Error('invalid_request')
  }
  return parameters(body)
}
function bounded(value) { return typeof value === 'string' && /^[\x21-\x7e]{16,512}$/.test(value) }
function authorization(values) {
  const client = clients.get(values.client_id)
  const scopes = values.scope?.split(' ')
  if (!client || values.redirect_uri !== client.redirect || values.response_type !== 'code' || values.resource !== audience ||
      values.code_challenge_method !== 'S256' || !/^[A-Za-z0-9_-]{43}$/.test(values.code_challenge ?? '') ||
      !/^[A-Za-z0-9_-]{43}$/.test(values.dpop_jkt ?? '') || !bounded(values.state) || !bounded(values.nonce) ||
      !scopes || new Set(scopes).size !== scopes.length || !scopes.includes('openid') || !scopes.includes('remote.access') ||
      scopes.some(scope => !['openid', 'remote.access'].includes(scope))) throw Error('invalid_request')
  return { ...values, client, expires: now() + 300 }
}
function proof(request, expected) {
  const compact = request.headers.dpop
  if (typeof compact !== 'string' || compact.length > 8192) throw Error('invalid_dpop_proof')
  const parts = compact.split('.')
  if (parts.length !== 3) throw Error('invalid_dpop_proof')
  const header = JSON.parse(Buffer.from(parts[0], 'base64url')), claims = JSON.parse(Buffer.from(parts[1], 'base64url'))
  const key = header.jwk
  if (header.typ !== 'dpop+jwt' || header.alg !== 'ES256' || !key || key.kty !== 'EC' || key.crv !== 'P-256' ||
      Object.keys(key).some(name => !['kty', 'crv', 'x', 'y'].includes(name)) ||
      !verify('sha256', Buffer.from(parts[0] + '.' + parts[1]), { key: createPublicKey({ key, format: 'jwk' }), dsaEncoding: 'ieee-p1363' }, Buffer.from(parts[2], 'base64url')) ||
      claims.htm !== 'POST' || claims.htu !== issuer + '/token' || !Number.isInteger(claims.iat) || Math.abs(now() - claims.iat) > 60 || !bounded(claims.jti)) throw Error('invalid_dpop_proof')
  const thumbprint = hash(JSON.stringify({ crv: key.crv, kty: key.kty, x: key.x, y: key.y }))
  if (!equal(thumbprint, expected)) throw Error('invalid_dpop_proof')
  const replay = thumbprint + ':' + claims.jti
  if (replays.has(replay)) throw Error('invalid_dpop_proof')
  const nonce = nonces.get(thumbprint)
  if (!nonce || nonce.expires <= now() || !equal(claims.nonce, nonce.value)) {
    const value = random()
    nonces.set(thumbprint, { value, expires: now() + 300 })
    const error = Error('use_dpop_nonce'); error.nonce = value; throw error
  }
  replays.set(replay, { expires: now() + 120 })
  nonces.delete(thumbprint)
}
async function oidc(request, response, url) {
  if (request.method === 'GET' && url.pathname === '/issuer/readyz') { response.writeHead(204); response.end(); return }
  if (request.method === 'GET' && url.pathname === '/issuer/.well-known/openid-configuration') {
    reply(response, 200, { issuer, authorization_endpoint: issuer + '/authorize', token_endpoint: issuer + '/token', jwks_uri: issuer + '/jwks.json',
      response_types_supported: ['code'], subject_types_supported: ['public'], id_token_signing_alg_values_supported: ['ES256'],
      token_endpoint_auth_methods_supported: ['none'], grant_types_supported: ['authorization_code'], code_challenge_methods_supported: ['S256'],
      dpop_signing_alg_values_supported: ['ES256'], scopes_supported: ['openid', 'remote.access'], authorization_response_iss_parameter_supported: true })
    return
  }
  if (request.method === 'GET' && url.pathname === '/issuer/jwks.json') { reply(response, 200, { keys: [authority.public] }); return }
  if (request.method === 'GET' && url.pathname === '/issuer/authorize') {
    const transaction = authorization(parameters(url.search)), id = random(), cookie = random()
    if (transactions.size >= 128) throw Error('temporarily_unavailable')
    transactions.set(id, { ...transaction, cookie })
    reply(response, 200, `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>TokenWorks Mobile local development identity</title><h1>Local development identity only</h1><p>This is NOT USTC or school login. No school password is requested.</p><p>Client: ${escaped(transaction.client_id)}. Account: ${escaped(transaction.client.subject)}.</p><p>Continue grants this local app access to the explicitly enrolled development Host and repository workspace. Model providers remain your existing dsh configuration.</p><form method="post" action="/issuer/login"><input type="hidden" name="transaction" value="${id}"><button name="consent" value="allow">Sign in as local development ${transaction.client.label}</button><button name="consent" value="deny">Cancel</button></form></html>`,
      { 'set-cookie': `tokenworks_mobile_local_consent=${cookie}; Path=/issuer; Max-Age=300; Secure; HttpOnly; SameSite=Strict` })
    return
  }
  if (request.method === 'POST' && url.pathname === '/issuer/login') {
    if (request.headers.origin !== origin) throw Error('invalid_request')
    const values = await form(request), transaction = transactions.get(values.transaction)
    const consentPrefix = 'tokenworks_mobile_local_consent='
    const cookie = request.headers.cookie?.split(';').map(part => part.trim()).find(part => part.startsWith(consentPrefix))?.slice(consentPrefix.length)
    if (!transaction || transaction.expires <= now() || !equal(cookie, transaction.cookie) || !['allow', 'deny'].includes(values.consent)) throw Error('invalid_request')
    transactions.delete(values.transaction)
    const redirect = new URL(transaction.redirect_uri)
    redirect.searchParams.set('state', transaction.state); redirect.searchParams.set('iss', issuer)
    if (values.consent === 'deny') redirect.searchParams.set('error', 'access_denied')
    else {
      if (codes.size >= 128) throw Error('temporarily_unavailable')
      const code = random()
      codes.set(hash(code), { ...transaction, authenticated: now(), expires: now() + 120 })
      redirect.searchParams.set('code', code)
    }
    reply(response, 303, '', { location: redirect.toString(), 'set-cookie': 'tokenworks_mobile_local_consent=; Path=/issuer; Max-Age=0; Secure; HttpOnly; SameSite=Strict' })
    return
  }
  if (request.method === 'POST' && url.pathname === '/issuer/token') {
    const values = await form(request), transaction = codes.get(hash(values.code ?? ''))
    if (values.grant_type !== 'authorization_code') throw Error('unsupported_grant_type')
    if (!transaction || transaction.expires <= now() || values.client_id !== transaction.client_id || values.redirect_uri !== transaction.redirect_uri ||
        values.resource !== audience || !/^[A-Za-z0-9._~-]{43,128}$/.test(values.code_verifier ?? '') || !equal(hash(values.code_verifier), transaction.code_challenge)) throw Error('invalid_grant')
    proof(request, transaction.dpop_jkt)
    codes.delete(hash(values.code))
    const issued = now(), expires = issued + 900, subject = transaction.client.subject
    const access = jwt({ alg: 'ES256', typ: 'at+jwt', kid: authority.public.kid }, {
      iss: issuer, sub: subject, aud: audience, client_id: transaction.client_id, iat: issued, exp: expires, jti: randomUUID(),
      scope: transaction.scope, cnf: { jkt: transaction.dpop_jkt }, auth_time: transaction.authenticated, acr: 'urn:local-dev:fixture', amr: ['fixture'],
    })
    const id = jwt({ alg: 'ES256', typ: 'JWT', kid: authority.public.kid }, {
      iss: issuer, sub: subject, aud: transaction.client_id, azp: transaction.client_id, iat: issued, exp: expires,
      nonce: transaction.nonce, auth_time: transaction.authenticated,
      at_hash: createHash('sha256').update(access).digest().subarray(0, 16).toString('base64url'),
      c_hash: createHash('sha256').update(values.code).digest().subarray(0, 16).toString('base64url'),
    })
    reply(response, 200, { access_token: access, id_token: id, token_type: 'DPoP', expires_in: 900, scope: transaction.scope })
    return
  }
  if (request.method === 'GET' && url.pathname === '/issuer/host-callback') {
    reply(response, 200, '<!doctype html><title>Local Host identity</title><p>The development Host uses its private installation key and signed fixture account token. This browser callback does not install or expose credentials.</p>')
    return
  }
  reply(response, 404, { error: 'not_found' })
}
function relayHeaders(request) {
  const headers = { ...request.headers, host: 'localhost:8443' }
  for (const name of Object.keys(headers)) if (name === 'forwarded' || name.startsWith('x-forwarded-')) delete headers[name]
  return headers
}
// The explicit development HTTP bridge does not pool client connections across
// ADB reverse idle closes. WebSocket upgrades remain full-duplex connections.
function httpHeaders(headers) {
  const result = { ...headers }
  // Proxy-Authorization is a lease secret meant for the Relay's tunnel-lease
  // introspection; this gateway only terminates TLS, so it must pass through.
  const hop = new Set(['connection', 'keep-alive', 'proxy-authenticate', 'te', 'trailer', 'transfer-encoding', 'upgrade'])
  for (const field of String(headers.connection ?? '').split(',')) if (field.trim()) hop.add(field.trim().toLowerCase())
  for (const field of hop) delete result[field]
  result.connection = 'close'
  return result
}
const server = https.createServer({ key: await readFile(resolve(secureState, 'android/server-key.pem')),
  cert: await readFile(resolve(state, 'android/server.pem')), minVersion: 'TLSv1.2' }, async (request, response) => {
  if (!['localhost:8443', '127.0.0.1:8443'].includes(request.headers.host)) { reply(response, 400, { error: 'invalid_request' }); return }
  try {
    const url = new URL(request.url, origin)
    if (url.pathname.startsWith('/issuer/') || url.pathname === '/issuer') { await oidc(request, response, url); return }
    const upstream = http.request({ host: '127.0.0.1', port: 8080, path: request.url, method: request.method, headers: httpHeaders(relayHeaders(request)) }, remote => {
      response.writeHead(remote.statusCode, httpHeaders(remote.headers)); remote.pipe(response)
    })
    upstream.on('error', () => { if (!response.headersSent) reply(response, 502, { error: 'relay_unavailable' }); else response.destroy() })
    request.on('aborted', () => upstream.destroy()); response.on('close', () => upstream.destroy())
    request.pipe(upstream)
  } catch (error) {
    const allowed = ['invalid_request', 'invalid_grant', 'unsupported_grant_type', 'invalid_dpop_proof', 'use_dpop_nonce', 'temporarily_unavailable']
    reply(response, 400, { error: allowed.includes(error.message) ? error.message : 'invalid_request' }, error.nonce ? { 'dpop-nonce': error.nonce } : {})
  }
})
// Two upgrades cross this bridge: the Host's native tunnel and the phone's
// native mux under /api/. The retired v1 realtime socket stays refused.
const tunnelPath = /^\/v2\/runtimes\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\/tunnel$/
server.on('upgrade', (request, socket, head) => {
  const path = new URL(request.url, origin).pathname
  if (!['localhost:8443', '127.0.0.1:8443'].includes(request.headers.host) || !(tunnelPath.test(path) || path.startsWith('/api/'))) { socket.destroy(); return }
  const upstream = http.request({ host: '127.0.0.1', port: 8080, path: request.url, method: 'GET', headers: relayHeaders(request) })
  upstream.on('upgrade', (response, remote, remainder) => {
    socket.write(`HTTP/1.1 ${response.statusCode} ${response.statusMessage}\r\n` + response.rawHeaders.reduce((text, value, index) => text + value + (index % 2 ? '\r\n' : ': '), '') + '\r\n')
    if (remainder.length) socket.write(remainder)
    if (head.length) remote.write(head)
    remote.on('error', () => socket.destroy()); socket.on('error', () => remote.destroy())
    remote.on('close', () => socket.destroy()); socket.on('close', () => remote.destroy())
    socket.pipe(remote).pipe(socket)
  })
  // Admission failures are HTTP responses, not upgrades. Forward them
  // verbatim so the Host can read DPoP nonce headers and the error body.
  upstream.on('response', response => {
    socket.write(`HTTP/1.1 ${response.statusCode} ${response.statusMessage}\r\n` + response.rawHeaders.reduce((text, value, index) => text + value + (index % 2 ? '\r\n' : ': '), '') + '\r\n')
    response.pipe(socket)
  })
  upstream.on('error', () => socket.destroy()); socket.on('error', () => upstream.destroy())
  upstream.end()
})
server.headersTimeout = 15000; server.requestTimeout = 30000
setInterval(() => { for (const map of [transactions, codes, nonces, replays]) for (const [key, value] of map) if (value.expires <= now()) map.delete(key) }, 30000).unref()
server.listen(8443, '127.0.0.1', () => console.log('Local-development TLS gateway listening at ' + origin + '; issuer ' + issuer))
for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => { server.close(); server.closeAllConnections(); process.exit(0) })
