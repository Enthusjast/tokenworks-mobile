/** Real SQLite writer; only the transaction boundaries are synchronized. */
import { readSync, writeSync } from 'node:fs'
import { DatabaseSync } from 'node:sqlite'
import { PersistentCommandJournal } from '../../src/command-journal.js'
import { ProbeError } from '../../src/protocol.js'

const [path, runtime, request, role, operation] = process.argv.slice(2) as [string, string, string, string, string]
const owner = { userId: 'fixture-user', deviceId: 'fixture-device' }
const report = (phase: string, values: object = {}) => writeSync(1, JSON.stringify({ phase, ...values }) + '\n')
const wait = () => { if (readSync(0, Buffer.alloc(1), 0, 1, null) !== 1) throw new Error('fixture control closed') }
const journal = await PersistentCommandJournal.open(path, runtime)
const exec = DatabaseSync.prototype.exec
let notified = false
DatabaseSync.prototype.exec = function (sql: string) {
  if (!notified && (role === 'writer' ? sql === 'COMMIT' : sql === 'BEGIN IMMEDIATE')) {
    notified = true
    report(role === 'writer' ? 'before-commit' : 'before-begin')
    if (role === 'writer') wait()
  }
  return exec.call(this, sql)
}
try {
  report('ready')
  wait()
  try {
    if (operation === 'unknown') journal.fail(request, owner, 'outcome_unknown', 'fixture.unknown')
    else if (operation === 'reject') journal.fail(request, owner, 'rejected', 'fixture.rejected')
    else if (operation === 'start') journal.start(request, owner)
    else journal.complete(request, owner, operation === 'queued' ? 'agent_queued' : 'settled', { source: role })
    report('done', { result: 'ok' })
  } catch (cause) {
    report('done', { result: 'error', code: cause instanceof ProbeError ? cause.code : 'fixture.operation_failed' })
  }
  wait()
} finally { DatabaseSync.prototype.exec = exec; journal.close() }
