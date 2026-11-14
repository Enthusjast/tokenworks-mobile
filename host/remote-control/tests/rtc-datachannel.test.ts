import assert from 'node:assert/strict'
import test from 'node:test'
import { once } from 'node:events'
import { RTCPeerConnection } from 'werift'
import { RtcDataChannelDuplex, guardRtcAssociation } from '../src/rtc-datachannel.js'
import { rtcConfigSchema, rtcPeerConfig } from '../src/rtc-config.js'

test('real fragmented gap-ACK retransmission holds write credit; callback once; slow-peer overflow closes and drains', { timeout: 30_000 }, async t => {
  const config = rtcPeerConfig(rtcConfigSchema.parse({ enabled: true }), 'p2p', 'test'), a = new RTCPeerConnection(config), b = new RTCPeerConnection(config)
  let closingA: Promise<void> | undefined, closingB: Promise<void> | undefined
  const closeA = () => closingA ??= a.close(), closeB = () => closingB ??= b.close()
  t.after(async () => { await closeA(); await closeB(); await a.sctpTransport?.drainCarrierResources(); await b.sctpTransport?.drainCarrierResources() })
  const dc = a.createDataChannel('tokenworks-native', { protocol: 'twm-noise.v1', ordered: true }), channel = Promise.withResolvers<RtcDataChannelDuplex>()
  b.onDataChannel.subscribe(remote => {
    let outer: RtcDataChannelDuplex
    outer = new RtcDataChannelDuplex(remote, b, (inbound, outbound) => {
      const queue = outer.carrierQueuedBytes()
      if (outer.readableLength + queue.inbound + inbound > 256 * 1024 || outer.writableLength + queue.outbound + outbound > 256 * 1024) throw new Error('test/direction-budget')
    }, () => { void closeB() })
    channel.resolve(outer)
  })
  await a.setLocalDescription(await a.createOffer())
  guardRtcAssociation(a, () => { void closeA() })
  await b.setRemoteDescription({ type: 'offer', sdp: a.localDescription!.sdp })
  guardRtcAssociation(b, () => { void closeB() })
  await b.setLocalDescription(await b.createAnswer())
  const opened = dc.stateChanged.watch(state => state === 'open', 10_000)
  await a.setRemoteDescription({ type: 'answer', sdp: b.localDescription!.sdp }); await opened
  let writer: RtcDataChannelDuplex
  writer = new RtcDataChannelDuplex(dc, a, (inbound, outbound) => {
    const queue = writer.carrierQueuedBytes()
    if (writer.readableLength + queue.inbound + inbound > 256 * 1024 || writer.writableLength + queue.outbound + outbound > 256 * 1024) throw new Error('test/direction-budget')
  }, () => { void closeA() })
  const reader = await channel.promise, received = once(reader, 'readable'), consumed = Promise.withResolvers<void>()
  for (const transport of [dc.sctp, reader.channel.sctp]) {
    if (transport.getCarrierQueueBytes().pendingWriteBytes === 0) continue
    const idle = Promise.withResolvers<void>(), sctp = transport.sctp, previous = sctp.onSackReceived
    sctp.onSackReceived = async () => { await previous.call(sctp); if (transport.getCarrierQueueBytes().pendingWriteBytes === 0) idle.resolve() }
    try { await idle.promise } finally { sctp.onSackReceived = previous }
  }
  let callbacks = 0
  const dtls = b.dtlsTransports[0]!, receive = dtls.dataReceiver
  let dropped = false
  // Public library receiver fault seam: drop one actual decrypted SCTP packet.
  // Subsequent fragments produce real gap SACKs and library retransmission.
  dtls.dataReceiver = bytes => { if (!dropped) { dropped = true; return }; receive(bytes) }
  const payload = Buffer.alloc(16384, 0x5a)
  writer.write(payload, error => { callbacks++; if (error) consumed.reject(error); else consumed.resolve() })
  await received
  // This assertion is at the real receive event, before the receiver sends the
  // delayed platform SACK. Advancing virtual time cannot drive UDP/DTLS/SCTP.
  assert.equal(callbacks, 0)
  assert.ok(dc.sctp.getCarrierQueueBytes().pendingWriteBytes > 0)
  assert.deepEqual(reader.read(payload.length), payload)
  await consumed.promise; assert.equal(callbacks, 1)
  assert.equal(dropped, true)
  const stats = dc.sctp.getCarrierResourceStats()
  assert.ok(stats.gapAcks > 0); assert.ok(stats.retransmits > 0)
  assert.ok(stats.maxReceiveTasks <= 32); assert.ok(stats.maxSendTasks <= 32); assert.ok(stats.maxHandshakeTasks <= 8)
  let failure: Error | undefined; reader.once('error', error => { failure = error })
  const closed = once(reader, 'close').catch(() => {})
  for (let index = 0; index < 13; index++) {
    const written = Promise.withResolvers<void>()
    writer.write(Buffer.alloc(16384, index), error => error ? written.reject(error) : written.resolve())
    try { await written.promise } catch { break }
  }
  await closed
  assert.match(failure?.message ?? '', /budget/)
  assert.ok(reader.readableLength <= 256 * 1024)
  assert.equal(callbacks, 1)
  await closeA(); await closeB(); await a.sctpTransport?.drainCarrierResources(); await b.sctpTransport?.drainCarrierResources()
  assert.deepEqual(a.sctpTransport?.getCarrierQueueBytes(), { inbound: 0, outbound: 0, pendingWriteBytes: 0 })
  assert.deepEqual(b.sctpTransport?.getCarrierQueueBytes(), { inbound: 0, outbound: 0, pendingWriteBytes: 0 })
})
