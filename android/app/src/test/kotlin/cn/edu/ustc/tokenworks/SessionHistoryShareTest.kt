package cn.edu.ustc.tokenworks

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SessionHistoryShareTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun exportsPastTheDisplayLimitInOrderUsingAnExactNativeCut() = runBlocking {
        var calls = 0
        val end = 4105L
        val address = sessionHistoryAddress("child", DshSubagentAddress("parent", "continuable"))
        val connection = fixture { args ->
            val request = args.obj("request")
            val child = request.obj("address")
            assertEquals("subagent", child.string("kind"))
            assertEquals("child", child.string("childSessionId"))
            assertEquals("parent", child.string("parentSessionId"))
            assertEquals("continuable", child.string("mode"))
            val last = if (calls++ == 0) {
                assertFalse("throughSeq" in request.fields)
                assertFalse("beforeSeq" in request.fields)
                end
            } else {
                assertEquals(end, request.integer("throughSeq"))
                request.integer("beforeSeq") - 1
            }
            val first = (last - 99).coerceAtLeast(0)
            response(end, (first..last).map { record(it) }, first > 0)
        }
        val output = ByteArrayOutputStream()
        StagedSessionHistory(temp.root, null).use { staged ->
            staged.collect(connection, address, "会话", SessionExportFormat.JSONL)
            staged.writeTo(output)
        }
        val lines = output.toString(Charsets.UTF_8.name()).lineSequence().filter(String::isNotEmpty).toList()
        assertEquals((0L..end).toList(), lines.map { JsonDocument.parse(it).root.integer("seq", 0) })
        assertEquals("unknown but retained", JsonDocument.parse(lines.last()).root.string("futureField"))
        assertTrue(calls > 1)
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }

    @Test fun refusesDiscontinuousOrReplacedHistoryAndDoesNotExposeAStagedFile() = runBlocking {
        for (bad in listOf(record(1, "different session"), record(2))) {
            var calls = 0
            var pages = 0
            try {
                readSessionHistory(sessionHistoryAddress("id"), { _, _ ->
                    if (calls++ == 0) response(2, listOf(record(1), record(2)), true)
                    else response(2, listOf(record(0), bad), false)
                }) { pages++ }
                fail("inconsistent native page was accepted")
            } catch (_: ProtocolException) {
                assertEquals(1, pages)
            }
        }
    }

    @Test fun markdownInsulatesUntrustedTextAndShareAuthenticatesTheSamePlaintext() = runBlocking {
        val output = ByteArrayOutputStream()
        val copy = SessionHistoryMarkdownCopy("Snapshot", "No events.", "Events #0–#%1\$d.", "User", "Assistant", "Tool", "Event")
        StagedSessionHistory(temp.root, copy).use { staged ->
            staged.collect(fixture { response(0, listOf(record(0, "[link](https://invalid)\n<script>ignored</script>🙂")), false) },
                sessionHistoryAddress("id"), "# Untrusted title", SessionExportFormat.MARKDOWN)
            staged.writeTo(output)
            val expected = output.toString(Charsets.UTF_8.name())
            assertTrue(expected.startsWith("# Snapshot\n\n"))
            assertTrue(expected.contains("Events #0–#0.\n\n## User · #0\n\n"))
            assertTrue(expected.contains("    # Untrusted title\n"))
            assertTrue(expected.contains("    [link](https://invalid)\n    <script>ignored</script>🙂"))
            val sealed = staged.seal()
            assertTrue(sealed.fragment.matches(Regex("v1=[A-Za-z0-9_-]{43}")))
            assertEquals(1.toByte(), sealed.ciphertext[0])
            assertFalse(sealed.ciphertext.toString(Charsets.UTF_8).contains("Untrusted title"))
            val key = Base64.getUrlDecoder().decode(sealed.fragment.substringAfter('='))
            fun decrypt(): String {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed.ciphertext, 1, 12))
                cipher.updateAAD("tokenworks.session-share.v1".toByteArray(Charsets.US_ASCII))
                return cipher.doFinal(sealed.ciphertext, 13, sealed.ciphertext.size - 13).toString(Charsets.UTF_8)
            }
            assertEquals(expected, decrypt())
            sealed.ciphertext[13] = (sealed.ciphertext[13].toInt() xor 1).toByte()
            try { decrypt(); fail("unauthenticated payload decrypted") } catch (_: AEADBadTagException) { }
        }
    }

    private fun fixture(read: (JsonObject) -> JsonObject) = object : DshConnection {
        override suspend fun native(endpoint: String, args: JsonObject): JsonValue {
            assertEquals("session/page", endpoint)
            return read(args)
        }
        override suspend fun openMux(): DshMux = error("exports must not follow a UI window")
        override fun close() = Unit
    }

    private fun record(seq: Long, text: String = "原始内容🙂") = jsonObject(
        "seq" to seq.json(), "time" to 1700L.json(), "type" to "user/message".json(),
        "data" to jsonObject("content" to JsonArray(listOf(jsonObject("type" to "text".json(), "text" to text.json())))),
        "futureField" to "unknown but retained".json(),
    )

    private fun response(cursor: Long, events: List<JsonObject>, more: Boolean) = jsonObject(
        "cursor" to cursor.json(), "hasMore" to JsonBoolean(more),
        "records" to JsonArray(events.map { jsonObject("type" to "event".json(), "event" to it) }),
    )
}
