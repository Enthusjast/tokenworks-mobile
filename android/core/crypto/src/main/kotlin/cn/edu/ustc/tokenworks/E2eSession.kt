package cn.edu.ustc.tokenworks

import java.io.File
import java.nio.Buffer
import java.nio.ByteBuffer

data class E2eStaticKeyPair(val privateKey: ByteArray, val publicKey: ByteArray) {
    override fun toString(): String = "E2eStaticKeyPair(<redacted>)"
}

data class E2eHandshakeBinding(val remoteStatic: ByteArray, val handshakeHash: ByteArray) {
    override fun toString(): String = "E2eHandshakeBinding(<public binding>)"
}

/** Fixed-suite native Noise state. Callers own buffer lifetimes and must close the session. */
class E2eSession private constructor(private var handle: Long) : AutoCloseable {
    companion object {
        const val SUITE = "Noise_IK_25519_ChaChaPoly_SHA256"

        fun generateStatic(): E2eStaticKeyPair {
            E2eNative.load()
            val private = ByteArray(32)
            val public = ByteArray(32)
            try {
                E2eNative.generateStatic(private, public)
                return E2eStaticKeyPair(private, public)
            } catch (failure: Throwable) {
                private.fill(0)
                throw failure
            }
        }

        fun initiator(localPrivate: ByteArray, remotePublic: ByteArray, prologue: ByteArray): E2eSession {
            require(localPrivate.size == 32 && remotePublic.size == 32 && prologue.size <= 4096) { "e2e/argument" }
            E2eNative.load()
            return E2eSession(E2eNative.create(true, localPrivate, remotePublic, prologue))
        }

        fun responder(localPrivate: ByteArray, prologue: ByteArray): E2eSession {
            require(localPrivate.size == 32 && prologue.size <= 4096) { "e2e/argument" }
            E2eNative.load()
            return E2eSession(E2eNative.create(false, localPrivate, null, prologue))
        }
    }

    @Synchronized
    private fun record(operation: Int, input: ByteBuffer, output: ByteBuffer): Int {
        check(handle != 0L) { "e2e/closed" }
        require(input.isDirect && output.isDirect && !output.isReadOnly) { "e2e/direct-buffer" }
        require(input.remaining() <= 65535 && output.remaining() <= 65535) { "e2e/bounds" }
        val inputPosition = input.position()
        val inputLength = input.remaining()
        val outputPosition = output.position()
        val written = try {
            E2eNative.record(handle, operation, input, inputPosition, inputLength,
                output, outputPosition, output.remaining())
        } catch (failure: Throwable) {
            handle = 0L
            throw failure
        }
        (input as Buffer).position(inputPosition + inputLength)
        (output as Buffer).position(outputPosition + written)
        return written
    }

    fun handshakeWrite(payload: ByteBuffer, output: ByteBuffer): Int = record(0, payload, output)
    fun handshakeRead(record: ByteBuffer, output: ByteBuffer): Int = record(1, record, output)
    fun encrypt(plaintext: ByteBuffer, output: ByteBuffer): Int = record(2, plaintext, output)
    fun decrypt(record: ByteBuffer, output: ByteBuffer): Int = record(3, record, output)

    @Synchronized
    fun finishHandshake(): E2eHandshakeBinding {
        check(handle != 0L) { "e2e/closed" }
        val remote = ByteArray(32)
        val hash = ByteArray(32)
        try {
            E2eNative.finish(handle, remote, hash)
            return E2eHandshakeBinding(remote, hash)
        } catch (failure: Throwable) {
            handle = 0L
            throw failure
        }
    }

    @Synchronized
    fun rekeySend() = rekey(true)

    @Synchronized
    fun rekeyReceive() = rekey(false)

    private fun rekey(send: Boolean) {
        check(handle != 0L) { "e2e/closed" }
        try { E2eNative.rekey(handle, send) } catch (failure: Throwable) {
            handle = 0L
            throw failure
        }
    }

    @Synchronized
    override fun close() {
        val previous = handle
        handle = 0L
        if (previous != 0L) E2eNative.close(previous)
    }
}

internal object E2eNative {
    private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        try {
            val explicit = System.getProperty("tokenworks.noise.library")
            if (explicit == null) System.loadLibrary("tokenworks_noise") else {
                val file = File(explicit)
                require(file.isAbsolute && file.isFile) { "e2e/unsupported" }
                System.load(file.absolutePath)
            }
        } catch (failure: UnsatisfiedLinkError) {
            throw UnsupportedOperationException("e2e/unsupported", failure)
        }
        loaded = true
    }

    @JvmStatic external fun generateStatic(privateKey: ByteArray, publicKey: ByteArray)
    @JvmStatic external fun create(initiator: Boolean, localPrivate: ByteArray, remotePublic: ByteArray?, prologue: ByteArray): Long
    @JvmStatic external fun record(handle: Long, operation: Int, input: ByteBuffer, inputOffset: Int, inputLength: Int,
        output: ByteBuffer, outputOffset: Int, outputLength: Int): Int
    @JvmStatic external fun finish(handle: Long, remoteStatic: ByteArray, handshakeHash: ByteArray)
    @JvmStatic external fun rekey(handle: Long, send: Boolean)
    @JvmStatic external fun close(handle: Long)
}
