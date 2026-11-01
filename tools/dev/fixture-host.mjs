/** Local-only persistent identity composition. Never imported by the shipped Host.
 * Fixture keys are private files, not an OS key store or a school login. */
import { constants } from 'node:fs'
import { lstat, mkdir, open, readFile, rename, writeFile } from 'node:fs/promises'
import { createCipheriv, createDecipheriv, createHash, createPrivateKey, createPublicKey, generateKeyPairSync, randomBytes, randomUUID, sign } from 'node:crypto'
import { dirname, resolve } from 'node:path'
import { homedir } from 'node:os'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { Config, inject as hostInject, HostEnrollmentClient, HostEnrollmentBindingStore, HostEnrollmentRecoveryStore,
  HostResourceJwtProvider, HostRestCredentialProvider, createEnrolledRemoteControlPlugin } from '../../host/remote-control/lib/index.js'

export { Config }
export const inject = [...hostInject, 'workingDirectory']
export const name = 'ustc-remote-control-local-fixture'
export const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
export const state = resolve(root, '.local/dev')
export const origin = 'http://127.0.0.1:8080'
export const issuer = 'http://127.0.0.1:8082'
export const audience = 'urn:dsh-remote:local-dev'
export const organizationId = '3ed32392-2684-448d-83e7-d8b5b977035b'
export const secureState = resolve(homedir(), '.local/state/dsh-remote-development', createHash('sha256').update(root).digest('hex').slice(0, 16))
const fixtureFile = resolve(secureState, 'identity.json')
const runtimeFile = resolve(state, 'enrolled-runtime.json')
const hostSubject = 'local-dev-host-owner', phoneSubject = 'local-dev-fixture-phone'
const now = () => Math.floor(Date.now() / 1000)
const hash = value => createHash('sha256').update(value).digest('base64url')
const unavailable = () => new Error('local development fixture unavailable')

async function privateBytes(path, max = 65536) {
  const before = await lstat(path)
  if (!before.isFile() || before.isSymbolicLink() || before.nlink !== 1 || before.uid !== process.getuid() || (before.mode & 0o777) !== 0o600 || before.size > max) throw unavailable()
  const file = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW)
  try {
    const actual = await file.stat()
    if (actual.dev !== before.dev || actual.ino !== before.ino || actual.nlink !== 1 || actual.size !== before.size) throw unavailable()
    const bytes = Buffer.alloc(max + 1)
    try {
      let total = 0
      while (total < bytes.length) {
        const { bytesRead } = await file.read(bytes, total, bytes.length - total)
        if (!bytesRead) break
        total += bytesRead
      }
      if (total !== actual.size || total > max) throw unavailable()
      return Buffer.from(bytes.subarray(0, total))
    } finally { bytes.fill(0) }
  } finally { await file.close() }
}

async function privateJSON(path) {
  const raw = await privateBytes(path)
  try { return JSON.parse(raw.toString('utf8')) }
  finally { raw.fill(0) }
}

async function saveJSON(path, value) {
  const temporary = path + '.' + randomUUID() + '.tmp'
  await writeFile(temporary, JSON.stringify(value, null, 2) + '\n', { flag: 'wx', mode: 0o600 })
  await rename(temporary, path)
}

async function directory() {
  const info = await lstat(state)
  if (process.platform !== 'linux' || !info.isDirectory() || info.isSymbolicLink() || info.uid !== process.getuid() || (info.mode & 0o777) !== 0o700) throw unavailable()
}

function keyPair() {
  const key = generateKeyPairSync('ec', { namedCurve: 'prime256v1' })
  return { private: key.privateKey.export({ format: 'jwk' }), public: key.publicKey.export({ format: 'jwk' }) }
}
function decodedKey(value) {
  const privateKey = createPrivateKey({ format: 'jwk', key: value.private })
  const publicJwk = createPublicKey(privateKey).export({ format: 'jwk' })
  if (publicJwk.kty !== 'EC' || publicJwk.crv !== 'P-256' || JSON.stringify(publicJwk) !== JSON.stringify(value.public)) throw unavailable()
  return { privateKey, publicJwk, thumbprint: hash(JSON.stringify({ crv: publicJwk.crv, kty: publicJwk.kty, x: publicJwk.x, y: publicJwk.y })) }
}
function jws(key, header, claims) {
  const input = [header, claims].map(value => Buffer.from(JSON.stringify(value)).toString('base64url')).join('.')
  return input + '.' + sign('sha256', Buffer.from(input), { key, dsaEncoding: 'ieee-p1363' }).toString('base64url')
}

