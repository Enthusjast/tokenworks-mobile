package cn.edu.ustc.tokenworks

import java.io.IOException
import java.nio.ByteBuffer

/** Reliable ordered ciphertext only. A write returns after actual outer consumption, not enqueue. */
interface CarrierOuterSocket : AutoCloseable {
    val queuedWriteBytes: Long
    fun write(bytes: ByteArray, offset: Int, length: Int, deadlineNanos: Long)
    override fun close()
}

/** Synchronous callback admission is bounded by the shared per-connection and per-pair byte budgets. */
class CarrierReceiver internal constructor(
    private val admit: (ByteArray, Int, Int) -> Unit,
    private val admitBuffer: (ByteBuffer) -> Unit,
    private val reject: (IOException) -> Unit,
    private val admitStreamBuffer: (ByteBuffer) -> Unit = admitBuffer,
) {
    fun receive(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        admit(bytes, offset, length)
    }
    /** Reserves before allocation; the callback's remaining bytes are consumed only after admission. */
    fun receive(bytes: ByteBuffer) { admitBuffer(bytes) }
    /** Only a dedicated ordered stream reader may wait for queue credit. */
    internal fun receiveStream(bytes: ByteBuffer) { admitStreamBuffer(bytes) }
    fun fail(code: String) {
        val safe = if (code.matches(Regex("^[a-z0-9][a-z0-9/._-]{0,63}$"))) code else "e2e/outer_failed"
        reject(IOException(safe))
    }
    internal fun fail(error: IOException) { reject(error) }
}

fun interface CarrierOuterDialer {
    /** Purpose is exactly native or pairing-control. It cannot be selected by untrusted outer metadata. */
    suspend fun open(receiver: CarrierReceiver, purpose: String): CarrierOuterSocket
}

