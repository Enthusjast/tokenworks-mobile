import test from 'node:test'
import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import { constants } from 'node:fs'
import { chmod, mkdir, mkdtemp, open, rm, writeFile } from 'node:fs/promises'
import { createHash, randomUUID } from 'node:crypto'
import { tmpdir } from 'node:os'
import { isAbsolute, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { hostPackageEntry } from '../../../tools/integration/host-package-entry.js'

test('native secret-tool and isolated GNOME keyring protect installation and journal across lock and restart', {
  skip: process.platform !== 'linux' || process.env.REMOTE_TEST_NATIVE_KEYRING !== '1',
  timeout: 85_000,
}, async t => {
  const binary = process.env.REMOTE_TEST_SECRET_TOOL!, expected = process.env.REMOTE_TEST_SECRET_TOOL_SHA256
  assert.ok(binary && isAbsolute(binary) && expected && /^[0-9a-f]{64}$/.test(expected), 'explicit native secret-tool and pinned SHA-256 required')
  assert.equal(spawnSync('/usr/bin/python3', ['-c', 'from gi.repository import Gio, GLib'], { stdio: 'ignore', timeout: 5000 }).status, 0)
  const root = await mkdtemp(join(tmpdir(), 'dsh-native-keyring-'))
  let child: ReturnType<typeof spawn> | undefined
  const stop = () => { if (child?.pid && child.exitCode === null && child.signalCode === null) { try { process.kill(-child.pid, 'SIGKILL') } catch { /* owned group exited */ } } }
  t.after(async () => { stop(); await rm(root, { recursive: true, force: true }) })
  const plugin = await hostPackageEntry(t)
  const executable = join(root, 'secret-tool'), source = await open(binary, constants.O_RDONLY | constants.O_NOFOLLOW)
  try {
    const info = await source.stat(), bytes = Buffer.alloc((2 << 20) + 1)
    assert.ok(info.isFile() && info.size > 0 && info.size <= (2 << 20))
    let total = 0
    while (total < bytes.length) {
      const { bytesRead } = await source.read(bytes, total, bytes.length - total, total)
      if (!bytesRead) break
      total += bytesRead
    }
    assert.equal(total, info.size)
    assert.equal(createHash('sha256').update(bytes.subarray(0, total)).digest('hex'), expected)
    await writeFile(executable, bytes.subarray(0, total), { flag: 'wx', mode: 0o700 })
    bytes.fill(0)
  } finally { await source.close() }
  await chmod(executable, 0o700)
  for (const directory of ['home', 'data', 'config', 'runtime', 'identity', 'journal']) await mkdir(join(root, directory), { mode: 0o700 })
  const config = join(root, 'fixture.json')
  await writeFile(config, JSON.stringify({ root, executable, plugin, runtimeId: randomUUID(), requestId: randomUUID(), epoch: randomUUID() }), { mode: 0o600 })
  const coordinator = fileURLToPath(new URL('../../../tools/integration/isolated_secret_service.py', import.meta.url))
  const client = fileURLToPath(new URL('./fixtures/native-secret-service.mjs', import.meta.url))
  // No inherited D-Bus, login HOME, display, keyring control or loader injection.
  child = spawn('/usr/bin/dbus-run-session', ['--', '/usr/bin/python3', coordinator, root, process.execPath, client, config], {
    cwd: root, detached: true, stdio: ['ignore', 'pipe', 'pipe'],
    env: { PATH: '/usr/bin:/bin', HOME: join(root, 'home'), XDG_DATA_HOME: join(root, 'data'),
      XDG_CONFIG_HOME: join(root, 'config'), XDG_RUNTIME_DIR: join(root, 'runtime'), NODE_NO_WARNINGS: '1', LANG: 'C.UTF-8' },
  })
  let stdout = '', stderr = ''
  child.stdout!.setEncoding('utf8').on('data', chunk => { stdout = (stdout + chunk).slice(-4096) })
  child.stderr!.setEncoding('utf8').on('data', chunk => { stderr = (stderr + chunk).slice(-4096) })
  const timer = setTimeout(stop, 80_000)
  let exit: number | null
  try { exit = await new Promise<number | null>((resolve, reject) => { child!.once('error', reject); child!.once('close', resolve) }) }
  finally { clearTimeout(timer) }
  assert.equal(exit, 0, 'isolated native keyring workflow failed: ' + (stderr.match(/failed at [a-z-]+/)?.[0] ?? 'private diagnostics withheld'))
  assert.deepEqual(JSON.parse(stdout.trim()), { installation_signing: true, protected_journal: true, locked_rejected: true,
    wrong_password_rejected: true, unlock_reopened: true, daemon_restart_reopened: true })
  t.diagnostic('Actual pinned secret-tool; private session bus, HOME and GNOME keyring; native package signing/journal, lock rejection, password gate and daemon restart passed.')
})
