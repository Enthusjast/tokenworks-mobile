import test from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../../../', import.meta.url))

test('Go bundle verification rejects unsafe archives and provenance without executing an artifact', () => {
  const args = ['-m', 'unittest', 'discover', '-s', 'tools/release', '-p', 'test_go_bundle.py']
  const options = {
    cwd: root, encoding: 'utf8', timeout: 15_000,
  } as const
  const python3 = spawnSync('python3', args, options)
  const result = (python3.error as NodeJS.ErrnoException | undefined)?.code === 'ENOENT'
    ? spawnSync('python', args, options)
    : python3
  assert.ifError(result.error)
  assert.equal(result.status, 0, result.stderr)
})
