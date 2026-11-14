package cn.edu.ustc.tokenworks

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.hc.core5.http.HttpVersion
import org.apache.hc.core5.http.config.Http1Config
import org.apache.hc.core5.http.impl.io.DefaultHttpResponseParser
import org.apache.hc.core5.http.impl.io.SessionInputBufferImpl
import org.java_websocket.WebSocket
import org.java_websocket.WebSocketImpl
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.enums.HandshakeState
import org.java_websocket.enums.Opcode
import org.java_websocket.exceptions.IncompleteHandshakeException
import org.java_websocket.exceptions.InvalidDataException
import org.java_websocket.exceptions.InvalidHandshakeException
import org.java_websocket.framing.Framedata
import org.java_websocket.framing.PongFrame
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.Handshakedata
import org.java_websocket.handshake.HandshakeImpl1Server
import org.java_websocket.handshake.ServerHandshake
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import javax.net.ssl.SSLException

internal class CarrierUpgradeException(val status: Int, val nonce: String?, val nonceChallenge: Boolean) : IOException(
    when (status) { 401 -> "auth.proof_invalid"; 403, 410 -> "access.denied"; 404, 426 -> "e2e.unsupported"; else -> "network.upgrade_failed" })

/** Public bounded codec. TLS trust, hostname checks, pinning, DNS and proxy configuration come from the original client. */
internal class WssCarrierOuterDialer(private val options: OkHttpClient, private val request: Request) : CarrierOuterDialer {
    override suspend fun open(receiver: CarrierReceiver, purpose: String): CarrierOuterSocket {
        val state = WssConnectionState(receiver)
        val headers = request.headers.names().associateWith { name -> request.headers.values(name).singleOrNull() ?: throw IOException("e2e/outer_headers") }
        val socket = object : WebSocketClient(URI(request.url.toString().replaceFirst("https://", "wss://")), BoundedOuterDraft(state), headers, 15_000) {
            override fun reconnect() { throw IllegalStateException("e2e/reconnect_forbidden") }
            override fun reconnectBlocking(): Boolean { throw IllegalStateException("e2e/reconnect_forbidden") }
            override fun onOpen(handshake: ServerHandshake) { state.opened() }
            override fun onMessage(bytes: ByteBuffer) {
                if (bytes.remaining() !in 1..65537) { state.fail(IOException("e2e/outer_binary_bounds")); return }
                try { receiver.receive(bytes) }
                catch (_: Exception) { state.fail(IOException("e2e/inbound_budget")) }
            }
            override fun onMessage(text: String) { state.fail(IOException("e2e/binary_required")) }
            override fun onClose(code: Int, reason: String, remote: Boolean) { state.fail(EOFException("e2e/truncated")) }
            override fun onError(error: Exception) { state.fail(when (error) {
                is SSLException -> IOException("lan.tls_failed", IllegalStateException(error.javaClass.name))
                is InvalidDataException -> IOException("e2e/outer_invalid", IllegalStateException(error.javaClass.name))
                else -> IOException("network.unavailable", IllegalStateException(error.javaClass.name))
            }) }
            override fun onWebsocketPing(connection: WebSocket, frame: Framedata) {
                if (!state.pong(frame.payloadData.remaining()) { sendFrame(PongFrame().also { it.setPayload(frame.payloadData) }) }) state.fail(IOException("e2e/control_budget"))
            }
        }
        state.client = socket
        socket.isDaemon = true
        socket.connectionLostTimeout = 0
        // The selected original proxy is applied by CarrierTlsDialer before the verified real TLS socket is supplied.
        socket.setProxy(Proxy.NO_PROXY)
        socket.setDnsResolver { throw java.net.UnknownHostException("e2e/no_codec_dns") }
        socket.setSocketFactory(object : SocketFactory() {
            private val once = AtomicBoolean()
            override fun createSocket(): Socket {
                if (!once.compareAndSet(false, true) || !connects.tryAcquire()) throw IOException("e2e/connect_budget")
                try {
                    val tls = CarrierTlsDialer.connect(options, request, state.deadline, state::own)
                    state.own(tls)
                    return CarrierTlsSocket(tls, state)
                } finally { connects.release() }
            }
            override fun createSocket(host: String?, port: Int): Socket = throw SocketException("e2e/no_codec_tcp")
            override fun createSocket(host: String?, port: Int, local: InetAddress?, localPort: Int): Socket = throw SocketException("e2e/no_codec_tcp")
            override fun createSocket(host: InetAddress?, port: Int): Socket = throw SocketException("e2e/no_codec_tcp")
            override fun createSocket(host: InetAddress?, port: Int, local: InetAddress?, localPort: Int): Socket = throw SocketException("e2e/no_codec_tcp")
        })
        try { socket.connect(); withTimeout(15_000) { state.ready.await() }; return state }
        catch (error: Exception) { state.close(); throw error }
    }
    companion object { private val connects = Semaphore(8) }
}

