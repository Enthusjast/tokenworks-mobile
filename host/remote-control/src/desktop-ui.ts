import type { Context } from '@deepseek-ai/cordis'
import type { IncomingMessage, ServerResponse } from 'node:http'

export interface DesktopStatus {
  runtimeId: string
  relay: { origin: string; phase: string } | null
  /** F-03/N-8: experimental LAN direct connection; null when not enabled. */
  lan?: { host: string; port: number; phase: string } | null
  development: boolean
}

// Optional Web services: headless and SDK hosts do not need the desktop UI.
interface WebContext extends Context {
  webServer: {
    register(route: { kind: 'exact'; path: string; handler(req: IncomingMessage, res: ServerResponse): void }): () => void
  }
}

/** Owner-only metadata, using DSH's existing browser authentication and origin checks. */
export function registerDesktopUI(ctx: Context, status: () => DesktopStatus): void {
  ctx.inject(['webServer', 'connection'], scope => {
    const web = scope as WebContext
    scope.effect(() => web.webServer.register({ kind: 'exact', path: '/tokenworks-mobile/status', handler(req, res) {
      res.setHeader('Cache-Control', 'no-store')
      res.setHeader('X-Content-Type-Options', 'nosniff')
      const admission = web.connection.admit(req)
      if ('rejection' in admission) { res.writeHead(admission.rejection); res.end(); return }
      if (admission.peer.signal.aborted || admission.peer.principal.kind !== 'owner') { res.writeHead(403); res.end(); return }
      if (req.method !== 'GET') { res.setHeader('Allow', 'GET'); res.writeHead(405); res.end(); return }
      // Never serialize config, provider objects, raw errors, tokens or paths.
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' })
      const roots = scope.get('hmr')?.config?.root
      res.end(JSON.stringify({ ...status(), hotReload: Array.isArray(roots) && roots.length > 0 }))
    } }), 'remote-control.desktop-status')
  })
}
