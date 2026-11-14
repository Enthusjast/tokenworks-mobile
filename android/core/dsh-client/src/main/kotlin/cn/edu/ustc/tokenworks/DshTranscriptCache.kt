package cn.edu.ustc.tokenworks

import java.security.MessageDigest
import java.util.Collections

/** A local, route-bound display cache. It never authorizes or queues an operation. */
interface DshTranscriptCache {
    val route: DshCommandRoute
    suspend fun load(): DshTranscriptSnapshot?
    suspend fun save(snapshot: DshTranscriptSnapshot)
    /** Delete the scoped history and invalidate this handle, including outstanding writes. */
    suspend fun clear()
}

/** Only the immutable native header fields identify an incarnation; IDs are not UUIDs. */
data class DshTranscriptIdentity(val id: String, val createdAt: Long, val version: Long) {
    init {
        Contract.boundedText(id, 128)
        require(createdAt in 0..MAX_SAFE_INTEGER && version in 1..MAX_SAFE_INTEGER)
    }
    internal fun json() = jsonObject("id" to id.json(), "createdAt" to createdAt.json(), "version" to version.json())
    companion object {
        fun parse(header: JsonObject) = DshTranscriptIdentity(header.string("id"), header.integer("createdAt", 0), header.integer("version", 0))
    }
}

/** The fingerprint authenticates the exact native event, not just its display projection. */
class DshTranscriptRecord internal constructor(val record: SessionRecord, val fingerprint: String) {
    init { require(fingerprint.length == 64 && fingerprint.all { it in '0'..'9' || it in 'a'..'f' }) }
    val seq get() = record.seq
    internal val weight get() = record.weight + 256
    internal fun same(other: DshTranscriptRecord) = fingerprint == other.fingerprint && SessionJson.same(record.raw, other.record.raw)
    internal fun json() = jsonObject("event" to record.raw, "sha256" to fingerprint.json())

    companion object {
        /** Drop every non-transcript payload (inbox, approvals, questions, auth, projections). */
        fun fromNative(event: JsonObject): DshTranscriptRecord {
            DshSessionFollowResultCase1Event.codec.decode(event)
            val seq = event.integer("seq", 0)
            event.integer("time", 0)
            val sourceType = event.string("type")
            val transcriptTypes = setOf("user/message", "assistant/message", "assistant/attempt", "tool/result", "tool/call", "turn/start", "turn/end", "step/start", "step/end")
            val data = if (sourceType in transcriptTypes) event.obj("data") else jsonObject()
            var type = sourceType
            fun fields(source: JsonObject, names: Set<String>) = JsonObject(source.fields.filterKeys { it in names })
            fun content(source: JsonObject): JsonArray = JsonArray(source.array("content").map { value ->
                val block = value as? JsonObject ?: throw ProtocolException()
                when (block.string("type")) {
                    "text", "reasoning" -> jsonObject("type" to block["type"], "text" to block["text"])
                    // Retain only intrinsic native image display metadata. A cached locator is not permission.
                    "image" -> jsonObject("type" to "image".json(), "attachment" to
                        NativeImageReference.parse(fields(block.obj("attachment"), setOf("attachmentId", "mediaType", "bytes", "width", "height", "name"))).json())
                    "file" -> {
                        val attachment = block.obj("attachment")
                        val name = (attachment.fields["name"] as? JsonString)?.value.orEmpty()
                        val bytes = attachment.integer("bytes", 0)
                        jsonObject("type" to "text".json(), "text" to "[文件：$name，$bytes 字节]".json())
                    }
                    "tool-call" -> jsonObject("type" to "text".json(), "text" to
                        (block.string("name") + "(" + block.string("arguments") + ")").json())
                    else -> throw SessionDataException("protocol.unsupported")
                }
            })
            fun message(source: JsonObject): JsonObject = fields(source, setOf("id", "callId", "isError")).with("content" to content(source))
            val projected = when (sourceType) {
                "user/message" -> {
                    val source = data.fields["source"] as? JsonObject
                    if ((source?.fields?.get("kind") as? JsonString)?.value in setOf("runtime-context", "system-prompt")) {
                        type = "cursor"; jsonObject()
                    } else fields(data, setOf("id")).with("content" to content(data))
                }
                "assistant/message", "tool/result" -> fields(data, setOf("turn", "step", "interrupted")).with("message" to message(data.obj("message")))
                // These are committed durable attempt records, not the transient assistantStream baseline.
                "assistant/attempt" -> fields(data, setOf("turn", "step", "stream"))
                "tool/call" -> fields(data, setOf("turn", "step", "callId", "name", "arguments"))
                "turn/start", "step/start", "step/end" -> fields(data, setOf("turn", "step"))
                "turn/end" -> {
                    val reason = data.fields["reason"]
                    val name = (reason as? JsonObject)?.fields?.get("kind") ?: reason
                    fields(data, setOf("turn")).let { if (name is JsonString) it.with("reason" to name) else it }
                }
                else -> { type = "cursor"; jsonObject() }
            }
            var root = jsonObject("seq" to seq.json(), "time" to event["time"], "type" to type.json(), "data" to projected)
            // Replacement semantics belong to the native record, never to cached client state.
            if (type != "cursor") {
                event.fields["surfaceOp"]?.let { root = root.with("surfaceOp" to it) }
                event.fields["sourceEventSeqs"]?.let { root = root.with("sourceEventSeqs" to it) }
            }
            val parsed = SessionRecord.parse(root)
            // SessionRecord may bound a huge message; never retain its unbounded raw payload.
            root = root.with("data" to parsed.data)
            return DshTranscriptRecord(SessionRecord.parse(root), nativeTranscriptFingerprint(event))
        }
        internal fun parse(value: JsonObject): DshTranscriptRecord {
            value.exactKeys("event", "sha256")
            val record = SessionRecord.parse(value.obj("event"))
            require(record.type in setOf("cursor", "user/message", "assistant/message", "assistant/attempt", "tool/result", "tool/call", "turn/start", "turn/end", "step/start", "step/end"))
            return DshTranscriptRecord(record, value.string("sha256"))
        }
    }
}

