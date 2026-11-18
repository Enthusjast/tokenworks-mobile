import assert from 'node:assert/strict'
import { readdirSync } from 'node:fs'
import { chmod, link, lstat, mkdir, mkdtemp, readFile, readdir, rename, rm, statfs, symlink, unlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { basename, dirname, join } from 'node:path'
import { test, type TestContext } from 'node:test'
import { Context } from '@deepseek-ai/cordis'
import { SESSION_FORMAT_VERSION, SessionId } from '@deepseek-ai/dsh-session'
import { SessionAlreadyOwnedError, SessionPersistenceRevision, type SessionPersistence, type SessionPersistenceSnapshot } from '@deepseek-ai/dsh-session-persistence'
import { apply as jsonl } from '@deepseek-ai/dsh-session-persistence-jsonl'
import { SessionDeletionStorageError, SessionDeletionStore } from '../src/session-deletion-store.js'

const quarantineName = '!tokenworks-session-deletion'
const unavailable = { name: 'SessionDeletionStorageError', code: 'unavailable' }
const unknown = { name: 'SessionDeletionStorageError', code: 'outcome-unknown' }
const signal = new AbortController().signal
const current = () => {}
const linux = { skip: process.platform !== 'linux' || !await statfs('/proc/self/fd').then(info => info.type === 0x9fa0, () => false), timeout: 20_000 }

async function fixture(t: TestContext, compression: 'none' | 'zstd' = 'none') {
  const temp = await mkdtemp(join(tmpdir(), 'session-delete-test-'))
  const root = join(temp, 'sessions')
  const cwd = join(temp, 'workspace fixture 雪')
  const contexts: Context[] = []
  let opened: SessionDeletionStore | undefined
  t.after(async () => {
    try {
      await opened?.close()
      await Promise.all(contexts.map(ctx => ctx.fiber.dispose()))
    } finally { await rm(temp, { recursive: true, force: true }) }
  })
  await mkdir(root, { mode: 0o700 })
  await mkdir(cwd, { mode: 0o700 })
  const connect = async () => {
    const ctx = new Context()
    contexts.push(ctx)
    await ctx.plugin(jsonl, { root, compression }).await()
    return ctx.sessionPersistence
  }
  const persistence = await connect()
  const store = opened = await SessionDeletionStore.open(root)
  const create = async (name: string) => {
    const id = SessionId(name)
    {
      await using writer = await persistence.create({ id, version: SESSION_FORMAT_VERSION, cwd, createdAt: 1, isSeeded: false })
      await writer.flush()
    }
    const snapshot = await persistence.stat(id)
    assert.ok(snapshot?.location)
    const log = snapshot.location.path
    return { id, snapshot, log, dir: dirname(log), project: dirname(dirname(log)) }
  }
  return { temp, root, cwd, store, persistence, connect, create, quarantine: join(root, quarantineName) }
}

function intercept(persistence: SessionPersistence, overrides: Partial<Pick<SessionPersistence, 'open' | 'stat'>>): SessionPersistence {
  return new Proxy(persistence, {
    get(target, property) {
      if (property === 'open' || property === 'stat') return overrides[property] ?? target[property].bind(target)
      return Reflect.get(target, property, target)
    },
  })
}

for (const compression of ['none', 'zstd'] as const) {
  test(`deletes all native JSONL generations atomically without deleting the project or sibling (${compression})`, linux, async t => {
    const { cwd, root, quarantine, store, persistence, connect, create } = await fixture(t, compression)
    const target = await create('session /~雪')
    const sibling = await create('sibling')
    const siblingBytes = await readFile(sibling.log)
    const workspaceFile = join(cwd, 'fixture.txt')
    await writeFile(workspaceFile, 'workspace fixture')
    const suffix = compression === 'none' ? '' : '.zstd'
    for (let version = 0; version < SESSION_FORMAT_VERSION; version++) {
      const name = version === 0 ? 'session.jsonl' : `session.v${version}.jsonl`
      await writeFile(join(target.dir, name + suffix), `synthetic historical generation ${version}\n`, { mode: 0o600 })
    }
    assert.equal(await store.canDelete(target.snapshot), true)
    await store.remove(persistence, target.id, current, signal)
    await assert.rejects(lstat(target.dir), { code: 'ENOENT' })
    assert.deepEqual(await readdir(target.project), [basename(sibling.dir)])
    assert.deepEqual(await readFile(sibling.log), siblingBytes)
    assert.equal(await readFile(workspaceFile, 'utf8'), 'workspace fixture')
    assert.deepEqual(await readdir(quarantine), [])
    assert.equal(await persistence.stat(target.id), undefined)
    const reopened = await connect()
    assert.deepEqual((await reopened.list()).map(row => row.header.id), [sibling.id])
    await store.close()
    const deletionAfterRestart = await SessionDeletionStore.open(root)
    await deletionAfterRestart.close()
  })
}

test('refuses noncanonical roots, conflicting provider locations and non-current snapshots', linux, async t => {
  const { temp, root, store, persistence, create } = await fixture(t)
  const { id, log, snapshot } = await create('candidate')
  const linkPath = join(temp, 'alias')
  await symlink(temp, linkPath)
  await assert.rejects(SessionDeletionStore.open(join(linkPath, 'sessions')), unavailable)
  await assert.rejects(SessionDeletionStore.open(`${temp}/./sessions`), unavailable)
  await chmod(root, 0o770)
  await assert.rejects(SessionDeletionStore.open(root), unavailable)
  await chmod(root, 0o700)
  assert.ok(snapshot.location)
  const escapedPaths: SessionPersistenceSnapshot[] = [
    { ...snapshot, location: { ...snapshot.location, path: `${temp}/../other/${basename(log)}` } },
    { ...snapshot, location: { ...snapshot.location, path: `${dirname(log)}/./${basename(log)}` } },
    { ...snapshot, location: { ...snapshot.location, kind: 'other' } },
    { ...snapshot, formatStatus: 'migration-required' },
    { ...snapshot, header: { ...snapshot.header, cwd: '/wrong-project' } },
    { ...snapshot, header: { ...snapshot.header, id: SessionId('different-id') } },
  ]
  const { location: _location, ...noLocation } = snapshot
  escapedPaths.push(noLocation)
  for (const candidate of escapedPaths) {
    assert.equal(await store.canDelete(candidate), false)
    let writerOpens = 0
    const wrong = intercept(persistence, {
      stat: async () => candidate,
      open: (...args) => { writerOpens++; return persistence.open(...args) },
    })
    await assert.rejects(store.remove(wrong, id, current, signal), unavailable)
    assert.equal(writerOpens, 0)
  }
  assert.equal(await store.canDelete(snapshot), true)
  assert.ok(await persistence.stat(id))
})

test('rejects unsafe children and modes before the public write-open can truncate a lock', linux, async t => {
  for (const scenario of ['unknown', 'subdir', 'hardlink', 'log-symlink', 'lock-symlink', 'session-symlink', 'project-symlink', 'unsafe-mode', 'other-encoding', 'bad-version', 'future-version', 'no-current', 'no-lock'] as const) {
    await t.test(scenario, async t => {
      const { temp, store, persistence, create } = await fixture(t)
      const { id, dir, project, log, snapshot } = await create('candidate')
      const victim = join(temp, 'unrelated.txt')
      const sessionLock = join(dir, 'session.lock')
      await writeFile(victim, 'preserve fixture')
      switch (scenario) {
        case 'unknown': await writeFile(join(dir, 'note'), 'unknown'); break
        case 'subdir': await mkdir(join(dir, 'session.jsonl')); break
        case 'hardlink': await link(log, join(temp, 'hardlink')); break
        case 'log-symlink': await unlink(log); await symlink(victim, log); break
        case 'lock-symlink': await unlink(sessionLock); await symlink(victim, sessionLock); break
        case 'session-symlink': await rename(dir, join(temp, 'moved')); await symlink(join(temp, 'moved'), dir); break
        case 'project-symlink': await rename(project, join(temp, 'moved')); await symlink(join(temp, 'moved'), project); break
        case 'unsafe-mode': await chmod(dir, 0o770); break
        case 'other-encoding': await writeFile(`${log}.zstd`, 'different encoding'); break
        case 'bad-version': await writeFile(join(dir, 'session.v01.jsonl'), 'not a canonical generation'); break
        case 'future-version': await writeFile(join(dir, `session.v${SESSION_FORMAT_VERSION + 1}.jsonl`), 'unknown generation'); break
        case 'no-current': await rename(log, join(dir, 'session.jsonl')); break
        case 'no-lock': await unlink(sessionLock); break
      }
      assert.equal(await store.canDelete(snapshot), false)
      let calls = 0
      const monitored = intercept(persistence, {
        stat: (...args) => { calls++; return persistence.stat(...args) },
        open: (...args) => { calls++; return persistence.open(...args) },
      })
      await assert.rejects(store.remove(monitored, id, current, signal), unavailable)
      assert.equal(calls, 0)
      assert.equal(await readFile(victim, 'utf8'), 'preserve fixture')
      assert.ok(await lstat(dir))
    })
  }
})

test('holds the actual JSONL kernel lease across public re-stat and releases it on rejection', linux, async t => {
  const { store, persistence, connect, create } = await fixture(t)
  const { id, snapshot } = await create('leased')
  const independent = await connect()
  {
    await using holder = await independent.open(id, 'write')
    await assert.rejects(store.remove(persistence, id, current, signal), unavailable)
  }
  let observations = 0
  let blocked = false
  const changing = intercept(persistence, {
    stat: async (id, options) => {
      const snapshot = await persistence.stat(id, options)
      if (++observations !== 2 || !snapshot) return snapshot
      try {
        await using unexpected = await independent.open(id, 'write')
      } catch (error) {
        blocked = error instanceof SessionAlreadyOwnedError
      }
      return { ...snapshot, revision: SessionPersistenceRevision('different fixture revision') }
    },
  })
  await assert.rejects(store.remove(changing, id, current, signal), unavailable)
  assert.equal(observations, 2)
  assert.equal(blocked, true)
  assert.equal(await store.canDelete(snapshot), true)
  await using nextWriter = await independent.open(id, 'write')
})

test('an abort during the leased re-stat refuses cutover and drains the writer', linux, async t => {
  const { quarantine, store, persistence, create } = await fixture(t)
  const { id, snapshot } = await create('abort')
  const abort = new AbortController()
  let observations = 0
  const cancel = intercept(persistence, {
    stat: async (id, options) => {
      const row = await persistence.stat(id, options)
      if (++observations === 2) abort.abort()
      return row
    },
  })
  await assert.rejects(store.remove(cancel, id, current, abort.signal), unavailable)
  assert.equal(observations, 2)
  assert.equal(await store.canDelete(snapshot), true)
  assert.deepEqual(await readdir(quarantine), [])
  await using nextWriter = await persistence.open(id, 'write')
})

test('revocation at the final guard preserves the original path and releases the writer', linux, async t => {
  const { quarantine, store, persistence, create } = await fixture(t)
  const { id, log, snapshot } = await create('revoked')
  const original = await readFile(log)
  let finalGuard = false
  await assert.rejects(store.remove(persistence, id, () => {
    if (readdirSync(quarantine).length === 0) return
    finalGuard = true
    throw new Error('fixture authority was revoked before cutover')
  }, signal), unavailable)
  assert.equal(finalGuard, true)
  assert.deepEqual(await readFile(log), original)
  assert.deepEqual(await readdir(quarantine), [])
  assert.equal(await store.canDelete(snapshot), true)
  await using nextWriter = await persistence.open(id, 'write')
})

test('close drains a paused removal without allowing cutover or releasing its live lease early', linux, async t => {
  const { store, persistence, connect, create } = await fixture(t)
  const { id, log } = await create('close')
  const independent = await connect()
  const entered = Promise.withResolvers<void>()
  const resume = Promise.withResolvers<void>()
  let observations = 0
  const paused = intercept(persistence, {
    stat: async (id, options) => {
      const row = await persistence.stat(id, options)
      if (++observations === 2) {
        entered.resolve()
        await resume.promise
      }
      return row
    },
  })
  const rejected = assert.rejects(store.remove(paused, id, current, signal), unavailable)
  await Promise.race([entered.promise, rejected.then(() => assert.fail('removal ended before the leased re-stat'))])
  let closed = false
  const closing = store.close().then(() => { closed = true })
  try {
    await assert.rejects(async () => {
      await using unexpected = await independent.open(id, 'write')
    }, SessionAlreadyOwnedError)
    assert.equal(closed, false)
  } finally { resume.resolve() }
  await rejected
  await closing
  assert.ok(await lstat(log))
  await using nextWriter = await independent.open(id, 'write')
})

test('post-cutover failure reports unknown, blocks reuse and preserves its quarantine marker', linux, async t => {
  const { root, store, quarantine, persistence, create } = await fixture(t)
  const target = await create('cutover')
  const sibling = await create('still-present')
  const failingRelease = intercept(persistence, {
    open: async (...args) => {
      const real = await persistence.open(...args)
      return new Proxy(real, {
        get(target, property) {
          if (property === Symbol.asyncDispose) return async () => {
            await real.close()
            throw new Error('injected release acknowledgement failure')
          }
          return Reflect.get(target, property, target)
        },
      })
    },
  })
  await assert.rejects(store.remove(failingRelease, target.id, current, signal), unknown)
  assert.equal(await persistence.stat(target.id), undefined)
  assert.ok(await persistence.stat(sibling.id))
  assert.equal((await readdir(quarantine)).length, 1)
  assert.equal(await store.canDelete(sibling.snapshot), false)
  await assert.rejects(store.remove(persistence, sibling.id, current, signal), unknown)
  await store.close()
  await assert.rejects(SessionDeletionStore.open(root), error => error instanceof SessionDeletionStorageError && error.code === 'outcome-unknown')
  assert.equal((await readdir(quarantine)).length, 1)
})

test('an orphaned payload cannot be discovered by the provider and is never reclaimed on open', linux, async t => {
  const { quarantine, root, store, persistence, create } = await fixture(t)
  const target = await create('payload')
  const sibling = await create('other')
  await store.close()
  const slot = join(quarantine, '!transaction-orphan-fixture')
  await mkdir(slot, { mode: 0o700 })
  const payload = join(slot, 'payload')
  const originalBytes = await readFile(target.log)
  await rename(target.dir, payload)
  assert.equal(await persistence.stat(target.id), undefined)
  assert.deepEqual((await persistence.list()).map(row => row.header.id), [sibling.id])
  await assert.rejects(SessionDeletionStore.open(root), unknown)
  assert.deepEqual(await readFile(join(payload, basename(target.log))), originalBytes)
})
