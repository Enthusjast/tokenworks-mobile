package cn.edu.ustc.tokenworks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal const val CARRIER_QUEUE_BYTES = 256 * 1024
internal const val CARRIER_TIMEOUT_MILLIS = 30_000L

/** Account/Host/pair epochs form the only shared budget domain; Relay leases do not create identities. */
private object CarrierBudgets {
    private class Entry(var connections: Int = 0, var bytes: Int = 0)
    private val entries = mutableMapOf<String, Entry>()
    @Synchronized fun acquire(key: String) {
        val entry = entries.getOrPut(key) { Entry() }
        if (entry.connections >= 8) throw DshFailure("e2e.connection_budget")
        entry.connections++
    }
    @Synchronized fun reserve(key: String, bytes: Int) {
        val entry = entries[key] ?: throw IOException("e2e/closed")
        if (bytes < 0 || entry.bytes > 2 * 1024 * 1024 - bytes) throw IOException("e2e/pair_budget")
        entry.bytes += bytes
    }
    @Synchronized fun release(key: String, bytes: Int) { entries[key]?.let { it.bytes -= bytes; if (it.connections == 0 && it.bytes == 0) entries.remove(key) } }
    @Synchronized fun close(key: String) {
        entries[key]?.let { it.connections--; if (it.connections == 0 && it.bytes == 0) entries.remove(key) }
    }
}

/** Callback-only admission: never blocks the library reader or creates a coroutine for a frame. */
private class CarrierInbound(private val budget: String) {
    private val lock = Object()
    private val queue = ArrayDeque<ByteArray>()
    private var offset = 0
    private var bytes = 0
    private var ended = false
    private var sealed = false
    private var failure: IOException? = null
    fun offer(value: ByteArray, start: Int, count: Int) = enqueue(count) { value.copyOfRange(start, start + count) }
    fun offer(value: ByteBuffer) = enqueue(value.remaining()) { ByteArray(value.remaining()).also(value::get) }
    private inline fun enqueue(count: Int, copy: () -> ByteArray) = synchronized(lock) {
        if (sealed) throw IOException("e2e/record_after_close")
        if (ended) return@synchronized
        if (count == 0 || count > CARRIER_QUEUE_BYTES - bytes) throw IOException("e2e/inbound_budget")
        CarrierBudgets.reserve(budget, count)
        val saved = try { copy() } catch (error: Throwable) { CarrierBudgets.release(budget, count); throw error }
        queue.addLast(saved); bytes += count; lock.notifyAll()
    }
    fun end(error: IOException) = synchronized(lock) { if (!ended) { ended = true; failure = error }; lock.notifyAll() }
    fun seal() = synchronized(lock) { if (bytes != 0) throw IOException("e2e/record_after_close"); sealed = true }
    fun read(target: ByteArray, start: Int, count: Int, deadline: Long): Int = synchronized(lock) {
        while (queue.isEmpty()) {
            if (ended) throw failure ?: EOFException("e2e/truncated")
            val left = deadline - System.nanoTime()
            if (left <= 0) throw SocketTimeoutException("e2e/record_timeout")
            TimeUnit.NANOSECONDS.timedWait(lock, left)
        }
        val first = queue.first
        val copied = minOf(count, first.size - offset)
        first.copyInto(target, start, offset, offset + copied)
        offset += copied; bytes -= copied; CarrierBudgets.release(budget, copied)
        if (offset == first.size) { first.fill(0); queue.removeFirst(); offset = 0 }
        copied
    }
    fun discard() = synchronized(lock) {
        queue.forEach { it.fill(0) }; queue.clear(); CarrierBudgets.release(budget, bytes); bytes = 0; ended = true; lock.notifyAll()
    }
}

