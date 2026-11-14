package cn.edu.ustc.tokenworks

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NativeGoldenTest {
    private val golden = File(System.getProperty("contracts.root"), "typert/golden")
    private fun fixture(name: String) = JsonDocument.parse(File(golden, name).readBytes()).root
    private fun same(expected: JsonObject, actual: JsonObject) = assertEquals(JsonDocument.of(expected).canonicalText(), JsonDocument.of(actual).canonicalText())

    @Test fun everyNativeInputAndResultExecutesTheGeneratedTypedCodecAgainstSharedVectors() {
        val coverage = mutableSetOf<String>()
        var accepted = 0
        var rejected = 0
        // JSONL permits each value to pass the production bounded JSON reader;
        // the aggregate test corpus does not weaken that reader's limits.
        File(golden, "native-vectors.jsonl").useLines { lines ->
            lines.forEach { line ->
                val vector = JsonDocument.parse(line).root
                val endpoint = vector.string("endpoint")
                val side = vector.string("side")
                assertEquals("schema-generated", vector.string("source"))
                coverage.add("$endpoint:$side")
                val entry = DshNativeRegistry.methods[endpoint] ?: error("Missing endpoint: $endpoint")
                val positive = (vector["accepted"] as JsonBoolean).value
                if (positive) {
                    try { same(vector.obj("expected"), entry.roundTrip(side, vector.obj("value"))) }
                    catch (failure: Exception) { throw AssertionError(vector.string("id"), failure) }
                    accepted++
                } else {
                    assertThrows(vector.string("id"), ProtocolException::class.java) { entry.roundTrip(side, vector.obj("value")) }
                    rejected++
                }
            }
        }
        assertEquals(38, DshNativeRegistry.methods.size)
        assertEquals(DshNativeRegistry.methods.flatMap { (endpoint, entry) -> listOf("$endpoint:input", "$endpoint:result") + if (entry.method.uplink != null) listOf("$endpoint:uplink") else emptyList() }.toSet(), coverage)
        assertTrue(accepted > 0)
        assertTrue(rejected > 0)
    }

    @Test fun nativeNumberCodecRejectsNonfiniteValuesButDoesNotImposeSafeIntegerBusinessRules() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(ProtocolException::class.java) { DshCodecs.number.decode(JsonNumber(value)) }
            assertThrows(ProtocolException::class.java) { DshCodecs.number.encode(value) }
        }
        for (value in listOf(Double.MIN_VALUE, -Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, 9_007_199_254_740_992.0, 0.5)) {
            assertEquals(value, DshCodecs.number.decode(DshCodecs.number.encode(value)), 0.0)
        }
    }

    @Test fun realCapturedCallsExecuteTheSameCodecsWithoutClaimingSchemaVectorsWereCaptured() {
        val capture = fixture("native-captured.json")
        assertEquals("isolated-installed-native-execution", capture.string("source"))
        assertEquals(DshMethods.VERSION, capture.string("dshVersion"))
        val endpoints = mutableSetOf<String>()
        for (value in capture.array("calls")) {
            val call = value as JsonObject
            val endpoint = call.string("endpoint")
            endpoints.add(endpoint)
            val entry = DshNativeRegistry.methods.getValue(endpoint)
            assertEquals(entry.method.streaming, (call["streaming"] as JsonBoolean).value)
            assertEquals(if (entry.method.streaming) "cancelled" else "returned", call.string("termination"))
            same(call.obj("input"), entry.roundTrip("input", call.obj("rawInput")))
            val rawResults = call.array("rawResults")
            val results = call.array("results")
            assertTrue(results.isNotEmpty())
            assertEquals(results.size, rawResults.size)
            for (index in results.indices) same(results[index] as JsonObject, entry.roundTrip("result", rawResults[index] as JsonObject))
            if (entry.method.uplink != null) {
                assertTrue(call.array("uplink").isNotEmpty())
                for (value in call.array("uplink")) {
                    val carrier = jsonObject("present" to JsonBoolean(true), "value" to value)
                    same(carrier, entry.roundTrip("uplink", carrier))
                }
            }
        }
        assertEquals(DshNativeRegistry.methods.keys, endpoints)
        assertEquals(0, capture.array("notCaptured").size)
        assertEquals(0.0, (capture.obj("execution")["networkAttempts"] as JsonNumber).value, 0.0)
    }

    @Test fun versionDecisionsConsumeTheSameTransportIndependentCompatibilityManifest() {
        val manifest = fixture("compatibility.json")
        assertEquals(DshMethods.VERSION, manifest.string("dshVersion"))
        assertEquals(DshNativeRegistry.methods.keys, manifest.array("endpoints").map { (it as JsonString).value }.toSet())
        for (value in manifest.array("cases")) {
            val vector = value as JsonObject
            assertEquals(vector.string("decision"), DshNativeRegistry.compatibility(vector["advertisedVersion"]))
        }
    }

    @Test fun nativeServerFramesRejectUnknownUnionsAndMalformedFieldsAndRetainMissingVersusNull() {
        var missing = false
        var explicitNull = false
        for (value in fixture("wire-vectors.json").array("vectors")) {
            val vector = value as JsonObject
            if (vector.string("side") != "server") continue
            if (!(vector["accepted"] as JsonBoolean).value) {
                assertThrows(vector.string("id"), ProtocolException::class.java) {
                    DshStreams.decode(vector["value"] as? JsonObject ?: throw ProtocolException())
                }
                continue
            }
            val original = vector.obj("value")
            val decoded = DshStreams.decode(original)
            val encoded = when (decoded) {
                is DshMuxFrame.Item -> {
                    if (decoded.value === DshOptional.Missing) missing = true
                    if (decoded.value is DshOptional.Value && decoded.value.value === JsonNull) explicitNull = true
                    val fields = linkedMapOf<String, JsonValue>("type" to "item".json(), "streamId" to decoded.streamId.json())
                    DshCodecs.put(fields, "value", decoded.value, DshCodecs.json)
                    JsonObject(fields)
                }
                is DshMuxFrame.End -> jsonObject("type" to "end".json(), "streamId" to decoded.streamId.json())
                is DshMuxFrame.Error -> jsonObject("type" to "error".json(), "streamId" to decoded.streamId.json(), "error" to jsonObject(
                    "code" to decoded.error.code.json(), "message" to decoded.error.message.json(), "details" to decoded.error.details))
            }
            same(vector.obj("expected"), encoded)
        }
        assertTrue(missing)
        assertTrue(explicitNull)
    }

    @Test fun nativeClientFramesAndEventOutcomesEncodeToTheNativeParserGoldens() {
        for (value in fixture("wire-vectors.json").array("vectors")) {
            val vector = value as JsonObject
            if (!(vector["accepted"] as JsonBoolean).value) continue
            val original = vector.obj("value")
            when (vector.string("side")) {
                "client" -> {
                    val id = original.string("streamId")
                    val request = when (original.string("type")) {
                        "open" -> DshMuxRequest.Open(id, original.string("endpoint"), original["payload"])
                        "item" -> DshMuxRequest.Item(id, DshCodecs.optional(original, "value", DshCodecs.json))
                        "end" -> DshMuxRequest.End(id)
                        "cancel" -> DshMuxRequest.Cancel(id)
                        else -> error("Unsupported native request golden")
                    }
                    same(vector.obj("expected"), DshStreams.encode(request))
                }
                "event-result" -> {
                    val outcome = original.obj("outcome")
                    val parsed = when (outcome.string("kind")) {
                        "next" -> DshEventOutcome.Next
                        "result" -> DshEventOutcome.Result(DshCodecs.optional(outcome, "value", DshCodecs.json))
                        "rejected" -> {
                            val error = outcome.obj("error")
                            DshEventOutcome.Rejected(error.string("name"), error.string("message"),
                                (error.fields["code"] as? JsonString)?.value, DshCodecs.optional(error, "details", DshCodecs.json))
                        }
                        else -> error("Unsupported native outcome golden")
                    }
                    same(vector.obj("expected"), DshStreams.result(DshEventResult(original.string("clientId"), original.string("eventId"), parsed)))
                }
            }
        }
        assertThrows(ProtocolException::class.java) { DshStreams.encode(DshMuxRequest.End("")) }
        assertThrows(ProtocolException::class.java) { DshStreams.encode(DshMuxRequest.Open("fixture", "", JsonNull)) }
        assertThrows(ProtocolException::class.java) { DshStreams.result(DshEventResult("", "fixture", DshEventOutcome.Next)) }
        assertThrows(ProtocolException::class.java) { DshMethods.WorkspaceDelete.output.encode(DshWorkspaceDeleteResult(deleted = false)) }
    }
}