/** Count-only TLS stream credits; no HTTP or WebSocket syntax is decoded here. */
internal class WssConnectionState(private val receiver: CarrierReceiver) : CarrierOuterSocket {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
    val ready = CompletableDeferred<Unit>()
    val writeGate = Any()
    private val gate = Object()
    private val ended = AtomicBoolean()
    lateinit var client: WebSocketClient
    @Volatile private var socket: Socket? = null
    private var headersRead = 0
    private var upgraded = false
    private var pendingReadWire = 0L
    private var sendReady = false
    @Volatile var responseNonce: String? = null
        private set
    private var produced = 0L
    private var consumed = 0L
    private var queuedAt = 0L
    private var lastPing = 0L
    private var failure: IOException? = null
    private val watchdog = timers.scheduleAtFixedRate({
        val expired = synchronized(gate) { queuedAt != 0L && System.nanoTime() - queuedAt >= TimeUnit.SECONDS.toNanos(30) }
        if (expired) fail(SocketTimeoutException("e2e/write_timeout"))
    }, 1, 1, TimeUnit.SECONDS)
    override val queuedWriteBytes: Long get() = synchronized(gate) { produced - consumed }
    fun own(socket: Socket) {
        this.socket = socket
        if (ended.get()) { socket.close(); throw SocketException("e2e/closed") }
    }
    fun readAllowance(): Int {
        val allowed = synchronized(gate) {
            if (ended.get()) throw failure ?: SocketException("e2e/closed")
            if (upgraded) 65551 else 32768 - headersRead
        }
        if (allowed <= 0) { val error = IOException("e2e/upgrade_header_budget"); fail(error); throw error }
        return allowed
    }
    fun readBytes(count: Int) = synchronized(gate) {
        if (!upgraded) headersRead += count else {
            pendingReadWire += count
            socket?.soTimeout = 30_000
        }
    }
    fun upgradeComplete(unconsumed: Int, nonce: String?) = synchronized(gate) {
        upgraded = true; pendingReadWire = unconsumed.toLong(); responseNonce = nonce
        socket?.soTimeout = if (pendingReadWire == 0L) 0 else 30_000
    }
    fun frameConsumed(size: Int) = synchronized(gate) {
        val wire = size.toLong() + if (size < 126) 2 else if (size <= 65535) 4 else 10
        pendingReadWire = maxOf(0L, pendingReadWire - wire)
        socket?.soTimeout = if (pendingReadWire == 0L) 0 else 30_000
    }
    fun opened() {
        synchronized(writeGate) { synchronized(gate) { sendReady = true } }
        ready.complete(Unit)
    }
    fun written(bytes: Int) = synchronized(gate) {
        if (sendReady) {
            consumed = minOf(produced, consumed + bytes)
            if (consumed == produced) queuedAt = 0L
            gate.notifyAll()
        }
    }
    private fun reserve(bytes: Int): Long {
        if (ended.get()) throw failure ?: SocketException("e2e/closed")
        if (bytes < 0 || produced - consumed > CARRIER_QUEUE_BYTES - 131 - bytes) throw IOException("e2e/outbound_budget")
        if (produced == consumed) queuedAt = System.nanoTime()
        produced = Math.addExact(produced, bytes.toLong())
        return produced
    }
    override fun write(bytes: ByteArray, offset: Int, length: Int, deadlineNanos: Long) {
        require(offset >= 0 && length in 1..65537 && offset <= bytes.size - length)
        val frameBytes = length + if (length < 126) 6 else if (length <= 65535) 8 else 14
        val ticket = try { synchronized(gate) {
            val ticket = reserve(frameBytes)
            client.send(ByteBuffer.wrap(bytes, offset, length))
            ticket
        } } catch (_: Exception) { fail(IOException("network.unavailable")); throw failure!! }
        try {
            synchronized(gate) {
                while (consumed < ticket) {
                    if (ended.get()) throw failure ?: SocketException("e2e/closed")
                    val left = deadlineNanos - System.nanoTime()
                    if (left <= 0) throw SocketTimeoutException("e2e/write_timeout")
                    TimeUnit.NANOSECONDS.timedWait(gate, left)
                }
            }
        } catch (error: Exception) { close(); throw error }
    }
    fun pong(payloadBytes: Int, enqueue: () -> Unit): Boolean = synchronized(gate) {
        val now = System.nanoTime()
        if (payloadBytes > 125 || now - lastPing < TimeUnit.SECONDS.toNanos(1)) return@synchronized false
        lastPing = now
        try { reserve(payloadBytes + 6); enqueue(); true } catch (_: Exception) { false }
    }
    fun closing(enqueue: () -> Unit) = synchronized(gate) {
        // The mandatory echo can contain at most 125 payload bytes plus the client mask/header.
        reserve(131); enqueue()
    }
    fun fail(error: IOException) {
        val chosen = synchronized(gate) { if (failure == null) failure = error; gate.notifyAll(); failure!! }
        ready.completeExceptionally(chosen)
        receiver.fail(chosen)
        close()
    }
    override fun close() {
        if (!ended.compareAndSet(false, true)) return
        watchdog.cancel(false)
        synchronized(gate) { gate.notifyAll() }
        runCatching { socket?.close() }
        if (::client.isInitialized) {
            client.closeConnection(1006, "")
            (client.connection as? WebSocketImpl)?.outQueue?.clear()
        }
    }
    companion object {
        private val timers = ScheduledThreadPoolExecutor(1) { runnable -> Thread(runnable, "carrier-write-budget").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true }
    }
}

