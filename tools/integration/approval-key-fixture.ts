import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { generateKeyPairSync, randomUUID } from 'node:crypto'
import { lstat, readFile, realpath, rename, writeFile } from 'node:fs/promises'
import { basename, dirname, isAbsolute, join } from 'node:path'
import { parsePinnedApprovalAuthorityDescription, type ApprovalAuthorityDescription } from '../../host/remote-control/src/approval-authority.js'

/** Owns only private, disposable fixture files. No live installation/configuration
 * or external key path can be selected by the renewal methods. */
export async function approvalKeyFixtures(root: string, binary: string, policyFile: string, issuer: string) {
  const info = await lstat(root)
  assert.ok(isAbsolute(root) && await realpath(root) === root && info.isDirectory() && info.uid === process.getuid?.() &&
    (info.mode & 0o777) === 0o700 && /^clients-[A-Za-z0-9_-]+$/.test(basename(root)))
  assert.equal(dirname(policyFile), root)
  assert.ok(isAbsolute(binary) && dirname(binary) === dirname(root) && basename(binary) === 'remote')
  const original = await readFile(policyFile), policy = JSON.parse(original.toString('utf8'))
  assert.ok(isAbsolute(policy.signing.private_key_file) && dirname(policy.signing.private_key_file) === root)
  const env = { PATH: process.env.PATH ?? '', REMOTE_DATABASE_URL: 'INVALID_FIXTURE_DATABASE_NOT_USED',
    REMOTE_ISSUER: issuer, REMOTE_CLIENT_IDS: 'fixture-host,fixture-phone' }
  const describe = async (path: string): Promise<ApprovalAuthorityDescription> => {
    const raw = execFileSync(binary, ['approval-authority', 'describe', '--policy-file', path], { env, encoding: 'utf8' })
    // Pins are provisioned here by the fixture operator, not by a Relay/JWS.
    const pin = JSON.parse(raw).sha256 as string
    return await parsePinnedApprovalAuthorityDescription(raw, pin)
  }
  const old = await describe(policyFile), keys = generateKeyPairSync('ec', { namedCurve: 'prime256v1' })
  const nextKey = join(root, 'approval-authority-next.pem'), nextPolicy = join(root, 'approval-policy-next.json')
  await writeFile(nextKey, keys.privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600, flag: 'wx' })
  const candidate = { ...policy, signing: { ...policy.signing, key_id: `${old.authority.key_id}-next`, private_key_file: nextKey } }
  const candidateBytes = Buffer.from(JSON.stringify(candidate))
  await writeFile(nextPolicy, candidateBytes, { mode: 0o600, flag: 'wx' })
  const next = await describe(nextPolicy)
  assert.notEqual(old.authority.key_jkt, next.authority.key_jkt); assert.notEqual(old.authority.key_id, next.authority.key_id)
  assert.deepEqual(old.policy, next.policy)
  const paths = { trust: join(root, 'approval-fixture-trust.json'), reload: join(root, 'approval-fixture-reload.json'),
    verified: join(root, 'approval-fixture-verified.json'), challenge: join(root, 'approval-fixture-lease.json'),
    forged: join(root, 'approval-fixture-old-proof.json') }
  const atomic = async (path: string, bytes: Uint8Array) => {
    assert.equal(dirname(path), root)
    const stage = join(root, `approval-stage-${randomUUID()}`)
    await writeFile(stage, bytes, { mode: 0o600, flag: 'wx' }); await rename(stage, path)
  }
  let activated = false, retired = false
  await atomic(paths.trust, Buffer.from(JSON.stringify({ revision: 1, documents: [old, next], pins: [old.sha256, next.sha256], forge_old: false })))
  return { old, next, paths,
    async activateNext() {
      assert.equal(activated, false); assert.deepEqual(await readFile(policyFile), original)
      await atomic(policyFile, candidateBytes); activated = true
      assert.equal((await describe(policyFile)).sha256, next.sha256)
    },
    async retireOld(nativeId: string) {
      assert.ok(activated && !retired && typeof nativeId === 'string' && nativeId.length > 0 && nativeId.length < 2048)
      await atomic(paths.trust, Buffer.from(JSON.stringify({ revision: 2, documents: [next], pins: [next.sha256], native_id: nativeId, forge_old: true })))
      retired = true
    },
  }
}
