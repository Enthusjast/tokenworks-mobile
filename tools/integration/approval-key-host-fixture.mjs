/** Test-only normal-plugin scope reload and verified-kid observer. Service private
 * keys are never loaded into this Host process. All paths belong to its fixture. */
import { readFile, writeFile } from 'node:fs/promises'
import { ApprovalLeaseCache } from '../../host/remote-control/lib/approval-lease.js'
import { parsePinnedApprovalAuthorityDescription } from '../../host/remote-control/lib/approval-authority.js'

export async function installApprovalKeyFixture(ctx, config, factory, paths) {
  let scope, revision = 0, stopped = false, busy = false, input, injected = false, oldRejected = false
  const original = ApprovalLeaseCache.prototype.admit
  ApprovalLeaseCache.prototype.admit = async function (context, decision, lease) {
    if (input?.forge_old && !injected) {
      injected = true
      await writeFile(paths.challenge, JSON.stringify({ approval_id: context.approval_id, lease }), { mode: 0o600 })
      const deadline = Date.now() + 1200
      let replacement
      while (Date.now() < deadline) {
        try { replacement = JSON.parse(await readFile(paths.forged, 'utf8')); break }
        catch (error) { if (error.code !== 'ENOENT') throw new Error('approval fixture proof unavailable') }
        await new Promise(resolve => setTimeout(resolve, 10))
      }
      if (replacement?.approval_id !== context.approval_id || typeof replacement.lease !== 'string') throw new Error('approval fixture proof timed out')
      try { await original.call(this, context, decision, replacement.lease) } catch { oldRejected = true }
      if (!oldRejected) throw new Error('retired fixture authority was accepted')
    }
    const result = await original.call(this, context, decision, lease)
    const header = JSON.parse(Buffer.from(lease.split('.')[0], 'base64url').toString('utf8'))
    await writeFile(paths.verified, JSON.stringify({ approval_id: context.approval_id, key_id: header.kid, old_key_rejected: oldRejected }), { mode: 0o600 })
    return result
  }
  const apply = async (value) => {
    const descriptions = await Promise.all(value.documents.map((document, index) => parsePinnedApprovalAuthorityDescription(JSON.stringify(document), value.pins[index])))
    const before = value.native_id ? ctx.agents.get(value.native_id) : undefined
    if (scope) await scope.dispose()
    if (stopped) return
    scope = ctx.plugin(factory(descriptions.map(document => document.authority)), config)
    await scope
    input = value; revision = value.revision
    await writeFile(paths.reload, JSON.stringify({ revision, keys: descriptions.map(document => document.authority.key_id),
      same_agent: value.native_id ? !!before && ctx.agents.get(value.native_id) === before : true }), { mode: 0o600 })
  }
  await apply(JSON.parse(await readFile(paths.trust, 'utf8')))
  const timer = setInterval(async () => {
    if (busy || stopped) return
    busy = true
    try {
      const value = JSON.parse(await readFile(paths.trust, 'utf8'))
      if (value.revision > revision) await apply(value)
    } catch { await writeFile(paths.reload, JSON.stringify({ error: 'approval fixture reload failed' }), { mode: 0o600 }) }
    finally { busy = false }
  }, 25)
  timer.unref()
  ctx.effect(() => async () => { stopped = true; clearInterval(timer); if (scope) await scope.dispose(); ApprovalLeaseCache.prototype.admit = original })
}
