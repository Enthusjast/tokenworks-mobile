import assert from 'node:assert/strict'
import { fork } from 'node:child_process'
import { createHash } from 'node:crypto'
import dns from 'node:dns'
import http from 'node:http'
import https from 'node:https'
import net from 'node:net'
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { Context } from '@deepseek-ai/cordis'
import Loader, { type EntryOptions } from '@deepseek-ai/cordis-plugin-loader'
import { Peer } from '@deepseek-ai/dsh-client-connection'
import type { TypertContribution } from '@deepseek-ai/dsh-typert-registry'
import { carrier, assertNativePackages, nativeMethods, nativeRoundTrip, nativeVersion, type Carrier } from './native-contract.js'
import type {} from '@deepseek-ai/dsh-api-gateway'
import type {} from '@deepseek-ai/dsh-api-session-controller'
import type {} from '@deepseek-ai/dsh-api-terminal-controller'
import type {} from '@deepseek-ai/dsh-user-questions'
import type {} from '@deepseek-ai/dsh-agent-preset-registry'
import { TYPERT as sessionHost } from '@deepseek-ai/dsh-api-session-controller/typert'
import { TYPERT as workspaceHost } from '@deepseek-ai/dsh-api-workspace-controller/typert'
import { TYPERT as uploadHost } from '@deepseek-ai/dsh-client-file-upload/typert'
import { TYPERT as questionHost } from '@deepseek-ai/dsh-user-questions/typert'
import { TYPERT as accessHost } from '@deepseek-ai/dsh-api-remote-access/typert'
import { TYPERT as terminalHost } from '@deepseek-ai/dsh-api-terminal-controller/typert'

export type CapturedCall = {
  endpoint: string; input: Carrier; results: Carrier[]; rawInput: Carrier; rawResults: Carrier[]
  streaming: boolean; termination: 'returned' | 'cancelled'; uplink?: unknown[]
}
export type NativeCapture = {
  source: 'isolated-installed-native-execution'; dshVersion: string; isolation: string
  execution: { dispatcher: string; providers: string[]; model: { fixture: string; sha256: string }; environmentKeys: string[]; networkAttempts: number }
  calls: CapturedCall[]; notCaptured: { endpoint: string; prerequisites: string }[]
}
const destination = new URL('../contracts/typert/golden/native-captured.json', import.meta.url)
const entry = fileURLToPath(import.meta.url)
const repository = fileURLToPath(new URL('../', import.meta.url))
const modelFixture = 'host/remote-control/tests/fixtures/dsh-local-model.mjs'

/** Execute in a separate process: never inherit the developer's environment or profile. */
export async function captureNativeFixture(): Promise<NativeCapture> {
  assertNativePackages()
  const root = await mkdtemp(join(tmpdir(), 'tokenworks-native-golden-'))
  try {
    const environment = {
      PATH: '/usr/bin:/bin', HOME: root, TMPDIR: root, XDG_CONFIG_HOME: join(root, 'config'),
      XDG_DATA_HOME: join(root, 'data'), XDG_CACHE_HOME: join(root, 'cache'), SHELL: '/bin/bash',
      LANG: 'C.UTF-8', TERM: 'xterm-256color',
    }
    const { promise, resolve, reject } = Promise.withResolvers<NativeCapture>()
    const child = fork(entry, ['--worker', root], {
      cwd: root, env: environment, execArgv: ['--import', import.meta.resolve('tsx')],
      stdio: ['ignore', 'ignore', 'pipe', 'ipc'], serialization: 'json',
    })
    let capture: NativeCapture | undefined
    let errors = ''
    const deadline = setTimeout(() => { child.kill('SIGKILL'); reject(new Error('Native capture exceeded 60 seconds')) }, 60_000)
    child.stderr!.on('data', (bytes: Buffer) => { errors = (errors + bytes.toString()).slice(-16_384) })
    child.on('message', message => { capture = message as NativeCapture })
    child.once('error', error => { clearTimeout(deadline); reject(error) })
    child.once('exit', code => {
      clearTimeout(deadline)
      if (code !== 0 || !capture) reject(new Error(`Native capture worker failed (${code}): ${errors}`))
      else resolve(capture)
    })
    return await promise
  } finally { await rm(root, { recursive: true, force: true }) }
}

