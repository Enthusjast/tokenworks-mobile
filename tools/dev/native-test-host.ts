import assert from 'node:assert/strict'
import { fork, execFileSync } from 'node:child_process'
import { createCipheriv, createDecipheriv, createHash, randomBytes, randomUUID } from 'node:crypto'
import dns from 'node:dns'
import dgram from 'node:dgram'
import { openSync, writeSync, closeSync } from 'node:fs'
import http from 'node:http'
import https from 'node:https'
import net from 'node:net'
import { chmod, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir, networkInterfaces } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { once } from 'node:events'
import { createInterface } from 'node:readline'
import { createIsolatedNativeAssembly } from '../capture-native-golden.js'
import { nativeMethods, nativeVersion } from '../native-contract.js'
import { NativeGateway } from '../../host/remote-control/src/native-gateway.js'
import { LanGateway } from '../../host/remote-control/src/lan-gateway.js'
import { LanPairing, type LanPairingDescriptor } from '../../host/remote-control/src/lan-pairing.js'
import { LanPairingStore, type PairingManifest } from '../../host/remote-control/src/lan-pairing-store.js'
import type { JournalKeyProtector } from '../../host/remote-control/src/journal-key-protection.js'
import type {} from '@deepseek-ai/dsh-tools'
import { AttachmentId } from '@deepseek-ai/dsh-attachment'
import { NoiseCarrier, type CarrierAdmission } from '../../host/remote-control/src/noise-carrier.js'
import type { RtcCarrierHost } from '../../host/remote-control/src/rtc-carrier.js'
import { loadRtcConfig } from '../../host/remote-control/src/rtc-config.js'
import type { Duplex } from 'node:stream'

export interface NativeTestDescriptor {
  version: 1
  dshVersion: string
  pairing: LanPairingDescriptor
  operatorCaPem: string
  sessionId: string
  workspaceId: string
  owner: PairingManifest['owner']
  image: { name: string; data: string; sha256: string; uploadedPath: string }
  artifacts: { root: string; workspace: string; ca: string; descriptor: string }
  rtc?: { enabled: boolean; capturePath: string; selectedRoutesPath: string; interfaceAddress: string; largeFile: { path: string; sha256: string; bytes: number } }
}
const entry = fileURLToPath(import.meta.url)

/** Test-only launcher. Never imported by the shipped plugin. The caller owns its lifetime.
 * Only this private IPC channel controls the worker; HTTP cannot issue/refresh pairing codes.
 * No inherited HOME, provider variables, credentials, NODE_OPTIONS, profile, stdin or cwd.
 */
