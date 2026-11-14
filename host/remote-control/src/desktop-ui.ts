import type { Context } from '@deepseek-ai/cordis'
import type { IncomingMessage, ServerResponse } from 'node:http'
import QRCode from 'qrcode'
import { parseSigningObject } from './canonical.js'
import type { LanPairing } from './lan-pairing.js'

export interface DesktopStatus {
  runtimeId: string
  relay: { origin: string; phase: string } | null
  lan?: { host: string; port: number; phase: string; origin?: string; tlsPin?: string } | null
  development: boolean
}
interface WebContext extends Context {
  webServer: { register(route: { kind: 'exact'; path: string; handler(req: IncomingMessage, res: ServerResponse): void }): () => void }
}

/** Native owner admission and same-origin protection guard every operator mutation. */
export function registerDesktopUI(ctx: Context, status: () => DesktopStatus, pairing?: LanPairing): void {
  ctx.inject(['webServer', 'connection'], scope => {
    const web = scope as WebContext
    const routes = pairing ? ['/tokenworks-mobile/status', '/tokenworks-mobile/pairing', '/tokenworks-mobile/pairing/revoke', '/tokenworks-mobile/pairing/rotate-host'] : ['/tokenworks-mobile/status']
    for (const path of routes) scope.effect(() => web.webServer.register({ kind: 'exact', path, handler(req, res) {
      res.setHeader('Cache-Control', 'no-store'); res.setHeader('X-Content-Type-Options', 'nosniff')
      const admission = web.connection.admit(req)
      if ('rejection' in admission) { res.writeHead(admission.rejection).end(); return }
      if (admission.peer.signal.aborted || admission.peer.principal.kind !== 'owner') { res.writeHead(403).end(); return }
      if (path === '/tokenworks-mobile/status') {
        if (req.method !== 'GET') { res.setHeader('Allow', 'GET'); res.writeHead(405).end(); return }
        const roots = scope.get('hmr')?.config?.root
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' }).end(JSON.stringify({ ...status(), pairing: pairing ? { ...pairing.identity, ...pairing.status() } : null, hotReload: Array.isArray(roots) && roots.length > 0 })); return
      }
      if (req.method === 'GET' && path === '/tokenworks-mobile/pairing') {
        res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ devices: pairing!.list(), ...pairing!.status() })); return
      }
      if (req.method !== 'POST') { res.setHeader('Allow', path === '/tokenworks-mobile/pairing' ? 'GET, POST' : 'POST'); res.writeHead(405).end(); return }
      let sameOrigin = false
      try { sameOrigin = typeof req.headers.origin === 'string' && new URL(req.headers.origin).host === req.headers.host && req.headers['sec-fetch-site'] !== 'cross-site' } catch { /* Malformed Origin is refused. */ }
      if (!sameOrigin || req.headers['content-type'] !== 'application/json') { res.writeHead(403).end(); return }
      void (async () => {
        try {
          const chunks: Buffer[] = []; let bytes = 0
          const deadline = setTimeout(() => req.destroy(), 10_000); deadline.unref()
          try { for await (const chunk of req) { bytes += chunk.length; if (bytes > 512) { res.writeHead(413).end(); return }; chunks.push(Buffer.from(chunk)) } } finally { clearTimeout(deadline) }
          const body = parseSigningObject(Buffer.concat(chunks))
          if (path === '/tokenworks-mobile/pairing') {
            if (Object.keys(body).length || status().lan?.phase !== 'listening') { res.writeHead(409).end(); return }
            const descriptor = pairing!.begin(), qrSvg = await QRCode.toString(JSON.stringify(descriptor), { type: 'svg', errorCorrectionLevel: 'M', margin: 4 })
            res.writeHead(201, { 'Content-Type': 'application/json' }).end(JSON.stringify({ descriptor, qrSvg })); return
          }
          if (path.endsWith('/rotate-host')) {
            if (Object.keys(body).sort().join(',') !== 'confirm,operationId' || body.confirm !== 'rotate-and-revoke-all' || typeof body.operationId !== 'string') { res.writeHead(400).end(); return }
            const result = await pairing!.rotateHost(body.operationId)
            res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ ...result, revokedAllPairs: true, requiresOperatorRepair: true })); return
          }
          if (Object.keys(body).join(',') !== 'deviceId' || typeof body.deviceId !== 'string' || !/^[0-9a-f-]{36}$/i.test(body.deviceId)) { res.writeHead(400).end(); return }
          await pairing!.revoke(body.deviceId); res.writeHead(204).end()
        } catch { if (!res.headersSent) res.writeHead(503).end(); else res.destroy() }
      })()
    } }), `remote-control.desktop-${path.split('/').pop()}`)
  })
}
