package cn.edu.ustc.tokenworks

import okhttp3.Headers
import org.apache.hc.core5.http.HttpVersion
import org.apache.hc.core5.http.config.Http1Config
import org.apache.hc.core5.http.impl.io.DefaultBHttpServerConnection
import org.apache.hc.core5.http.message.BasicClassicHttpResponse
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Test-only real HTTP CONNECT splice. HttpCore parses control HTTP; target TLS bytes are never decoded. */
internal class CarrierConnectProxy(private val targetHost: String, private val targetPort: Int, private val requireAuthentication: Boolean = false) : AutoCloseable {
    data class Connect(val target: String, val headers: Headers)
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val closed = AtomicBoolean()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    val requests = CopyOnWriteArrayList<Connect>()
    val forwardedBytes = AtomicLong()
    val port: Int get() = server.localPort
    private val accepting = Thread({
        while (!closed.get()) {
            val phone = try { server.accept() } catch (_: Exception) { break }
            if (sockets.size >= 8) { phone.close(); continue }
            sockets.add(phone)
            Thread({ handle(phone) }, "test-connect-handler").apply { isDaemon = true; start() }
        }
    }, "test-connect-accept").apply { isDaemon = true; start() }
    private fun handle(phone: Socket) {
        var host: Socket? = null
        try {
            phone.soTimeout = 10_000
            val factory = object : org.apache.hc.core5.http.HttpRequestFactory<org.apache.hc.core5.http.ClassicHttpRequest> {
                override fun newHttpRequest(method: String, target: String) = org.apache.hc.core5.http.message.BasicClassicHttpRequest(method, null, null, target)
                override fun newHttpRequest(method: String, target: java.net.URI) = org.apache.hc.core5.http.message.BasicClassicHttpRequest(method, target)
            }
            val parsers = org.apache.hc.core5.http.io.HttpMessageParserFactory<org.apache.hc.core5.http.ClassicHttpRequest> { config ->
                org.apache.hc.core5.http.impl.io.DefaultHttpRequestParser(config, null, factory)
            }
            val connection = DefaultBHttpServerConnection("http", Http1Config.custom().setMaxHeaderCount(100).setMaxLineLength(8192).build(),
                null, null, null, null, parsers, null)
            connection.bind(phone)
            val request = connection.receiveRequestHeader()
            val headers = Headers.Builder().apply { request.headers.forEach { add(it.name, it.value) } }.build()
            requests.add(Connect(request.requestUri, headers))
            check(request.method == "CONNECT" && request.requestUri == "$targetHost:$targetPort")
            if (requireAuthentication && headers["Proxy-Authorization"] != "Basic cHJveHk6c2VjcmV0") {
                val denied = BasicClassicHttpResponse(407, "Proxy Authentication Required")
                denied.version = HttpVersion.HTTP_1_1
                denied.setHeader("Proxy-Authenticate", "Basic realm=\"carrier-test\"")
                denied.setHeader("Content-Length", "0")
                denied.setHeader("Connection", "close")
                connection.sendResponseHeader(denied); connection.flush()
                return
            }
            val target = Socket(targetHost, targetPort)
            host = target; sockets.add(target)
            val accepted = BasicClassicHttpResponse(200, "Connection Established")
            accepted.version = HttpVersion.HTTP_1_1
            connection.sendResponseHeader(accepted); connection.flush()
            phone.soTimeout = 0
            Thread({
                try { copy(target, phone) } catch (_: Exception) { }
                finally { runCatching { phone.close() }; runCatching { target.close() } }
            }, "test-connect-response").apply { isDaemon = true; start() }
            copy(phone, target)
        } catch (_: Exception) {
            // The test asserts completion and public CONNECT facts, never logs TLS or credentials.
        } finally {
            runCatching { phone.close() }; runCatching { host?.close() }
            sockets.remove(phone); host?.let(sockets::remove)
        }
    }
    private fun copy(from: Socket, to: Socket) {
        val buffer = ByteArray(8192)
        val input = from.getInputStream(); val output = to.getOutputStream()
        while (!closed.get()) {
            val count = input.read(buffer); if (count < 0) return
            output.write(buffer, 0, count); output.flush(); forwardedBytes.addAndGet(count.toLong())
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        server.close(); sockets.forEach { runCatching { it.close() } }; sockets.clear()
        accepting.join(1000)
    }
}