/** A completed Noise stream, not a TCP or pretend TLS socket. Every connect operation is prohibited. */
internal class CarrierSocket private constructor(
    private val credentials: LanCredentials, private val signer: DeviceProofSigner,
    private val routeKind: String, val purpose: String, private val relayLease: DshLease?,
) : Socket() {
    private val budget = credentials.ownerSha256 + ":" + credentials.runtimeId + ":" + credentials.installationId + ":" + credentials.pairId
    private val inbound = CarrierInbound(budget)
    private val ended = AtomicBoolean()
    private val engineGate = Any()
    private val sendGate = Any()
    private val readGate = Any()
    private var engine: E2eSession? = null
    private var outer: CarrierOuterSocket? = null
    private val receiver = CarrierReceiver({ bytes, offset, length -> inbound.offer(bytes, offset, length) }, { inbound.offer(it) }, { inbound.end(it) })
    private val creditGate = Any()
    private var libraryBytes = 0
    private val cipherIn = ByteBuffer.allocateDirect(65535)
    private val cipherOut = ByteBuffer.allocateDirect(65535)
    private val plainIn = ByteBuffer.allocateDirect(65535)
    private val plainOut = ByteBuffer.allocateDirect(65535)
    private val wireIn = ByteArray(65535)
    private val wireOut = ByteArray(65537)
    private var receiveCount = 0L
    private var sendCount = 0L
    private var receiveBytes = 0L
    private var sendBytes = 0L
    private var remoteClosed = false
    private var outputClosed = false
    @Volatile private var ready = false
    @Volatile private var timeout = 30_000
    @Volatile var consumed: ((Int) -> Unit)? = null
    val queuedCiphertextBytes: Long get() = outer?.queuedWriteBytes ?: 0
    val responseNonce: String? get() = (outer as? WssConnectionState)?.responseNonce
    lateinit var peerGeneration: String
        private set
    lateinit var handshakeHash: ByteArray
        private set
    private var absoluteDeadline = Long.MAX_VALUE
    private var headerRemaining: Int? = null

    init { CarrierBudgets.acquire(budget); plainIn.limitCompat(0) }

    private fun live() { if (ended.get()) throw SocketException("e2e/closed") }
    private fun deadline(): Long = minOf(absoluteDeadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(if (timeout == 0) CARRIER_TIMEOUT_MILLIS else timeout.toLong()))
    private fun exact(target: ByteArray, start: Int, count: Int, deadline: Long) {
        var at = start
        while (at < start + count) { live(); at += inbound.read(target, at, start + count - at, deadline) }
    }
    private fun record(maximum: Int, minimum: Int = 1): Int {
        val firstDeadline = if (timeout == 0) absoluteDeadline else deadline()
        exact(wireIn, 0, 1, firstDeadline)
        val deadline = minOf(absoluteDeadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CARRIER_TIMEOUT_MILLIS))
        exact(wireIn, 1, 1, deadline)
        val size = ((wireIn[0].toInt() and 255) shl 8) or (wireIn[1].toInt() and 255)
        if (size !in minimum..maximum) throw IOException("e2e/record_bounds")
        exact(wireIn, 0, size, deadline)
        return size
    }
    private fun sendRecord(size: Int) {
        live()
        if (size !in 1..65535) throw IOException("e2e/record_bounds")
        wireOut[0] = (size ushr 8).toByte(); wireOut[1] = size.toByte()
        cipherOut.flipCompat(); cipherOut.get(wireOut, 2, size)
        val socket = outer ?: throw SocketException("e2e/closed")
        CarrierBudgets.reserve(budget, size + 2)
        try {
            if (socket.queuedWriteBytes > CARRIER_QUEUE_BYTES - size - 2) throw IOException("e2e/outbound_budget")
            socket.write(wireOut, 0, size + 2, deadline())
        } finally { CarrierBudgets.release(budget, size + 2); wireOut.fill(0, 0, size + 2) }
    }
    private fun decryptRecord(maximum: Int): Int {
        val size = record(maximum, 17)
        cipherIn.clearCompat(); cipherIn.put(wireIn, 0, size); cipherIn.flipCompat(); wireIn.fill(0, 0, size)
        plainIn.clearCompat()
        val result = synchronized(engineGate) { engine!!.decrypt(cipherIn, plainIn) }
        receiveCount++
        if (receiveCount % (1L shl 20) == 0L) synchronized(engineGate) { engine!!.rekeyReceive() }
        plainIn.flipCompat()
        return result
    }
    private fun encryptRecord() {
        plainOut.flipCompat(); cipherOut.clearCompat()
        val size = synchronized(engineGate) { engine!!.encrypt(plainOut, cipherOut) }
        sendCount++
        if (sendCount % (1L shl 20) == 0L) synchronized(engineGate) { engine!!.rekeySend() }
        sendRecord(size)
    }
    private fun handshake() {
        val size = record(4096)
        val helloBytes = wireIn.copyOf(size)
        val hello = JsonDocument.parse(helloBytes).root
        if (!JsonDocument.of(hello).canonicalBytes().contentEquals(helloBytes)) throw DshFailure("e2e.hello_invalid")
        hello.exactKeys("magic", "v", "suite", "runtimeId", "installationId", "hostKeyEpoch", "hostStaticPublicKey", "purpose", "routeKind", "dshVersion", "capabilities")
        if (hello.string("magic") != "tokenworks-noise" || hello.integer("v", 1, 1) != 1L || hello.string("suite") != E2eSession.SUITE ||
            hello.string("purpose") != purpose || hello.string("routeKind") != routeKind || !DshProductAccess.isSupportedDshVersion(hello.string("dshVersion")))
            throw DshFailure("e2e.unsupported")
        if (hello.string("runtimeId") != credentials.runtimeId || hello.string("installationId") != credentials.installationId ||
            hello.string("hostStaticPublicKey") != credentials.hostStaticPublicKey || hello.integer("hostKeyEpoch", 1, MAX_SAFE_INTEGER) != credentials.hostKeyEpoch)
            throw DshFailure("e2e.repair_required")
        val capabilities = hello.array("capabilities").map { (it as? JsonString)?.value ?: throw ProtocolException() }
        val expectedCapabilities = if (routeKind in setOf("p2p", "turn")) setOf("native.http.v1", "noise.ik.v1", "transport.webrtc.v1")
            else setOf("native.http.v1", "noise.ik.v1")
        if (capabilities.toSet() != expectedCapabilities || capabilities.size != expectedCapabilities.size) throw DshFailure("e2e.unsupported")
        if (credentials.manifest.string("proofJkt") != signer.publicKey.thumbprint) throw DshFailure("e2e.owner_mismatch")
        val local = credentials.localPrivate()
        engine = try { E2eSession.initiator(local, Contract.base64url(credentials.hostStaticPublicKey), helloBytes) }
        catch (_: UnsupportedOperationException) { throw DshFailure("e2e.unsupported") }
        finally { local.fill(0) }
        plainOut.clearCompat(); plainOut.limitCompat(0); cipherOut.clearCompat()
        sendRecord(engine!!.handshakeWrite(plainOut, cipherOut))
        val ik2 = record(4096)
        cipherIn.clearCompat(); cipherIn.put(wireIn, 0, ik2); cipherIn.flipCompat(); plainIn.clearCompat()
        if (engine!!.handshakeRead(cipherIn, plainIn) != 0) throw DshFailure("e2e.handshake_invalid")
        val binding = engine!!.finishHandshake()
        if (!MessageDigest.isEqual(binding.remoteStatic, Contract.base64url(credentials.hostStaticPublicKey))) throw DshFailure("e2e.pin_mismatch")
        handshakeHash = binding.handshakeHash
        var authFields = jsonObject("type" to "auth".json(), "pairId" to credentials.pairId.json(), "token" to credentials.token.json(),
            "hostKeyEpoch" to credentials.hostKeyEpoch.json(), "deviceKeyEpoch" to credentials.deviceKeyEpoch.json(),
            "proof" to NoiseBindingProof.sign(signer, credentials, handshakeHash, purpose).json())
        relayLease?.let { lease ->
            if (!lease.usable()) throw DshFailure("lease/expired")
            authFields = authFields.with("relayLease" to jsonObject("leaseId" to lease.id.json(), "token" to lease.token.json()))
        }
        val auth = JsonDocument.of(authFields).canonicalBytes()
        if (auth.size > 8192 - 16) throw DshFailure("e2e.auth_bounds")
        plainOut.clearCompat(); plainOut.put(auth); encryptRecord(); auth.fill(0)
        val readySize = decryptRecord(8192)
        val readyBytes = ByteArray(readySize); plainIn.get(readyBytes)
        val response = JsonDocument.parse(readyBytes).root
        if (!JsonDocument.of(response).canonicalBytes().contentEquals(readyBytes)) throw DshFailure("e2e.ready_invalid")
        response.exactKeys("type", "v", "suite", "dshVersion", "runtimeId", "installationId", "pairId", "hostKeyEpoch", "deviceKeyEpoch", "ownerSha256", "bindingSha256", "peerGeneration")
        if (response.string("type") != "ready" || response.integer("v", 1, 1) != 1L || response.string("suite") != E2eSession.SUITE ||
            response.string("dshVersion") != hello.string("dshVersion") || response.string("runtimeId") != credentials.runtimeId ||
            response.string("installationId") != credentials.installationId || response.string("pairId") != credentials.pairId ||
            response.integer("hostKeyEpoch", 1, MAX_SAFE_INTEGER) != credentials.hostKeyEpoch || response.integer("deviceKeyEpoch", 1, MAX_SAFE_INTEGER) != credentials.deviceKeyEpoch ||
            response.string("ownerSha256") != credentials.ownerSha256 || response.string("bindingSha256") != credentials.bindingSha256)
            throw DshFailure("e2e.binding_mismatch")
        peerGeneration = lanIdentity(response.string("peerGeneration"))
        plainIn.clearCompat(); plainIn.limitCompat(0); ready = true; absoluteDeadline = Long.MAX_VALUE
    }
    private val input = object : InputStream() {
        override fun read(): Int { val one = ByteArray(1); return if (read(one) == -1) -1 else one[0].toInt() and 255 }
        override fun read(target: ByteArray, offset: Int, length: Int): Int = synchronized(readGate) {
            require(offset >= 0 && length >= 0 && offset <= target.size - length)
            if (length == 0) return@synchronized 0
            try {
                live()
                while (!plainIn.hasRemaining()) {
                    if (remoteClosed) return@synchronized -1
                    val size = decryptRecord(65535)
                    when (plainIn.get().toInt() and 255) {
                        0 -> { if (size !in 2..65519) throw IOException("e2e/data_bounds"); receiveBytes = Math.addExact(receiveBytes, (size - 1).toLong()) }
                        1 -> {
                            if (size != 17 || plainIn.long != receiveBytes || plainIn.long != receiveCount) throw IOException("e2e/close_invalid")
                            inbound.seal()
                            remoteClosed = true
                            return@synchronized -1
                        }
                        else -> throw IOException("e2e/record_kind")
                    }
                }
                val count = minOf(length, plainIn.remaining())
                headerRemaining?.let { if (count > it) throw IOException("e2e/header_budget"); headerRemaining = it - count }
                plainIn.get(target, offset, count); count
            } catch (error: Exception) { close(); throw error }
        }
        override fun close() { this@CarrierSocket.close() }
    }
    private val output = object : OutputStream() {
        override fun write(value: Int) { write(byteArrayOf(value.toByte())) }
        override fun write(source: ByteArray, offset: Int, length: Int) = synchronized(sendGate) {
            require(offset >= 0 && length >= 0 && offset <= source.size - length)
            try {
                live(); if (!ready || outputClosed) throw SocketException("e2e/not_ready")
                var at = offset
                while (at < offset + length) {
                    val count = minOf(65518, offset + length - at)
                    plainOut.clearCompat(); plainOut.put(0); plainOut.put(source, at, count); encryptRecord()
                    sendBytes = Math.addExact(sendBytes, count.toLong()); at += count
                    consumed?.invoke(count)
                }
            } catch (error: Exception) { close(); throw error }
        }
        override fun close() { shutdownOutput() }
    }
    fun limitHeaders(bytes: Int?) { headerRemaining = bytes }
    override fun getInputStream(): InputStream { live(); if (!ready) throw SocketException("e2e/not_ready"); return input }
    override fun getOutputStream(): OutputStream { live(); if (!ready) throw SocketException("e2e/not_ready"); return output }
    override fun connect(endpoint: SocketAddress?) { throw SocketException("e2e/no_tcp") }
    override fun connect(endpoint: SocketAddress?, timeout: Int) { throw SocketException("e2e/no_tcp") }
    override fun bind(bindpoint: SocketAddress?) { throw SocketException("e2e/no_tcp") }
    override fun isConnected() = ready && !ended.get()
    override fun isBound() = isConnected
    override fun isClosed() = ended.get()
    override fun isInputShutdown() = remoteClosed || ended.get()
    override fun isOutputShutdown() = outputClosed || ended.get()
    override fun getInetAddress(): InetAddress? = null
    override fun getLocalAddress(): InetAddress? = null
    override fun getRemoteSocketAddress(): SocketAddress? = null
    override fun getLocalSocketAddress(): SocketAddress? = null
    override fun getPort() = -1
    override fun getLocalPort() = -1
    override fun setSoTimeout(value: Int) { require(value >= 0); timeout = value }
    fun reserveLibrary(bytes: Int): Boolean = synchronized(creditGate) {
        if (ended.get() || bytes < 0 || libraryBytes > CARRIER_QUEUE_BYTES - 65537 - bytes) return@synchronized false
        try { CarrierBudgets.reserve(budget, bytes) } catch (_: IOException) { return@synchronized false }
        libraryBytes += bytes; true
    }
    fun releaseLibrary(bytes: Int) = synchronized(creditGate) {
        val released = minOf(libraryBytes, bytes); libraryBytes -= released; CarrierBudgets.release(budget, released)
    }
    override fun getSoTimeout() = timeout
    override fun setTcpNoDelay(value: Boolean) { live() }
    override fun getTcpNoDelay() = true
    override fun setKeepAlive(value: Boolean) { live() }
    override fun getKeepAlive() = false
    override fun setReuseAddress(value: Boolean) { live() }
    override fun getReuseAddress() = false
    override fun setReceiveBufferSize(value: Int) { require(value > 0); live() }
    override fun getReceiveBufferSize() = 8192
    override fun setSendBufferSize(value: Int) { require(value > 0); live() }
    override fun getSendBufferSize() = 8192
    override fun setSoLinger(on: Boolean, linger: Int) { live() }
    override fun getSoLinger() = -1
    override fun sendUrgentData(data: Int) { throw SocketException("e2e/no_tcp") }
    override fun shutdownInput() { close() }
    override fun shutdownOutput() = synchronized(sendGate) {
        if (!outputClosed && !ended.get() && ready) {
            try { plainOut.clearCompat(); plainOut.put(1); plainOut.putLong(sendBytes); plainOut.putLong(sendCount + 1); encryptRecord(); outputClosed = true }
            catch (error: Exception) { close(); throw error }
        }
    }
    override fun close() {
        if (!ended.compareAndSet(false, true)) return
        inbound.end(SocketException("e2e/closed")); outer?.close(); inbound.discard()
        synchronized(engineGate) { engine?.close(); engine = null }
        synchronized(creditGate) { CarrierBudgets.release(budget, libraryBytes); libraryBytes = 0 }
        CarrierBudgets.close(budget)
        consumed = null
    }
    companion object {
        suspend fun open(client: OkHttpClient, request: Request, credentials: LanCredentials, signer: DeviceProofSigner, routeKind: String, purpose: String,
            relayLease: DshLease? = null): CarrierSocket =
            open(WssCarrierOuterDialer(client, request), credentials, signer, routeKind, purpose, relayLease)

        suspend fun open(dialer: CarrierOuterDialer, credentials: LanCredentials, signer: DeviceProofSigner, routeKind: String, purpose: String,
            relayLease: DshLease? = null): CarrierSocket {
            require(routeKind in setOf("lan", "relay", "p2p", "turn") && purpose in setOf("native", "pairing-control"))
            if (purpose == "native" && credentials.manifest.fields["relay"] is JsonObject && relayLease == null)
                throw DshFailure("e2e.relay_authority_required")
            if (relayLease != null && !relayLease.usable()) throw DshFailure("lease/expired")
            val socket = CarrierSocket(credentials, signer, routeKind, purpose, relayLease)
            socket.absoluteDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            try {
                if (routeKind in setOf("p2p", "turn")) {
                    socket.outer = withTimeout(20_000) { dialer.open(socket.receiver, purpose) }
                    socket.absoluteDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                    return withTimeout(15_000) { runInterruptible(Dispatchers.IO) { socket.handshake(); socket } }
                }
                return withTimeout(15_000) {
                    socket.outer = dialer.open(socket.receiver, purpose)
                    runInterruptible(Dispatchers.IO) { socket.handshake(); socket }
                }
            } catch (_: TimeoutCancellationException) {
                socket.close()
                currentCoroutineContext().ensureActive()
                throw SocketTimeoutException("network.unavailable")
            } catch (error: Exception) { socket.close(); throw error }
        }
    }
}

