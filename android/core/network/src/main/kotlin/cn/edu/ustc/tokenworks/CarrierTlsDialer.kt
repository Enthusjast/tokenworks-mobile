package cn.edu.ustc.tokenworks

import okhttp3.Address
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Route
import org.apache.hc.core5.http.HttpVersion
import org.apache.hc.core5.http.config.Http1Config
import org.apache.hc.core5.http.impl.io.DefaultBHttpClientConnection
import org.apache.hc.core5.http.impl.io.HttpRequestExecutor
import org.apache.hc.core5.http.message.BasicClassicHttpRequest
import java.io.FilterInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket

/** Public standard TCP/proxy/TLS only; no business HTTP, redirects, socket failover, or credential fallback. */
internal object CarrierTlsDialer {
    fun connect(client: OkHttpClient, request: Request, deadline: Long, own: (Socket) -> Unit): SSLSocket {
        val url = request.url
        require(url.scheme == "https")
        val proxy = client.proxy ?: client.proxySelector.select(url.toUri()).firstOrNull() ?: Proxy.NO_PROXY
        val proxyAddress = if (proxy.type() == Proxy.Type.DIRECT) null else proxy.address() as? InetSocketAddress ?: throw IOException("network.proxy_invalid")
        val target = if (proxy.type() == Proxy.Type.SOCKS) InetSocketAddress.createUnresolved(url.host, url.port) else {
            val host = proxyAddress?.hostString ?: url.host
            val port = proxyAddress?.port ?: url.port
            val address = proxyAddress?.address ?: client.dns.lookup(host).firstOrNull() ?: throw java.net.UnknownHostException("network.dns_failed")
            InetSocketAddress(address, port)
        }
        var authorization: String? = null
        var socket: Socket? = null
        try {
            repeat(2) { attempt ->
                val raw = if (proxy.type() == Proxy.Type.SOCKS) Socket(proxy) else client.socketFactory.createSocket()
                socket = raw; own(raw)
                raw.connect(target, remaining(deadline, client.connectTimeoutMillis))
                raw.soTimeout = remaining(deadline, client.readTimeoutMillis)
                if (proxy.type() == Proxy.Type.HTTP) {
                    val authority = (if (':' in url.host) "[${url.host}]" else url.host) + ":${url.port}"
                    // The two-argument URI constructor treats host:port as an opaque URI and loses its target.
                    val connect = BasicClassicHttpRequest("CONNECT", null, null, authority)
                    connect.version = HttpVersion.HTTP_1_1
                    connect.setHeader("Host", authority)
                    connect.setHeader("Proxy-Connection", "Keep-Alive")
                    authorization?.let { connect.setHeader("Proxy-Authorization", it) }
                    val connection = DefaultBHttpClientConnection(Http1Config.custom().setBufferSize(8192).setMaxLineLength(8192).setMaxHeaderCount(100).build())
                    connection.bind(ProxyHeaderSocket(raw))
                    val response = HttpRequestExecutor().execute(connect, connection, org.apache.hc.core5.http.protocol.HttpCoreContext.create())
                    if (response.code != 200) {
                        if (attempt == 0 && response.code == 407) {
                            val headers = Headers.Builder().apply { response.headers.forEach { add(it.name, it.value) } }.build()
                            val connectRequest = Request.Builder().url(url).method("CONNECT", null).build()
                            val observed = Response.Builder().request(connectRequest).protocol(Protocol.HTTP_1_1).code(407)
                                .message("Proxy Authentication Required").headers(headers).body(ByteArray(0).toResponseBody(null)).build()
                            val address = Address(url.host, url.port, client.dns, client.socketFactory, client.sslSocketFactory, client.hostnameVerifier,
                                client.certificatePinner, client.proxyAuthenticator, client.proxy, client.protocols, client.connectionSpecs, client.proxySelector)
                            val authenticated = observed.use { client.proxyAuthenticator.authenticate(Route(address, proxy, target), it) }
                            authorization = authenticated?.header("Proxy-Authorization")?.also { value ->
                                if (value.length > 8192 || value.any { it.code < 32 || it.code == 127 }) throw IOException("network.proxy_auth_invalid")
                            }
                            raw.close()
                            if (authorization != null) return@repeat
                        }
                        throw IOException(if (response.code == 407) "network.proxy_auth_required" else "network.proxy_connect_failed")
                    }
                }
                val tls = client.sslSocketFactory.createSocket(raw, url.host, url.port, true) as? SSLSocket ?: throw SSLException("network.tls_required")
                socket = tls; own(tls)
                tls.useClientMode = true
                val spec = client.connectionSpecs.firstOrNull { it.isTls && it.isCompatible(tls) } ?: throw SSLException("network.tls_unsupported")
                spec.tlsVersions?.let { versions -> tls.enabledProtocols = tls.enabledProtocols.filter { name -> versions.any { it.javaName == name } }.toTypedArray() }
                spec.cipherSuites?.let { suites -> tls.enabledCipherSuites = tls.enabledCipherSuites.filter { name -> suites.any { it.javaName.removePrefix("TLS_").removePrefix("SSL_") == name.removePrefix("TLS_").removePrefix("SSL_") } }.toTypedArray() }
                tls.sslParameters = tls.sslParameters.also { it.endpointIdentificationAlgorithm = "HTTPS" }
                tls.soTimeout = remaining(deadline, client.readTimeoutMillis)
                tls.startHandshake()
                if (!client.hostnameVerifier.verify(url.host, tls.session)) throw SSLPeerUnverifiedException("network.hostname_failed")
                client.certificatePinner.check(url.host, tls.session.peerCertificates.toList())
                return tls
            }
            throw IOException("network.proxy_auth_required")
        } catch (error: Exception) {
            runCatching { socket?.close() }
            if (proxyAddress != null && client.proxy == null && error is IOException) runCatching { client.proxySelector.connectFailed(url.toUri(), proxyAddress, error) }
            throw error
        }
    }
    private fun remaining(deadline: Long, configured: Int): Int {
        val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        if (left <= 0) throw SocketTimeoutException("network.connect_timeout")
        return minOf(left, if (configured > 0) configured.toLong() else left).coerceAtLeast(1).toInt()
    }
    /** HttpCore parses CONNECT; this wrapper only caps bytes read before TLS starts on the original socket. */
    private class ProxyHeaderSocket(private val socket: Socket) : Socket() {
        private val source = object : FilterInputStream(socket.getInputStream()) {
            private var remaining = 32768
            override fun read(): Int { if (remaining == 0) throw IOException("network.proxy_header_budget"); return super.read().also { if (it >= 0) remaining-- } }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (remaining == 0) throw IOException("network.proxy_header_budget")
                return `in`.read(bytes, offset, minOf(length, remaining)).also { if (it > 0) remaining -= it }
            }
        }
        override fun getInputStream() = source
        override fun getOutputStream() = socket.getOutputStream()
        override fun getSoTimeout() = socket.soTimeout
        override fun setSoTimeout(value: Int) { socket.soTimeout = value }
        override fun getLocalSocketAddress() = socket.localSocketAddress
        override fun getRemoteSocketAddress() = socket.remoteSocketAddress
        override fun isConnected() = socket.isConnected
        override fun isClosed() = socket.isClosed
        override fun close() = socket.close()
    }
}