export async function startNativeTestHost(options: { rtcConfigFile?: string; rtcInterfaceAddress?: string } = {}) {
  const rtcConfig = options.rtcConfigFile ? await loadRtcConfig(options.rtcConfigFile) : undefined
  const rtcInterface = options.rtcInterfaceAddress ?? '127.0.0.1'
  const localAddresses = new Set(['127.0.0.1', ...Object.values(networkInterfaces()).flatMap(values => (values ?? []).filter(value => value.family === 'IPv4').map(value => value.address))])
  if (rtcConfig && (!rtcConfig.enabled || !localAddresses.has(rtcInterface))) throw new Error('RTC fixture requires an enabled private config and exact local interface')
  const root = await mkdtemp(join(tmpdir(), 'dsh-native-android-'))
  await chmod(root, 0o700)
  const privateRtc = join(root, 'rtc-private.json')
  if (rtcConfig) await writeFile(privateRtc, JSON.stringify(rtcConfig), { mode: 0o600, flag: 'wx' })
  const environment = {
    PATH: '/usr/bin:/bin', HOME: root, TMPDIR: root, XDG_CONFIG_HOME: join(root, 'config'),
    XDG_DATA_HOME: join(root, 'data'), XDG_CACHE_HOME: join(root, 'cache'), SHELL: '/bin/bash',
    LANG: 'C.UTF-8', TERM: 'xterm-256color',
    ...(rtcConfig ? { TOKENWORKS_TEST_RTC_CONFIG_PATH: privateRtc, TOKENWORKS_TEST_RTC_INTERFACE: rtcInterface } : {}),
  }
  const child = fork(entry, ['--isolated-worker', root], {
    cwd: root, env: environment, execArgv: ['--import', import.meta.resolve('tsx')],
    stdio: ['ignore', 'ignore', 'pipe', 'ipc'], serialization: 'json',
  })
  let errors = ''
  child.stderr!.on('data', (bytes: Buffer) => { errors = (errors + bytes.toString()).slice(-16_384) })
  const exited = once(child, 'exit')
  let closing: Promise<void> | undefined
  const close = (): Promise<void> => closing ??= (async () => {
    if (child.exitCode === null && child.signalCode === null) {
      if (child.connected) child.send({ type: 'stop' })
      const force = setTimeout(() => child.kill('SIGKILL'), 10_000)
      try { await exited } finally { clearTimeout(force) }
    }
    await rm(root, { recursive: true, force: true })
  })()
  try {
    const ready = Promise.withResolvers<string>()
    const timeout = setTimeout(() => ready.reject(new Error('Native test host startup timed out')), 60_000)
    child.on('message', message => {
      if (typeof message === 'object' && message !== null && 'type' in message && message.type === 'ready' && 'path' in message && typeof message.path === 'string') ready.resolve(message.path)
    })
    child.once('error', ready.reject)
    void exited.then(([code]) => ready.reject(new Error(`Native test host exited (${code}): ${errors}`)))
    let path: string
    try { path = await ready.promise } finally { clearTimeout(timeout) }
    assert.equal(path, join(root, 'fixture.json'))
    const descriptor = JSON.parse(await readFile(path, 'utf8')) as NativeTestDescriptor
    return { path, descriptor, close, exited }
  } catch (error) { await close(); throw error }
}

