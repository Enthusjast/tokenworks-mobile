package cn.edu.ustc.tokenworks

/** dsh's four disjoint billing buckets. Reasoning tokens are already part of output. */
data class UsageCounts(val input: Long = 0, val output: Long = 0, val cacheRead: Long = 0, val cacheWrite: Long = 0) {
    val total: Long get() = add(add(input, output), add(cacheRead, cacheWrite))
    operator fun plus(other: UsageCounts) = UsageCounts(
        add(input, other.input), add(output, other.output), add(cacheRead, other.cacheRead), add(cacheWrite, other.cacheWrite),
    )

    fun lessKnown(known: UsageCounts) = UsageCounts(
        (input - known.input).coerceAtLeast(0), (output - known.output).coerceAtLeast(0),
        (cacheRead - known.cacheRead).coerceAtLeast(0), (cacheWrite - known.cacheWrite).coerceAtLeast(0),
    )

    internal fun json() = jsonObject("uncachedInputTokens" to input.json(), "outputTokens" to output.json(),
        "cacheReadTokens" to cacheRead.json(), "cacheWriteTokens" to cacheWrite.json())

    companion object {
        private fun add(a: Long, b: Long) = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b

        fun fromProjection(value: JsonObject): UsageCounts? = parse(value, "uncachedInputTokens", optionalCache = false)

        fun fromProjection(input: Double, output: Double, cacheRead: Double, cacheWrite: Double): UsageCounts? {
            return UsageCounts(asCount(input) ?: return null, asCount(output) ?: return null,
                asCount(cacheRead) ?: return null, asCount(cacheWrite) ?: return null)
        }

        private fun asCount(number: Double): Long? =
            number.toLong().takeIf { it in 0..MAX_SAFE_INTEGER && it.toDouble() == number }

        internal fun fromSample(value: JsonObject): UsageCounts? = parse(value, "inputTokens", optionalCache = true)

        private fun parse(value: JsonObject, inputKey: String, optionalCache: Boolean): UsageCounts? {
            fun count(key: String, optional: Boolean = false): Long? {
                val raw = value.fields[key] ?: return if (optional) 0 else null
                return asCount((raw as? JsonNumber)?.value ?: return null)
            }
            return UsageCounts(count(inputKey) ?: return null, count("outputTokens") ?: return null,
                count("cacheReadTokens", optionalCache) ?: return null, count("cacheWriteTokens", optionalCache) ?: return null)
        }
    }
}

data class UsageModel(val provider: String, val model: String)
data class UsageSnapshot(val throughSeq: Long, val counts: UsageCounts)

/** Only separate retry starts open a new billing slot for the same turn and step. */
sealed interface SessionUsageEvent {
    val turn: Long
    val step: Long

    data class Retry(override val turn: Long, override val step: Long) : SessionUsageEvent
    data class Sample(override val turn: Long, override val step: Long, val counts: UsageCounts, val model: UsageModel?) : SessionUsageEvent

    companion object {
        /** Derive only accounting metadata; never keep full native streams or assistant sources here. */
        fun project(recordType: String, data: JsonObject): JsonObject? {
            if (recordType != "assistant/message" && recordType != "assistant/attempt" && recordType != "llm/retry-started") return null
            val turn = readPosition(data, "turn") ?: return null
            val step = readPosition(data, "step") ?: return null
            if (recordType == "llm/retry-started") return jsonObject("kind" to "retry".json(), "turn" to turn.json(), "step" to step.json())
            val isMessage = recordType == "assistant/message"
            val raw = if (isMessage && "usage" in data.fields) data.fields["usage"] else lastStreamUsage(data)
            val counts = UsageCounts.fromSample(raw as? JsonObject ?: return null) ?: return null
            val source = ((data.fields["message"] as? JsonObject)?.fields?.get("source") as? JsonObject)
                ?.takeIf { isMessage && it.fields["kind"] == "model".json() }
            val model = source?.let(::readModel)
            val projected = jsonObject("kind" to "sample".json(), "turn" to turn.json(), "step" to step.json(), "counts" to counts.json())
            return if (model == null) projected else projected.with("model" to jsonObject(
                "provider" to model.provider.json(), "model" to model.model.json()))
        }

        internal fun decode(value: JsonObject, recordType: String): SessionUsageEvent {
            val turn = readPosition(value, "turn") ?: throw SessionDataException()
            val step = readPosition(value, "step") ?: throw SessionDataException()
            return when (value.string("kind")) {
                "retry" -> {
                    if (recordType != "cursor") throw SessionDataException()
                    value.exactKeys("kind", "turn", "step")
                    Retry(turn, step)
                }
                "sample" -> {
                    if (recordType != "assistant/message" && recordType != "assistant/attempt") throw SessionDataException()
                    SessionJson.fields(value, setOf("kind", "turn", "step", "counts"), setOf("model"))
                    val counts = value.obj("counts")
                    counts.exactKeys("uncachedInputTokens", "outputTokens", "cacheReadTokens", "cacheWriteTokens")
                    val model = if ("model" in value.fields) {
                        if (recordType != "assistant/message") throw SessionDataException()
                        value.obj("model").also { it.exactKeys("provider", "model") }.let(::readModel) ?: throw SessionDataException()
                    } else null
                    Sample(turn, step, UsageCounts.fromProjection(counts) ?: throw SessionDataException(), model)
                }
                else -> throw SessionDataException()
            }
        }

        private fun readPosition(value: JsonObject, key: String): Long? {
            val n = (value.fields[key] as? JsonNumber)?.value ?: return null
            return n.toLong().takeIf { it in 0..MAX_SAFE_INTEGER && it.toDouble() == n }
        }

        private fun readModel(value: JsonObject): UsageModel? {
            val provider = (value.fields["provider"] as? JsonString)?.value?.takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
            val model = (value.fields["model"] as? JsonString)?.value?.takeIf { it.isNotBlank() && it.length <= 256 } ?: return null
            return UsageModel(provider, model)
        }

        private fun lastStreamUsage(data: JsonObject): JsonValue? {
            val stream = (data.fields["stream"] as? JsonArray)?.values ?: return null
            for (index in stream.indices.reversed()) {
                val record = stream[index] as? JsonObject ?: continue
                if ((record.fields["type"] as? JsonString)?.value != "chunk") continue
                val chunk = record.fields["chunk"] as? JsonObject ?: continue
                if ((chunk.fields["type"] as? JsonString)?.value == "usage") return chunk.fields["usage"]
            }
            return null
        }
    }
}
