import type { HostConnectionClock } from './connection-lease.js'
import { ProbeError } from './protocol.js'

export const fileHandoffClock: HostConnectionClock = Object.freeze({ wall: () => Date.now(), monotonic: () => performance.now() })
const margin = 100
const leaseLimit = 5_000
const hardLimit = 300_000

/** A lease may be shortened or renewed while live; no later frame can revive
 * an observed expiry or clock reversal. The original hard deadline never moves. */
export class FileHandoffLease {
  readonly expires: number
  private until: number
  private untilMono: number
  private readonly expiresMono: number
  private lastWall: number
  private lastMono: number
  private ended = false

  constructor(leaseUntil: string, expiresAt: string, private readonly clock: HostConnectionClock = fileHandoffClock) {
    this.lastWall = clock.wall(); this.lastMono = clock.monotonic()
    this.until = Date.parse(leaseUntil); this.expires = Date.parse(expiresAt)
    if (![this.lastWall, this.lastMono, this.until, this.expires].every(Number.isFinite) || this.lastWall < 0 || this.lastMono < 0 ||
        this.until > this.expires || this.until - this.lastWall > leaseLimit + 500 || this.expires - this.lastWall > hardLimit + 30_000) throw new ProbeError('file.stale')
    this.untilMono = this.lastMono + Math.min(leaseLimit, this.until - this.lastWall)
    this.expiresMono = this.lastMono + Math.min(hardLimit, this.expires - this.lastWall)
  }
  private live(wall: number, mono: number): boolean {
    if (this.ended) return false
    if (!Number.isFinite(wall) || !Number.isFinite(mono) || wall < this.lastWall || mono < this.lastMono ||
        wall >= this.until - margin || wall >= this.expires - margin || mono >= this.untilMono - margin || mono >= this.expiresMono - margin) {
      this.ended = true
      return false
    }
    this.lastWall = wall; this.lastMono = mono
    return true
  }
  current(): boolean { return this.live(this.clock.wall(), this.clock.monotonic()) }
  renew(leaseUntil: string, expiresAt: string): boolean {
    const wall = this.clock.wall(), mono = this.clock.monotonic()
    if (!this.live(wall, mono)) return false
    const until = Date.parse(leaseUntil)
    if (Date.parse(expiresAt) !== this.expires || !Number.isFinite(until) || until > this.expires || until - wall > leaseLimit + 500) throw new ProbeError('protocol.invalid')
    this.until = until
    this.untilMono = Math.min(this.expiresMono, mono + Math.min(leaseLimit, until - wall))
    return this.live(wall, mono)
  }
}