/** Shared test-only installed provider assembly. No production profile or provider discovery. */
export async function createIsolatedNativeAssembly(root: string, additional: EntryOptions[] = []) {
  const ctx = new Context()
  const calls: CapturedCall[] = []
  const errors: unknown[] = []
  ctx.on('agent/error', ({ error }) => { errors.push(error) }, { global: true })
  const cwd = join(root, 'workspace')
  await Promise.all([mkdir(cwd), mkdir(join(root, 'documents'))])
  const provider = (name: string, config?: object): EntryOptions => ({ id: name, name: `@deepseek-ai/${name}`, ...(config ? { config } : {}) })
  const rows: EntryOptions[] = [
    provider('dsh-session'), provider('dsh-session-persistence-jsonl', { root: join(root, 'sessions'), compression: 'none' }),
    provider('dsh-session-projection'), provider('dsh-storage'), provider('dsh-storage-json', { root: join(root, 'storage') }),
    provider('dsh-storage-domain', { backend: 'json' }), provider('dsh-workspace'), provider('dsh-session-query-sqlite', { path: join(root, 'query.sqlite'), openAt: 'startup' }),
    provider('dsh-session-title', { fallbackMaxWords: 5, fallbackMaxBytes: 40, maxTitleBytes: 80 }),
    provider('dsh-agent'), provider('dsh-llm'), provider('dsh-system-prompt', { includeHarnessIdentity: false, includeRuntimeContext: false }),
    provider('dsh-tools'), provider('dsh-user-approval'), provider('dsh-typert-registry'), provider('dsh-api-remote-access'),
    provider('dsh-user-questions'), provider('dsh-agent-default-model', { provider: 'dsh-m0-test', model: 'offline' }),
    provider('dsh-fs-local'), provider('dsh-working-directory'), provider('dsh-attachment-local', { dshHome: root }),
    provider('dsh-credentials-local', { path: join(root, 'credentials.yaml') }), provider('dsh-client-connection'),
    provider('dsh-commands'), provider('dsh-client-file-upload'), provider('dsh-api-session-controller', { nativeOpen: false }),
    provider('dsh-agent-preset-registry', { default: 'golden' }), provider('dsh-agent-loop', { agents: [] }),
    provider('dsh-api-workspace-controller', { documentsDirectory: join(root, 'documents') }),
    provider('dsh-sandbox-policy', { mode: 'read-only', workspaceRoot: cwd }), provider('dsh-subprocess-local'),
    provider('dsh-api-terminal-controller', { shell: { path: '/bin/bash', name: 'Isolated bash', args: ['--noprofile', '--norc'] }, shellCandidates: ['/bin/bash'], unattendedTimeoutMs: 0 }),
    provider('dsh-api-gateway'), provider('dsh-tool-ask-user', { mode: 'timed', timeout: 1 }),
    { id: 'offline-model', name: pathToFileURL(join(repository, modelFixture)).href },
    ...additional,
  ]
  assertNativePackages(rows.filter(row => row.name?.startsWith('@deepseek-ai/dsh-')).map(row => row.name!.slice('@deepseek-ai/'.length)))
  try {
    await ctx.plugin(Loader, { baseUrl: pathToFileURL(repository).href }).await()
    await ctx.loader.root.update(rows)
    await ctx.loader.await()
    await Promise.all([...ctx.loader.entries()].map(item => item.fiber?.await()))
    for (const contribution of [sessionHost, workspaceHost, uploadHost, questionHost, accessHost, terminalHost]) {
      ctx.typert.register(contribution as TypertContribution)
    }
    await ctx.agentPresets.register({ id: 'golden', plugins: [] })
    const peer = new Peer(ctx, { principal: { kind: 'owner' }, interactions: 'none' })
    return { ctx, calls, errors, cwd, rows, peer }
  } catch (error) { await ctx.fiber.dispose(); throw error }
}

