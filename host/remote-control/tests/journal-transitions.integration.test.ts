import test from 'node:test'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { once } from 'node:events'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createInterface } from 'node:readline'
import { fileURLToPath } from 'node:url'
import type { CommandState } from '../src/command-journal.js'
import { parseCommand } from '../src/protocol.js'
import { hostPackageModule } from '../../../tools/integration/host-package-module.js'

const owner = { userId: 'fixture-user', deviceId: 'fixture-device' }
const fixture = fileURLToPath(new URL('./fixtures/journal-transition-child.ts', import.meta.url))

function peer(path: string, runtime: string, request: string, role: string, operation: string, modules: { journal: string; protocol: string }) {
  const child = spawn(process.execPath, ['--import', 'tsx', fixture, path, runtime, request, role, operation, modules.journal, modules.protocol], {
    env: { ...process.env, NODE_NO_WARNINGS: '1' }, stdio: ['pipe', 'pipe', 'ignore'],
  })
  const exited = once(child, 'exit')
  const lines = createInterface({ input: child.stdout }), iterator = lines[Symbol.asyncIterator]()
  return {
    child, exited,
    send() { child.stdin.write('x') },
    async next(phase: string) {
      let timer: NodeJS.Timeout | undefined
      const row = await Promise.race([iterator.next(), new Promise<never>((_, reject) => {
        timer = setTimeout(() => reject(new Error('journal fixture boundary timeout')), 6000)
      })]).finally(() => { if (timer) clearTimeout(timer) })
      assert.equal(row.done, false, 'journal peer exited before the controlled boundary')
      const event = JSON.parse(row.value!) as { phase: string; result?: string; code?: string }
      assert.equal(event.phase, phase)
      return event
    },
    async close() {
      if (child.exitCode === null && child.signalCode === null) child.kill('SIGTERM')
      await exited.catch(() => {})
      lines.close()
    },
  }
}

test('SQLite journal transitions preserve a competing committed terminal fact', { skip: process.platform === 'win32', timeout: 30_000 }, async t => {
  const selected = await hostPackageModule<typeof import('../src/command-journal.js')>(t, 'command-journal')
  const { PersistentCommandJournal } = selected.module
  const modules = { journal: selected.url.href, protocol: new URL(selected.url.pathname.endsWith('.ts') ? './protocol.ts' : './protocol.js', selected.url).href }
  const cases: Array<{ name: string; writer: string; contender: string; initial: 'host_accepted' | 'dispatching'; state: CommandState; error?: string; result?: object; rejected: boolean }> = [
    { name: 'late completion cannot overwrite unknown', writer: 'unknown', contender: 'queued', initial: 'dispatching', state: 'outcome_unknown', error: 'fixture.unknown', rejected: true },
    { name: 'late failure cannot overwrite settled', writer: 'settled', contender: 'unknown', initial: 'dispatching', state: 'settled', result: { source: 'writer' }, rejected: true },
    { name: 'matching completion retains the first committed result', writer: 'settled', contender: 'settled', initial: 'dispatching', state: 'settled', result: { source: 'writer' }, rejected: false },
    { name: 'late start cannot resurrect rejected', writer: 'reject', contender: 'start', initial: 'host_accepted', state: 'rejected', error: 'fixture.rejected', rejected: true },
  ]
  for (const scenario of cases) await t.test(scenario.name, async t => {
    const root = await mkdtemp(join(tmpdir(), 'dsh-journal-transitions-')), path = join(root, 'commands.sqlite'), runtime = randomUUID()
    const command = parseCommand({ v: 0, type: 'message.submit', request_id: randomUUID(), runtime_epoch: randomUUID(),
      session_id: randomUUID(), payload: { mode: 'queue', text: 'PRIVATE_JOURNAL_RACE_COMMAND' } })
    let journal = await PersistentCommandJournal.open(path, runtime)
    journal.admit(command, owner)
    if (scenario.initial === 'dispatching') journal.start(command.request_id, owner)
    journal.close()
    const writer = peer(path, runtime, command.request_id, 'writer', scenario.writer, modules)
    const contender = peer(path, runtime, command.request_id, 'contender', scenario.contender, modules)
    t.after(async () => { await Promise.all([writer.close(), contender.close()]); await rm(root, { recursive: true, force: true }) })
    await Promise.all([writer.next('ready'), contender.next('ready')])
    writer.send()
    await writer.next('before-commit')
    contender.send()
    await contender.next('before-begin')
    // The first writer has updated the row under a real write transaction.
    // Let it commit only after the second caller reaches BEGIN IMMEDIATE.
    writer.send()
    assert.equal((await writer.next('done')).result, 'ok')
    const outcome = await contender.next('done')
    writer.send(); contender.send()
    const exits = await Promise.all([writer.exited, contender.exited])
    assert.ok(exits.every(([code]) => code === 0))
    journal = await PersistentCommandJournal.open(path, runtime)
    try {
      const fact = journal.get(command.request_id, owner)!
      assert.equal(fact.state, scenario.state)
      assert.equal(fact.errorCode, scenario.error)
      assert.deepEqual(fact.result, scenario.result)
      if (scenario.state === 'outcome_unknown') assert.deepEqual(fact.command, command)
      assert.equal(journal.admit(command, owner).inserted, false)
      assert.equal(outcome.result, scenario.rejected ? 'error' : 'ok')
      if (scenario.rejected) assert.equal(outcome.code, 'journal.invalid_transition')
    } finally { journal.close() }
  })
})
