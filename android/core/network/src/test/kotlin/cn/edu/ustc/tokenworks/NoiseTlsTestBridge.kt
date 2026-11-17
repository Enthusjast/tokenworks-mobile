package cn.edu.ustc.tokenworks

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Test-only encrypted splice to the existing TLS protocol-test server. Production has no TCP adapter. */
internal class NoiseTlsTestBridge(
    private val keys: E2eStaticKeyPair,
    private val credentials: () -> LanCredentials,
    private val backend: () -> Socket,
    private val installation: () -> String = { credentials().installationId },
    private val routeKind: String = "lan",
    private val purpose: String = "native",
    private val onAuth: (JsonObject) -> Unit = {},
    private val dshVersion: String = DshMethods.VERSION,
    private val readyDshVersion: String = dshVersion,
) : WebSocketListener() {
    private val queue = ArrayBlockingQueue<ByteArray>(16)
    private val queued = AtomicInteger()
    private val ended = AtomicBoolean()
    private var upstream: Socket? = null
    private var session: E2eSession? = null
    private lateinit var ws: WebSocket
    private val engineGate = Any()
    private val outputGate = Any()
    private var sent = 0L
    private var received = 0L
    private var sentBytes = 0L
    private var receivedBytes = 0L
    private val bytes = object : InputStream() {
        private var current = ByteArrayInputStream(ByteArray(0))
        override fun read(): Int {
            while (current.available() == 0) {
                if (ended.get()) throw EOFException()
                val chunk = queue.poll(30, TimeUnit.SECONDS) ?: throw EOFException()
                queued.addAndGet(-chunk.size); current = ByteArrayInputStream(chunk)
            }
            return current.read()
        }
        override fun read(value: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val first = read(); if (first < 0) return -1
            value[offset] = first.toByte()
            val rest = minOf(length - 1, current.available())
            current.read(value, offset + 1, rest)
            return rest + 1
        }
    }
    private val input = DataInputStream(bytes)
    private fun record(): ByteArray {
        val size = input.readUnsignedShort()
        require(size in 1..65535)
        return ByteArray(size).also(input::readFully)
    }
    private fun wire(record: ByteArray) {
        val buffer = ByteBuffer.allocate(record.size + 2).putShort(record.size.toShort()).put(record)
        check(ws.send(buffer.array().toByteString()))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (ws.queueSize() != 0L) { check(System.nanoTime() < deadline); Thread.sleep(1) }
    }
    private fun direct(bytes: ByteArray): ByteBuffer = ByteBuffer.allocateDirect(maxOf(1, bytes.size)).also { it.put(bytes); (it as java.nio.Buffer).flip() }
    private fun output(operation: (ByteBuffer) -> Int): ByteArray {
        val buffer = ByteBuffer.allocateDirect(65535)
        val size = operation(buffer)
        (buffer as java.nio.Buffer).flip()
        return ByteArray(size).also(buffer::get)
    }
    private fun decrypt(): ByteArray {
        val record = direct(record())
        val result = synchronized(engineGate) { output { session!!.decrypt(record, it) } }
        received++
        if (received % (1L shl 20) == 0L) synchronized(engineGate) { session!!.rekeyReceive() }
        return result
    }
    private fun encrypt(bytes: ByteArray) = synchronized(outputGate) {
        val plaintext = direct(bytes)
        val encrypted = synchronized(engineGate) { output { session!!.encrypt(plaintext, it) } }
        sent++
        if (sent % (1L shl 20) == 0L) synchronized(engineGate) { session!!.rekeySend() }
        wire(encrypted)
    }
    override fun onOpen(webSocket: WebSocket, response: Response) {
        ws = webSocket
        Thread({
            try {
                val pair = credentials()
                val hello = JsonDocument.of(jsonObject("magic" to "tokenworks-noise".json(), "v" to 1L.json(), "suite" to E2eSession.SUITE.json(),
                    "runtimeId" to pair.runtimeId.json(), "installationId" to installation().json(), "hostKeyEpoch" to pair.hostKeyEpoch.json(),
                    "hostStaticPublicKey" to pair.hostStaticPublicKey.json(), "purpose" to purpose.json(), "routeKind" to routeKind.json(),
                    "dshVersion" to dshVersion.json(), "capabilities" to JsonArray(listOf("native.http.v1".json(), "noise.ik.v1".json())))).canonicalBytes()
                session = E2eSession.responder(keys.privateKey, hello)
                wire(hello)
                val ik1 = direct(record())
                check(output { session!!.handshakeRead(ik1, it) }.isEmpty())
                wire(output { session!!.handshakeWrite(direct(ByteArray(0)), it) })
                val binding = session!!.finishHandshake()
                check(binding.remoteStatic.contentEquals(Contract.base64url(pair.deviceStaticPublicKey)))
                val auth = JsonDocument.parse(decrypt()).root
                check(auth.string("pairId") == pair.pairId && auth.string("token") == pair.token)
                onAuth(auth)
                val proof = com.nimbusds.jose.JWSObject.parse(auth.string("proof"))
                check(proof.verify(com.nimbusds.jose.crypto.ECDSAVerifier(com.nimbusds.jose.jwk.ECKey.parse(proof.header.jwk.toJSONString()))))
                val claims = JsonDocument.parse(proof.payload.toString()).root
                check(claims.string("h") == Contract.base64url(binding.handshakeHash))
                encrypt(JsonDocument.of(jsonObject("type" to "ready".json(), "v" to 1L.json(), "suite" to E2eSession.SUITE.json(), "dshVersion" to readyDshVersion.json(),
                    "runtimeId" to pair.runtimeId.json(), "installationId" to pair.installationId.json(), "pairId" to pair.pairId.json(),
                    "hostKeyEpoch" to pair.hostKeyEpoch.json(), "deviceKeyEpoch" to pair.deviceKeyEpoch.json(), "ownerSha256" to pair.ownerSha256.json(),
                    "bindingSha256" to pair.bindingSha256.json(), "peerGeneration" to "test-peer-generation".json())).canonicalBytes())
                val socket = backend(); upstream = socket
                Thread({
                    try {
                        val buffer = ByteArray(65518)
                        while (!ended.get()) {
                            val count = socket.getInputStream().read(buffer)
                            if (count < 0) break
                            encrypt(ByteArray(count + 1).also { buffer.copyInto(it, 1, 0, count) }); sentBytes += count
                        }
                        encrypt(ByteBuffer.allocate(17).put(1).putLong(sentBytes).putLong(sent + 1).array())
                        ws.close(1000, "")
                    } catch (_: Exception) { close() }
                }, "test-noise-backend-reader").apply { isDaemon = true; start() }
                while (!ended.get()) {
                    val plaintext = decrypt()
                    when (plaintext[0].toInt()) {
                        0 -> { socket.getOutputStream().write(plaintext, 1, plaintext.size - 1); socket.getOutputStream().flush(); receivedBytes += plaintext.size - 1 }
                        1 -> { val close = ByteBuffer.wrap(plaintext, 1, 16); check(close.long == receivedBytes && close.long == received); socket.shutdownOutput(); break }
                        else -> error("record kind")
                    }
                }
            } catch (_: Exception) { close() }
        }, "test-noise-carrier-reader").apply { isDaemon = true; start() }
    }
    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        if (queued.addAndGet(bytes.size) > CARRIER_QUEUE_BYTES || !queue.offer(bytes.toByteArray())) close()
    }
    override fun onMessage(webSocket: WebSocket, text: String) { close() }
    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { close() }
    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close() }
    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { close() }
    private fun close() {
        if (!ended.compareAndSet(false, true)) return
        upstream?.close(); if (::ws.isInitialized) ws.cancel()
        synchronized(engineGate) { session?.close() }
        queue.offer(ByteArray(0))
    }
}
