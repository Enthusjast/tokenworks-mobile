import test from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../../../', import.meta.url))

test('Go bundle verification rejects unsafe archives and provenance without executing an artifact', () => {
  const result = spawnSync('python3', ['-m', 'unittest', 'discover', '-s', 'tools/release', '-p', 'test_go_bundle.py'], {
    cwd: root, encoding: 'utf8', timeout: 15_000,
  })
  assert.equal(result.status, 0, result.stderr)
})