async function worker(root: string) {
  process.umask(0o077)
  assert.equal(process.cwd(), root)
  assert.equal(process.env.HOME, root)
  await Promise.all(['config', 'data', 'cache'].map(name => mkdir(join(root, name))))
  const ca = join(root, 'operator-ca.pem'), caKey = join(root, 'operator-ca.key')
  const certificate = join(root, 'localhost.pem'), keyPath = join(root, 'localhost.key')
  const openssl = (args: string[]) => execFileSync('/usr/bin/openssl', args, { cwd: root, env: process.env, stdio: 'ignore', timeout: 15_000 })
  openssl(['req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:P-256', '-nodes', '-keyout', caKey, '-out', ca, '-days', '1', '-subj', '/CN=DSH isolated test operator CA', '-addext', 'basicConstraints=critical,CA:TRUE', '-addext', 'keyUsage=critical,keyCertSign,cRLSign'])
  openssl(['req', '-new', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:P-256', '-nodes', '-keyout', keyPath, '-out', join(root, 'localhost.csr'), '-subj', '/CN=localhost'])
  await writeFile(join(root, 'localhost.ext'), 'basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:localhost,IP:127.0.0.1\n')
  openssl(['x509', '-req', '-in', join(root, 'localhost.csr'), '-CA', ca, '-CAkey', caKey, '-CAcreateserial', '-out', certificate, '-days', '1', '-extfile', join(root, 'localhost.ext')])
  // Default mode forbids all outbound network; explicit RTC mode admits only
  // declared server endpoints and the bounded observed ICE candidates below.
  const rtcPath = process.env.TOKENWORKS_TEST_RTC_CONFIG_PATH
  if (rtcPath && rtcPath !== join(root, 'rtc-private.json')) throw new Error('RTC fixture private config path mismatch')
  const rtcConfig = await loadRtcConfig(rtcPath), rtcInterface = process.env.TOKENWORKS_TEST_RTC_INTERFACE ?? '127.0.0.1'
  const allowedUdp = new Map<string, number>(), declaredUdp = new Set<string>()
  for (const value of [...rtcConfig.stunUrls, ...(rtcConfig.turn?.urls ?? [])]) {
    const endpoint = new URL(value.replace(/^(?:stun|turn):/, 'http://'))
    const address = endpoint.hostname.replace(/^\[|\]$/g, '')
    if (!net.isIP(address)) throw new Error('RTC fixture requires literal operator server addresses')
    declaredUdp.add(JSON.stringify([address, Number(endpoint.port || 3478)]))
  }
  const deny = (): never => { throw new Error('Outbound network forbidden in isolated native test host') }
  net.Socket.prototype.connect = deny
  http.request = deny; http.get = deny; https.request = deny; https.get = deny
  dgram.Socket.prototype.connect = deny
  dgram.Socket.prototype.send = new Proxy(dgram.Socket.prototype.send, {
    apply(target, receiver, args) {
      // Exact overload used by werift's UDP transport; no DNS/connected sends.
      const key = JSON.stringify([args[2], args[1]])
      if (!rtcPath || typeof args[1] !== 'number' || typeof args[2] !== 'string' || (!declaredUdp.has(key) && !allowedUdp.has(key))) return deny()
      return Reflect.apply(target, receiver, args)
    },
  })
  const lookup = dns.lookup
  dns.lookup = new Proxy(lookup, {
    apply(target, receiver, args) {
      if (args[0] !== '127.0.0.1') return deny()
      return Reflect.apply(target, receiver, args)
    },
    get(target, property, receiver) { return property === '__promisify__' ? deny : Reflect.get(target, property, receiver) },
  })
  dns.resolve = Object.assign(deny, { __promisify__: deny }); globalThis.fetch = deny
  const assembly = await createIsolatedNativeAssembly(root, [
    { id: 'test-shell-env', name: '@deepseek-ai/dsh-shell-env' },
    { id: 'test-bash-local', name: '@deepseek-ai/dsh-bash-local', config: { cwd: join(root, 'workspace') } },
    { id: 'test-bash-tool', name: '@deepseek-ai/dsh-tool-bash' },
    { id: 'test-native-remotes', name: '@deepseek-ai/dsh-api-remotes' },
  ])
  const { ctx, peer, cwd } = assembly
  let lan: LanGateway | undefined, pairing: LanPairing | undefined, native: NativeGateway | undefined
  let carrier: NoiseCarrier | undefined
  let rtc: RtcCarrierHost | undefined, captureFd: number | undefined, routesFd: number | undefined
  let rtcDetails: NativeTestDescriptor['rtc']
  let capturedBytes = 0, routeBytes = 0
  const capturePath = join(root, 'rtc-post-dtls.bin'), selectedRoutesPath = join(root, 'rtc-selected-routes.jsonl')
  const master = randomBytes(32)
  const protector: JournalKeyProtector = {
    id: 'isolated-test-memory-key',
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
  try {
    // The installed tool and approval service perform real work. The fixture model may
    // request only these exact local sentinel actions, never arbitrary commands/providers.
    const commands = new Set(['allow', 'reject', 'revoke'].map(mode => `printf authorized >> approval-${mode}.txt`))
    ctx.on('tools/pre-execute', async (exec, next) => {
      if (exec.name !== 'bash') return next()
      const args = exec.arguments as Record<string, unknown>
      if (!commands.has(String(args.command)) || Object.keys(args).some(key => !['command', 'description'].includes(key))) return { kind: 'deny', reason: 'test command not allowlisted' }
      return { kind: 'ask', reason: 'Isolated native approval proof' }
    }, { global: true })
    const invoke = async (endpoint: string, args: Record<string, unknown>): Promise<any> => {
      const method = nativeMethods.find(([name]) => name === endpoint)?.[1]
      assert.ok(method)
      return ctx.typertGateway.invoke({ namespace: method.namespace, method: method.method, args, peer, signal: new AbortController().signal })
    }
    const workspace = await invoke('workspace/create', { request: { path: cwd } })
    const workspaceId = workspace.workspace.workspaceId as string
    const created = await invoke('session/create', { request: { workspaceId } })
    const sessionId = created.sessionId as string
    const reserve = net.createServer()
    reserve.listen(0, '127.0.0.1'); await once(reserve, 'listening')
    const bound = reserve.address(); assert.ok(bound && typeof bound === 'object')
    const reserved = Promise.withResolvers<void>()
    reserve.close(error => error ? reserved.reject(error) : reserved.resolve())
    await reserved.promise
    const origin = `https://localhost:${bound.port}`, runtimeId = randomUUID(), installationId = randomUUID()
    const cert = await readFile(certificate, 'utf8'), key = await readFile(keyPath, 'utf8')
    pairing = await LanPairing.open({ origin, runtimeId, installationId }, cert, new LanPairingStore(join(root, 'paired'), runtimeId, installationId, protector), ctx.connection)
    native = new NativeGateway(ctx.connection, ctx.typertGateway)
    class CapturedCarrier extends NoiseCarrier {
      override async accept(outer: Duplex, admission: CarrierAdmission): Promise<void> {
        if (admission.routeKind === 'p2p' || admission.routeKind === 'turn') {
          const capture = (bytes: unknown) => {
            if (bytes === null) return
            if (!Buffer.isBuffer(bytes) || captureFd === undefined || capturedBytes + bytes.length > 4 * 1024 * 1024) { outer.destroy(new Error('test/rtc-capture-budget')); throw new Error('test/rtc-capture-budget') }
            let offset = 0
            while (offset < bytes.length) offset += writeSync(captureFd, bytes, offset)
            capturedBytes += bytes.length
          }
          const push = outer.push.bind(outer), write = outer._write.bind(outer)
          outer.push = (bytes, encoding) => { capture(bytes); return push(bytes, encoding) }
          outer._write = (bytes, encoding, callback) => { capture(bytes); write(bytes, encoding, callback) }
        }
        await super.accept(outer, admission)
      }
    }
    carrier = new CapturedCarrier(pairing, native, nativeVersion, origin)
    if (rtcPath) {
      // Test-only loading boundary: strict native mode must not load RTC or
      // require a prepared/patched RTC dependency merely to run LAN proofs.
      const { RtcCarrierHost, selectedRtcRoute } = await import('../../host/remote-control/src/rtc-carrier.js')
      captureFd = openSync(capturePath, 'wx', 0o600); routesFd = openSync(selectedRoutesPath, 'wx', 0o600)
      const largeBytes = Buffer.alloc(400003)
      for (let index = 0; index < largeBytes.length; index++) largeBytes[index] = (index * 37 + 11) & 255
      const largeFile = { path: join(cwd, 'rtc-large-native.bin'), sha256: createHash('sha256').update(largeBytes).digest('hex'), bytes: largeBytes.length }
      await writeFile(largeFile.path, largeBytes, { mode: 0o600, flag: 'wx' })
      rtcDetails = { enabled: true, capturePath, selectedRoutesPath, interfaceAddress: rtcInterface, largeFile }
      rtc = new RtcCarrierHost(pairing, carrier, rtcConfig, (pc, remote) => {
        const endpoints = new Set<string>(), peerIps = new Set<string>()
        const admit = (address: string, port: number) => {
          const key = JSON.stringify([address, port])
          if (endpoints.has(key)) return
          if (endpoints.size >= 64 || !net.isIP(address)) throw new Error('test/rtc-candidate-budget')
          endpoints.add(key); allowedUdp.set(key, (allowedUdp.get(key) ?? 0) + 1)
        }
        for (const candidate of remote.media[0]!.iceCandidates) { admit(candidate.ip, candidate.port); peerIps.add(candidate.ip) }
        pc.setConfiguration({ iceInterfaceAddresses: { udp4: rtcInterface }, iceUseIpv6: false,
          iceFilterStunResponse: (_message, address) => { if (!peerIps.has(address[0])) return false; admit(address[0], address[1]); return true } })
        pc.connectionStateChange.subscribe(state => {
          if (state === 'closed') for (const key of endpoints) { const count = allowedUdp.get(key) ?? 0; if (count <= 1) allowedUdp.delete(key); else allowedUdp.set(key, count - 1) }
          if (state === 'connected' && routesFd !== undefined) {
            const routeKind = remote.media[0]!.iceCandidates.every(candidate => candidate.type === 'relay') ? 'turn' : 'p2p'
            const bytes = Buffer.from(JSON.stringify(selectedRtcRoute(pc, routeKind)) + '\n')
            if (bytes.length > 512 || routeBytes + bytes.length > 256 * 1024) { void pc.close(); throw new Error('test/rtc-route-budget') }
            routeBytes += writeSync(routesFd, bytes)
          }
        })
      })
      carrier.setRtcControl(rtc)
    }
    lan = new LanGateway({ host: '127.0.0.1', port: bound.port, certificate: { cert, key }, pairing, connection: ctx.connection, gateway: native, carrier })
    await lan.listen()
    const path = join(root, 'fixture.json')
    const imageData = 'iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAACXBIWXMAAAPoAAAD6AG1e1JrAAAAEUlEQVQImWOosJGrsJFjgFAAGG4DSSYQepwAAAAASUVORK5CYII='
    const imageBytes = Buffer.from(imageData, 'base64'), name = 'native-proof.png'
    const sha256 = createHash('sha256').update(imageBytes).digest('hex')
    // Ask the installed provider for the future immutable object path. Do not
    // seed it: only the Android upload may create this object.
    const uploadedPath = ctx.attachments.fileHostPath({ attachmentId: AttachmentId(`sha256:${sha256}`), name, bytes: imageBytes.length })
    assert.ok(uploadedPath)
    assert.ok(uploadedPath.startsWith(root + '/'))
    const owner = { account_id: randomUUID(), organization_id: randomUUID(), deployment_sha256: createHash('sha256').update('isolated native Android fixture').digest('hex'), user_id: randomUUID(), device_id: randomUUID() }
    const descriptor: NativeTestDescriptor = { version: 1, dshVersion: nativeVersion, pairing: pairing.begin(), operatorCaPem: await readFile(ca, 'utf8'), sessionId, workspaceId, owner,
      image: { name, data: imageData, sha256, uploadedPath }, artifacts: { root, workspace: cwd, ca, descriptor: path },
      ...(rtcDetails ? { rtc: rtcDetails } : {}) }
    await writeFile(path, JSON.stringify(descriptor) + '\n', { mode: 0o600, flag: 'wx' })
    process.send!({ type: 'ready', path })
    const stopped = Promise.withResolvers<void>()
    // Child stdin is closed. The only trusted parent operation is shutdown; no
    // prompt injection, shell command, pairing refresh, path or provider control.
    process.on('message', message => { if (typeof message === 'object' && message !== null && Object.keys(message).length === 1 && 'type' in message && message.type === 'stop') stopped.resolve() })
    process.once('disconnect', stopped.resolve); process.once('SIGTERM', stopped.resolve); process.once('SIGINT', stopped.resolve)
    setTimeout(stopped.resolve, 30 * 60_000).unref()
    await stopped.promise
  } finally {
    pairing?.close(); carrier?.close(); await carrier?.drain(); native?.close(); await lan?.close()
    if (captureFd !== undefined) closeSync(captureFd)
    if (routesFd !== undefined) closeSync(routesFd)
    await peer.dispose(); await ctx.fiber.dispose(); master.fill(0)
  }
}

if (process.argv[1] === entry) {
  if (process.argv[2] === '--isolated-worker' && process.argv.length === 4 && process.send) {
    try { await worker(process.argv[3]!) }
    catch (error) { console.error(error); process.exitCode = 1 }
    finally { await rm(process.argv[3]!, { recursive: true, force: true }); process.disconnect?.() }
  } else if (process.argv.length === 2 || (process.argv.length === 6 && process.argv[2] === '--rtc-config-file' && process.argv[4] === '--rtc-interface')) {
    const host = await startNativeTestHost(process.argv.length === 2 ? {} : { rtcConfigFile: process.argv[3]!, rtcInterfaceAddress: process.argv[5]! })
    // Only a private path is printed. Pairing code is never printed or served.
    console.log(`NATIVE_TEST_FIXTURE=${host.path}`)
    const input = createInterface({ input: process.stdin, terminal: false })
    const stopped = Promise.withResolvers<void>()
    input.on('line', line => { if (line === 'stop') stopped.resolve() })
    input.once('close', stopped.resolve); process.once('SIGTERM', stopped.resolve); process.once('SIGINT', stopped.resolve)
    void host.exited.then(() => stopped.resolve())
    await stopped.promise
    input.close(); await host.close()
  } else throw new Error('Usage: tsx tools/dev/native-test-host.ts; stdin accepts only stop')
}