/** Library HTTP parsing and RFC6455 verification; the only custom policy is strict bounded binary records. */
internal class BoundedOuterDraft(private val state: WssConnectionState) : Draft_6455(emptyList(), 65537) {
    private var remainingAfterHeaders = 0
    private var challengeNonce: String? = null
    private var nonceChallenge = false
    override fun copyInstance(): Draft = BoundedOuterDraft(state)
    override fun translateHandshake(buffer: ByteBuffer): Handshakedata {
        val start = buffer.position()
        val available = buffer.remaining()
        val source = object : InputStream() {
            override fun read(): Int { if (!buffer.hasRemaining()) throw IncompleteUpgrade(); return buffer.get().toInt() and 255 }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (!buffer.hasRemaining()) throw IncompleteUpgrade()
                val count = minOf(length, buffer.remaining()); buffer.get(bytes, offset, count); return count
            }
        }
        try {
            // One-byte buffering preserves coalesced WS bytes. The direct parser's count includes the status line.
            val response = DefaultHttpResponseParser(Http1Config.custom().setMaxLineLength(8192).setMaxHeaderCount(100).setMaxEmptyLineCount(1).build())
                .parse(SessionInputBufferImpl(1), source)
            if (response.version != HttpVersion.HTTP_1_1) reject("e2e/upgrade_version")
            val handshake = HandshakeImpl1Server()
            handshake.httpStatus = response.code.toShort(); handshake.httpStatusMessage = response.reasonPhrase ?: ""
            response.headers.forEach { header ->
                handshake.put(header.name, if (handshake.hasFieldValue(header.name)) handshake.getFieldValue(header.name) + "; " + header.value else header.value)
            }
            challengeNonce = response.getHeaders("DPoP-Nonce").singleOrNull()?.value?.let { runCatching { DeviceProofs.nonce(it) }.getOrNull() }
            nonceChallenge = response.getHeaders("WWW-Authenticate").any { it.value == "DPoP error=\"use_dpop_nonce\"" }
            remainingAfterHeaders = buffer.remaining()
            return handshake
        } catch (_: IncompleteUpgrade) {
            (buffer as java.nio.Buffer).position(start)
            if (available >= 32768) reject("e2e/upgrade_header_budget")
            throw IncompleteHandshakeException(minOf(32768, buffer.capacity() + 8192))
        } catch (error: InvalidHandshakeException) { state.fail(IOException("e2e/upgrade_invalid")); throw error }
        catch (error: Exception) {
            state.fail(IOException("e2e/upgrade_invalid", IllegalStateException(error.javaClass.name)))
            throw InvalidHandshakeException("e2e/upgrade_invalid")
        }
    }
    override fun acceptHandshakeAsClient(request: ClientHandshake, response: ServerHandshake): HandshakeState {
        if (response.httpStatus.toInt() != 101) {
            state.fail(CarrierUpgradeException(response.httpStatus.toInt(), challengeNonce, nonceChallenge))
            throw InvalidHandshakeException("e2e/upgrade_refused")
        }
        if (response.hasFieldValue("Sec-WebSocket-Extensions")) reject("e2e/compression_forbidden")
        val result = super.acceptHandshakeAsClient(request, response)
        if (result != HandshakeState.MATCHED) reject("e2e/upgrade_rfc6455")
        state.upgradeComplete(remainingAfterHeaders, challengeNonce)
        return result
    }
    override fun processFrame(socket: WebSocketImpl, frame: Framedata) {
        if (!frame.isFin || frame.opcode == Opcode.CONTINUOUS || frame.opcode == Opcode.TEXT || frame.transfereMasked || frame.isRSV1 || frame.isRSV2 || frame.isRSV3) {
            state.fail(IOException("e2e/outer_frame_policy"))
            throw InvalidDataException(1003, "e2e/outer_frame_policy")
        }
        val payloadBytes = frame.payloadData.remaining()
        if (frame.opcode == Opcode.CLOSING) state.closing { super.processFrame(socket, frame) } else super.processFrame(socket, frame)
        state.frameConsumed(payloadBytes)
    }
    override fun translateFrame(buffer: ByteBuffer): MutableList<Framedata> = try { super.translateFrame(buffer) }
    catch (error: InvalidDataException) { state.fail(IOException("e2e/outer_frame_bounds")); throw error }
    private fun reject(code: String): Nothing { state.fail(IOException(code)); throw InvalidHandshakeException(code) }
    private class IncompleteUpgrade : EOFException()
}
