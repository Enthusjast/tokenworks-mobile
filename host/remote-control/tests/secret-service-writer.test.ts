import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { hostPackageModule } from '../../../tools/integration/host-package-module.js'

test('Secret Service writer bounds live children and restores capacity after cancelled children exit', {
  skip: process.platform !== 'linux', timeout: 8000,
}, async t => {
  const { module: { LinuxSecretServiceWriter } } = await hostPackageModule<typeof import('../src/linux-secret-service.js')>(t, 'linux-secret-service')
  const root = await mkdtemp(join(tmpdir(), 'dsh-secret-writer-')), executable = join(root, 'secret-tool'), pids = join(root, 'children')
  const children = async () => (await readFile(pids, 'utf8').catch(() => '')).trim().split('\n').filter(Boolean).map(Number)
  t.after(async () => {
    for (const pid of await children()) { try { process.kill(pid, 'SIGKILL') } catch { /* already reaped */ } }
    await rm(root, { recursive: true, force: true })
  })
  await writeFile(executable, [
    '#!/usr/bin/env node', "const fs=require('node:fs')",
    "process.on('SIGTERM',()=>{})",
    `fs.appendFileSync(${JSON.stringify(pids)},String(process.pid)+'\\n',{mode:0o600})`,
    `const count=fs.readFileSync(${JSON.stringify(pids)},'utf8').trim().split('\\n').length`,
    "process.stdin.on('data',chunk=>chunk.fill(0))",
    "if(count>8)process.stdin.on('end',()=>process.exit(0));else setInterval(()=>{},1000)", '',
  ].join('\n'), { mode: 0o700 })
  const writer = new LinuxSecretServiceWriter({ executable, service: 'fixture-writer', account: 'fixture-reference' })
  const secret = Buffer.from('PRIVATE_SYNTHETIC_WRITER_INPUT'), controllers = Array.from({ length: 8 }, () => new AbortController())
  const pending = controllers.map(controller => writer.store(secret, controller.signal))
  const outcomes = Promise.allSettled(pending)
  try {
    const deadline = Date.now() + 3000
    while ((await children()).length < 8) {
      assert.ok(Date.now() < deadline, 'owned children never reached the controlled boundary')
      await new Promise(resolve => setTimeout(resolve, 10))
    }
    await assert.rejects(writer.store(secret, AbortSignal.timeout(1500)), /^Error: Secret Service unavailable$/)
    assert.equal((await children()).length, 8, 'a ninth helper was launched while eight remained alive')
  } finally {
    for (const controller of controllers) controller.abort()
    assert.ok((await outcomes).every(result => result.status === 'rejected'))
    secret.fill(0)
  }
  const deadline = Date.now() + 2000
  while (true) {
    const live = (await children()).some(pid => {
      try { process.kill(pid, 0); return true } catch (error) {
        if ((error as NodeJS.ErrnoException).code === 'ESRCH') return false
        throw error
      }
    })
    if (!live) break
    assert.ok(Date.now() < deadline, 'cancelled helper did not terminate after escalation')
    await new Promise(resolve => setTimeout(resolve, 10))
  }
  await new Promise<void>(resolve => setImmediate(resolve))
  await writer.store(Buffer.from('NEW_SYNTHETIC_INPUT'), AbortSignal.timeout(1500))
  assert.equal((await children()).length, 9, 'settled children did not release their capacity')
})
