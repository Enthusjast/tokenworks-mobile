package cn.edu.ustc.tokenworks

import android.content.res.Resources
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal enum class SessionExportFormat(val extension: String, val mime: String) {
    JSONL("jsonl", "application/x-ndjson"), MARKDOWN("md", "text/markdown"),
}

internal fun sessionHistoryAddress(sessionId: String, subagent: DshSubagentAddress? = null): DshSessionPageInputRequestAddress =
    if (subagent == null) DshSessionPageInputRequestAddress.Case1(DshSessionPageInputRequestAddressCase1("session", sessionId))
    else DshSessionPageInputRequestAddress.Case2(DshSessionPageInputRequestAddressCase2("subagent", subagent.parentSessionId, sessionId, subagent.mode))

/** Read a fresh committed cut from native history, never the UI's bounded/projected window. */
internal suspend fun readSessionHistory(
    address: DshSessionPageInputRequestAddress,
    native: suspend (String, JsonObject) -> JsonValue,
    page: (List<JsonObject>) -> Unit,
): Long {
    var cut: Long? = null
    var first: Long? = null
    var boundary: String? = null
    while (true) {
        currentCoroutineContext().ensureActive()
        val method = DshMethods.SessionPage
        val request = DshSessionPageInputRequest(address,
            throughSeq = cut?.let { DshOptional.Value(it.toDouble()) } ?: DshOptional.Missing,
            beforeSeq = first?.let { DshOptional.Value((it + 1).toDouble()) } ?: DshOptional.Missing,
            maxMessages = DshOptional.Value(8.0))
        val root = native(method.endpoint, method.input.encode(DshSessionPageInput(request)) as JsonObject) as? JsonObject
            ?: throw ProtocolException()
        val response = method.output.decode(root)
        val cursor = sequence(response.cursor, -1)
        if (cut == null) cut = cursor else if (cursor != cut) throw ProtocolException()
        val records = response.records
        if (cursor == -1L) {
            if (first != null || response.hasMore || records.isNotEmpty()) throw ProtocolException()
            return cursor
        }
        val start = sequence(records.firstOrNull()?.event?.seq ?: throw ProtocolException())
        val end = first ?: cursor
        if (start > end || end - start + 1 != records.size.toLong() || response.hasMore != (start > 0)) throw ProtocolException()
        records.forEachIndexed { index, record ->
            if (sequence(record.event.seq) != start + index) throw ProtocolException()
        }
        // Keep the original event objects, including forward-compatible fields, in JSONL.
        val raw = root.array("records").map { (it as? JsonObject ?: throw ProtocolException()).obj("event") }
        if (first != null && (start == first || WireJson.encode(raw.last()) != boundary)) throw ProtocolException()
        // Exact overlap prevents splicing histories if this session is wiped/replaced mid-export.
        page(raw.subList(0, if (first == null) raw.size else raw.size - 1))
        if (!response.hasMore) return cursor
        if (start == MAX_SAFE_INTEGER) throw ProtocolException()
        first = start
        boundary = WireJson.encode(raw.first())
    }
}

private fun sequence(value: Double, min: Long = 0): Long {
    if (!value.isFinite() || value < min || value > MAX_SAFE_INTEGER || value % 1.0 != 0.0) throw ProtocolException()
    return value.toLong()
}

private fun OutputStream.text(text: String) = write(text.toByteArray(Charsets.UTF_8))

private fun OutputStream.literal(text: String) {
    // Indented code renders untrusted text literally: no links, images or HTML are evaluated.
    text.lineSequence().forEach { line -> text("    "); text(line); write('\n'.code) }
    write('\n'.code)
}

internal data class SessionHistoryMarkdownCopy(
    val title: String,
    val empty: String,
    val eventRange: String,
    val user: String,
    val assistant: String,
    val tool: String,
    val event: String,
) {
    companion object {
        fun from(resources: Resources) = SessionHistoryMarkdownCopy(
            title = resources.getString(R.string.session_history_snapshot),
            empty = resources.getString(R.string.session_history_empty),
            eventRange = resources.getString(R.string.session_history_event_range),
            user = resources.getString(R.string.session_history_role_user),
            assistant = resources.getString(R.string.session_history_role_assistant),
            tool = resources.getString(R.string.session_history_role_tool),
            event = resources.getString(R.string.session_history_role_event),
        )
    }
}

private fun OutputStream.markdown(event: JsonObject, copy: SessionHistoryMarkdownCopy) {
    val type = event.string("type")
    val role = when (type) {
        "user/message", "user/message-appended", "agent/inbox/spliced" -> copy.user
        "assistant/message" -> copy.assistant
        "tool/call", "tool/result" -> copy.tool
        else -> copy.event
    }
    text("## $role · #${event.integer("seq", 0)}\n\n")
    val data = event["data"] as? JsonObject
    val message = data?.fields?.get("message") as? JsonObject ?: data
    val content = (message?.fields?.get("content") as? JsonArray)?.values
    if (!content.isNullOrEmpty() && type in markdownMessages) {
        for (value in content) {
            val part = value as? JsonObject
            val kind = (part?.fields?.get("type") as? JsonString)?.value
            val text = (part?.fields?.get("text") as? JsonString)?.value
            if ((kind == "text" || kind == "reasoning") && text != null) literal(text)
            else literal(WireJson.encode(part ?: jsonObject("value" to value)))
        }
    } else literal(WireJson.encode(event))
}

