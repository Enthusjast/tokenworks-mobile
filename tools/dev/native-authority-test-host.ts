import assert from 'node:assert/strict'
import { fork, execFileSync } from 'node:child_process'
import { createCipheriv, createDecipheriv, createHash, randomBytes, randomUUID } from 'node:crypto'
import { once } from 'node:events'
import { chmod, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import net from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createInterface } from 'node:readline'
import { fileURLToPath } from 'node:url'
import { createIsolatedNativeAssembly } from '../capture-native-golden.js'
import { nativeMethods, nativeVersion } from '../native-contract.js'
import { NativeAccess, type NativeLease } from '../../host/remote-control/src/native-access.js'
import { NativeGateway } from '../../host/remote-control/src/native-gateway.js'
import { NoiseCarrier } from '../../host/remote-control/src/noise-carrier.js'
import { LanGateway } from '../../host/remote-control/src/lan-gateway.js'
import { LanPairing } from '../../host/remote-control/src/lan-pairing.js'
import { LanPairingStore } from '../../host/remote-control/src/lan-pairing-store.js'
import { PRODUCT_OWNER_BINDINGS } from '../../host/remote-control/src/product-access.generated.js'
import type { JournalKeyProtector } from '../../host/remote-control/src/journal-key-protection.js'

const entry = fileURLToPath(import.meta.url)

/** Test-only seeded authority facts, not a PostgreSQL/Go authorization-service proof.
 * The native providers, policy, Gateway, pairing, TLS, and Noise are production implementations.
 * No developer profile, provider credentials, or network discovery is inherited by the worker.
 */
