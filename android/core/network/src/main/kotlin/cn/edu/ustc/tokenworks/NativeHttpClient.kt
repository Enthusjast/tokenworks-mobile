package cn.edu.ustc.tokenworks

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import org.apache.hc.core5.http.ContentType
import org.apache.hc.core5.http.HttpHost
import org.apache.hc.core5.http.config.Http1Config
import org.apache.hc.core5.http.impl.io.DefaultBHttpClientConnection
import org.apache.hc.core5.http.impl.io.HttpRequestExecutor
import org.apache.hc.core5.http.io.entity.ByteArrayEntity
import org.apache.hc.core5.http.io.entity.InputStreamEntity
import org.apache.hc.core5.http.message.BasicClassicHttpRequest
import org.apache.hc.core5.http.protocol.HttpCoreContext
import org.apache.hc.core5.http.protocol.HttpProcessorBuilder
import org.apache.hc.core5.http.protocol.RequestContent
import org.apache.hc.core5.http.protocol.RequestTargetHost
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.framing.Framedata
import org.java_websocket.framing.PongFrame
import org.java_websocket.handshake.ServerHandshake
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory

/** One immutable pairing/Peer generation. No retries, redirects, routing, cookies, TCP or TLS emulation. */
internal class NativeHttpClient(private val dialer: CarrierDialer) : AutoCloseable {
    private class HttpConnection(val socket: CarrierSocket, val http: DefaultBHttpClientConnection)
    private val gate = Any()
    private val ended = AtomicBoolean()
    val retired: Boolean get() = ended.get()
    private val sockets = ConcurrentHashMap.newKeySet<CarrierSocket>()
    private val opening = ConcurrentHashMap.newKeySet<Job>()
    private val muxes = ConcurrentHashMap.newKeySet<DshMux>()
    private val idle = ArrayDeque<HttpConnection>()
    private var peerGeneration: String? = null
    private val config = Http1Config.custom().setBufferSize(8192).setMaxHeaderCount(100).setMaxLineLength(8192).setMaxEmptyLineCount(0).build()
    private val processor = HttpProcessorBuilder.create().add(RequestContent()).add(RequestTargetHost()).build()
    private val executor = HttpRequestExecutor(config, null, null)
    fun live() { if (ended.get()) throw DshFailure("transport/changed") }
    private suspend fun socket(purpose: String = "native"): CarrierSocket = coroutineScope {
        val openingJob = coroutineContext.job
        synchronized(gate) { live(); opening.add(openingJob) }
        try {
            val socket = dialer.open(purpose)
            try {
                synchronized(gate) {
                    live()
                    if (purpose == "native") {
                        val previous = peerGeneration
                        if (previous != null && previous != socket.peerGeneration) { close(); throw DshFailure("transport/changed") }
                        peerGeneration = socket.peerGeneration
                    }
                    sockets.add(socket)
                }
                socket
            } catch (error: Exception) { socket.close(); throw error }
        } finally { opening.remove(openingJob) }
    }
    suspend fun probe() {
        val connection = acquire()
        synchronized(gate) { if (!ended.get()) idle.addLast(connection) else connection.socket.close() }
    }
    private suspend fun acquire(): HttpConnection {
        synchronized(gate) { live(); if (idle.isNotEmpty()) return idle.removeFirst() }
        val socket = socket()
        try { return HttpConnection(socket, DefaultBHttpClientConnection(config).also { it.bind(socket) }) }
        catch (error: Exception) { sockets.remove(socket); socket.close(); throw error }
    }
    private fun retire(connection: HttpConnection) { sockets.remove(connection.socket); connection.socket.close() }
    private suspend fun <T> exchange(request: BasicClassicHttpRequest, read: (org.apache.hc.core5.http.ClassicHttpResponse) -> T): T {
        val connection = acquire()
        var reusable = false
        try {
            return runInterruptible(Dispatchers.IO) {
                live()
                connection.socket.limitHeaders(32768)
                val context = HttpCoreContext.create()
                executor.preProcess(request, processor, context)
                executor.execute(request, connection.http, context).use { response ->
                    connection.socket.limitHeaders(null)
                    executor.postProcess(response, processor, context)
                    val result = read(response)
                    live()
                    reusable = connection.http.isConsistent && executor.keepAlive(request, response, connection.http, context)
                    result
                }
            }
        } finally {
            connection.socket.limitHeaders(null)
            synchronized(gate) { if (reusable && !ended.get()) idle.addLast(connection) else retire(connection) }
        }
    }
    private fun request(method: String, path: String): BasicClassicHttpRequest {
        exactPath(path)
        return BasicClassicHttpRequest(method, HttpHost("http", "dsh-native.invalid", 80), path).also {
            it.setHeader("Cache-Control", "no-store"); it.setHeader("Accept-Encoding", "identity")
        }
    }
    private fun json(response: org.apache.hc.core5.http.ClassicHttpResponse): ByteArray {
        if (response.code !in 200..299) throw DshFailure(when (response.code) { 401 -> "gateway/unauthorized"; 403 -> "gateway/forbidden"; 404, 426 -> "gateway/unsupported"; else -> "gateway/http_error" })
        val entity = response.entity ?: throw DshFailure("gateway/invalid_response")
        val type = entity.contentType?.substringBefore(';')?.trim()?.lowercase()
        if (type != "application/json" && type?.endsWith("+json") != true) throw DshFailure("gateway/invalid_response")
        if (entity.contentLength > SESSION_PAYLOAD_BYTES) throw DshFailure("gateway/response_too_large")
        return entity.content.use { boundedBytes(it, SESSION_PAYLOAD_BYTES) }
    }
    suspend fun native(endpoint: String, args: JsonObject): JsonValue {
        if (endpoint != DSH_EVENTS_RESULT_ENDPOINT && !endpoint.matches(Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]*/[A-Za-z0-9_.:-]+$"))) throw DshFailure("protocol/invalid")
        val rpcId = UUID.randomUUID().toString()
        val envelope = jsonObject("type" to "client-request".json(), "rpcId" to rpcId.json(), "method" to endpoint.json(), "payload" to jsonObject("args" to args))
        val request = request("POST", "/api/$endpoint")
        request.entity = ByteArrayEntity(SessionJson.encode(envelope), ContentType.APPLICATION_JSON)
        request.setHeader("Accept", "application/json")
        return exchange(request) { response -> nativeResult(SessionJson.parse(json(response)), rpcId) }
    }
    private fun nativeResult(root: JsonObject, rpcId: String): JsonValue {
        if (root.fields.keys != setOf("type", "rpcId", "result") || root.string("type") != "server-response" || root.string("rpcId") != rpcId) throw DshFailure("gateway/invalid_response")
        val result = root.obj("result")
        return when {
            result["ok"] == JsonBoolean(true) && result.fields.keys.all { it in setOf("ok", "value") } -> result.fields["value"] ?: JsonNull
            result["ok"] == JsonBoolean(false) && result.fields.keys == setOf("ok", "error") -> {
                val code = runCatching { result.obj("error").string("code") }.getOrDefault("gateway/internal")
                throw DshFailure(if (code.matches(Regex("^[a-z0-9][a-z0-9/._-]{0,63}$"))) code else "gateway/internal")
            }
            else -> throw DshFailure("gateway/invalid_response")
        }
    }
    suspend fun download(path: String, sink: OutputStream, maxBytes: Long, range: DshByteRange?): DshDownload {
        require(maxBytes > 0)
        val request = request("GET", path)
        request.setHeader("Accept", "*/*"); range?.let { request.setHeader("Range", it.header()) }
        return exchange(request) { response ->
            if (response.code != if (range == null) 200 else 206) throw DshFailure("files/http_error")
            if (response.getHeaders("Content-Encoding").any { it.value != "identity" }) throw DshFailure("files/encoding_invalid")
            val contentRange = response.getHeaders("Content-Range").singleOrNull()?.value
            if (range != null) {
                val parts = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)").matchEntire(contentRange ?: "") ?: throw DshFailure("files/range_invalid")
                val start = parts.groupValues[1].toLongOrNull() ?: throw DshFailure("files/range_invalid")
                val end = parts.groupValues[2].toLongOrNull() ?: throw DshFailure("files/range_invalid")
                if (start != range.start || end < start || range.endInclusive?.let { end > it } == true) throw DshFailure("files/range_invalid")
            }
            val entity = response.entity ?: throw DshFailure("files/empty_response")
            if (entity.contentLength > maxBytes) throw DshFailure("files/too_large")
            var copied = 0L
            val buffer = ByteArray(32 * 1024)
            entity.content.use { source ->
                while (true) {
                    live(); val count = source.read(buffer); if (count == -1) break
                    if (count > maxBytes - copied) throw DshFailure("files/too_large")
                    sink.write(buffer, 0, count); copied += count
                }
            }
            if (entity.contentLength >= 0 && copied != entity.contentLength) throw DshFailure("files/truncated")
            if (range != null) {
                val parts = contentRange!!.substringAfter("bytes ").substringBefore('/').split('-')
                if (copied != parts[1].toLong() - parts[0].toLong() + 1) throw DshFailure("files/range_invalid")
            }
            DshDownload(entity.contentType?.substringBefore(';')?.lowercase() ?: "application/octet-stream", copied,
                response.getHeaders("ETag").singleOrNull()?.value, contentRange)
        }
    }
    suspend fun upload(path: String, mediaType: String, source: InputStream, sizeBytes: Long): JsonValue {
        require(sizeBytes in 0..200_000_000L)
        if (sizeBytes == 0L && source.read() != -1) throw IOException("files/source_length")
        val request = request("POST", path)
        val bounded = object : FilterInputStream(source) {
            private var left = sizeBytes
            override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255 }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (left == 0L) return -1
                val count = super.read(bytes, offset, minOf(length.toLong(), left).toInt())
                if (count < 0) throw IOException("files/truncated_source")
                left -= count
                if (left == 0L && source.read() != -1) throw IOException("files/source_length")
                return count
            }
            override fun close() { /* Source ownership remains with the caller. */ }
        }
        request.entity = InputStreamEntity(bounded, sizeBytes, ContentType.parse(mediaType))
        request.setHeader("Accept", "application/json")
        return SessionJson.parse(exchange(request, ::json))
    }
    suspend fun control(method: String, path: String, body: JsonObject? = null, proof: (CarrierSocket) -> String): JsonObject {
        require(method in setOf("GET", "POST", "DELETE", "PUT") && (path in setOf("/pair/identity", "/pair", "/pair/noise-key") ||
            path.matches(Regex("^/pair/rtc/[A-Za-z0-9/_-]{1,160}$"))))
        val socket = socket("pairing-control")
        val connection = HttpConnection(socket, DefaultBHttpClientConnection(config).also { it.bind(socket) })
        try {
            return runInterruptible(Dispatchers.IO) {
                val request = BasicClassicHttpRequest(method, HttpHost("http", "dsh-native.invalid", 80), path)
                request.setHeader("Connection", "close"); request.setHeader("DPoP", proof(socket))
                body?.let { request.entity = ByteArrayEntity(JsonDocument.of(it).canonicalBytes(), ContentType.APPLICATION_JSON) }
                val context = HttpCoreContext.create(); socket.limitHeaders(32768)
                executor.preProcess(request, processor, context)
                executor.execute(request, connection.http, context).use { response ->
                    socket.limitHeaders(null)
                    if (path.startsWith("/pair/rtc/") && response.code !in 200..299) {
                        val code = response.entity?.takeIf {
                            it.contentType?.substringBefore(';')?.trim()?.lowercase() == "application/json"
                        }?.content?.use { source ->
                            JsonDocument.parse(boundedBytes(source, 8192)).root.fields["code"] as? JsonString
                        }?.value
                        if (response.code == 404 && code == "rtc/unsupported") throw DshFailure("rtc.disabled")
                        if (response.code == 429 && code == "rtc/pc-budget" ||
                            response.code == 503 && code == "rtc/unavailable") throw DshFailure("rtc.unavailable")
                        throw DshFailure(when (response.code) {
                            401 -> "gateway/unauthorized"
                            403 -> "gateway/forbidden"
                            else -> "gateway/http_error"
                        })
                    }
                    JsonDocument.parse(json(response)).root
                }
            }
        } finally { retire(connection) }
    }
    suspend fun openMux(): DshMux {
        val carrier = socket()
        val opened = CompletableDeferred<DshMux>()
        val mux = AtomicReference<DshMux?>()
        val credits = SocketWriteCredits(carrier)
        val client = object : WebSocketClient(URI("ws://dsh-native.invalid/api/remote.mux"), Draft_6455(emptyList(), WireBudget.FRAME_BYTES), emptyMap(), 15_000) {
            override fun reconnect() { throw IllegalStateException("e2e/reconnect_forbidden") }
            override fun reconnectBlocking(): Boolean { throw IllegalStateException("e2e/reconnect_forbidden") }
            override fun onOpen(handshake: ServerHandshake) {
                carrier.limitHeaders(null)
                val created = DshMux(
                    { text -> credits.sendText(text) { send(text) } },
                    { _, _ -> close(1000, "") },
                    queuedWriteBytes = { credits.queued() },
                )
                mux.set(created); muxes.add(created)
                if (!opened.complete(created)) created.close()
            }
            override fun onMessage(message: String) { mux.get()?.receive(message) ?: carrier.close() }
            override fun onMessage(bytes: ByteBuffer) { mux.get()?.fail("wire/binary_forbidden"); carrier.close() }
            override fun onClose(code: Int, reason: String, remote: Boolean) { mux.get()?.fail("wire/disconnected"); opened.completeExceptionally(DshFailure("wire/disconnected")); sockets.remove(carrier); carrier.close() }
            override fun onError(error: Exception) { mux.get()?.fail("wire/disconnected"); opened.completeExceptionally(DshFailure("wire/disconnected")); carrier.close() }
            override fun onWebsocketPing(connection: WebSocket, frame: Framedata) {
                if (!credits.pong(frame.payloadData.remaining()) { sendFrame(PongFrame().also { it.setPayload(frame.payloadData) }) }) carrier.close()
            }
        }
        client.connectionLostTimeout = 0
        client.setProxy(Proxy.NO_PROXY)
        client.setDnsResolver { throw java.net.UnknownHostException("e2e/no_dns") }
        client.setSocketFactory(CompletedCarrierFactory(carrier))
        carrier.soTimeout = 0
        carrier.limitHeaders(32768)
        try { client.connect(); return withTimeout(15_000) { opened.await() }.also { live() } }
        catch (error: Exception) { carrier.close(); client.closeConnection(1006, ""); throw error }
    }
    override fun close() {
        if (!ended.compareAndSet(false, true)) return
        opening.forEach { it.cancel("transport/changed") }; opening.clear()
        muxes.forEach(DshMux::close); muxes.clear()
        sockets.forEach(CarrierSocket::close); sockets.clear()
        synchronized(gate) { idle.clear() }
    }
    private fun exactPath(path: String) {
        if (!path.startsWith("/api/") || path.length > 8192 || '#' in path || '\\' in path || path.any { it.code < 33 || it.code == 127 }) throw DshFailure("protocol/invalid")
    }
    private fun boundedBytes(source: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(limit, 8192)); val buffer = ByteArray(8192)
        while (true) { val count = source.read(buffer); if (count < 0) return out.toByteArray(); if (count > limit - out.size()) throw DshFailure("gateway/response_too_large"); out.write(buffer, 0, count) }
    }
}

