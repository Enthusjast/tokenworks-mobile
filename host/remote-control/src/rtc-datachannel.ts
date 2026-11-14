import { Duplex } from 'node:stream'
import type { RTCDataChannel, RTCPeerConnection } from 'werift'

export const RTC_CHUNK_BYTES = 16384, RTC_DIRECTION_BYTES = 256 * 1024
export interface RtcQueueBytes { inbound: number; outbound: number }
/** These public APIs are supplied by scripts/patch-werift.mjs, not upstream. */
export function rtcLibraryQueuedBytes(pc: RTCPeerConnection): RtcQueueBytes {
  const transport = pc.sctpTransport
  if (!transport) return { inbound: 0, outbound: 0 }
  if (typeof transport.getCarrierQueueBytes !== 'function') throw new Error('rtc/resource-patch-required')
  return transport.getCarrierQueueBytes()
}
export class RtcDataChannelDuplex extends Duplex {
  readonly #detach: (() => void)[] = []
  #pending: { callback: (error?: Error | null) => void; bytes: Buffer; offset: number; deadline: number; sent: boolean } | undefined
  readonly #timer: NodeJS.Timeout
  constructor(readonly channel: RTCDataChannel, readonly pc: RTCPeerConnection, readonly reserve: (inbound: number, outbound: number) => void, readonly retire: () => void) {
    super({ readableHighWaterMark: 65536, writableHighWaterMark: 65536, allowHalfOpen: false })
    this.on('error', () => {})
    if (channel.label !== 'tokenworks-native' || channel.protocol !== 'twm-noise.v1' || !channel.ordered || channel.maxRetransmits !== null || channel.maxPacketLifeTime !== null || channel.negotiated) throw new Error('rtc/channel-policy')
    if (typeof channel.sctp.admitCarrierChannel !== 'function') throw new Error('rtc/resource-patch-required')
    channel.sctp.admitCarrierChannel(channel)
    channel.bufferedAmountLowThreshold = 0
    this.#detach.push(channel.onMessage.subscribe(data => {
      if (this.destroyed) return
      try {
        if (!Buffer.isBuffer(data) || data.length === 0 || data.length > RTC_CHUNK_BYTES) throw new Error('rtc/binary-chunk-required')
        this.reserve(data.length, 0)
        this.push(Buffer.from(data)) // reserve before copying callback-owned data
      } catch (error) { this.destroy(error as Error) }
    }).unSubscribe)
    this.#detach.push(channel.stateChanged.subscribe(state => { if (state === 'closed' || state === 'closing') this.destroy(new Error('rtc/channel-closed')); else this.#pump() }).unSubscribe)
    this.#detach.push(channel.error.subscribe(() => this.destroy(new Error('rtc/channel-failed'))).unSubscribe)
    this.#detach.push(channel.bufferedAmountLow.subscribe(() => this.#pump()).unSubscribe)
    this.#timer = setInterval(() => this.#pump(), 10); this.#timer.unref()
  }
  carrierQueuedBytes(): RtcQueueBytes { return rtcLibraryQueuedBytes(this.pc) }
  override _read(): void {}
  override _write(bytes: Buffer, _encoding: BufferEncoding, callback: (error?: Error | null) => void): void {
    if (!Buffer.isBuffer(bytes) || bytes.length === 0 || bytes.length > RTC_DIRECTION_BYTES) { callback(new Error('rtc/write-budget')); return }
    this.#pending = { bytes, callback, offset: 0, deadline: Date.now() + 30_000, sent: false }; this.#pump()
  }
  #pump(): void {
    const pending = this.#pending
    if (!pending || this.destroyed) return
    try {
      if (Date.now() >= pending.deadline) throw new Error('rtc/write-timeout')
      this.reserve(0, 0)
      if (this.channel.readyState !== 'open') return
      // bufferedAmount drops at first transmission. The patched public API also
      // charges SCTP retransmission payloads until SACK and UDP callback completion.
      if (this.channel.bufferedAmount !== 0 || this.channel.sctp.getCarrierQueueBytes().pendingWriteBytes !== 0) return
      if (pending.sent && pending.offset === pending.bytes.length) { this.#pending = undefined; pending.callback(); return }
      const end = Math.min(pending.offset + RTC_CHUNK_BYTES, pending.bytes.length), part = pending.bytes.subarray(pending.offset, end)
      this.reserve(0, part.length * 2 + 128 * (Math.ceil(part.length / 1000) + 1))
      this.channel.send(part); pending.offset = end; pending.sent = true
    } catch (error) { this.destroy(error as Error) }
  }
  override _final(callback: (error?: Error | null) => void): void { callback(); this.destroy() }
  override _destroy(error: Error | null, callback: (error?: Error | null) => void): void {
    clearInterval(this.#timer)
    for (const detach of this.#detach.splice(0)) detach()
    const pending = this.#pending; this.#pending = undefined
    pending?.callback(error ?? new Error('rtc/closed'))
    this.channel.close(); this.retire(); callback(error)
  }
}

/** Install the audited source patch's guard before ICE/DTLS/SCTP activation. */
export function guardRtcAssociation(pc: RTCPeerConnection, fail: (error: Error) => void, reserve?: (inbound: number, outbound: number) => void): void {
  const transport = pc.sctpTransport
  if (!transport || typeof transport.configureCarrierResources !== 'function') throw new Error('rtc/resource-patch-required')
  transport.configureCarrierResources({
    reserve(inbound, outbound) {
      const queued = rtcLibraryQueuedBytes(pc)
      if (queued.inbound + inbound > RTC_DIRECTION_BYTES || queued.outbound + outbound > RTC_DIRECTION_BYTES) throw new Error('rtc/library-task-budget')
      reserve?.(inbound, outbound)
    }, fail,
  })
}