async function worker(root: string) {
  process.umask(0o077)
  assert.equal(process.cwd(), root)
  assert.equal(process.env.HOME, root)
  await Promise.all(['config', 'data', 'cache'].map(name => mkdir(join(root, name))))
  const certPath = join(root, 'localhost.pem'), keyPath = join(root, 'localhost.key')
  execFileSync('/usr/bin/openssl', ['req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:P-256', '-nodes',
    '-keyout', keyPath, '-out', certPath, '-days', '1', '-subj', '/CN=localhost',
    '-addext', 'subjectAltName=DNS:localhost,IP:127.0.0.1', '-addext', 'basicConstraints=critical,CA:TRUE'],
  { cwd: root, env: process.env, stdio: 'ignore', timeout: 15_000 })
  // The installed offline provider is explicit. No outbound native tools are enabled.
  const assembly = await createIsolatedNativeAssembly(root, [{ id: 'authority-native-remotes', name: '@deepseek-ai/dsh-api-remotes' }])
  const { ctx, peer } = assembly
  const master = randomBytes(32)
  const protector: JournalKeyProtector = {
    id: 'isolated-native-authority-test-key',
    async wrap(bytes, context) {
      const iv = randomBytes(12), cipher = createCipheriv('aes-256-gcm', master, iv)
      cipher.setAAD(context)
      return Buffer.concat([iv, cipher.update(bytes), cipher.final(), cipher.getAuthTag()])
    },
    async unwrap(bytes, context) {
      const raw = Buffer.from(bytes), cipher = createDecipheriv('aes-256-gcm', master, raw.subarray(0, 12))
      cipher.setAAD(context); cipher.setAuthTag(raw.subarray(-16))
      return Buffer.concat([cipher.update(raw.subarray(12, -16)), cipher.final()])
    },
  }
  let pairing: LanPairing | undefined, gateway: NativeGateway | undefined, carrier: NoiseCarrier | undefined
  let access: NativeAccess | undefined, lan: LanGateway | undefined
  try {
    const invoke = async (endpoint: string, args: Record<string, unknown>): Promise<any> => {
      const method = nativeMethods.find(([name]) => name === endpoint)?.[1]
      assert.ok(method)
      return ctx.typertGateway.invoke({ namespace: method.namespace, method: method.method, args, peer, signal: new AbortController().signal })
    }
    const workspace = await invoke('workspace/create', { request: { path: assembly.cwd } })
    const workspaceId = workspace.workspace.workspaceId as string
    const sessionId = (await invoke('session/create', { request: { workspaceId } })).sessionId as string
    const forbiddenSessionId = (await invoke('session/create', { request: { workspaceId } })).sessionId as string
    const reserve = net.createServer()
    reserve.listen(0, '127.0.0.1'); await once(reserve, 'listening')
    const address = reserve.address(); assert.ok(address && typeof address === 'object')
    const reserved = Promise.withResolvers<void>()
    reserve.close(error => error ? reserved.reject(error) : reserved.resolve())
    await reserved.promise
    const origin = `https://localhost:${address.port}`, runtimeId = randomUUID(), installationId = randomUUID()
    const cert = await readFile(certPath, 'utf8'), key = await readFile(keyPath, 'utf8')
    const owner = { account_id: randomUUID(), organization_id: randomUUID(), deployment_sha256: createHash('sha256').update('native authority fixture').digest('hex'), user_id: randomUUID(), device_id: randomUUID() }
    const relay = { issuer: 'https://native-authority.invalid', organizationId: owner.organization_id,
      deploymentSha256: owner.deployment_sha256, userId: owner.user_id, deviceId: owner.device_id }
    pairing = await LanPairing.open({ origin, runtimeId, installationId }, cert,
      new LanPairingStore(join(root, 'pairs'), runtimeId, installationId, protector), ctx.connection)
    gateway = new NativeGateway(ctx.connection, ctx.typertGateway)
    const facts = new Map<string, { token: string; data: NativeLease }>()
    const leases: Record<string, { id: string; token: string; expiresAt: number }> = {}
    const base: NativeLease = { active: true, organization_id: owner.organization_id, sub: owner.user_id, aud: runtimeId, exp: Math.floor(Date.now() / 1000) + 300,
      client_id: 'native-authority-fixture', device_id: owner.device_id, runtime_id: runtimeId, installation_id: installationId,
      grant_version: 1, role: 'viewer', is_installation_owner: false,
      bindings: [{ resource: { kind: 'session', id: sessionId }, actions: ['session/follow', 'event:api-session/status'] }] }
    const issue = (name: string, changes: Partial<NativeLease> = {}) => {
      const id = randomUUID(), token = randomBytes(32).toString('base64url')
      facts.set(id, { token, data: { ...structuredClone(base), ...changes } })
      leases[name] = { id, token, expiresAt: Date.now() + 300_000 }
    }
    issue('viewer')
    issue('changed')
    issue('operator', { role: 'operator', is_installation_owner: true, bindings: [{ resource: { kind: 'session', id: sessionId }, actions: ['session/follow', 'session/selectModel', 'terminal/list'] }] })
    issue('owner', { role: 'owner', is_installation_owner: true, bindings: structuredClone(PRODUCT_OWNER_BINDINGS) as NativeLease['bindings'] })
    issue('wrongUser', { sub: randomUUID() }); issue('wrongDevice', { device_id: randomUUID() })
    issue('wrongRuntime', { runtime_id: randomUUID() }); issue('wrongInstallation', { installation_id: randomUUID() })
    issue('wrongOrganization', { organization_id: randomUUID() })
    issue('expired', { exp: Math.floor(Date.now() / 1000) - 1 })
    issue('revoked'); facts.delete(leases.revoked!.id)
    access = new NativeAccess(ctx.connection, ctx.remoteAccess, runtimeId, installationId, async (id, token) => {
      const fact = facts.get(id)
      return { ok: true, data: fact?.token === token ? fact.data : null }
    }, () => ({ sessions: [sessionId, forbiddenSessionId], workspaces: [workspaceId] }), 'owner')
    carrier = new NoiseCarrier(pairing, gateway, nativeVersion, relay.issuer)
    carrier.setRelayAuthority((id, token, pairedOwner) => access!.admitFresh(id, token, pairedOwner))
    lan = new LanGateway({ host: '127.0.0.1', port: address.port, certificate: { cert, key }, pairing, connection: ctx.connection, gateway, carrier })
    await lan.listen()
    const fixturePath = join(root, 'fixture.json')
    await writeFile(fixturePath, JSON.stringify({ pairing: pairing.begin(), operatorCaPem: cert, owner, relay, leases,
      sessionId, forbiddenSessionId, runtimeId, installationId }) + '\n', { mode: 0o600, flag: 'wx' })
    process.send!({ type: 'ready', path: fixturePath })
    const stop = Promise.withResolvers<void>()
    process.on('message', message => {
      if (typeof message !== 'object' || message === null || !('type' in message)) return
      if (message.type === 'stop') stop.resolve()
      if (message.type === 'revoke-viewer') {
        const lease = leases.viewer!
        facts.delete(lease.id)
        void access!.admitFresh(lease.id, lease.token).catch(() => process.send!({ type: 'revoked' }))
      }
      if (message.type === 'change-grant') {
        const lease = leases.changed!, fact = facts.get(lease.id)!
        facts.set(lease.id, { token: fact.token, data: { ...fact.data, grant_version: fact.data.grant_version + 1,
          role: 'operator', bindings: [{ resource: { kind: 'session', id: sessionId }, actions: ['session/follow', 'session/selectModel'] }] } })
        void access!.admitFresh(lease.id, lease.token).catch(() => process.send!({ type: 'changed' }))
      }
    })
    process.once('disconnect', stop.resolve); process.once('SIGTERM', stop.resolve)
    setTimeout(stop.resolve, 120_000).unref()
    await stop.promise
  } finally {
    pairing?.close(); carrier?.close(); await carrier?.drain(); access?.close(); gateway?.close(); await lan?.close()
    await peer.dispose(); await ctx.fiber.dispose(); master.fill(0)
  }
}