async function executeNativeFixture(root: string): Promise<NativeCapture> {
  const environmentKeys = Object.keys(process.env).sort()
  // No HTTP listener or model-network provider is installed. These guards also
  // fail closed if a dependency unexpectedly attempts Node network access.
  let networkAttempts = 0
  const denyNetwork = (): never => { networkAttempts++; throw new Error('Network is forbidden in native golden capture') }
  net.Socket.prototype.connect = denyNetwork
  http.request = denyNetwork; http.get = denyNetwork
  https.request = denyNetwork; https.get = denyNetwork
  const denyDns = Object.assign(denyNetwork, { __promisify__: denyNetwork })
  dns.lookup = denyDns; dns.resolve = denyDns
  globalThis.fetch = denyNetwork
  const { ctx, calls, errors, cwd, rows, peer } = await createIsolatedNativeAssembly(root)
  try {
    const invoke = async (endpoint: string, args: Record<string, unknown>, uplink?: unknown[]): Promise<any> => {
      const method = nativeMethods.find(([name]) => name === endpoint)?.[1]
      if (!method) throw new Error(`Missing native fixture endpoint: ${endpoint}`)
      const control = new AbortController()
      const input = nativeRoundTrip(endpoint, 'input', carrier(args))
      const request = {
        namespace: method.namespace, method: method.method, args, peer, signal: control.signal,
        ...(uplink ? { uplink: (async function* () { yield* uplink })() } : {}),
      }
      const streaming = method.mode === 'stream'
      const results: unknown[] = []
      try {
        if (streaming) {
          const iterator = (await ctx.typertGateway.stream(request))[Symbol.asyncIterator]()
          try {
            let acknowledged = false
            let output = false
            do {
              const next = await iterator.next()
              assert.equal(next.done, false, `${endpoint}: native stream must produce an actual frame`)
              results.push(next.value)
              const frame = next.value as { type?: string; sequence?: number; data?: string }
              acknowledged ||= frame.type === 'ack' && frame.sequence === uplink?.length
              output ||= frame.type === 'output' && typeof frame.data === 'string'
              assert.ok(results.length <= 128, `${endpoint}: bounded fixture stream`)
            } while (uplink && !(acknowledged && output))
          } finally { control.abort(); await iterator.return?.() }
        } else results.push(await ctx.typertGateway.invoke(request))
      } catch (cause) { throw new Error(`Native execution failed: ${endpoint}`, { cause }) }
      calls.push(JSON.parse(JSON.stringify({ endpoint, input, rawInput: carrier(args), rawResults: results.map(carrier), results: results.map(value => nativeRoundTrip(endpoint, 'result', carrier(value))), streaming, termination: streaming ? 'cancelled' : 'returned', ...(uplink ? { uplink } : {}) })) as CapturedCall)
      return results[0]
    }
    await invoke('remoteAccess/follow', {})
    const initial = await invoke('workspace/create', { request: { path: join(root, 'documents') } })
    await invoke('workspace/initializeDefault', {}) // a real undefined result, not JSON null
    const workspace = await invoke('workspace/create', { request: { path: cwd } })
    const workspaceId: string = workspace.workspace.workspaceId
    await invoke('workspace/rename', { request: { workspaceId, title: 'isolated fixture 原样🙂' } })
    await invoke('workspace/insertBefore', { request: { workspaceId: initial.workspace.workspaceId, beforeWorkspaceId: workspaceId } })
    const created = await invoke('session/create', { request: { workspaceId } })
    const sessionId = created.sessionId
    const agent = ctx.agents.get(sessionId)!
    await invoke('session/modelCatalog', {})
    await invoke('session/selectModel', { request: { sessionId, provider: 'dsh-m0-test', model: 'offline-alternative' } })
    await invoke('session/rename', { request: { sessionId, title: 'native execution 原样🙂', unknownFixtureField: 'native codec strips this key' } })
    const upload = await invoke('fileUploads/upload', { agentId: sessionId, request: { data: Buffer.from('isolated upload\0原样🙂').toString('base64'), name: 'native.txt' } })
    // Public fixture pixels, never an image from the user's profile or filesystem.
    const image = 'iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAACXBIWXMAAAPoAAAD6AG1e1JrAAAAEUlEQVQImWOosJGrsJFjgFAAGG4DSSYQepwAAAAASUVORK5CYII='
    await invoke('session/prompt', { request: { sessionId, requestId: 'golden-image', mode: 'queue', content: [
      { type: 'text', text: 'isolated capture 原样🙂' }, { type: 'file', receiptId: upload.receiptId },
      { type: 'image', mediaType: 'image/png', data: image, name: 'fixture.png' },
    ] } })
    await agent.whenIdle()
    const imagePart = agent.session.snapshotEvents().filter(event => event.type === 'user/message').flatMap(event => event.data.content).find(part => part.type === 'image')
    assert.ok(imagePart && imagePart.type === 'image')
    await invoke('session/attachment', { request: { sessionId, attachmentId: imagePart.attachment.attachmentId } })
    // A native idle maintenance hold parks the real inbox without a fabricated Agent/model.
    const hold = Promise.withResolvers<void>()
    const held = agent.runMaintenance(() => hold.promise)
    await invoke('session/prompt', { request: { sessionId, requestId: 'golden-queue', mode: 'queue', content: [{ type: 'text', text: 'queued native capture' }] } })
    const pending = agent.inbox.nextTurn[0]!
    await invoke('session/updateQueue', { request: { sessionId, itemId: pending.id, action: { kind: 'edit', content: [{ type: 'text', text: 'edited native capture' }] } } })
    await invoke('session/updateQueue', { request: { sessionId, itemId: pending.id, action: { kind: 'remove' } } })
    hold.resolve(); await held; await agent.whenIdle()
    await invoke('session/cancel', { request: { sessionId } })
    await invoke('session/list', { _request: {} })
    await invoke('session/search', { request: { query: 'isolated capture' } })
    await invoke('session/projections', { request: { sessionId } })
    await invoke('session/projections', { request: { sessionId: 'missing-isolated-session' } })
    const opening = await invoke('session/follow', { request: { address: { kind: 'session', sessionId }, assistantStream: true } })
    await invoke('session/page', { request: { address: { kind: 'session', sessionId }, throughSeq: opening.cursor, maxMessages: 20 } })
    await invoke('session/control', {})
    await invoke('session/fork', { request: { sessionId } })
    // Drive the installed question tool with the offline model. Attach to its
    // actual foreground wait, then answer its actual continued projection.
    const questionReady = Promise.withResolvers<string>()
    const disposeQuestion = ctx.on('user-questions/request', async (request, next) => {
      if (request.agent?.id === sessionId && request.wait) { disposeQuestion(); questionReady.resolve(request.wait.callId) }
      return next()
    }, { global: true })
    await invoke('session/prompt', { request: { sessionId, requestId: 'golden-question', mode: 'queue', content: [{ type: 'text', text: 'REMOTE_TIMED_LATE' }] } })
    const callId = await questionReady.promise
    await invoke('userQuestions/attachWait', { agentId: sessionId, callId })
    await agent.whenIdle()
    assert.equal(await invoke('userQuestions/answer', { agentId: sessionId, callId, answer: { answers: [{ id: 'timed-choice', selected: ['Reply'] }] } }), true)
    await agent.whenIdle()
    await invoke('terminal/environment', { agentId: sessionId })
    await invoke('terminal/shells', { agentId: sessionId })
    const terminal = await invoke('terminal/create', { agentId: sessionId, request: { id: 'golden-terminal', cols: 80, rows: 24 } })
    await invoke('terminal/list', { sessionId })
    await invoke('terminal/retain', { sessionId, id: terminal.id })
    await invoke('terminal/attach', { agentId: sessionId, id: terminal.id }, [
      { type: 'resize', sequence: 1, cols: 81, rows: 25 },
      { type: 'write', sequence: 2, data: "printf 'native-terminal-output\\n'\r" },
    ])
    await invoke('terminal/rename', { agentId: sessionId, id: terminal.id, title: 'native terminal 原样🙂' })
    await invoke('terminal/close', { agentId: sessionId, id: terminal.id })
    await invoke('workspace/insertSessionBefore', { request: { workspaceId, sessionId } })
    await invoke('workspace/pinSession', { request: { sessionId } })
    await invoke('workspace/unpinSession', { request: { sessionId } })
    await invoke('workspace/archiveSession', { request: { sessionId } })
    await invoke('workspace/unarchiveSession', { request: { sessionId } })
    await invoke('workspace/follow', {})
    await invoke('workspace/delete', { request: { workspaceId } })
    assert.deepEqual(errors, [])
    assert.equal(networkAttempts, 0)
    return {
      source: 'isolated-installed-native-execution', dshVersion: nativeVersion,
      isolation: 'Separate child process with allowlisted environment, fresh HOME/XDG/cwd/storage/persistence/attachments/credentials under one private temporary directory, removed after exit. Installed native Gateway, Agent and providers; offline model fixture only; no HTTP listener, user profiles, inherited secrets or network; bash --noprofile --norc.',
      execution: {
        dispatcher: '@deepseek-ai/dsh-api-gateway invoke/stream', providers: rows.map(row => row.id === 'offline-model' ? modelFixture : row.name!),
        model: { fixture: modelFixture, sha256: createHash('sha256').update(await readFile(join(repository, modelFixture))).digest('hex') },
        environmentKeys, networkAttempts,
      }, calls, notCaptured: [],
    }
  } finally { await ctx.fiber.dispose() }
}