/** The library can obtain this one already-authenticated socket, and cannot dial anything else. */
private class CompletedCarrierFactory(private val socket: CarrierSocket) : SocketFactory() {
    private val once = AtomicBoolean()
    override fun createSocket(): Socket { if (!once.compareAndSet(false, true) || !socket.isConnected) throw SocketException("e2e/socket_reused"); return socket }
    override fun createSocket(host: String?, port: Int): Socket = throw SocketException("e2e/no_tcp")
    override fun createSocket(host: String?, port: Int, local: InetAddress?, localPort: Int): Socket = throw SocketException("e2e/no_tcp")
    override fun createSocket(host: InetAddress?, port: Int): Socket = throw SocketException("e2e/no_tcp")
    override fun createSocket(host: InetAddress?, port: Int, local: InetAddress?, localPort: Int): Socket = throw SocketException("e2e/no_tcp")
}

/** Exact frame credits persist until socket consumption; a fixed 131-byte credit bounds automatic close. */
private class SocketWriteCredits(private val socket: CarrierSocket) {
    private val gate = Any()
    private var pending = 0L
    private var reservedAt = 0L
    private var lastPing = 0L
    init {
        if (!socket.reserveLibrary(131)) { socket.close(); throw DshFailure("e2e.queue_budget") }
        socket.consumed = { count -> synchronized(gate) {
            val released = minOf(pending, count.toLong()).toInt()
            pending -= released; socket.releaseLibrary(released)
            if (pending == 0L) reservedAt = 0L
            if (pending != 0L && System.nanoTime() - reservedAt > TimeUnit.SECONDS.toNanos(30)) socket.close()
        } }
    }
    // The close-frame reserve is capacity, not queued output; reporting it would
    // leave a phantom backlog and time out every otherwise idle native mux.
    fun queued(): Long = synchronized(gate) { pending + socket.queuedCiphertextBytes }
    fun sendText(text: String, action: () -> Unit): Boolean {
        val size = text.toByteArray(Charsets.UTF_8).size
        return send(size + if (size < 126) 6 else if (size <= 65535) 8 else 14, action)
    }
    private fun send(bytes: Int, action: () -> Unit): Boolean = synchronized(gate) {
        if (reservedAt != 0L && System.nanoTime() - reservedAt > TimeUnit.SECONDS.toNanos(30) || !socket.reserveLibrary(bytes)) { socket.close(); return@synchronized false }
        if (pending == 0L) reservedAt = System.nanoTime()
        pending += bytes
        try { action(); true } catch (_: Exception) { socket.close(); false }
    }
    fun pong(payloadBytes: Int, action: () -> Unit): Boolean = synchronized(gate) {
        val now = System.nanoTime()
        if (now - lastPing < TimeUnit.SECONDS.toNanos(1) || payloadBytes > 125) return@synchronized false
        lastPing = now; send(payloadBytes + 6, action)
    }
}