internal object NoiseBindingProof {
    fun sign(signer: DeviceProofSigner, credentials: LanCredentials, hash: ByteArray, purpose: String): String {
        val header = jsonObject("typ" to "twm-noise-bind+jwt".json(), "alg" to "ES256".json(), "jwk" to signer.publicKey.document.root)
        val claims = jsonObject("v" to 1L.json(), "h" to Contract.base64url(hash).json(), "runtime_id" to credentials.runtimeId.json(),
            "installation_id" to credentials.installationId.json(), "pair_id" to credentials.pairId.json(), "host_static" to credentials.hostStaticPublicKey.json(),
            "device_static" to credentials.deviceStaticPublicKey.json(), "host_key_epoch" to credentials.hostKeyEpoch.json(), "device_key_epoch" to credentials.deviceKeyEpoch.json(),
            "owner_sha256" to credentials.ownerSha256.json(), "purpose" to purpose.json(), "iat" to (System.currentTimeMillis() / 1000).json(),
            "jti" to DeviceProofs.randomId().json(), "ath" to Contract.base64url(MessageDigest.getInstance("SHA-256").digest(credentials.token.toByteArray())).json())
        val input = Contract.base64url(JsonDocument.of(header).canonicalBytes()) + "." + Contract.base64url(JsonDocument.of(claims).canonicalBytes())
        val signature = signer.signJose(input.toByteArray(Charsets.US_ASCII))
        if (signature.size != 64) throw DeviceProofException()
        return input + "." + Contract.base64url(signature)
    }
}

