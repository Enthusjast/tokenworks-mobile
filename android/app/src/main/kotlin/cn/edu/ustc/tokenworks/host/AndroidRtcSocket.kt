package cn.edu.ustc.tokenworks

import kotlinx.coroutines.*
import org.webrtc.*
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal data class RtcCandidateFacts(val path: DshTransportPath, val localType: String, val remoteType: String, val protocol: String)

/** Only ordered binary ciphertext crosses this adapter; NativeHttpClient and Noise remain unchanged. */
internal class AndroidRtcSocket(
    val runtime: String,
    private val path: DshTransportPath,
    private val receiver: CarrierReceiver,
    private val factory: PeerConnectionFactory,
    private val current: () -> Unit,
    private val controlFactory: () -> PairingControlClient,
    private val retire: (AndroidRtcSocket) -> Unit,
) : CarrierOuterSocket {
    private val ended = AtomicBoolean()
    private val failureOnce = AtomicBoolean()
    private val statsActive = AtomicBoolean()
    private val statsAgain = AtomicBoolean()
    private val candidateCount = AtomicInteger()
    private val candidateBytes = AtomicInteger()
    private val writers = ReentrantLock()
    private val nativeCalls = AtomicInteger()
    private val wake = Object()
    private val gathered = CompletableDeferred<Unit>()
    private val opened = CompletableDeferred<Unit>()
    private val selected = CompletableDeferred<Unit>()
    @Volatile private var pc: PeerConnection? = null
    @Volatile private var channel: DataChannel? = null
    @Volatile private var unexpectedChannel: DataChannel? = null
    @Volatile private var control: PairingControlClient? = null
    @Volatile private var error: IOException? = null
    @Volatile private var allocationFailure: IOException? = null
    private val offerId = UUID.randomUUID().toString()
    @Volatile private var signaled = false
    @Volatile private var admitted = false
    @Volatile var candidateFacts: RtcCandidateFacts? = null
        private set
    val closed: Boolean get() = ended.get()
    override val queuedWriteBytes: Long get() = try { native { channel?.bufferedAmount() ?: 0 } } catch (_: IOException) { 0 }

    suspend fun open() {
        try {
            withTimeout(20_000) {
                current()
                val signaling = native { controlFactory().also { control = it } }
                val config = signaling.request("GET", "/pair/rtc/config")
                if (config["enabled"] != JsonBoolean(true)) throw RemoteRequestException("rtc.disabled")
                if (config.array("capabilities").none { it == JsonString("transport.webrtc.v1") }) throw IOException("e2e/rtc_capability")
                val now = System.currentTimeMillis()
                if (config.integer("expiresAt") <= now) throw IOException("network.unavailable")
                val servers = iceServers(config)
                current()
                if (ended.get()) throw IOException("e2e/rtc_closed")
                val configuration = PeerConnection.RTCConfiguration(servers).apply {
                    iceTransportsType = if (path == DshTransportPath.TURN) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
                    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
                    iceCandidatePoolSize = 0
                    tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                }
                val peer = native {
                    val created = factory.createPeerConnection(configuration, observer) ?: throw IOException("network.unavailable")
                    pc = created
                    val init = DataChannel.Init().apply {
                        ordered = true; maxRetransmits = -1; maxRetransmitTimeMs = -1
                        protocol = "twm-noise.v1"; negotiated = false
                    }
                    val data = created.createDataChannel("tokenworks-native", init) ?: throw IOException("network.unavailable")
                    channel = data
                    data.registerObserver(dataObserver)
                    created
                }
                val offer = createOffer(peer)
                validateSdp(offer.description)
                // Negotiate 16KiB messages; callback admission separately reserves the shared carrier budget.
                val boundedOffer = offer.description.replace(Regex("(?m)^a=max-message-size:[0-9]+(?=\\r?\\n|$)"), "a=max-message-size:16384")
                if (!boundedOffer.contains("a=max-message-size:16384")) throw IOException("e2e/rtc_sdp")
                setDescription(peer, SessionDescription(SessionDescription.Type.OFFER, boundedOffer), true)
                gathered.await()
                current(); live()
                val gatheredSdp = native { peer.localDescription?.description } ?: throw IOException("e2e/rtc_sdp")
                if (gatheredSdp.lineSequence().none { it.startsWith("a=candidate:") }) {
                    throw RemoteRequestException("rtc.unavailable").also {
                        val media = gatheredSdp.lineSequence().firstOrNull { line -> line.startsWith("m=") }
                        it.initCause(allocationFailure ?: IllegalStateException("ICE gathering completed without candidates (callbacks=${candidateCount.get()}, media=$media)"))
                    }
                }
                // Native ICE completion is observed above; signal its truthful non-trickle marker explicitly.
                val sdp = if (gatheredSdp.contains("\na=end-of-candidates")) gatheredSdp else gatheredSdp +
                    if (gatheredSdp.endsWith("\n")) "a=end-of-candidates\r\n" else "\r\na=end-of-candidates\r\n"
                validateSdp(sdp)
                // Set before dispatch: a lost answer is an ambiguous signaling write and is cleaned up, never replayed.
                signaled = true
                val answer = signaling.request("POST", "/pair/rtc/offer", jsonObject(
                    "offerId" to offerId.json(), "routeKind" to (if (path == DshTransportPath.TURN) "turn" else "p2p").json(), "sdp" to sdp.json()))
                answer.exactKeys("offerId", "routeKind", "sdp", "expiresAt")
                if (answer.string("offerId") != offerId || answer.string("routeKind") != (if (path == DshTransportPath.TURN) "turn" else "p2p")) throw IOException("e2e/rtc_signal_binding")
                if (answer.integer("expiresAt") <= System.currentTimeMillis()) throw IOException("network.unavailable")
                val remote = answer.string("sdp")
                validateSdp(remote)
                setDescription(peer, SessionDescription(SessionDescription.Type.ANSWER, remote), false)
                closeControl()
                opened.await()
                verifySelectedPair()
                selected.await()
                current(); live(); admitted = true
            }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            fail("network.unavailable")
            throw IOException("network.unavailable")
        } catch (cancelled: CancellationException) { close(); throw cancelled }
        catch (failure: Exception) {
            val stable = when (failure) {
                is IOException -> failure
                is RemoteRequestException -> failure
                is LanPairingException -> failure
                is DshFailure -> failure
                else -> IOException("e2e/rtc_signal", failure)
            }
            close()
            throw stable
        }
    }
    private fun iceServers(config: JsonObject): List<PeerConnection.IceServer> {
        val result = ArrayList<PeerConnection.IceServer>()
        if (path == DshTransportPath.P2P) {
            val urls = config.array("stunUrls")
            if (urls.size > 8) throw IOException("e2e/rtc_config")
            for (value in urls) {
                val url = (value as? JsonString)?.value ?: throw IOException("e2e/rtc_config")
                if (url.length > 512 || !url.matches(Regex("^stun:[A-Za-z0-9.\\[\\]:_-]{1,480}$"))) throw IOException("e2e/rtc_config")
                result.add(PeerConnection.IceServer.builder(url).createIceServer())
            }
        } else {
            val servers = config.array("turnServers")
            if (servers.isEmpty()) throw RemoteRequestException("rtc.unavailable")
            if (servers.size > 4) throw IOException("e2e/rtc_config")
            for (value in servers) {
                val server = value as? JsonObject ?: throw IOException("e2e/rtc_config")
                server.exactKeys("urls", "username", "credential")
                val urls = server.array("urls")
                if (urls.isEmpty() || urls.size > 4) throw IOException("e2e/rtc_config")
                val username = server.string("username"); val credential = server.string("credential")
                if (username.length !in 1..2048 || credential.length !in 1..2048 || username.any { it.code < 32 } || credential.any { it.code < 32 }) throw IOException("e2e/rtc_config")
                for (valueUrl in urls) {
                    val url = (valueUrl as? JsonString)?.value ?: throw IOException("e2e/rtc_config")
                    // UDP is the deliberately negotiated intersection with the pinned Host implementation.
                    if (url.length > 512 || !url.matches(Regex("^turn:[A-Za-z0-9.\\[\\]:_-]{1,480}(\\?transport=udp)?$"))) throw IOException("e2e/rtc_config")
                    result.add(PeerConnection.IceServer.builder(url).setUsername(username).setPassword(credential).createIceServer())
                }
            }
        }
        return result
    }
    private fun validateSdp(sdp: String) {
        if (sdp.length > 65_536 || sdp.toByteArray(Charsets.UTF_8).size > 65_536 || sdp.indexOf('\u0000') >= 0) throw IOException("e2e/rtc_sdp")
        val media = sdp.lineSequence().filter { it.startsWith("m=") }.toList()
        if (media.size != 1 || !media.single().startsWith("m=application ") || !media.single().contains("UDP/DTLS/SCTP")) throw IOException("e2e/rtc_media")
        var candidates = 0
        for (line in sdp.lineSequence()) {
            if (line.length > 2048) throw IOException("e2e/rtc_sdp")
            if (line.startsWith("a=candidate:") && ++candidates > 64) throw IOException("e2e/rtc_ice_budget")
        }
    }
    private suspend fun createOffer(peer: PeerConnection): SessionDescription {
        val result = CompletableDeferred<SessionDescription>()
        native { peer.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) { result.complete(sdp) }
            override fun onCreateFailure(message: String) { result.completeExceptionally(IOException("network.unavailable")) }
            override fun onSetSuccess() = Unit
            override fun onSetFailure(message: String) = Unit
        }, MediaConstraints()) }
        return result.await()
    }
    private suspend fun setDescription(peer: PeerConnection, sdp: SessionDescription, local: Boolean) {
        val result = CompletableDeferred<Unit>()
        val observer = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = Unit
            override fun onCreateFailure(message: String) = Unit
            override fun onSetSuccess() { result.complete(Unit) }
            override fun onSetFailure(message: String) { result.completeExceptionally(IOException("e2e/rtc_sdp", IllegalStateException(message))) }
        }
        native { if (local) peer.setLocalDescription(observer, sdp) else peer.setRemoteDescription(observer, sdp) }
        result.await()
    }
    /** Actual nominated transport/candidate IDs, never the requested configuration label. */
    private fun verifySelectedPair() {
        if (ended.get()) return
        val peer = pc ?: return
        if (!statsActive.compareAndSet(false, true)) { statsAgain.set(true); return }
        try { native { peer.getStats { report ->
            try {
            if (ended.get()) return@getStats
            val stats = report.statsMap
            val transport = stats.values.firstOrNull { it.type == "transport" && it.members["selectedCandidatePairId"] is String } ?: return@getStats
            val pair = stats[transport.members["selectedCandidatePairId"] as String] ?: return@getStats
            val local = stats[pair.members["localCandidateId"] as? String] ?: return@getStats
            val remote = stats[pair.members["remoteCandidateId"] as? String] ?: return@getStats
            val localType = local.members["candidateType"] as? String
            val remoteType = remote.members["candidateType"] as? String
            val protocol = local.members["protocol"] as? String
            val valid = pair.type == "candidate-pair" && pair.members["state"] == "succeeded" && protocol == "udp" &&
                (if (path == DshTransportPath.TURN) localType == "relay" && remoteType in ALL_TYPES else localType in DIRECT_TYPES && remoteType in DIRECT_TYPES)
            if (!valid) fail("e2e/rtc_candidate") else {
                candidateFacts = RtcCandidateFacts(path, localType!!, remoteType!!, protocol!!)
                selected.complete(Unit)
            }
            } finally {
                statsActive.set(false)
                if (statsAgain.getAndSet(false) && !ended.get()) verifySelectedPair()
            }
        } } } catch (_: IOException) { statsActive.set(false); if (!ended.get()) fail("network.unavailable") }
    }
    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            when (state) {
                PeerConnection.IceConnectionState.FAILED, PeerConnection.IceConnectionState.DISCONNECTED, PeerConnection.IceConnectionState.CLOSED -> fail("network.unavailable")
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> verifySelectedPair()
                else -> Unit
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) { if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit) }
        override fun onIceCandidate(candidate: IceCandidate) {
            if (candidate.sdp.length > 2048 || candidateCount.incrementAndGet() > 64 || candidateBytes.addAndGet(candidate.sdp.length) > 65_536) fail("e2e/rtc_ice_budget")
        }
        override fun onIceCandidateError(event: IceCandidateErrorEvent) {
            val reason = if (event.errorText.startsWith("Native ")) event.errorText.take(256) else "ICE server error ${event.errorCode}"
            allocationFailure = IOException("network.unavailable", IllegalStateException(reason))
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
            if (admitted) fail("network.unavailable") else verifySelectedPair()
        }
        override fun onAddStream(stream: MediaStream) { fail("e2e/rtc_media") }
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(data: DataChannel) {
            if (ended.get()) return
            unexpectedChannel = data
            fail("e2e/rtc_extra_channel")
        }
        override fun onRenegotiationNeeded() = Unit
        override fun onTrack(transceiver: RtpTransceiver) { fail("e2e/rtc_media") }
    }
    private val dataObserver = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) { synchronized(wake) { wake.notifyAll() } }
        override fun onStateChange() {
            val state = try { native { channel?.state() } } catch (_: IOException) { return }
            when (state) {
                DataChannel.State.OPEN -> opened.complete(Unit)
                DataChannel.State.CLOSED, DataChannel.State.CLOSING -> fail("e2e/rtc_channel_closed")
                else -> Unit
            }
        }
        override fun onMessage(buffer: DataChannel.Buffer) {
            if (ended.get()) return
            if (!buffer.binary || buffer.data.remaining() !in 1..CHUNK) { fail("e2e/rtc_frame"); return }
            try {
                current()
                // CarrierReceiver reserves shared 256KiB/2MiB credits before copying this native callback buffer.
                receiver.receive(buffer.data)
            } catch (_: Exception) { fail("e2e/rtc_receive") }
        }
    }
    private fun live() { if (ended.get()) throw error ?: IOException("network.unavailable") }
    private inline fun <T> native(block: () -> T): T {
        nativeCalls.incrementAndGet()
        try { live(); return block() }
        finally { if (nativeCalls.decrementAndGet() == 0) synchronized(wake) { wake.notifyAll() } }
    }
    override fun write(bytes: ByteArray, offset: Int, length: Int, deadlineNanos: Long) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0 || !writers.tryLock(remaining.coerceAtLeast(0), java.util.concurrent.TimeUnit.NANOSECONDS)) {
            fail("network.unavailable"); throw IOException("network.unavailable")
        }
        try {
            current(); live()
            if (!admitted) throw IOException("e2e/rtc_not_ready")
            val data = channel ?: throw IOException("network.unavailable")
            var cursor = offset
            val end = offset + length
            while (cursor < end) {
                waitDrained(data, deadlineNanos)
                live()
                val count = minOf(CHUNK, end - cursor)
                if (!native { data.send(DataChannel.Buffer(ByteBuffer.wrap(bytes, cursor, count), true)) }) throw IOException("network.unavailable")
                cursor += count
            }
            waitDrained(data, deadlineNanos)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt(); fail("network.unavailable"); throw IOException("network.unavailable")
        } catch (failure: Exception) {
            fail(if (failure is IOException && failure.message?.startsWith("e2e/") == true) failure.message!! else "network.unavailable")
            throw failure
        } finally { writers.unlock() }
    }
    private fun waitDrained(data: DataChannel, deadline: Long) {
        while (true) {
            live()
            if (deadline - System.nanoTime() <= 0) throw IOException("network.unavailable")
            // Never hold wake while calling JNI: WebRTC may synchronously invoke its observer on another thread.
            val queued = native { data.bufferedAmount() }
            if (queued > CHUNK) { fail("e2e/rtc_send_budget"); throw IOException("e2e/rtc_send_budget") }
            if (queued == 0L) return
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw IOException("network.unavailable")
            synchronized(wake) {
                live()
                // Bounded timed wait also covers a low-water notification racing the JNI amount query.
                val wait = minOf(remaining, 50_000_000L)
                wake.wait(wait / 1_000_000L, (wait % 1_000_000L).toInt())
            }
        }
    }
    private fun fail(code: String) {
        if (ended.get() || !failureOnce.compareAndSet(false, true)) return
        error = IOException(code)
        close()
    }
    override fun close() {
        if (!ended.compareAndSet(false, true)) return
        admitted = false
        val failure = error ?: IOException("e2e/rtc_closed")
        receiver.fail(failure.message ?: "e2e/rtc_closed")
        gathered.completeExceptionally(failure); opened.completeExceptionally(failure); selected.completeExceptionally(failure)
        synchronized(wake) { wake.notifyAll() }
        retire(this)
    }
    fun disposeNative() {
        synchronized(wake) { while (nativeCalls.get() != 0) wake.wait() }
        writers.withLock {
            channel?.let { it.unregisterObserver(); it.close(); it.dispose() }; channel = null
            unexpectedChannel?.let { it.close(); it.dispose() }; unexpectedChannel = null
            pc?.let { it.close(); it.dispose() }; pc = null
        }
    }
    suspend fun deleteOffer() {
        try { if (signaled) withTimeout(5_000) { controlFactory().use { it.request("DELETE", "/pair/rtc/offer/$offerId") } } }
        catch (_: Exception) { /* Pair generation and the server offer deadline also fence cleanup. */ }
        finally { closeControl() }
    }
    fun closeControl() { control?.close(); control = null }
    companion object {
        private const val CHUNK = 16 * 1024
        private val DIRECT_TYPES = setOf("host", "srflx", "prflx")
        private val ALL_TYPES = DIRECT_TYPES + "relay"
    }
}
