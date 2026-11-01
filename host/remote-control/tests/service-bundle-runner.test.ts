import test from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const runner = fileURLToPath(new URL('../../../tools/integration/run_remote_dsh.py', import.meta.url))

test('service bundle argument and trust failures cannot initialize PostgreSQL or compile Go', async () => {
  const root = await mkdtemp(join(tmpdir(), 'service-bundle-guard-'))
  try {
    const tools = join(root, 'tools'), marker = join(root, 'unexpected-tool-call')
    await mkdir(tools, { mode: 0o700 })
    for (const name of ['go', 'initdb', 'pg_ctl', 'psql', 'pg_dump']) {
      await writeFile(join(tools, name), '#!/bin/sh\nprintf called > "$SERVICE_BUNDLE_GUARD_MARKER"\nexit 97\n', { mode: 0o700 })
    }
    const directory = join(root, 'not-a-bundle')
    const pair = ['--service-bundle-directory', directory, '--service-bundle-manifest-sha256', 'a'.repeat(64)]
    const cases = [pair, ['--dsh-only', '--service-bundle-directory', directory],
      ['--dsh-only', '--service-bundle-manifest-sha256', 'a'.repeat(64)],
      ['--dsh-only', '--service-bundle-directory', 'relative', '--service-bundle-manifest-sha256', 'a'.repeat(64)],
      ['--dsh-only', '--service-bundle-directory', directory, '--service-bundle-manifest-sha256', 'invalid'],
      ['--dsh-only', ...pair, '--go-run', '^Test'], ['--dsh-only', ...pair]]
    for (const args of cases) {
      const result = spawnSync('python3', [runner, ...args], { encoding: 'utf8', timeout: 5000,
        env: { ...process.env, REMOTE_TEST_PG_BIN: tools, REMOTE_TEST_GO: join(tools, 'go'), SERVICE_BUNDLE_GUARD_MARKER: marker } })
      assert.equal(result.status, 2, result.stderr)
      assert.match(result.stderr, /error:/)
      assert.doesNotMatch(result.stderr, /Traceback|server started|Set REMOTE_TEST_GO/)
      assert.equal(result.stdout, '')
      assert.equal(existsSync(marker), false)
    }
  } finally { await rm(root, { recursive: true, force: true }) }
})


test('service bundle extraction and embedded-version gates reject before database startup', () => {
  const root = fileURLToPath(new URL('../../../', import.meta.url))
  const result = spawnSync('python3', ['-m', 'unittest', 'discover', '-s', 'tools/integration', '-p', 'test_service_bundle_runner.py'], {
    cwd: root, encoding: 'utf8', timeout: 10_000,
  })
  assert.equal(result.status, 0, result.stderr)
})
