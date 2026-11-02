/** Runs only inside the test-owned HOME, session bus and keyring. */
import assert from 'node:assert/strict'
import { createPublicKey, verify } from 'node:crypto'
import { lstat, readFile, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { pathToFileURL } from 'node:url'

const config = JSON.parse(await readFile(process.argv[2], 'utf8')), phase = process.argv[3]
const { LinuxInstallationKeyStore, LinuxSecretServiceJournalProtector, LinuxSecretServiceInstallationSigner } = await import(pathToFileURL(config.plugin).href)
const { PersistentCommandJournal } = await import(pathToFileURL(join(dirname(config.plugin), 'command-journal.js')).href)
const { parseCommand } = await import(pathToFileURL(join(dirname(config.plugin), 'protocol.js')).href)
const owner = new AbortController(), signal = AbortSignal.timeout(20_000)
const reference = join(config.root, 'identity/reference.json'), journalPath = join(config.root, 'journal/commands.sqlite')
const factsPath = join(config.root, 'facts.json'), body = 'PRIVATE_NATIVE_SECRET_SERVICE_COMMAND'
const store = new LinuxInstallationKeyStore({ path: reference, executable: config.executable,
  service: 'dsh-isolated-installation', ownerSignal: owner.signal })
const protector = new LinuxSecretServiceJournalProtector({ executable: config.executable, service: 'dsh-isolated-journal', account: 'fixture' })
const actor = { userId: 'fixture-user', deviceId: 'fixture-device' }
const command = parseCommand({ v: 0, type: 'message.submit', request_id: config.requestId, runtime_epoch: config.epoch,
  session_id: '55555555-5555-4555-8555-555555555555', payload: { mode: 'queue', text: body } })
let identity, signer, journal
try {
  if (phase === 'create') {
    assert.equal(await store.open(signal), undefined)
    identity = await store.create(signal)
    journal = await PersistentCommandJournal.open(journalPath, config.runtimeId, { protector })
    journal.admit(command, actor)
    journal.close(); journal = undefined
    assert.equal((await lstat(reference)).mode & 0o777, 0o600)
    assert.equal((await readFile(journalPath)).includes(Buffer.from(body)), false)
    await assert.rejects(lstat(journalPath + '.key'), error => error.code === 'ENOENT')
    assert.equal((await lstat(journalPath + '.wrapped-key')).mode & 0o777, 0o600)
    await writeFile(factsPath, JSON.stringify({ publicJwk: identity.publicJwk, thumbprint: identity.thumbprint }), { mode: 0o600, flag: 'wx' })
  } else if (phase === 'locked') {
    const original = await readFile(reference), envelope = await readFile(journalPath + '.wrapped-key')
    const facts = JSON.parse(await readFile(factsPath, 'utf8')), attributes = JSON.parse(original)
    signer = new LinuxSecretServiceInstallationSigner({ executable: config.executable, service: attributes.service,
      account: attributes.account, publicJwk: facts.publicJwk, ownerSignal: owner.signal })
    await assert.rejects(signer.sign(Buffer.from('locked-keyring-proof')))
    await assert.rejects(store.open(signal))
    await assert.rejects(PersistentCommandJournal.open(journalPath, config.runtimeId, { protector }))
    assert.deepEqual(await readFile(reference), original)
    assert.deepEqual(await readFile(journalPath + '.wrapped-key'), envelope)
    await assert.rejects(lstat(journalPath + '.key'), error => error.code === 'ENOENT')
  } else {
    assert.ok(['reopened', 'restarted'].includes(phase))
    const facts = JSON.parse(await readFile(factsPath, 'utf8'))
    identity = await store.open(signal)
    assert.ok(identity)
    assert.deepEqual(identity.publicJwk, facts.publicJwk)
    assert.equal(identity.thumbprint, facts.thumbprint)
    journal = await PersistentCommandJournal.open(journalPath, config.runtimeId, { protector })
    assert.deepEqual(journal.get(command.request_id, actor)?.command, command)
  }
  if (identity) {
    const input = Buffer.from('native-secret-service-installation-proof')
    assert.equal(verify('sha256', input, { key: createPublicKey({ key: identity.publicJwk, format: 'jwk' }),
      dsaEncoding: 'ieee-p1363' }, await identity.signer.sign(input)), true)
    owner.abort()
    await assert.rejects(identity.signer.sign(input))
  }
  process.stdout.write(phase + '-ok\n')
} finally { journal?.close(); identity?.close(); signer?.close(); store.close(); owner.abort() }
