import assert from 'node:assert/strict'
import test from 'node:test'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { mkdtemp, mkdir, readFile, writeFile, copyFile, rm } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'

const execute = promisify(execFile)
test('exact werift patch is idempotent and unknown source hashes fail before any rewrite', async t => {
  const root = await mkdtemp(join(tmpdir(), 'rtc-patch-integrity-')); t.after(() => rm(root, { recursive: true, force: true }))
  const scripts = join(root, 'scripts'), dependency = join(root, 'node_modules/werift')
  await mkdir(scripts, { recursive: true }); await mkdir(join(dependency, 'lib/webrtc/src'), { recursive: true })
  await writeFile(join(dependency, 'package.json'), JSON.stringify({ name: 'werift', version: '0.27.0', main: 'lib/webrtc/src/index.js' }))
  // Resolution-only package entry. The test never executes this fixture module.
  await writeFile(join(dependency, 'lib/webrtc/src/index.js'), 'throw new Error("resolution-only integrity fixture")\n')
  for (const name of ['patch-werift.mjs', 'werift-resource-patch.json']) await copyFile(resolve('host/remote-control/scripts', name), join(scripts, name))
  const manifest = JSON.parse(await readFile(join(scripts, 'werift-resource-patch.json'), 'utf8')) as Record<string, { original: string; patched: string }>
  for (const path of Object.keys(manifest)) { await mkdir(dirname(join(dependency, path)), { recursive: true }); await copyFile(resolve('node_modules/werift', path), join(dependency, path)) }
  const script = join(scripts, 'patch-werift.mjs')
  await execute(process.execPath, [script, '--check']); await execute(process.execPath, [script]); await execute(process.execPath, [script, '--check'])
  const changed = 'lib/dtls/src/socket.js'
  await writeFile(join(dependency, changed), (await readFile(join(dependency, changed), 'utf8')) + '\n// unknown modification\n')
  await assert.rejects(execute(process.execPath, [script]), error => error instanceof Error && error.message.includes('Unrecognized/unprepared'))
  for (const [path, expected] of Object.entries(manifest)) {
    if (path !== changed) assert.equal(createHash('sha256').update(await readFile(join(dependency, path))).digest('hex'), expected.patched)
  }
})
