import test from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync, spawn } from 'node:child_process'
import { once } from 'node:events'
import { randomUUID } from 'node:crypto'
import { mkdir, mkdtemp, rm, symlink, writeFile } from 'node:fs/promises'
import { request } from 'node:http'
import { tmpdir } from 'node:os'
import { dirname, isAbsolute, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { hostPackageEntry } from '../../../tools/integration/host-package-entry.js'

function ipc(socketPath: string, path: string, body?: unknown) {
  return new Promise<{ status: number; data: any }>((resolve, reject) => {
    const call = request({ socketPath, path, method: body === undefined ? 'GET' : 'POST',
      headers: { 'content-type': 'application/json' } }, response => {
      let raw = ''
      response.setEncoding('utf8').on('data', chunk => { raw += chunk }).on('end', () => {
        try { resolve({ status: response.statusCode!, data: JSON.parse(raw.trim()) }) }
        catch { reject(new Error('invalid fixture IPC response')) }
      }).on('error', reject)
    })
    call.setTimeout(20_000, () => call.destroy(new Error('fixture IPC timeout')))
    call.on('error', reject).end(body === undefined ? undefined : JSON.stringify(body))
  })
}

test('installed DSH CLI preserves file receipts, native attachments and scoped workspace exports', {
  skip: process.platform !== 'linux' || !process.env.REMOTE_TEST_DSH_BIN,
  timeout: 60_000,
}, async t => {
  const binary = process.env.REMOTE_TEST_DSH_BIN!
  assert.ok(isAbsolute(binary), 'use an absolute path to the installed dsh CLI')
  const plugin = await hostPackageEntry(t)
  const root = await mkdtemp(join(tmpdir(), 'dsh-cli-files-'))
  let child: ReturnType<typeof spawn> | undefined, stopped: Promise<unknown> | undefined
  t.after(async () => {
    if (child && child.exitCode === null && child.signalCode === null) {
      child.kill('SIGTERM')
      const timer = setTimeout(() => child?.kill('SIGKILL'), 5000)
      try { await stopped } finally { clearTimeout(timer) }
    }
    await rm(root, { recursive: true, force: true })
  })
  const cwd = join(root, 'workspace'), socket = join(root, 'probe.sock'), filesSocket = join(root, 'files.sock')
  await mkdir(join(cwd, 'output'), { recursive: true })
  await writeFile(join(cwd, 'output/report.bin'), Buffer.from('installed CLI workspace artifact\0\xff'))
  await writeFile(join(cwd, 'private'), 'private file outside configured export roots')
  await symlink(join(cwd, 'private'), join(cwd, 'output/outside-link'))
  const runtimeId = randomUUID(), workspace = { id: 'fixture', cwd, remoteId: randomUUID(), exportRoots: [{ id: 'reports', directory: 'output' }] }
  const patch = join(root, 'patch.yml')
  const fixture = fileURLToPath(new URL('./fixtures/dsh-cli-files.mjs', import.meta.url))
  const model = fileURLToPath(new URL('./fixtures/dsh-local-model.mjs', import.meta.url))
  // JSON flow objects are YAML values; no personal profile or provider config is read.
  await writeFile(patch, JSON.stringify([
    { insert: [
      { id: 'file-test-model', name: model },
      { id: 'file-test-adapters', name: fixture, config: { runtimeId, workspace, socketPath: filesSocket,
        clientEntry: join(dirname(plugin), 'dsh-client.js') } },
      { id: 'tokenworks-mobile', name: plugin, config: { enabled: true, runtimeId, socketPath: socket,
        commandJournalPath: join(root, 'commands.sqlite'), workspaces: [workspace], presets: ['standard'] } },
    ] },
    { id: 'agent-default-model', config: { provider: 'dsh-m0-test', model: 'offline' } },
  ]), { mode: 0o600 })
  const env = { PATH: process.env.PATH ?? '', HOME: join(root, 'home'), DSH_HOME: join(root, 'dsh'),
    TMPDIR: root, TMP: root, TEMP: root, DSH_TELEMETRY_DISABLED: '1', NO_COLOR: '1' }
  assert.equal(execFileSync(binary, ['--version'], { env, encoding: 'utf8', timeout: 5000 }).trim(), '0.2.1-rc.5')
  child = spawn(binary, ['--profile', 'file-test', '--from-default-profile', 'web', '--patch', patch,
    '--host', '127.0.0.1', '--port', '0', '--no-open'], { cwd, env, stdio: ['ignore', 'ignore', 'ignore'] })
  let spawnError: Error | undefined
  child.on('error', error => { spawnError = error })
  stopped = once(child, 'exit').catch(() => undefined)
  let probe: any
  for (const deadline = Date.now() + 30_000; Date.now() < deadline;) {
    if (spawnError) throw spawnError
    assert.ok(child.exitCode === null && child.signalCode === null, 'installed CLI exited before plugin startup')
    try {
      const result = await ipc(socket, '/m0/probe')
      if (result.status === 200 && result.data.type === 'probe') { probe = result.data; break }
    } catch { /* the private socket opens after native plugin startup */ }
    await new Promise(resolve => setTimeout(resolve, 50))
  }
  assert.ok(probe, 'installed CLI did not open the normal plugin probe')
  assert.equal(probe.payload.dsh_version, '0.2.1-rc.5')
  const created = await ipc(socket, '/m0/command', { v: 0, type: 'session.create', request_id: randomUUID(),
    runtime_epoch: probe.runtime_epoch, payload: { workspace: 'fixture', preset: 'standard' } })
  assert.equal(created.status, 200)
  assert.equal(created.data.type, 'session.create.result')
  const files = await ipc(filesSocket, '/files', { session_id: created.data.payload.session_id })
  assert.equal(files.status, 200, JSON.stringify(files.data))
  assert.deepEqual(files.data, { uploaded: true, persisted: true, integrity_rejected: true,
    attachment_exported: true, workspace_exported: true, symlink_rejected: true, changes_supported: true })
  t.diagnostic('Actual installed dsh 0.2.1-rc.5 web profile; normal compiled plugin and file adapters in the same native process; offline model, isolated HOME/DSH_HOME.')
})
