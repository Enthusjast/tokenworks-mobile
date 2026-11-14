import type { Context } from '@deepseek-ai/cordis'
import type { IncomingMessage, ServerResponse } from 'node:http'
import { parseSigningObject } from './canonical.js'
import type { RemoteTargetConfig } from './remote-targets.js'
import type { LanPairingDescriptor } from './lan-pairing.js'
import type { RemoteToolsRuntime } from './remote-tools.js'
import { pinnedRequest } from './remote-network.js'
import { RemoteToolError } from './remote-wait.js'

interface OperatorContext extends Context {
  webServer: { register(route: { kind: 'exact'; path: string; handler(req: IncomingMessage, res: ServerResponse): void }): () => void }
}

/** Every configuration secret and capability change stays in authenticated same-origin owner settings. */
export function registerRemoteToolsUI(ctx: Context, runtime: RemoteToolsRuntime): void {
  ctx.inject(['webServer', 'connection'], scope => {
    const web = scope as OperatorContext
    const paths = ['status', 'configure', 'enabled', 'pair', 'reconcile-pair', 'revoke', 'rotate', 'export']
    for (const action of paths) scope.effect(() => web.webServer.register({ kind: 'exact', path: `/tokenworks-mobile/remote-tools/${action}`, handler(req, res) {
      res.setHeader('Cache-Control', 'no-store'); res.setHeader('X-Content-Type-Options', 'nosniff')
      const admission = web.connection.admit(req)
      if ('rejection' in admission) { res.writeHead(admission.rejection).end(); return }
      if (admission.peer.signal.aborted || admission.peer.principal.kind !== 'owner') { res.writeHead(403).end(); return }
      const signal = AbortSignal.any([admission.peer.signal, AbortSignal.timeout(action === 'export' ? 120_000 : 30_000)])
      const abort = () => { if (!res.writableEnded) res.destroy() }
      signal.addEventListener('abort', abort, { once: true }); res.once('close', () => signal.removeEventListener('abort', abort))
      if (action === 'status') {
        if (req.method !== 'GET') { res.setHeader('Allow', 'GET'); res.writeHead(405).end(); return }
        void runtime.status().then(value => { if (!signal.aborted) res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' }).end(JSON.stringify(value)) }, () => { if (!res.headersSent) res.writeHead(503).end() }); return
      }
      if (req.method !== 'POST') { res.setHeader('Allow', 'POST'); res.writeHead(405).end(); return }
      let sameOrigin = false
      try { const origin = new URL(String(req.headers.origin)); sameOrigin = origin.host === req.headers.host && (origin.protocol === 'https:' || origin.protocol === 'http:') && origin.origin === req.headers.origin && req.headers['sec-fetch-site'] !== 'cross-site' } catch { /* Invalid Origin never grants operator authority. */ }
      if (!sameOrigin || req.headers['content-type'] !== 'application/json') { res.writeHead(403).end(); return }
      void (async () => {
        try {
          const chunks: Buffer[] = []; let bytes = 0
          for await (const chunk of req) { signal.throwIfAborted(); bytes += chunk.length; if (bytes > 96 * 1024) { res.writeHead(413).end(); return }; chunks.push(Buffer.from(chunk)) }
          const body = parseSigningObject(Buffer.concat(chunks))
          signal.throwIfAborted()
          let value: unknown
          if (action === 'configure') {
            if (Object.keys(body).sort().join(',') !== 'config,confirm' || body.confirm !== 'direct-only-full-native-owner') throw new RemoteToolError('remote/operator-confirmation-required')
            value = await runtime.targets.configure(body.config as RemoteTargetConfig)
          } else if (action === 'enabled') {
            if (Object.keys(body).join(',') !== 'enabled' || typeof body.enabled !== 'boolean') throw new RemoteToolError('remote/invalid-arguments')
            await runtime.targets.setEnabled(body.enabled); value = await runtime.targets.status()
          } else if (action === 'pair') {
            if (Object.keys(body).sort().join(',') !== 'confirm,descriptor,targetId' || typeof body.targetId !== 'string' || body.confirm !== 'direct-only-full-native-owner') throw new RemoteToolError('remote/operator-confirmation-required')
            value = await runtime.targets.pair(body.targetId, body.descriptor as LanPairingDescriptor, pinnedRequest, signal)
          } else if (action === 'reconcile-pair') {
            if (Object.keys(body).join(',') !== 'targetId' || typeof body.targetId !== 'string') throw new RemoteToolError('remote/invalid-arguments')
            value = await runtime.targets.reconcilePair(body.targetId, pinnedRequest, signal)
          } else if (action === 'revoke' || action === 'rotate') {
            if (Object.keys(body).sort().join(',') !== 'confirm,targetId' || typeof body.targetId !== 'string' || body.confirm !== 'local-fence-target-revocation-required') throw new RemoteToolError('remote/operator-confirmation-required')
            value = action === 'revoke' ? await runtime.targets.revoke(body.targetId) : await runtime.targets.rotate(body.targetId)
          } else {
            if (Object.keys(body).sort().join(',') !== 'confirm,operationId' || typeof body.operationId !== 'string' || body.confirm !== 'export-approved-private-file') throw new RemoteToolError('remote/operator-confirmation-required')
            const operation = await runtime.ledger.get(body.operationId)
            if (operation.kind !== 'files_download' || operation.state !== 'accepted') throw new RemoteToolError('remote/export-not-approved')
            const target = await runtime.targets.target(operation.targetId); await runtime.targets.assertCurrent(target)
            if (target.ownerSha256 !== operation.ownerSha256 || target.authorizationEpoch !== operation.targetEpoch) throw new RemoteToolError('remote/operation-binding')
            const input = JSON.parse(Buffer.from(await runtime.ledger.body(operation.operationId)).toString('utf8')) as { fileHandle: string; sessionId: string; path: string; bytes: number; sha256: string }
            const metadata = await runtime.spool.metadata(input.fileHandle, target.ownerSha256, signal)
            if (metadata.sourceTargetId !== target.id || metadata.sessionId !== input.sessionId || metadata.path !== input.path || metadata.bytes !== input.bytes || metadata.sha256 !== input.sha256) throw new RemoteToolError('remote/file-binding')
            const payload = await runtime.spool.read(metadata, signal)
            try {
              signal.throwIfAborted()
              res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Length': payload.byteLength, 'Content-Disposition': `attachment; filename="remote-file"; filename*=UTF-8''${encodeURIComponent(metadata.name)}` })
              const completed = Promise.withResolvers<void>()
              res.once('error', completed.reject); res.end(payload, completed.resolve)
              await completed.promise
            } finally { payload.fill(0) }
            return
          }
          signal.throwIfAborted()
          res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' }).end(JSON.stringify(value))
        } catch (error) {
          if (res.headersSent) { res.destroy(); return }
          const code = error instanceof Error && /^remote\/[A-Za-z0-9_/-]+$/.test(error.message) ? error.message : 'remote/operator-operation-failed'
          res.writeHead(409, { 'Content-Type': 'application/json' }).end(JSON.stringify({ code, automaticRetry: false }))
        }
      })()
    } }))
  })
}