async function fixtures() {
  await directory()
  const info = await lstat(secureState)
  if (!info.isDirectory() || info.isSymbolicLink() || info.uid !== process.getuid() || (info.mode & 0o777) !== 0o700) throw unavailable()
  const values = await privateJSON(fixtureFile)
  if (values.purpose !== 'dsh-local-development-only/v1') throw unavailable()
  return values
}

export async function prepare(profile) {
  await directory()
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,80}$/.test(profile)) throw unavailable()
  await mkdir(secureState, { mode: 0o700, recursive: true })
  let values
  try { values = await fixtures() }
  catch (error) {
    if (error.code !== 'ENOENT') throw error
    values = { purpose: 'dsh-local-development-only/v1', profile,
      installation: keyPair(), phone: keyPair(), approval: keyPair(), authority: keyPair(), master: randomBytes(32).toString('base64url') }
    await writeFile(fixtureFile, JSON.stringify(values) + '\n', { flag: 'wx', mode: 0o600 })
  }
  if (values.profile !== profile) throw unavailable()
  const authority = decodedKey(values.authority)
  const authorityId = 'dsh-approval-authority/local-dev'
  const validFrom = new Date(Date.now() - 60_000).toISOString(), validUntil = new Date(Date.now() + 7 * 86400_000).toISOString()
  const privateFile = resolve(secureState, 'approval-authority.pem')
  const encoded = Buffer.from(authority.privateKey.export({ format: 'pem', type: 'pkcs8' }))
  try {
    try { await writeFile(privateFile, encoded, { mode: 0o600, flag: 'wx' }) }
    catch (error) {
      if (error.code !== 'EEXIST') throw error
      const existing = await privateBytes(privateFile)
      try { if (!existing.equals(encoded)) throw unavailable() }
      finally { existing.fill(0) }
    }
  } finally { encoded.fill(0) }
  const trusted = { issuer: 'https://dsh-local-development.invalid', key_id: authorityId, public_jwk: authority.publicJwk,
    key_jkt: authority.thumbprint, active: true, valid_from: validFrom, valid_until: validUntil }
  await saveJSON(resolve(state, 'fixture-approval-anchor.json'), trusted)
  await saveJSON(resolve(state, 'fixture-approval-policy.json'), {
    policy: { version: 1, assurance_profile: 'fixture/local-development', max_auth_age_seconds: 60 },
    mappings: [{ profile: 'fixture/local-development', issuer, client_id: 'local-dev-phone', acr: 'urn:local-dev:fixture', required_amr: ['otp'] }],
    signing: { issuer: trusted.issuer, key_id: authorityId, private_key_file: privateFile, valid_from: validFrom, valid_until: validUntil },
  })
  await saveJSON(resolve(state, 'fixture-public.json'), { purpose: 'dsh-local-development-only/v1', profile, organization_id: organizationId, issuer, audience, host_subject: hostSubject, phone_subject: phoneSubject })
  for (const name of ['binding', 'recovery', 'relay-journal']) await mkdir(resolve(secureState, name), { mode: 0o700, recursive: true })
}

async function issuerKey() {
  const value = await privateJSON(resolve(secureState, 'issuer.json'))
  return createPrivateKey({ key: value.private, format: 'jwk' })
}
function accessToken(authority, subject, clientId, thumbprint) {
  const issued = now()
  return jws(authority, { alg: 'ES256', typ: 'at+jwt', kid: 'local-dev-only' }, {
    iss: issuer, sub: subject, aud: audience, client_id: clientId, jti: randomUUID(), iat: issued, exp: issued + 300,
    scope: 'remote.access', cnf: { jkt: thumbprint }, auth_time: issued, acr: 'urn:local-dev:fixture', amr: ['otp'],
  })
}

/** Loopback-only test peer. One fresh DPoP proof per attempt, one nonce retry,
 * no network-error replay and no HTTP endpoint that distributes fixture tokens. */
