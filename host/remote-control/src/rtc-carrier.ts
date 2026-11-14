import { z } from 'zod'
import { RTCPeerConnection, SessionDescription, type RTCDataChannel } from 'werift'
import type { AuthenticatedPair, LanPairing } from './lan-pairing.js'
import type { NoiseCarrier, RtcControlAuthority, RtcControlHandler } from './noise-carrier.js'
import { rtcConfigSchema, rtcCredentials, rtcPeerConfig, type RtcConfig, type RtcRouteKind } from './rtc-config.js'
import { guardRtcAssociation, RtcDataChannelDuplex, RTC_DIRECTION_BYTES, rtcLibraryQueuedBytes } from './rtc-datachannel.js'
import type { NativePeerAdmission } from './native-access.js'

const offerSchema = z.strictObject({ offerId: z.string().uuid(), routeKind: z.enum(['p2p', 'turn']), sdp: z.string().min(1).max(65536) })
class RtcPathUnavailable extends Error {}
interface Offer {
  id: string; pair: AuthenticatedPair; pc: RTCPeerConnection; routeKind: RtcRouteKind; expiresAt: number
  outer?: RtcDataChannelDuplex; channel?: RTCDataChannel; timer?: NodeJS.Timeout; closed: boolean; answered: boolean
  detach: (() => void)[]; cancelled: AbortController
  leasePin: NativePeerAdmission | undefined; authorityWork?: Promise<void>
}
export class RtcCarrierHost implements RtcControlHandler {
  readonly #offers = new Map<string, Offer>()
  readonly #closing = new Map<Offer, Promise<void>>()
  readonly #unfence: () => void
  readonly #config: RtcConfig
  #closed = false
  constructor(readonly pairing: LanPairing, readonly carrier: NoiseCarrier, config: RtcConfig, readonly observePeerConnection?: (pc: RTCPeerConnection, remote: SessionDescription) => void) {
    this.#config = rtcConfigSchema.parse(config)
    this.#unfence = pairing.onFence(pairId => { for (const entry of this.#offers.values()) if (!pairId || entry.pair.record.deviceId === pairId) this.#retire(entry, new Error('rtc/revoked')) })
  }
  get enabled(): boolean { return !this.#closed && this.#config.enabled }
  get activeCount(): number { return this.#offers.size }
  async handle(pair: AuthenticatedPair, method: string, path: string, body: unknown, signal: AbortSignal, authority: RtcControlAuthority | undefined): Promise<{ status: number; body: object }> {
    this.pairing.assertActive(pair)
    signal.throwIfAborted()
    if (pair.record.manifest.relay && !authority) throw new Error('e2e/relay-lease-required')
    if (!pair.record.manifest.relay && authority) throw new Error('e2e/relay-binding')
    if (this.#closed) throw new Error('rtc/closed')
    if (method === 'GET' && path === '/pair/rtc/config') {
      const credentials = rtcCredentials(this.#config, pair.record.deviceId)
      return { status: 200, body: { enabled: this.enabled, capabilities: this.enabled ? ['transport.webrtc.v1'] : [], stunUrls: this.enabled ? [...this.#config.stunUrls] : [], ...credentials } }
    }
    const deletion = /^\/pair\/rtc\/offer\/([0-9a-f-]{36})$/i.exec(path)
    if (method === 'DELETE' && deletion) {
      const id = z.string().uuid().parse(deletion[1]), entry = this.#offers.get(id)
      if (entry && entry.pair.record !== pair.record) return { status: 403, body: { code: 'rtc/offer-owner' } }
      if (entry) this.#retire(entry, new Error('rtc/cancelled'))
      return { status: 200, body: { ok: true } }
    }
    if (method !== 'POST' || path !== '/pair/rtc/offer') return { status: 404, body: { code: 'rtc/not-found' } }
    if (!this.enabled) return { status: 404, body: { code: 'rtc/unsupported' } }
    const input = offerSchema.parse(body)
    if (Buffer.byteLength(input.sdp, 'utf8') > 65536) throw new Error('rtc/sdp-budget')
    if (input.routeKind === 'turn' && !this.#config.turn) return { status: 503, body: { code: 'rtc/unavailable' } }
    if (this.#offers.has(input.offerId)) return { status: 409, body: { code: 'rtc/offer-conflict' } }
    let peers = 0
    for (const entry of this.#offers.values()) if (entry.pair.record.deviceId === pair.record.deviceId) peers++
    for (const entry of this.#closing.keys()) if (entry.pair.record.deviceId === pair.record.deviceId) peers++
    if (this.#offers.size + this.#closing.size >= 16 || peers >= 8) return { status: 429, body: { code: 'rtc/pc-budget' } }
    // Let the pinned library parse SDP. This is admission policy over its model,
    // not an application-owned SDP/ICE implementation.
    const description = SessionDescription.parse(input.sdp)
    if (description.media.length !== 1 || description.media[0]!.kind !== 'application' || description.media[0]!.port === 0 || !description.media[0]!.iceCandidatesComplete || !description.media[0]!.iceCandidates.length || description.media[0]!.iceCandidates.length > 32) throw new Error('rtc/data-only-nontrickle-required')
    if (input.routeKind === 'p2p' && description.media[0]!.iceCandidates.some(candidate => candidate.type === 'relay')) throw new Error('rtc/p2p-relay-forbidden')
    if (input.routeKind === 'turn' && description.media[0]!.iceCandidates.some(candidate => candidate.type !== 'relay')) throw new Error('rtc/relay-only-required')
    signal.throwIfAborted()
    const pc = new RTCPeerConnection(rtcPeerConfig(this.#config, input.routeKind, pair.record.deviceId))
    const entry: Offer = { id: input.offerId, pair, pc, routeKind: input.routeKind, expiresAt: Date.now() + 20_000, closed: false, answered: false, detach: [], cancelled: new AbortController(), leasePin: undefined }
    entry.timer = setTimeout(() => this.#retire(entry, authority ? new Error('rtc/authority-timeout') : new RtcPathUnavailable('rtc/offer-expired')), 20_000); entry.timer.unref()
    this.#offers.set(entry.id, entry)
    const abort = () => { if (!entry.answered) this.#retire(entry, new Error('rtc/signal-aborted')) }
    signal.addEventListener('abort', abort, { once: true })
    entry.detach.push(pc.onDataChannel.subscribe(channel => {
      try {
        this.#assert(entry)
        if (entry.channel) throw new Error('rtc/extra-channel')
        entry.channel = channel
        const outer = new RtcDataChannelDuplex(channel, pc, (inbound, outbound) => this.#budget(entry, inbound, outbound), () => this.#retire(entry))
        entry.outer = outer
        let started = false
        const open = () => {
          if (started || channel.readyState !== 'open') return
          try {
            this.#assert(entry)
            const facts = selectedRtcRoute(pc, entry.routeKind)
            started = true; clearTimeout(entry.timer)
            // Lifetime is now the carrier+pair, not the short signaling HTTP request.
            const pin = entry.leasePin
            if (pair.record.manifest.relay && !pin) throw new Error('e2e/relay-lease-required')
            void this.carrier.accept(outer, { routeKind: facts.routeKind, purpose: 'native', pairId: pair.record.deviceId,
              ...(pin ? { inheritedRelay: { lease: pin.lease, signal: pin.signal } } : {}) })
          } catch (error) { this.#retire(entry, error as Error) }
        }
        entry.detach.push(channel.stateChanged.subscribe(() => open()).unSubscribe); open()
      } catch (error) { channel.close(); this.#retire(entry, error as Error) }
    }).unSubscribe)
    entry.detach.push(pc.connectionStateChange.subscribe(state => {
      if (state === 'closed' || state === 'failed' || state === 'disconnected') this.#retire(entry,
        pc.iceConnectionState === 'failed' || pc.iceConnectionState === 'disconnected' ? new RtcPathUnavailable('rtc/ice-unavailable') : new Error('rtc/connection-closed'))
    }).unSubscribe)
    let gatherDeadline: NodeJS.Timeout | undefined
    try {
      if (authority) {
        entry.authorityWork = (async () => {
          const pin = await authority.fork(() => { this.pairing.assertActive(pair); return this.pairing.peers.acquire(pair.record).peer })
          if (entry.closed) { pin.release(); this.#assert(entry) }
          entry.leasePin = pin
          const revoked = () => this.#retire(entry, new Error('rtc/lease-revoked'))
          pin.signal.addEventListener('abort', revoked, { once: true }); entry.detach.push(() => pin.signal.removeEventListener('abort', revoked))
          if (pin.signal.aborted) revoked()
          this.#assert(entry)
        })()
        await this.#deadline(entry, entry.authorityWork)
      }
      this.#assert(entry)
      clearTimeout(entry.timer)
      entry.timer = setTimeout(() => this.#retire(entry, new RtcPathUnavailable('rtc/offer-expired')), Math.max(1, entry.expiresAt - Date.now())); entry.timer.unref()
      // Fresh authority failure is terminal; only subsequent ICE gathering
      // consumes the path-availability deadline. The offer TTL remains 20s.
      gatherDeadline = setTimeout(() => this.#retire(entry, new RtcPathUnavailable('rtc/gather-timeout')), 8_000); gatherDeadline.unref()
      this.observePeerConnection?.(pc, description)
      await this.#deadline(entry, pc.setRemoteDescription({ type: 'offer', sdp: input.sdp }))
      this.#assert(entry)
      guardRtcAssociation(pc, error => this.#retire(entry, error), (inbound, outbound) => this.#budget(entry, inbound, outbound))
      await this.#deadline(entry, pc.setLocalDescription(await pc.createAnswer()))
      this.#assert(entry)
      if (pc.iceGatheringState !== 'complete' || !pc.localDescription) throw new Error('rtc/gather-incomplete')
      const answer = pc.localDescription.sdp
      if (Buffer.byteLength(answer, 'utf8') > 65536) throw new Error('rtc/sdp-budget')
      const gathered = SessionDescription.parse(answer).media[0]!
      if (!gathered.iceCandidates.length || !gathered.iceCandidatesComplete) throw new RtcPathUnavailable('rtc/no-candidates')
      if (entry.routeKind === 'turn' && gathered.iceCandidates.some(candidate => candidate.type !== 'relay')) throw new Error('rtc/relay-only-required')
      signal.throwIfAborted(); entry.answered = true
      return { status: 200, body: { offerId: entry.id, routeKind: entry.routeKind, sdp: answer, expiresAt: entry.expiresAt } }
    } catch (error) {
      this.#retire(entry, error as Error)
      this.pairing.assertActive(pair)
      if (error instanceof RtcPathUnavailable && !signal.aborted && !this.#closed) return { status: 503, body: { code: 'rtc/unavailable' } }
      throw error
    } finally { clearTimeout(gatherDeadline); signal.removeEventListener('abort', abort) }
  }
  #assert(entry: Offer): void {
    if (this.#closed) throw new Error('rtc/closed')
    this.pairing.assertActive(entry.pair)
    if (entry.leasePin && (entry.leasePin.signal.aborted || entry.leasePin.lease.exp * 1000 <= Date.now())) throw new Error('rtc/lease-revoked')
    if (entry.closed) throw entry.cancelled.signal.reason instanceof Error ? entry.cancelled.signal.reason : new Error('rtc/closed')
  }
  async #deadline<T>(entry: Offer, operation: Promise<T>): Promise<T> {
    const signal = entry.cancelled.signal
    signal.throwIfAborted()
    const cancelled = Promise.withResolvers<never>(), abort = () => cancelled.reject(signal.reason instanceof Error ? signal.reason : new Error('rtc/cancelled'))
    signal.addEventListener('abort', abort, { once: true })
    try { return await Promise.race([operation, cancelled.promise]) } finally { signal.removeEventListener('abort', abort) }
  }
  #budget(entry: Offer, extraIn: number, extraOut: number): void {
    this.#assert(entry)
    let pairBytes = 0
    for (const other of this.#offers.values()) {
      if (other.pair.record.deviceId !== entry.pair.record.deviceId) continue
      const queue = other.outer?.carrierQueuedBytes() ?? rtcLibraryQueuedBytes(other.pc)
      pairBytes += queue.inbound + queue.outbound + (other.outer?.readableLength ?? 0) + (other.outer?.writableLength ?? 0)
    }
    for (const other of this.#closing.keys()) {
      if (other.pair.record.deviceId !== entry.pair.record.deviceId) continue
      const queue = other.outer?.carrierQueuedBytes() ?? rtcLibraryQueuedBytes(other.pc)
      pairBytes += queue.inbound + queue.outbound + (other.outer?.readableLength ?? 0) + (other.outer?.writableLength ?? 0)
    }
    const outer = entry.outer
    if (outer) {
      const queue = outer.carrierQueuedBytes()
      if (outer.readableLength + queue.inbound + extraIn > RTC_DIRECTION_BYTES || outer.writableLength + queue.outbound + extraOut > RTC_DIRECTION_BYTES) throw new Error('rtc/direction-budget')
      this.carrier.assertOuterBudget(outer, extraIn, extraOut)
    }
    else {
      const queue = rtcLibraryQueuedBytes(entry.pc)
      if (queue.inbound + extraIn > RTC_DIRECTION_BYTES || queue.outbound + extraOut > RTC_DIRECTION_BYTES) throw new Error('rtc/direction-budget')
    }
    if (pairBytes + extraIn + extraOut > 2 * 1024 * 1024) throw new Error('rtc/pair-budget')
  }
  #retire(entry: Offer, error?: Error): void {
    if (entry.closed) return
    entry.closed = true; clearTimeout(entry.timer); entry.cancelled.abort(error ?? new Error('rtc/closed'))
    this.#offers.delete(entry.id)
    const detach = entry.detach.splice(0).reverse()
    entry.outer?.destroy(error); entry.channel?.close()
    const transport = entry.pc.sctpTransport
    const closing = (async () => {
      try { await Promise.all([entry.authorityWork?.catch(() => {}), entry.pc.close().then(async () => { await transport?.drainCarrierResources() })]) }
      finally {
        for (const stop of detach) stop()
        const pin = entry.leasePin; entry.leasePin = undefined; pin?.release()
      }
    })()
    this.#closing.set(entry, closing)
    // Cleanup failure is terminal and remains charged; drain() reports it.
    void closing.then(() => { this.#closing.delete(entry) }, () => this.close())
  }
  close(): void { if (this.#closed) return; this.#closed = true; this.#unfence(); for (const entry of this.#offers.values()) this.#retire(entry, new Error('rtc/host-closed')) }
  async drain(): Promise<void> { await Promise.all(this.#closing.values()) }
}
export function selectedRtcRoute(pc: RTCPeerConnection, expected: RtcRouteKind): { routeKind: RtcRouteKind; localCandidateType: string; remoteCandidateType: string } {
  if (pc.iceTransports.length !== 1) throw new Error('rtc/selected-pair-required')
  const pair = pc.iceTransports[0]!.connection.nominated
  if (!pair?.nominated) throw new Error('rtc/selected-pair-required')
  const localCandidateType = pair.localCandidate.type, remoteCandidateType = pair.remoteCandidate.type
  if (expected === 'turn' ? localCandidateType !== 'relay' : !['host', 'srflx', 'prflx'].includes(localCandidateType) || !['host', 'srflx', 'prflx'].includes(remoteCandidateType)) throw new Error('rtc/selected-route-mismatch')
  return { routeKind: expected, localCandidateType, remoteCandidateType }
}