private val markdownMessages = setOf("user/message", "user/message-appended", "assistant/message", "tool/result")

private val random = SecureRandom()
private const val NONCE_BYTES = 12
private val shareAAD = "tokenworks.session-share.v1".toByteArray(Charsets.US_ASCII)

private fun aes(mode: Int, key: ByteArray, nonce: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").apply {
    init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
}

internal class SessionShareSizeException : IllegalArgumentException()
internal class SealedSessionShare(val ciphertext: ByteArray, val fragment: String)

/** Native pagination runs newest first. Spool encrypted pages and replay backwards for chronological output. */
internal class StagedSessionHistory(directory: File, private val markdownCopy: SessionHistoryMarkdownCopy?) : AutoCloseable {
    private val key = ByteArray(32).also(random::nextBytes)
    private val file = File.createTempFile("session-export-", ".tmp", directory).let { path ->
        val opened = try { RandomAccessFile(path, "rw") }
        catch (error: Exception) { path.delete(); throw error }
        // Android is POSIX: no named cache file survives cancellation or process death.
        if (!path.delete()) { opened.close(); throw java.io.IOException("cannot unlink export spool") }
        opened
    }
    private var plainSize = 0L
    private var cut: Long? = null

    suspend fun collect(connection: DshConnection, address: DshSessionPageInputRequestAddress, title: String,
        format: SessionExportFormat, maxBody: Long = Long.MAX_VALUE) {
        check(cut == null)
        val copy = if (format == SessionExportFormat.MARKDOWN) checkNotNull(markdownCopy) else null
        val lifetime = currentCoroutineContext()
        cut = readSessionHistory(address, connection::native) { events ->
            val offset = file.filePointer
            val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            val cipher = aes(Cipher.ENCRYPT_MODE, key, nonce)
            file.write(nonce)
            val output = BufferedOutputStream(object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(bytes: ByteArray, start: Int, count: Int) {
                    plainSize += count
                    if (plainSize > maxBody) throw SessionShareSizeException()
                    cipher.update(bytes, start, count)?.let(file::write)
                }
            })
            for (event in events) {
                lifetime.ensureActive()
                if (copy == null) { output.text(WireJson.encode(event)); output.write('\n'.code) }
                else output.markdown(event, copy)
            }
            output.flush()
            file.write(cipher.doFinal())
            file.writeInt(Math.toIntExact(file.filePointer - offset))
        }
        if (copy != null) {
            val cursor = checkNotNull(cut)
            val bytes = ByteArrayOutputStream()
            bytes.text("# ${copy.title}\n\n")
            bytes.literal(title)
            // Event identifiers retain ASCII digits just like the per-event headings.
            bytes.text(if (cursor == -1L) "${copy.empty}\n" else "${String.format(Locale.ROOT, copy.eventRange, cursor)}\n\n")
            val preamble = bytes.toByteArray()
            if (plainSize + preamble.size > maxBody) throw SessionShareSizeException()
            header = preamble
        }
    }

    private var header = ByteArray(0)

    suspend fun writeTo(output: OutputStream) {
        check(cut != null)
        output.write(header)
        var end = file.length()
        val nonce = ByteArray(NONCE_BYTES)
        while (end > 0) {
            currentCoroutineContext().ensureActive()
            val footer = end - Int.SIZE_BYTES
            file.seek(footer)
            val length = file.readInt()
            check(length >= NONCE_BYTES + 16 && length <= footer)
            end = footer - length
            file.seek(end)
            file.readFully(nonce)
            val ciphertext = ByteArray(length - NONCE_BYTES)
            file.readFully(ciphertext)
            val plain = aes(Cipher.DECRYPT_MODE, key, nonce).doFinal(ciphertext)
            try { output.write(plain) } finally { plain.fill(0) }
        }
    }

    suspend fun seal(): SealedSessionShare {
        val overhead = 1 + NONCE_BYTES + 16
        val size = plainSize + header.size + overhead
        if (size > SessionSharePublication.MAX_BYTES) throw SessionShareSizeException()
        val key = ByteArray(32).also(random::nextBytes)
        try {
            val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            val cipher = aes(Cipher.ENCRYPT_MODE, key, nonce).apply { updateAAD(shareAAD) }
            val payload = ByteArray(size.toInt())
            payload[0] = 1
            nonce.copyInto(payload, 1)
            var position = 1 + nonce.size
            writeTo(object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(bytes: ByteArray, start: Int, count: Int) {
                    position += cipher.update(bytes, start, count, payload, position)
                }
            })
            check(position + cipher.doFinal(payload, position) == payload.size)
            return SealedSessionShare(payload, "v1=" + Base64.getUrlEncoder().withoutPadding().encodeToString(key))
        } finally { key.fill(0) }
    }

    override fun close() { key.fill(0); file.close() }
}
