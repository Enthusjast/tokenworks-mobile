import { constants } from 'node:fs'
import { open } from 'node:fs/promises'
import { createHmac } from 'node:crypto'
import { z } from 'zod'
import type { RTCPeerConnectionConfig } from 'werift'

// UDP is the only TURN transport enabled by this adapter. Additional transports
// require independent interoperability and certificate-validation evidence.
const validIcePort = (url: string): boolean => {
  const port = /:(\d{1,5})(?=\?|$)/.exec(url)?.[1]
  return port === undefined || (Number(port) >= 1 && Number(port) <= 65535)
}
const stunUrl = z.string().max(512).regex(/^stun:(?:\[[0-9a-fA-F:]+\]|[a-zA-Z0-9.-]+)(?::[0-9]{1,5})?$/).refine(validIcePort)
const turnUrl = z.string().max(512).regex(/^turn:(?:\[[0-9a-fA-F:]+\]|[a-zA-Z0-9.-]+)(?::[0-9]{1,5})?\?transport=udp$/).refine(validIcePort)
export const rtcConfigSchema = z.strictObject({
  enabled: z.boolean().default(false),
  stunUrls: z.array(stunUrl).max(4).default([]),
  turn: z.strictObject({ urls: z.array(turnUrl).min(1).max(4), sharedSecret: z.string().min(32).max(256), credentialTtlSeconds: z.number().int().min(60).max(3600).default(300) }).optional(),
})
export type RtcConfig = z.infer<typeof rtcConfigSchema>
export type RtcRouteKind = 'p2p' | 'turn'
export interface RtcIceCredentials { urls: string[]; username: string; credential: string }
export function rtcCredentials(config: RtcConfig, pairId: string, now = Date.now()): { turnServers: RtcIceCredentials[]; expiresAt: number } {
  if (!config.enabled || !config.turn) return { turnServers: [], expiresAt: now + 20_000 }
  const expiry = Math.floor(now / 1000) + config.turn.credentialTtlSeconds
  const username = `${expiry}:${pairId}`
  return { turnServers: [{ urls: [...config.turn.urls], username, credential: createHmac('sha1', config.turn.sharedSecret).update(username).digest('base64') }], expiresAt: expiry * 1000 }
}
export function rtcPeerConfig(config: RtcConfig, routeKind: RtcRouteKind, pairId: string): RTCPeerConnectionConfig {
  if (!config.enabled) throw new Error('rtc/unsupported')
  if (routeKind === 'turn' && !config.turn) throw new Error('rtc/turn-unavailable')
  return { iceServers: routeKind === 'turn' ? rtcCredentials(config, pairId).turnServers : config.stunUrls.map(urls => ({ urls })),
    iceUseDefaultStunServer: false, iceTransportPolicy: routeKind === 'turn' ? 'relay' : 'all', iceUseTcp: false,
    maxMessageSize: 16384, iceStunGatherTimeout: 5, iceTurnConnectTimeout: 5 }
}
/** Secrets enter through an operator-owned private regular file, never public config/status. */
export async function loadRtcConfig(path?: string): Promise<RtcConfig> {
  if (!path) return rtcConfigSchema.parse({})
  const file = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW)
  try {
    const stat = await file.stat()
    if (!stat.isFile() || stat.size > 8192 || (stat.mode & 0o077) !== 0 || (process.getuid && stat.uid !== process.getuid())) throw new Error('rtc/private-config-required')
    const bytes = Buffer.alloc(8193)
    try {
      const { bytesRead } = await file.read(bytes, 0, bytes.length, 0)
      if (bytesRead > 8192) throw new Error('rtc/config-budget')
      const result = rtcConfigSchema.safeParse(JSON.parse(bytes.subarray(0, bytesRead).toString('utf8')))
      if (!result.success) throw new Error('rtc/invalid-config')
      return result.data
    } finally { bytes.fill(0) }
  } catch { throw new Error('rtc/invalid-private-config') }
  finally { await file.close() }
}
