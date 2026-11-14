package cn.edu.ustc.tokenworks

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator

internal val transcriptRuntime = "00000000-0000-4000-8000-000000000001"
internal val transcriptRoute = DshCommandRoute("account/deployment", "host-A", transcriptRuntime, "session-native-string")
internal val transcriptIdentity = DshTranscriptIdentity(transcriptRoute.sessionId, 1794636286925, 4)
internal fun transcriptEvent(seq: Long, text: String = "message-$seq"): JsonObject = jsonObject(
    "type" to "user/message".json(), "seq" to seq.json(), "time" to (1000 + seq).json(),
    "data" to jsonObject("role" to "user".json(), "source" to jsonObject("kind" to "user".json()),
        "content" to JsonArray(listOf(jsonObject("type" to "text".json(), "text" to text.json())))))
internal fun transcriptRecords(first: Long, last: Long) = (first..last).map { DshTranscriptRecord.fromNative(transcriptEvent(it)) }
internal fun transcriptSnapshot(first: Long = 0, last: Long = 4) = DshTranscriptSnapshot(transcriptIdentity, transcriptRecords(first, last))

class DshTranscriptCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val directory get() = File(temporary.root, "history")
    private fun store() = EncryptedTranscriptStore(directory, TranscriptKeyProvider { key })
    private fun location(route: DshCommandRoute) = File(directory,
        listOf(route.ownerId, route.hostId, route.runtimeId).joinToString("/") { transcriptDigest(it.toByteArray()) } + "/" + transcriptDigest(route.sessionId.toByteArray()) + ".bin")

    @Test fun restartRestoresOnlyEncryptedDurableReadOnlyHistoryAndNeverUsesCallerThreadForIo() = runBlocking {
        val caller = Thread.currentThread()
        val storage = EncryptedTranscriptStore({ assertNotSame(caller, Thread.currentThread()); directory }, TranscriptKeyProvider {
            assertNotSame(caller, Thread.currentThread()); key
        })
        storage.forRoute(transcriptRoute).save(transcriptSnapshot())
        val restarted = store().forRoute(transcriptRoute).load()!!
        assertEquals(4L, restarted.cursor)
        assertEquals(transcriptIdentity, restarted.identity)
        assertTrue(restarted.display().durableOnly)
        assertFalse(restarted.display().taskRunning)
        assertEquals(5, restarted.display().items.size)
        val bytes = location(transcriptRoute).readBytes().toString(Charsets.ISO_8859_1)
        for (secret in listOf("message-", "account/deployment", "session-native-string", "host-A")) assertFalse(bytes.contains(secret))
        assertFalse(directory.walkTopDown().any { it.extension == "tmp" })
    }

    @Test fun corruptionMissingKeysAndEveryWrongRouteFieldFailClosedAndDeleteUnusableCiphertext() = runBlocking {
        val storage = store()
        storage.forRoute(transcriptRoute).save(transcriptSnapshot())
        val authentic = location(transcriptRoute).readBytes()
        val others = listOf(transcriptRoute.copy(ownerId = "other-account"), transcriptRoute.copy(hostId = "other-host"),
            transcriptRoute.copy(runtimeId = "other-runtime"), transcriptRoute.copy(sessionId = "other-session"))
        for (other in others) {
            val target = location(other)
            target.parentFile.mkdirs(); target.writeBytes(authentic)
            assertNull(storage.forRoute(other).load())
            assertFalse(target.exists())
        }
        assertNotNull(storage.forRoute(transcriptRoute).load())
        val damaged = authentic.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        location(transcriptRoute).writeBytes(damaged)
        assertNull(storage.forRoute(transcriptRoute).load())
        assertFalse(location(transcriptRoute).exists())
        location(transcriptRoute).writeBytes(authentic)
        val missing = EncryptedTranscriptStore(directory, TranscriptKeyProvider { create ->
            assertFalse(create); throw GeneralSecurityException("missing")
        })
        assertNull(missing.forRoute(transcriptRoute).load())
        assertFalse(location(transcriptRoute).exists())
    }

    @Test fun failedWritePreservesAtomicOldCheckpointAndLeavesNoPlaintextOrTemporaryBackup() = runBlocking {
        store().forRoute(transcriptRoute).save(transcriptSnapshot())
        val old = location(transcriptRoute).readBytes()
        val unavailable = EncryptedTranscriptStore(directory, TranscriptKeyProvider { throw GeneralSecurityException("locked") })
        try { unavailable.forRoute(transcriptRoute).save(transcriptSnapshot(0, 8)); fail("expected unavailable key") }
        catch (_: GeneralSecurityException) { }
        assertArrayEquals(old, location(transcriptRoute).readBytes())
        assertEquals(4L, store().forRoute(transcriptRoute).load()!!.cursor)
        assertTrue(directory.walkTopDown().filter { it.isFile }.all { it.extension == "bin" || it.name == ".lock" })
    }

    @Test fun explicitRuntimeHostOwnerAndLogoutClearPersistedHistoryAndInvalidateOldWriters() = runBlocking {
        val storage = store()
        val scoped = storage.forRoute(transcriptRoute)
        val otherRuntime = transcriptRoute.copy(runtimeId = "runtime-B")
        val otherHost = transcriptRoute.copy(hostId = "host-B")
        val otherOwner = transcriptRoute.copy(ownerId = "account-B")
        val handles = (listOf(transcriptRoute, otherRuntime, otherHost, otherOwner)).associateWith { storage.forRoute(it) }
        handles.values.forEach { it.save(transcriptSnapshot()) }
        storage.clearRuntime(transcriptRoute.ownerId, transcriptRoute.hostId, transcriptRoute.runtimeId)
        scoped.save(transcriptSnapshot()); handles.getValue(transcriptRoute).save(transcriptSnapshot())
        assertNull(store().forRoute(transcriptRoute).load())
        assertNotNull(handles.getValue(otherRuntime).load())
        storage.clearHost(transcriptRoute.ownerId, transcriptRoute.hostId)
        handles.getValue(otherRuntime).save(transcriptSnapshot())
        assertNull(store().forRoute(otherRuntime).load())
        assertNotNull(handles.getValue(otherHost).load())
        storage.clearOwner(transcriptRoute.ownerId)
        handles.getValue(otherHost).save(transcriptSnapshot())
        assertNull(store().forRoute(otherHost).load())
        assertNotNull(handles.getValue(otherOwner).load())
        storage.clearAll()
        handles.getValue(otherOwner).save(transcriptSnapshot())
        assertNull(store().forRoute(otherOwner).load())
    }

    @Test fun onlyDisplayTranscriptDataIsCachedAndFingerprintCoversExcludedNativeMetadata() {
        val secret = "pending-approval-or-credential"
        val event = jsonObject("seq" to 0L.json(), "time" to 1000L.json(), "type" to "approval/asked".json(), "data" to jsonObject("reason" to secret.json()))
        val first = DshTranscriptRecord.fromNative(event)
        val changed = DshTranscriptRecord.fromNative(event.with("data" to jsonObject("reason" to "different".json())))
        assertEquals("cursor", first.record.type)
        assertFalse(SessionJson.encode(first.json()).toString(Charsets.UTF_8).contains(secret))
        assertNotEquals(first.fingerprint, changed.fingerprint)
        assertTrue(SessionJson.same(first.record.raw, changed.record.raw))
        val reordered = JsonObject(event.fields.entries.reversed().associate { it.key to it.value })
        assertEquals(first.fingerprint, DshTranscriptRecord.fromNative(reordered).fingerprint)
    }

    @Test fun nativeImagesRemainPreviewableAfterEncryptedRestartButNeverCarryReceiptsOrSourceAuthority() = runBlocking {
        val attachment = jsonObject("attachmentId" to "sha256:${"a".repeat(64)}".json(), "mediaType" to "image/png".json(),
            "bytes" to 123L.json(), "width" to 8L.json(), "height" to 6L.json(), "name" to "public.png".json(),
            "originalDimensions" to jsonObject("width" to 16L.json(), "height" to 12L.json()))
        val content = JsonArray(listOf(jsonObject("type" to "text".json(), "text" to "look".json()),
            jsonObject("type" to "image".json(), "attachment" to attachment, "receiptId" to "private-receipt".json())))
        val event = transcriptEvent(0).with("data" to transcriptEvent(0).obj("data").with("content" to content,
            "source" to jsonObject("kind" to "user".json(), "credential" to "private-source".json())))
        val projected = DshTranscriptRecord.fromNative(event)
        val changed = DshTranscriptRecord.fromNative(event.with("data" to event.obj("data").with("source" to
            jsonObject("kind" to "user".json(), "credential" to "different-source".json()))))
        assertNotEquals(projected.fingerprint, changed.fingerprint)
        assertTrue(SessionJson.same(projected.record.raw, changed.record.raw))
        val encoded = SessionJson.encode(projected.json()).toString(Charsets.UTF_8)
        assertFalse(encoded.contains("private-receipt")); assertFalse(encoded.contains("private-source"))
        assertFalse(encoded.contains("originalDimensions")); assertFalse(encoded.contains("manifest"))
        store().forRoute(transcriptRoute).save(DshTranscriptSnapshot(transcriptIdentity, listOf(projected)))
        val restored = store().forRoute(transcriptRoute).load()!!
        assertEquals(projected.fingerprint, restored.records.single().fingerprint)
        val display = restored.display()
        assertTrue(display.durableOnly)
        val item = display.items.single()
        assertTrue(item.persisted); assertFalse(item.live)
        assertEquals(listOf(BlockKind.TEXT, BlockKind.IMAGE), item.blocks.map { it.kind })
        assertEquals("look", item.blocks.first().text)
        val image = item.blocks.last().media!!
        assertEquals("sha256:${"a".repeat(64)}", image.attachmentId)
        assertEquals("image/png", image.mediaType); assertEquals(123L, image.bytes)
        assertEquals(8, image.width); assertEquals(6, image.height); assertEquals("public.png", image.name)
    }

    @Test fun nativeToolResultKeepsImageAndFileMetadataWithoutLosingTextErrorOrTurnIdentity() {
        val image = NativeImageReference("sha256:${"c".repeat(64)}", "image/png", 100, 10, 8, "tool.png")
        val content = JsonArray(listOf(
            jsonObject("type" to "text".json(), "text" to "tool detail".json()),
            jsonObject("type" to "image".json(), "attachment" to image.json()),
            jsonObject("type" to "file".json(), "attachment" to jsonObject("attachmentId" to "sha256:${"d".repeat(64)}".json(),
                "name" to "report.txt".json(), "bytes" to 321L.json()), "receiptId" to "private-file-receipt".json()),
        ))
        val message = jsonObject("role" to "tool".json(), "callId" to "call-9".json(), "isError" to JsonBoolean(true), "content" to content)
        val native = jsonObject("seq" to 9L.json(), "time" to 1009L.json(), "type" to "tool/result".json(),
            "data" to jsonObject("turn" to 2L.json(), "step" to 3L.json(), "message" to message))
        val record = DshTranscriptRecord.fromNative(native).record
        assertEquals("tool/result", record.type)
        assertEquals(2L, record.data.integer("turn", 0)); assertEquals(3L, record.data.integer("step", 0))
        assertEquals("call-9", record.data.obj("message").string("callId"))
        assertEquals(JsonBoolean(true), record.data.obj("message")["isError"])
        assertEquals(listOf(BlockKind.TEXT, BlockKind.IMAGE, BlockKind.TEXT), record.blocks.map { it.kind })
        assertEquals("tool detail", record.blocks.first().text)
        assertTrue(image.same(record.blocks[1].media!!))
        assertEquals("[文件：report.txt，321 字节]", record.blocks.last().text)
        assertFalse(SessionJson.encode(record.raw).toString(Charsets.UTF_8).contains("private-file-receipt"))
    }

    @Test fun recordAndByteBudgetsRetainTheLastContiguousDurableWindow() {
        // Small metadata markers exercise the count bound independently of the byte bound below.
        val rows = (0L..699).map { DshTranscriptRecord.fromNative(transcriptEvent(it).with("type" to "model/selection".json(), "data" to jsonObject())) }
        val tail = DshTranscriptSnapshot.tail(transcriptIdentity, rows)
        assertEquals(DshTranscriptSnapshot.MAX_RECORDS, tail.records.size)
        assertEquals(699L, tail.cursor)
        assertEquals(188L, tail.records.first().seq)
        val weighted = (0L..79).map { DshTranscriptRecord.fromNative(transcriptEvent(it, "x".repeat(20_000))) }
        val bounded = DshTranscriptSnapshot.tail(transcriptIdentity, weighted)
        assertTrue(bounded.records.sumOf { it.weight } <= DshTranscriptSnapshot.MAX_WEIGHT)
        assertEquals(79L, bounded.cursor)
        bounded.records.zipWithNext().forEach { (a, b) -> assertEquals(a.seq + 1, b.seq) }
        val previous = weighted.firstOrNull { it.seq == bounded.records.first().seq - 1 }
        assertTrue(previous == null || bounded.records.sumOf { it.weight } + previous.weight > DshTranscriptSnapshot.MAX_WEIGHT)
    }

    @Test fun actualCapturedNativeFollowAndPageDecodeWithoutLosingVisibleUserOrAssistantText() {
        val capture = WireJson.parse(File(System.getProperty("contracts.root"), "typert/golden/native-captured.json").readText())
        val calls = capture.array("calls").map { it as JsonObject }
        val follow = calls.first { it.string("endpoint") == "session/follow" }.array("rawResults").first() as JsonObject
        val snapshot = follow.obj("value")
        DshMethods.SessionFollow.output.decode(snapshot)
        val identity = DshTranscriptIdentity.parse(snapshot.obj("header"))
        val records = snapshot.array("records").map { DshTranscriptRecord.fromNative((it as JsonObject).obj("event")) }
        val display = DshTranscriptSnapshot.tail(identity, records).display()
        val text = display.items.flatMap { it.blocks }.joinToString("\n") { it.text }
        assertTrue(text.contains("isolated capture 原样🙂"))
        assertTrue(text.contains("DSH local integration fixture response"))
        assertTrue(text.contains("native.txt"))
        assertTrue(display.items.flatMap { it.blocks }.any { it.kind == BlockKind.IMAGE && it.media?.name == "fixture.png" })
        assertFalse(text.contains("Current runtime context"))
        val pageCall = calls.first { it.string("endpoint") == "session/page" }.array("rawResults").first() as JsonObject
        val page = DshTranscriptPage.decode(pageCall["value"])
        assertEquals(records.size, page.records.size)
        records.zip(page.records).forEach { (a, b) -> assertTrue(a.same(b)) }
    }
}
