package cn.edu.ustc.tokenworks


const val DSH_MUX_PATH = "/api/remote.mux"
const val DSH_EVENTS_ENDPOINT = "\$events"
const val DSH_EVENTS_RESULT_ENDPOINT = "\$events/result"

data class DshRemoteFailure(val code: String, val message: String, val details: JsonObject)

sealed interface DshMuxRequest {
    val streamId: String
    data class Open(override val streamId: String, val endpoint: String, val payload: JsonValue) : DshMuxRequest
    data class Item(override val streamId: String, val value: DshOptional<JsonValue>) : DshMuxRequest
    data class End(override val streamId: String) : DshMuxRequest
    data class Cancel(override val streamId: String) : DshMuxRequest
}

sealed interface DshMuxFrame {
    val streamId: String
    data class Item(override val streamId: String, val value: DshOptional<JsonValue>) : DshMuxFrame
    data class Error(override val streamId: String, val error: DshRemoteFailure) : DshMuxFrame
    data class End(override val streamId: String) : DshMuxFrame
}

sealed interface DshEventFrame {
    data class Ready(val clientId: String, val home: String) : DshEventFrame
    data class Emit(val event: String, val args: List<JsonValue>) : DshEventFrame
    data class Waterfall(val event: String, val eventId: String, val agentId: String, val request: JsonObject) : DshEventFrame
    data class Cancel(val eventId: String) : DshEventFrame
}

sealed interface DshEventOutcome {
    data object Next : DshEventOutcome
    data class Result(val value: DshOptional<JsonValue> = DshOptional.Missing) : DshEventOutcome
    data class Rejected(val name: String, val message: String, val code: String? = null, val details: DshOptional<JsonValue> = DshOptional.Missing) : DshEventOutcome
}

data class DshEventResult(val clientId: String, val eventId: String, val outcome: DshEventOutcome)

object DshStreams {
    fun encode(request: DshMuxRequest): JsonObject {
        requireId(request.streamId)
        val fields = linkedMapOf<String, JsonValue>("streamId" to JsonString(request.streamId))
        fields["type"] = JsonString(when (request) {
            is DshMuxRequest.Open -> "open"
            is DshMuxRequest.Item -> "item"
            is DshMuxRequest.End -> "end"
            is DshMuxRequest.Cancel -> "cancel"
        })
        when (request) {
            is DshMuxRequest.Open -> {
                if (request.endpoint.isEmpty()) throw ProtocolException()
                fields["endpoint"] = JsonString(request.endpoint)
                fields["payload"] = request.payload
            }
            is DshMuxRequest.Item -> DshCodecs.put(fields, "value", request.value, DshCodecs.json)
            else -> Unit
        }
        return JsonObject(fields)
    }

    fun decode(value: JsonObject): DshMuxFrame {
        val id = value.string("streamId").also(::requireId)
        return when (value.string("type")) {
            "item" -> {
                keys(value, setOf("type", "streamId"), setOf("value"))
                DshMuxFrame.Item(id, DshCodecs.optional(value, "value", DshCodecs.json))
            }
            "end" -> { value.exactKeys("type", "streamId"); DshMuxFrame.End(id) }
            "error" -> {
                value.exactKeys("type", "streamId", "error")
                val error = value.obj("error")
                error.exactKeys("code", "message", "details")
                DshMuxFrame.Error(id, DshRemoteFailure(error.string("code"), error.string("message"), error.obj("details")))
            }
            else -> throw ProtocolException("native: unsupported stream frame")
        }
    }

    fun event(value: JsonObject): DshEventFrame = when (value.string("type")) {
        "ready" -> DshEventFrame.Ready(value.string("clientId").also(::requireEventId), value.obj("host").string("home"))
        "emit" -> DshEventFrame.Emit(value.string("event"), value.array("args"))
        "waterfall" -> DshEventFrame.Waterfall(value.string("event"), value.string("eventId").also(::requireEventId), value.string("agentId").also(::requireEventId), value.obj("request"))
        "cancel" -> DshEventFrame.Cancel(value.string("eventId").also(::requireEventId))
        else -> throw ProtocolException("native: unsupported event frame")
    }

    fun result(result: DshEventResult): JsonObject {
        requireEventId(result.clientId)
        requireEventId(result.eventId)
        val fields = linkedMapOf<String, JsonValue>()
        when (val outcome = result.outcome) {
            DshEventOutcome.Next -> fields["kind"] = JsonString("next")
            is DshEventOutcome.Result -> {
                fields["kind"] = JsonString("result")
                DshCodecs.put(fields, "value", outcome.value, DshCodecs.json)
            }
            is DshEventOutcome.Rejected -> {
                fields["kind"] = JsonString("rejected")
                val error = linkedMapOf<String, JsonValue>("name" to JsonString(outcome.name), "message" to JsonString(outcome.message))
                if (outcome.name.isEmpty()) throw ProtocolException()
                outcome.code?.let { error["code"] = JsonString(it) }
                DshCodecs.put(error, "details", outcome.details, DshCodecs.json)
                fields["error"] = JsonObject(error)
            }
        }
        return jsonObject("clientId" to JsonString(result.clientId), "eventId" to JsonString(result.eventId), "outcome" to JsonObject(fields))
    }

    private fun keys(value: JsonObject, required: Set<String>, optional: Set<String>) {
        if (!value.fields.keys.containsAll(required) || value.fields.keys.any { it !in required && it !in optional }) throw ProtocolException()
    }
    private fun requireId(id: String) {
        if (id.isEmpty()) throw ProtocolException()
    }
    private fun requireEventId(id: String) {
        if (id.isEmpty()) throw ProtocolException()
    }
}
