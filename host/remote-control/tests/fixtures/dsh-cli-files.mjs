/** Test-only adapter probe inside the installed CLI; no model/provider network. */
import assert from 'node:assert/strict'
import { createHash, randomUUID } from 'node:crypto'
import { chmod } from 'node:fs/promises'
import { createServer } from 'node:http'
import { pathToFileURL } from 'node:url'

export const name = 'dsh-cli-files-fixture'
export const inject = ['sessionController', 'sessions', 'agents', 'workspaceRegistry', 'fs', 'fileUploads', 'attachments', 'loader']

export async function apply(ctx, config) {
  const { DshClient } = await import(pathToFileURL(config.clientEntry).href)
  const client = new DshClient(ctx, config.runtimeId, [config.workspace], ['standard'])
  const hash = bytes => createHash('sha256').update(bytes).digest('hex')
  const collect = async source => {
    const chunks = []
    for await (const chunk of source) chunks.push(Buffer.from(chunk))
    return Buffer.concat(chunks)
  }
  let busy = false
  const server = createServer(async (request, response) => {
    if (request.method !== 'POST' || request.url !== '/files' || busy) { response.writeHead(404).end(); return }
    busy = true
    let stage = 'input'
    try {
      let raw = ''
      for await (const chunk of request) {
        raw += chunk
        assert.ok(Buffer.byteLength(raw) <= 256)
      }
      const { session_id: session } = JSON.parse(raw)
      assert.match(session, /^[0-9a-f-]{36}$/)
      const signal = AbortSignal.timeout(15_000), current = () => signal.throwIfAborted()
      stage = 'resolve'
      const native = await client.resolve(session, signal)
      const bytes = Buffer.from('installed CLI attachment\0\xff'), manifest = { name: 'attached.bin', size_bytes: bytes.length, sha256: hash(bytes) }
      stage = 'upload'
      const receipt = await client.uploadFile(session, manifest, (async function* () { yield bytes })(), signal)
      assert.equal(client.fileStaged(native, receipt.receiptId), true)
      stage = 'prompt'
      await client.prompt(native, randomUUID(), 'queue', [{ type: 'file', receiptId: receipt.receiptId }], signal)
      await ctx.agents.get(native).whenIdle()
      assert.equal((await client.checkpoint(native)).state, 'persisted')
      const messages = ctx.agents.get(native).session.snapshotEvents().filter(event => event.type === 'user/message' && event.data.source.kind === 'user')
      assert.equal(messages.length, 1)
      const part = messages[0].data.content[0]
      assert.equal(part.type, 'file')
      assert.deepEqual(await collect(ctx.attachments.readFileStream(part.attachment, signal)), bytes)
      stage = 'integrity'
      await assert.rejects(client.uploadFile(session, { ...manifest, sha256: '0'.repeat(64) }, (async function* () { yield bytes })(), signal),
        error => error.code === 'file.integrity_mismatch')
      stage = 'attachment-export'
      const attachments = await client.attachmentExportPage(session, signal, current)
      assert.equal(attachments.items.length, 1)
      const item = attachments.items[0]
      const attachment = await client.attachmentExportSource(session, item.seq, item.index, signal, current)
      try {
        assert.deepEqual(await attachment.prepare(signal), manifest)
        assert.deepEqual(await collect(attachment.read(signal)), bytes)
      } finally { await attachment.close() }
      stage = 'workspace-export'
      const page = await client.workspaceExportPage(session, 'reports', [], signal, current)
      try { assert.ok(page.items.some(item => item.name === 'report.bin')) }
      finally { await page.reader.close() }
      const artifact = await client.workspaceExportSource(session, 'reports', ['report.bin'], signal, current)
      try {
        const artifactBytes = Buffer.from('installed CLI workspace artifact\0\xff')
        assert.equal((await artifact.prepare(signal)).sha256, hash(artifactBytes))
        assert.deepEqual(await collect(artifact.read(signal)), artifactBytes)
      } finally { await artifact.close() }
      stage = 'symlink-policy'
      await assert.rejects(client.workspaceExportSource(session, 'reports', ['outside-link'], signal, current))
      stage = 'workspace-changes'
      const changes = await client.workspaceChangesContext(session, signal)
      assert.equal(changes.root, config.workspace.cwd)
      changes.guard()
      response.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({
        uploaded: true, persisted: true, integrity_rejected: true, attachment_exported: true,
        workspace_exported: true, symlink_rejected: true, changes_supported: true,
      }))
    } catch (error) {
      // CLI stdout/stderr can contain auth tokens and native paths. Return only
      // the operation and our fixed public error code for parent diagnostics.
      response.writeHead(500, { 'content-type': 'application/json' }).end(JSON.stringify({ stage,
        code: typeof error?.code === 'string' && /^[a-z._]+$/.test(error.code) ? error.code : 'fixture.failed' }))
    }
  })
  ctx.effect(() => () => { server.closeAllConnections(); server.close() })
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(config.socketPath, resolve) })
  await chmod(config.socketPath, 0o600)
}