internal fun interface CarrierDialer { suspend fun open(purpose: String): CarrierSocket }

internal class LanCarrierDialer(private val credentials: LanCredentials, private val signer: DeviceProofSigner, client: OkHttpClient,
    private val relayLease: DshLease? = null) : CarrierDialer {
    private val http = LanHttp(credentials.origin, credentials.tlsPin, client)
    override suspend fun open(purpose: String): CarrierSocket {
        val url = http.url("/carrier")
        var nonce: String? = null
        repeat(2) { attempt ->
            val proof = DeviceProofs.dpop(signer, "GET", url.toString(), System.currentTimeMillis(), nonce = nonce, accessToken = credentials.token)
            val request = Request.Builder().url(url).header("Authorization", "DPoP ${credentials.token}").header("DPoP", proof.compact)
                .header("X-TokenWorks-Device-Id", credentials.pairId).header("X-TokenWorks-Carrier-Purpose", purpose).build()
            try { return CarrierSocket.open(http.client, request, credentials, signer, "lan", purpose, relayLease) }
            catch (error: CarrierUpgradeException) {
                if (attempt != 0 || error.status != 401 || !error.nonceChallenge || error.nonce == null) throw error
                nonce = error.nonce
            }
        }
        throw LanPairingException("lan.auth_required")
    }
}

// JVM17's ByteBuffer covariant setters are absent from Android API26; use the inherited Buffer ABI.
private fun ByteBuffer.clearCompat() { (this as java.nio.Buffer).clear() }
private fun ByteBuffer.flipCompat() { (this as java.nio.Buffer).flip() }
private fun ByteBuffer.limitCompat(limit: Int) { (this as java.nio.Buffer).limit(limit) }