export async function phoneClient(values = undefined) {
  values ??= await fixtures()
  const proof = decodedKey(values.phone), approval = decodedKey(values.approval), authority = await issuerKey()
  let nonce = ''
  const request = async (method, path, body, expected = 200) => {
    if (!['GET', 'POST', 'DELETE'].includes(method) || !path.startsWith('/v1/') || path.includes('#')) throw unavailable()
    const token = accessToken(authority, phoneSubject, 'local-dev-phone', proof.thumbprint)
    for (let attempt = 0; attempt < 2; attempt++) {
      const proofId = randomUUID()
      const dpop = jws(proof.privateKey, { typ: 'dpop+jwt', alg: 'ES256', jwk: proof.publicJwk },
        { htm: method, htu: origin + path.split('?')[0], ath: hash(token), jti: proofId, iat: now(), nonce })
      const payload = typeof body === 'function' ? body(proofId) : body
      const response = await fetch(origin + path, { method, redirect: 'error', signal: AbortSignal.timeout(5000),
        headers: { authorization: 'DPoP ' + token, dpop, ...(payload === undefined ? {} : { 'content-type': 'application/json' }) },
        ...(payload === undefined ? {} : { body: JSON.stringify(payload) }) })
      nonce = response.headers.get('dpop-nonce') ?? nonce
      const raw = await response.text()
      if (Buffer.byteLength(raw) > 65536) throw unavailable()
      const result = JSON.parse(raw)
      if (!attempt && response.status === 401 && response.headers.get('www-authenticate')?.includes('use_dpop_nonce')) continue
      if (response.status !== expected || result.ok !== true) throw unavailable()
      return result.data
    }
    throw unavailable()
  }
  return { request, proof, approval }
}

/** Deliberately file-backed fixture protector. Not production platform protection. */
function fixtureProtector(values, owner) {
  const master = Buffer.from(values.master, 'base64url')
  if (master.length !== 32 || master.toString('base64url') !== values.master) throw unavailable()
  const current = signal => { if (owner.signal.aborted || signal.aborted) throw unavailable() }
  owner.signal.addEventListener('abort', () => master.fill(0), { once: true })
  return { id: 'local-development-file-fixture-v1',
    async wrap(key, context, signal) {
      current(signal)
      const nonce = randomBytes(12), cipher = createCipheriv('aes-256-gcm', master, nonce)
      cipher.setAAD(context)
      return Buffer.concat([nonce, cipher.update(key), cipher.final(), cipher.getAuthTag()])
    },
    async unwrap(wrapped, context, signal) {
      current(signal)
      if (wrapped.length !== 60) throw unavailable()
      const input = Buffer.from(wrapped), decipher = createDecipheriv('aes-256-gcm', master, input.subarray(0, 12))
      try { decipher.setAAD(context); decipher.setAuthTag(input.subarray(44)); return Buffer.concat([decipher.update(input.subarray(12, 44)), decipher.final()]) }
      finally { input.fill(0) }
    },
  }
}

async function integration() {
  const values = await fixtures(), installation = decodedKey(values.installation), authority = await issuerKey()
  const owner = new AbortController(), protector = fixtureProtector(values, owner)
  const identity = { issuer, subject: hostSubject, audience, clientId: 'local-dev-host' }
  const account = new HostResourceJwtProvider({ origin, identity, publicJwk: installation.publicJwk, requiredScopes: ['remote.access'],
    jwksUrl: issuer + '/jwks.json', allowLoopbackHttp: true, source: { signal: owner.signal,
      async acquire(signal) { if (signal.aborted || owner.signal.aborted) throw unavailable(); return { token: accessToken(authority, hostSubject, 'local-dev-host', installation.thumbprint), tokenType: 'DPoP' } } } })
  const signer = { sign(input) { if (owner.signal.aborted) throw unavailable(); return sign('sha256', Buffer.from(input), { key: installation.privateKey, dsaEncoding: 'ieee-p1363' }) } }
  const options = { origin, identity, publicJwk: installation.publicJwk, signer, account, requiredScopes: ['remote.access'], allowLoopbackHttp: true }
  const client = new HostEnrollmentClient(options)
  const binding = new HostEnrollmentBindingStore({ path: resolve(secureState, 'binding/fixture.bin'), client, protector })
  const recovery = new HostEnrollmentRecoveryStore({ path: resolve(secureState, 'recovery/fixture.bin'), client, protector })
  return { values, options, installation, owner, protector, account, client, binding, recovery,
    close() { owner.abort(); binding.close(); recovery.close(); client.close(); account.close() } }
}

