package cn.edu.ustc.tokenworks

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import org.erdtman.jcs.JsonCanonicalizer

object WireBudget {
    const val FRAME_BYTES = 128 * 1024
    const val LOGICAL_BYTES = 2 * 1024 * 1024
    const val QUEUE_BYTES = 4 * 1024 * 1024
    const val QUEUE_COUNT = 256
    const val CHUNK_BYTES = 64 * 1024
}

/** Separate logical-frame parser: the normal document budget remains unchanged. */
object WireJson {
    private val factory = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(33)
            .maxStringLength(WireBudget.LOGICAL_BYTES).maxNameLength(WireBudget.FRAME_BYTES).maxNumberLength(1024).build()).build()

    private fun requireWireText(value: String) {
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (char.isLowSurrogate() || (char.isHighSurrogate() &&
                    (index == value.length || !value[index++].isLowSurrogate()))) throw ProtocolException("wire/invalid-frame")
        }
    }
    fun parse(text: String): JsonObject {
        if (text.toByteArray(Charsets.UTF_8).size > WireBudget.LOGICAL_BYTES) throw ProtocolException("wire/logical-limit")
        factory.createParser(text).use { parser ->
            var nodes = 0
            fun read(depth: Int): JsonValue {
                if (++nodes > 262_144 || depth > 32) throw ProtocolException("wire/json-limit")
                return when (parser.currentToken()) {
                    JsonToken.START_OBJECT -> {
                        val fields = linkedMapOf<String, JsonValue>()
                        while (parser.nextToken() != JsonToken.END_OBJECT) {
                            if (parser.currentToken() != JsonToken.FIELD_NAME) throw ProtocolException()
                            val key = parser.currentName()!!.also(::requireWireText)
                            parser.nextToken(); fields[key] = read(depth + 1)
                        }
                        JsonObject(fields)
                    }
                    JsonToken.START_ARRAY -> {
                        val values = arrayListOf<JsonValue>()
                        while (parser.nextToken() != JsonToken.END_ARRAY) values.add(read(depth + 1))
                        JsonArray(values)
                    }
                    JsonToken.VALUE_STRING -> JsonString(parser.text.also(::requireWireText))
                    JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT -> JsonNumber(parser.doubleValue.also { if (!it.isFinite()) throw ProtocolException() })
                    JsonToken.VALUE_TRUE -> JsonBoolean(true)
                    JsonToken.VALUE_FALSE -> JsonBoolean(false)
                    JsonToken.VALUE_NULL -> JsonNull
                    else -> throw ProtocolException()
                }
            }
            parser.nextToken()
            val root = read(0) as? JsonObject ?: throw ProtocolException()
            if (parser.nextToken() != null) throw ProtocolException()
            return root
        }
    }

    fun encode(root: JsonObject): String {
        val output = java.io.StringWriter()
        factory.createGenerator(output).use { writer ->
            var nodes = 0
            fun write(value: JsonValue, depth: Int) {
                if (++nodes > 262_144 || depth > 32) throw ProtocolException("wire/json-limit")
                when (value) {
                    is JsonObject -> { writer.writeStartObject(); value.fields.forEach { (key, item) -> requireWireText(key); writer.writeFieldName(key); write(item, depth + 1) }; writer.writeEndObject() }
                    is JsonArray -> { writer.writeStartArray(); value.values.forEach { write(it, depth + 1) }; writer.writeEndArray() }
                    is JsonString -> { requireWireText(value.value); writer.writeString(value.value) }
                    is JsonNumber -> { if (!value.value.isFinite()) throw ProtocolException(); writer.writeNumber(value.value) }
                    is JsonBoolean -> writer.writeBoolean(value.value)
                    JsonNull -> writer.writeNull()
                }
                if (output.buffer.length > WireBudget.LOGICAL_BYTES) throw ProtocolException("wire/logical-limit")
            }
            write(root, 0)
        }
        val text = JsonCanonicalizer(output.toString()).encodedString
        if (text.toByteArray(Charsets.UTF_8).size > WireBudget.LOGICAL_BYTES) throw ProtocolException("wire/logical-limit")
        return text
    }
}