class DshTranscriptSnapshot(val identity: DshTranscriptIdentity, records: List<DshTranscriptRecord>) {
    val records: List<DshTranscriptRecord> = Collections.unmodifiableList(ArrayList(records))
    val cursor get() = records.lastOrNull()?.seq ?: -1L
    init {
        require(records.size <= MAX_RECORDS && records.sumOf { it.weight } <= MAX_WEIGHT)
        for (i in 1 until records.size) require(records[i].seq == records[i - 1].seq + 1)
    }
    fun display(): SessionTranscript {
        val window = SessionWindow("cache")
        window.accept(SessionContent("session.snapshot", "cache", "cache", jsonObject(
            "cursor" to cursor.json(), "has_more" to JsonBoolean(records.firstOrNull()?.seq?.let { it > 0 } ?: false),
            "records" to JsonArray(records.map { it.record.raw }), "assistant" to JsonNull)))
        window.accept(SessionContent("session.checkpoint", "cache", "cache", jsonObject("state" to "persisted".json(), "through_seq" to cursor.json())))
        return window.snapshot(durableOnly = true).asReadOnly()
    }
    internal fun json() = jsonObject("identity" to identity.json(), "records" to JsonArray(records.map { it.json() }))
    companion object {
        const val MAX_RECORDS = 512
        const val MAX_WEIGHT = 512L * 1024
        fun tail(identity: DshTranscriptIdentity, records: List<DshTranscriptRecord>): DshTranscriptSnapshot {
            var first = records.size
            var weight = 0L
            while (first > 0 && records.size - first < MAX_RECORDS && weight + records[first - 1].weight <= MAX_WEIGHT) {
                weight += records[--first].weight
            }
            return DshTranscriptSnapshot(identity, records.subList(first, records.size))
        }
        internal fun parse(value: JsonObject): DshTranscriptSnapshot {
            value.exactKeys("identity", "records")
            return DshTranscriptSnapshot(DshTranscriptIdentity.parse(value.obj("identity")), value.array("records").map {
                DshTranscriptRecord.parse(it as? JsonObject ?: throw ProtocolException())
            })
        }
    }
}

/** Structural hashing covers the complete event without allocating a second serialized payload. */
private fun nativeTranscriptFingerprint(event: JsonObject): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun size(value: Int) { for (shift in 24 downTo 0 step 8) digest.update((value ushr shift).toByte()) }
    fun text(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        size(bytes.size); digest.update(bytes)
    }
    fun visit(value: JsonValue) {
        when (value) {
            is JsonObject -> {
                digest.update(1.toByte()); size(value.fields.size)
                for (name in value.fields.keys.sorted()) { text(name); visit(value.fields.getValue(name)) }
            }
            is JsonArray -> { digest.update(2.toByte()); size(value.values.size); value.values.forEach(::visit) }
            is JsonString -> { digest.update(3.toByte()); text(value.value) }
            is JsonNumber -> {
                digest.update(4.toByte())
                val bits = java.lang.Double.doubleToLongBits(if (value.value == 0.0) 0.0 else value.value)
                for (shift in 56 downTo 0 step 8) digest.update((bits ushr shift).toByte())
            }
            is JsonBoolean -> digest.update((if (value.value) 5 else 6).toByte())
            JsonNull -> digest.update(7.toByte())
        }
    }
    visit(event)
    return transcriptHex(digest.digest())
}

internal fun transcriptDigest(bytes: ByteArray): String = transcriptHex(MessageDigest.getInstance("SHA-256").digest(bytes))

private fun transcriptHex(digest: ByteArray): String {
    val alphabet = "0123456789abcdef"
    return CharArray(digest.size * 2) { index ->
        val byte = digest[index / 2].toInt() and 255
        alphabet[if (index % 2 == 0) byte ushr 4 else byte and 15]
    }.concatToString()
}