export async function bootstrap(profile) {
  const local = await integration(), signal = AbortSignal.timeout(45000)
  let attempt
  try {
    if (local.values.profile !== profile) throw unavailable()
    let active, pending = false
    try { active = await local.binding.open(signal) }
    catch (error) { if (error.code !== 'host.enrollment.review_required') throw error; pending = true }
    if (!active) {
      attempt = await local.recovery.load(signal)
      if (!attempt) {
        if (pending) throw unavailable()
        attempt = local.client.prepare({ organizationId, alias: 'Local development fixture', profileRef: profile,
          dshVersion: '0.2.1-rc.5', applicationVersion: 'local-development-fixture', pluginVersion: '0.0.1', protocolVersion: 1, workspaceRefs: ['project'] })
        // Persist before first network write; uncertain retries preserve ID/code.
        await local.recovery.save(attempt, signal)
      }
      const receipt = await local.recovery.create(attempt, signal)
      await local.binding.reserve(attempt, receipt, signal)
      const phone = await phoneClient(local.values), me = await phone.request('GET', '/v1/me')
      const current = await phone.request('GET', '/v1/devices/current')
      if (current.state === 'unregistered') {
        await phone.request('POST', '/v1/devices', proofId => ({ name: 'Local fixture phone',
          proof_public_jwk: phone.proof.publicJwk, approval_public_jwk: phone.approval.publicJwk,
          approval_proof: jws(phone.approval.privateKey, { alg: 'ES256', typ: 'dsh-device-registration-proof+jwt' }, {
            iss: issuer, sub: phoneSubject, aud: 'urn:dsh-mobile-ustc:device-registration', iat: now(), jti: randomUUID(),
            approval_key_jkt: phone.approval.thumbprint, proof_key_jkt: phone.proof.thumbprint, device_name_sha256: hash('Local fixture phone'), dpop_jti: proofId,
          }) }), 201)
      } else if (current.state !== 'active' || current.device.approval_key_jkt !== phone.approval.thumbprint) throw unavailable()
      const details = receipt.details
      await phone.request('POST', `/v1/enrollments/${details.enrollment_id}/preview`, { code: attempt.code })
      await phone.request('POST', `/v1/enrollments/${details.enrollment_id}/confirm`, { code: attempt.code })
      active = await local.binding.confirm(local.recovery, attempt, receipt, (review, approvalSignal) => {
        return !approvalSignal.aborted && review.enrollment.organization_id === organizationId && review.enrollment.profile_ref === profile &&
          review.enrollment.installation_key_jkt === local.installation.thumbprint &&
          review.confirmed_user?.user_id === me.user_id && review.confirmed_user?.issuer === issuer &&
          review.confirmed_user?.subject === phoneSubject && review.confirmed_user?.eligible === true
      }, signal)
    }
    if (active.profileRef !== profile || active.organizationId !== organizationId || active.workspaces.length !== 1 || active.workspaces[0].reference !== 'project') throw unavailable()
    await saveJSON(runtimeFile, { purpose: 'dsh-local-enrolled-runtime/v1', ...active })
    await local.recovery.removeApproved(local.binding, signal)
    return active
  } finally { attempt?.destroy(); local.close() }
}

/** Providers belong to one apply invocation. HMR unload aborts all old owners. */
export async function apply(ctx, config) {
  const local = await integration()
  let credentials
  try {
    ctx.effect(() => () => { credentials?.close(); local.close() }, 'local-development-fixture-owner')
    const active = await local.binding.open(AbortSignal.any([local.owner.signal, AbortSignal.timeout(10000)]))
    if (!active || active.profileRef !== local.values.profile) throw unavailable()
    credentials = new HostRestCredentialProvider({ ...local.options, installationId: active.installationId })
    const authority = await privateJSON(resolve(state, 'fixture-approval-anchor.json'))
    const signed = { hostId: active.hostId, installationId: active.installationId, policyVersion: 1,
      requiredAssurance: { profile: 'fixture/local-development', max_auth_age_seconds: 60 }, authorities: [authority] }
    const plugin = createEnrolledRemoteControlPlugin({ client: local.client, binding: local.binding, credentials, profileRef: active.profileRef }, signed, local.protector)
    return await plugin.apply(ctx, config)
  } catch (error) { credentials?.close(); local.close(); throw error }
}

async function main() {
  const [operation, profile = 'web'] = process.argv.slice(2)
  if (operation === 'prepare') await prepare(profile)
  else if (operation === 'bootstrap') await bootstrap(profile)
  else throw unavailable()
  console.log('local development fixture ' + operation + ' complete')
}
if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  main().catch(error => { const code = typeof error?.code === 'string' && /^host\.[a-z._]+$/.test(error.code) ? error.code : 'unavailable'; console.error('local development fixture ' + code); process.exitCode = 1 })
}