if (process.argv[2] === '--worker' && process.argv.length === 4 && process.send) {
  await worker(process.argv[3]!)
} else if (process.argv.length === 2) {
  const root = await mkdtemp(join(tmpdir(), 'dsh-native-authority-'))
  await chmod(root, 0o700)
  const child = fork(entry, ['--worker', root], { cwd: root,
    env: { PATH: '/usr/bin:/bin', HOME: root, TMPDIR: root, XDG_CONFIG_HOME: join(root, 'config'), XDG_DATA_HOME: join(root, 'data'), XDG_CACHE_HOME: join(root, 'cache'), LANG: 'C.UTF-8', SHELL: '/bin/bash' },
    execArgv: ['--import', import.meta.resolve('tsx')], stdio: ['ignore', 'ignore', 'pipe', 'ipc'] })
  child.stderr!.pipe(process.stderr)
  const lines = createInterface({ input: process.stdin })
  lines.on('line', line => { if (child.connected && ['stop', 'revoke-viewer', 'change-grant'].includes(line)) child.send({ type: line }) })
  lines.once('close', () => { if (child.connected) child.send({ type: 'stop' }) })
  child.on('message', message => {
    if (typeof message !== 'object' || message === null || !('type' in message)) return
    if (message.type === 'ready' && 'path' in message) process.stdout.write(`NATIVE_AUTHORITY_FIXTURE=${message.path}\n`)
    if (message.type === 'revoked') process.stdout.write('NATIVE_AUTHORITY_REVOKED\n')
    if (message.type === 'changed') process.stdout.write('NATIVE_AUTHORITY_CHANGED\n')
  })
  const kill = setTimeout(() => child.kill('SIGKILL'), 150_000); kill.unref()
  try { const [code] = await once(child, 'exit'); if (code !== 0) process.exitCode = 1 }
  finally { clearTimeout(kill); lines.close(); await rm(root, { recursive: true, force: true }) }
} else throw new Error('Usage: tsx tools/dev/native-authority-test-host.ts; stdin: revoke-viewer | change-grant | stop')
