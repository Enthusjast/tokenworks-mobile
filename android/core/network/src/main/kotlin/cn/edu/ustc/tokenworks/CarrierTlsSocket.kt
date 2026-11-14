package cn.edu.ustc.tokenworks

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.SocketAddress
import java.nio.channels.SocketChannel
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket

/** Delegates an already CA/hostname/pin-verified real TLS socket; no session or connection state is fabricated. */
internal class CarrierTlsSocket(private val tls: SSLSocket, private val owner: WssConnectionState) : SSLSocket() {
    private val source = tls.inputStream
    private val sink = tls.outputStream
    private val input = object : InputStream() {
        private val one = ByteArray(1)
        override fun read(): Int = if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val count = source.read(bytes, offset, minOf(length, owner.readAllowance()))
            if (count > 0) owner.readBytes(count)
            return count
        }
        override fun available() = source.available()
        override fun close() = tls.close()
    }
    private val output = object : OutputStream() {
        override fun write(value: Int) = synchronized(owner.writeGate) { sink.write(value); owner.written(1) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) = synchronized(owner.writeGate) {
            sink.write(bytes, offset, length); owner.written(length)
        }
        override fun flush() = synchronized(owner.writeGate) { sink.flush() }
        override fun close() = tls.close()
    }
    override fun getInputStream() = input
    override fun getOutputStream() = output
    override fun getSupportedCipherSuites(): Array<String> = tls.supportedCipherSuites
    override fun getEnabledCipherSuites(): Array<String> = tls.enabledCipherSuites
    override fun setEnabledCipherSuites(suites: Array<String>) { tls.enabledCipherSuites = suites }
    override fun getSupportedProtocols(): Array<String> = tls.supportedProtocols
    override fun getEnabledProtocols(): Array<String> = tls.enabledProtocols
    override fun setEnabledProtocols(protocols: Array<String>) { tls.enabledProtocols = protocols }
    override fun getSession(): SSLSession = tls.session
    override fun getHandshakeSession(): SSLSession? = tls.handshakeSession
    override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener) = tls.addHandshakeCompletedListener(listener)
    override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener) = tls.removeHandshakeCompletedListener(listener)
    override fun startHandshake() = tls.startHandshake()
    override fun setUseClientMode(mode: Boolean) { tls.useClientMode = mode }
    override fun getUseClientMode() = tls.useClientMode
    override fun setNeedClientAuth(need: Boolean) { tls.needClientAuth = need }
    override fun getNeedClientAuth() = tls.needClientAuth
    override fun setWantClientAuth(want: Boolean) { tls.wantClientAuth = want }
    override fun getWantClientAuth() = tls.wantClientAuth
    override fun setEnableSessionCreation(enabled: Boolean) { tls.enableSessionCreation = enabled }
    override fun getEnableSessionCreation() = tls.enableSessionCreation
    override fun getSSLParameters(): SSLParameters = tls.sslParameters
    override fun setSSLParameters(parameters: SSLParameters) { tls.sslParameters = parameters }
    override fun connect(endpoint: SocketAddress) = tls.connect(endpoint)
    override fun connect(endpoint: SocketAddress, timeout: Int) = tls.connect(endpoint, timeout)
    override fun bind(bindpoint: SocketAddress?) = tls.bind(bindpoint)
    override fun getInetAddress(): InetAddress? = tls.inetAddress
    override fun getLocalAddress(): InetAddress? = tls.localAddress
    override fun getPort() = tls.port
    override fun getLocalPort() = tls.localPort
    override fun getRemoteSocketAddress(): SocketAddress? = tls.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress? = tls.localSocketAddress
    override fun getChannel(): SocketChannel? = tls.channel
    override fun setTcpNoDelay(value: Boolean) { tls.tcpNoDelay = value }
    override fun getTcpNoDelay() = tls.tcpNoDelay
    override fun setSoLinger(on: Boolean, linger: Int) = tls.setSoLinger(on, linger)
    override fun getSoLinger() = tls.soLinger
    override fun sendUrgentData(data: Int) = tls.sendUrgentData(data)
    override fun setOOBInline(value: Boolean) { tls.oobInline = value }
    override fun getOOBInline() = tls.oobInline
    override fun setSoTimeout(timeout: Int) { tls.soTimeout = timeout }
    override fun getSoTimeout() = tls.soTimeout
    override fun setSendBufferSize(size: Int) { tls.sendBufferSize = size }
    override fun getSendBufferSize() = tls.sendBufferSize
    override fun setReceiveBufferSize(size: Int) { tls.receiveBufferSize = size }
    override fun getReceiveBufferSize() = tls.receiveBufferSize
    override fun setKeepAlive(value: Boolean) { tls.keepAlive = value }
    override fun getKeepAlive() = tls.keepAlive
    override fun setTrafficClass(value: Int) { tls.trafficClass = value }
    override fun getTrafficClass() = tls.trafficClass
    override fun setReuseAddress(value: Boolean) { tls.reuseAddress = value }
    override fun getReuseAddress() = tls.reuseAddress
    override fun close() = tls.close()
    override fun shutdownInput() = tls.shutdownInput()
    override fun shutdownOutput() = tls.shutdownOutput()
    override fun isConnected() = tls.isConnected
    override fun isBound() = tls.isBound
    override fun isClosed() = tls.isClosed
    override fun isInputShutdown() = tls.isInputShutdown
    override fun isOutputShutdown() = tls.isOutputShutdown
    override fun setPerformancePreferences(connectionTime: Int, latency: Int, bandwidth: Int) = tls.setPerformancePreferences(connectionTime, latency, bandwidth)
}