export function checkNativeCapture(capture: NativeCapture): void {
  if (capture.source !== 'isolated-installed-native-execution' || capture.dshVersion !== nativeVersion) throw new Error('Invalid native capture provenance')
  if (capture.execution.dispatcher !== '@deepseek-ai/dsh-api-gateway invoke/stream' || capture.execution.networkAttempts !== 0 || !/^[a-f0-9]{64}$/.test(capture.execution.model.sha256)) throw new Error('Invalid native execution evidence')
  assert.deepEqual(capture.execution.environmentKeys, ['HOME', 'LANG', 'PATH', 'SHELL', 'TERM', 'TMPDIR', 'XDG_CACHE_HOME', 'XDG_CONFIG_HOME', 'XDG_DATA_HOME'])
  const captured = new Set<string>()
  for (const call of capture.calls) {
    const method = nativeMethods.find(([endpoint]) => endpoint === call.endpoint)?.[1]
    if (!method || call.streaming !== (method.mode === 'stream') || call.termination !== (call.streaming ? 'cancelled' : 'returned') || !call.results.length || call.results.length !== call.rawResults.length) throw new Error(`Invalid native call evidence: ${call.endpoint}`)
    captured.add(call.endpoint)
    assert.deepEqual(nativeRoundTrip(call.endpoint, 'input', call.rawInput), call.input, `${call.endpoint}: captured input`)
    for (const [index, result] of call.rawResults.entries()) assert.deepEqual(nativeRoundTrip(call.endpoint, 'result', result), call.results[index], `${call.endpoint}: captured result`)
    if (method.uplink) {
      assert.ok(call.uplink?.length, `${call.endpoint}: actual duplex input is required`)
      for (const value of call.uplink) assert.deepEqual(nativeRoundTrip(call.endpoint, 'uplink', carrier(value)), carrier(value))
    } else assert.equal(call.uplink, undefined)
  }
  assert.deepEqual([...captured].sort(), nativeMethods.map(([endpoint]) => endpoint).sort(), 'Actual native execution must cover every selected method and stream')
  assert.deepEqual(capture.notCaptured, [], 'Schema-only endpoints cannot satisfy execution coverage')
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  if (process.argv[2] === '--worker') {
    try { process.send!(await executeNativeFixture(process.argv[3]!)); process.disconnect!() }
    catch (error) { console.error(error); process.exitCode = 1; process.disconnect?.() }
  } else if (process.argv.includes('--check')) {
    const capture = JSON.parse(await readFile(destination, 'utf8')) as NativeCapture
    checkNativeCapture(capture)
    console.log(`Checked ${capture.calls.length} actual native calls across ${nativeMethods.length} methods and streams; zero schema-only endpoints`)
  } else {
    const capture = await captureNativeFixture()
    checkNativeCapture(capture)
    await mkdir(dirname(fileURLToPath(destination)), { recursive: true })
    // Keep this aggregate below the production bounded JSON reader's size limit.
    await writeFile(destination, JSON.stringify(capture) + '\n')
    console.log(`Captured ${capture.calls.length} actual native calls across ${nativeMethods.length} methods and streams; zero schema-only endpoints`)
  }
}
