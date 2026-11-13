package cn.edu.ustc.tokenworks

import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.Base64

object Contract {
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val digest = Regex("[0-9a-f]{64}")
    private val utc = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z")
    private val utcFormat = DateTimeFormatterBuilder().appendInstant(3).toFormatter()
    fun uuid(value: String): String = value.also { if (!uuid.matches(it)) throw ProtocolException() }
    fun digest(value: String): String = value.also { if (!digest.matches(it)) throw ProtocolException() }
    fun utc(value: String): Long = try {
        if (!utc.matches(value)) throw ProtocolException()
        val instant = Instant.parse(value)
        if (utcFormat.format(instant) != value) throw ProtocolException()
        instant.toEpochMilli()
    } catch (_: Exception) { throw ProtocolException() }
    fun timestamp(millis: Long): String = utcFormat.format(Instant.ofEpochMilli(millis)).also { utc(it) }
    fun nonce(value: String): String = value.also { if (base64url(it).size != 32 || it.length != 43) throw ProtocolException() }
    fun base64url(value: String): ByteArray {
        if (value.isEmpty() || !Regex("[A-Za-z0-9_-]+").matches(value)) throw ProtocolException()
        val decoded = try { Base64.getUrlDecoder().decode(value) } catch (_: Exception) { throw ProtocolException() }
        if (base64url(decoded) != value) throw ProtocolException()
        return decoded
    }
    fun base64url(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    fun boundedText(value: String, bytes: Int): String = value.also {
        requireText(it)
        if (it.isEmpty() || it.toByteArray(Charsets.UTF_8).size > bytes || it.any { c -> c < ' ' || c == '\u007f' }) throw ProtocolException()
    }
    val identityFields = listOf("host_id", "installation_id", "runtime_id", "runtime_epoch", "session_id", "approval_id", "execution_id")
    fun identity(root: JsonObject) { identityFields.forEach { uuid(root.string(it)) } }
}

const val RELAY_FRAME_BYTES = 64 * 1024

/** RFC 3339 transport times include the Go server's nanosecond precision. */
fun relayTime(value: String): Long = try {
    if (value.length !in 20..40 || value.any { it <= ' ' || it >= '\u007f' }) throw ProtocolException()
    java.time.OffsetDateTime.parse(value, java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli().also {
        if (it !in 0..MAX_SAFE_INTEGER) throw ProtocolException()
    }
} catch (_: Exception) { throw ProtocolException() }

fun relayEnvelope(root: JsonObject, type: String, vararg fields: String) {
    root.exactKeys(*fields)
    if (root.integer("v", 1, 1) != 1L || root.string("type") != type) throw ProtocolException()
}
