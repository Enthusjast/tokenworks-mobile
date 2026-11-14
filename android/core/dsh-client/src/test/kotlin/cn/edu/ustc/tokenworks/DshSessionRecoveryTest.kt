package cn.edu.ustc.tokenworks

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.KeyGenerator

private class TranscriptConnection(val mux: DshMux, val open: JsonObject) {
    private val id = open.string("streamId")
    fun item(value: JsonObject) = mux.receive(WireJson.encode(jsonObject("type" to "item".json(), "streamId" to id.json(), "value" to value)))
    fun revoke() = mux.receive("""{"type":"error","streamId":"$id","error":{"code":"access/revoked","message":"revoked","details":{}}}""")
}
private class TranscriptTransport : DshTransport {
    override val runtimeId = transcriptRuntime
    override val state = MutableStateFlow(DshTransportState(DshTransportPath.RELAY, DshTransportPhase.READY))
    val connections = Channel<TranscriptConnection>(Channel.UNLIMITED)
    val calls = Channel<Pair<String, JsonObject>>(Channel.UNLIMITED)
    var invoke: suspend (String, JsonObject) -> JsonValue = { _, _ -> JsonBoolean(true) }
    override suspend fun openMux(): DshMux {
        lateinit var mux: DshMux
        mux = DshMux({ text ->
            val frame = WireJson.parse(text)
            if (frame.string("type") == "open") connections.trySend(TranscriptConnection(mux, frame))
            true
        }, { _, _ -> })
        return mux
    }
    override suspend fun native(endpoint: String, args: JsonObject): JsonValue {
        calls.send(endpoint to args); return invoke(endpoint, args)
    }
    override suspend fun download(path: String, sink: OutputStream, maxBytes: Long, range: DshByteRange?): DshDownload = error("unused")
    override suspend fun upload(path: String, mediaType: String, source: InputStream, sizeBytes: Long): JsonValue = error("unused")
    override fun close() { }
}
private class TranscriptCacheProbe(private val delegate: DshTranscriptCache) : DshTranscriptCache by delegate {
    val saved = Channel<DshTranscriptSnapshot>(Channel.UNLIMITED)
    val cleared = CompletableDeferred<Unit>()
    override suspend fun save(snapshot: DshTranscriptSnapshot) { delegate.save(snapshot); saved.send(snapshot) }
    override suspend fun clear() { delegate.clear(); cleared.complete(Unit) }
}
private class MemoryTranscriptCache(var initial: DshTranscriptSnapshot? = null) : DshTranscriptCache {
    override val route = transcriptRoute
    val saved = Channel<DshTranscriptSnapshot>(Channel.UNLIMITED)
    val cleared = CompletableDeferred<Unit>()
    override suspend fun load() = initial
    override suspend fun save(snapshot: DshTranscriptSnapshot) { initial = snapshot; saved.send(snapshot) }
    override suspend fun clear() { initial = null; cleared.complete(Unit) }
}
private fun nativeTranscriptSnapshot(first: Long, last: Long, identity: DshTranscriptIdentity = transcriptIdentity): JsonObject = jsonObject(
    "type" to "snapshot".json(),
    "header" to identity.json().with("cwd" to "/isolated".json(), "isSeeded" to JsonBoolean(false), "agentPreset" to "fixture".json()),
    "cursor" to last.json(), "hasMore" to JsonBoolean(first > 0),
    "records" to JsonArray((first..last).map { jsonObject("type" to "event".json(), "event" to transcriptEvent(it)) }),
    "projections" to jsonObject("asOfSeq" to last.json(), "values" to jsonObject()),
    "assistantStream" to jsonObject("revision" to 0L.json()))
private fun nativeTranscriptEvent(event: JsonObject) = jsonObject("type" to "event".json(), "event" to event)
private fun assistantFrame(frame: JsonObject) = jsonObject("type" to "assistant-stream".json(), "frame" to frame)
private suspend fun DshSessionReader.ready(cursor: Long) = withTimeout(5000) { state.first { it.phase == SessionReadPhase.READY && it.window?.cursor == cursor } }

class DshSessionRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun disk() = EncryptedTranscriptStore(File(temporary.root, "history"), TranscriptKeyProvider { key })

    @Test fun restartShowsStaleReadOnlyCacheThenUsesFreshNativeBaselineAndNeverReplaysCommands() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstCache = TranscriptCacheProbe(disk().forRoute(transcriptRoute))
        val firstTransport = TranscriptTransport()
        val first = DshSessionReader(firstTransport, transcriptRoute.sessionId, owner, cache = firstCache)
        val secondTransport = TranscriptTransport()
        var second: DshSessionReader? = null
        try {
            val connection = withTimeout(5000) { firstTransport.connections.receive() }
            connection.item(nativeTranscriptSnapshot(0, 4))
            first.ready(4)
            assertEquals(4L, withTimeout(5000) { firstCache.saved.receive() }.cursor)
            first.close()
            val resumed = DshSessionReader(secondTransport, transcriptRoute.sessionId, owner, cache = disk().forRoute(transcriptRoute))
            second = resumed
            val reconnect = withTimeout(5000) { secondTransport.connections.receive() }
            assertTrue(resumed.state.value.stale); assertTrue(resumed.state.value.readOnly)
            assertFalse(resumed.state.value.running)
            assertEquals(4L, resumed.state.value.window!!.cursor)
            val commands: List<suspend () -> Unit> = listOf(
                { resumed.prompt("must not send", "00000000-0000-4000-8000-000000000002") },
                { resumed.cancel() }, { resumed.rename("name") }, { resumed.archive() },
                { resumed.selectModel("provider", "model") }, { resumed.fork(); Unit })
            for (command in commands) {
                try { command(); fail("cached view must not authorize commands") }
                catch (error: DshFailure) { assertEquals("session.read_only", error.code) }
            }
            assertTrue(secondTransport.calls.tryReceive().isFailure)
            reconnect.item(nativeTranscriptSnapshot(3, 6))
            val live = resumed.ready(6)
            // READY first publishes the native baseline; wait for exact-overlap repair to restore the prefix.
            val repaired = withTimeout(5000) { resumed.state.first { it.window?.records?.firstOrNull()?.seq == 0L && it.window?.cursor == 6L } }
            assertFalse(live.stale); assertFalse(repaired.readOnly)
            assertEquals((0L..6L).toList(), repaired.window!!.records.map { it.seq })
            assertTrue(secondTransport.calls.tryReceive().isFailure)
        } finally { first.close(); second?.close(); owner.cancel() }
    }

    @Test fun nativeGapRepairUsesFreshThroughSeqAndAuthenticatedOverlappingBeforeSeqPages() = runBlocking {
        val cache = MemoryTranscriptCache(transcriptSnapshot())
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = TranscriptTransport()
        transport.invoke = { endpoint, args ->
            assertEquals("session/page", endpoint)
            val request = args.obj("request")
            assertEquals(12L, request.integer("throughSeq", -1))
            val before = request.integer("beforeSeq", 0)
            val range = when (before) { 11L -> 7L..10L; 8L -> 4L..7L; else -> error("unexpected page") }
            jsonObject("records" to JsonArray(range.map { nativeTranscriptEvent(transcriptEvent(it)) }), "hasMore" to JsonBoolean(true))
        }
        val reader = DshSessionReader(transport, transcriptRoute.sessionId, owner, cache = cache)
        try {
            withTimeout(5000) { transport.connections.receive() }.item(nativeTranscriptSnapshot(10, 12))
            val repaired = withTimeout(5000) { reader.state.first { it.window?.cursor == 12L && it.window?.records?.firstOrNull()?.seq == 0L } }
            assertEquals(13, repaired.window!!.records.size)
            assertEquals(listOf(11L, 8L), List(2) { withTimeout(5000) { transport.calls.receive() }.second.obj("request").integer("beforeSeq", 0) })
        } finally { reader.close(); owner.cancel() }
    }

    @Test fun olderPagesUseLiveOpeningCursorAndCorrectWindowRequestIdentity() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = TranscriptTransport()
        transport.invoke = { endpoint, args ->
            assertEquals("session/page", endpoint)
            assertEquals(6L, args.obj("request").integer("throughSeq", -1))
            assertEquals(4L, args.obj("request").integer("beforeSeq", 0))
            jsonObject("records" to JsonArray((0L..3L).map { nativeTranscriptEvent(transcriptEvent(it)) }), "hasMore" to JsonBoolean(false))
        }
        val reader = DshSessionReader(transport, transcriptRoute.sessionId, owner)
        try {
            withTimeout(5000) { transport.connections.receive() }.item(nativeTranscriptSnapshot(3, 6))
            reader.ready(6)
            reader.loadOlder()
            assertEquals((0L..6L).toList(), reader.state.value.window!!.records.map { it.seq })
            assertFalse(reader.state.value.window!!.hasMore)
            assertNull(reader.state.value.pageError)
        } finally { reader.close(); owner.cancel() }
    }

    @Test fun authorityFailureDuringPagingIsTerminalAndClearsCachedHistory() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = TranscriptTransport()
        val cache = MemoryTranscriptCache()
        transport.invoke = { _, _ -> throw DshFailure("access/revoked") }
        val reader = DshSessionReader(transport, transcriptRoute.sessionId, owner, cache = cache)
        try {
            withTimeout(5000) { transport.connections.receive() }.item(nativeTranscriptSnapshot(3, 6))
            reader.ready(6)
            reader.loadOlder()
            val revoked = withTimeout(5000) { reader.state.first { it.phase == SessionReadPhase.REVOKED } }
            withTimeout(5000) { cache.cleared.await() }
            assertNull(revoked.window); assertTrue(revoked.readOnly)
            assertTrue(transport.connections.tryReceive().isFailure)
        } finally { reader.close(); owner.cancel() }
    }

    @Test fun rollbackHeaderConflictAndOverlapMismatchClearCacheWithoutMixingOldRecords() = runBlocking {
        for (kind in listOf("rollback", "header", "version", "overlap")) {
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val cache = MemoryTranscriptCache(transcriptSnapshot())
            val transport = TranscriptTransport()
            val reader = DshSessionReader(transport, transcriptRoute.sessionId, owner, cache = cache)
            try {
                var snapshot = when (kind) {
                    "rollback" -> nativeTranscriptSnapshot(2, 3)
                    "header" -> nativeTranscriptSnapshot(3, 6, transcriptIdentity.copy(createdAt = transcriptIdentity.createdAt + 1))
                    "version" -> nativeTranscriptSnapshot(3, 6, transcriptIdentity.copy(version = transcriptIdentity.version + 1))
                    else -> nativeTranscriptSnapshot(3, 6)
                }
                if (kind == "overlap") snapshot = snapshot.with("records" to JsonArray((3L..6L).map {
                    nativeTranscriptEvent(transcriptEvent(it, "replacement-$it"))
                }))
                withTimeout(5000) { transport.connections.receive() }.item(snapshot)
                val fresh = reader.ready(if (kind == "rollback") 3 else 6)
                withTimeout(5000) { cache.cleared.await() }
                assertEquals(if (kind == "rollback") 2L else 3L, fresh.window!!.records.first().seq)
                assertFalse(fresh.stale)
                assertTrue(transport.calls.tryReceive().isFailure)
            } finally { reader.close(); owner.cancel() }
        }
    }

    @Test fun revocationDeletesHistoryDropsVisibleCacheAndNeverReconnectsOrAuthorizesCommands() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cache = TranscriptCacheProbe(disk().forRoute(transcriptRoute))
        val transport = TranscriptTransport()
        val reader = DshSessionReader(transport, transcriptRoute.sessionId, owner, cache = cache)
        try {
            val connection = withTimeout(5000) { transport.connections.receive() }
            connection.item(nativeTranscriptSnapshot(0, 4)); reader.ready(4)
            withTimeout(5000) { cache.saved.receive() }
            connection.revoke()
            val revoked = withTimeout(5000) { reader.state.first { it.phase == SessionReadPhase.REVOKED } }
            withTimeout(5000) { cache.cleared.await() }
            assertNull(revoked.window); assertTrue(revoked.readOnly)
            assertNull(disk().forRoute(transcriptRoute).load())
            assertTrue(transport.connections.tryReceive().isFailure)
        } finally { reader.close(); owner.cancel() }
    }

    @Test fun slowCacheLoadAndSaveCannotBlockTheLiveNativeFollowerOrPublishLateStaleData() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val load = CompletableDeferred<DshTranscriptSnapshot?>()
        val loadReturned = CompletableDeferred<Unit>()
        val saveStarted = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        val cache = object : DshTranscriptCache {
            override val route = transcriptRoute
            override suspend fun load(): DshTranscriptSnapshot? = load.await().also { loadReturned.complete(Unit) }
            override suspend fun save(snapshot: DshTranscriptSnapshot) { saveStarted.complete(Unit); releaseSave.await() }
            override suspend fun clear() { }
        }
        val transport = TranscriptTransport()
        val reader = DshSessionReader(transport, transcriptRoute.sessionId, owner, cache = cache)
        try {
            val connection = withTimeout(5000) { transport.connections.receive() }
            connection.item(nativeTranscriptSnapshot(0, 1)); reader.ready(1)
            withTimeout(5000) { saveStarted.await() }
            connection.item(nativeTranscriptEvent(transcriptEvent(2)))
            reader.ready(2)
            load.complete(transcriptSnapshot(0, 99)); withTimeout(5000) { loadReturned.await() }
            assertEquals(2L, reader.state.value.window!!.cursor)
            assertFalse(reader.state.value.stale)
        } finally { releaseSave.complete(Unit); reader.close(); owner.cancel() }
    }

    @Test fun slowCacheStillRestoresReadOnlyHistoryWithoutWaitingForANativeBaseline() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val load = CompletableDeferred<DshTranscriptSnapshot?>()
        val cache = object : DshTranscriptCache {
            override val route = transcriptRoute
            override suspend fun load() = load.await()
            override suspend fun save(snapshot: DshTranscriptSnapshot) = error("Offline history must not be written as a live checkpoint")
            override suspend fun clear() { }
        }
        val transport = TranscriptTransport()
        val reader = DshSessionReader(transport, transcriptRoute.sessionId, owner, cache = cache)
        try {
            // Opening native follow proves the cache fast-path deadline has already elapsed.
            withTimeout(5000) { transport.connections.receive() }
            load.complete(transcriptSnapshot())
            val restored = withTimeout(5000) { reader.state.first { it.stale && it.window?.cursor == 4L } }
            assertTrue(restored.readOnly)
            assertTrue(restored.items.any { row -> row.blocks.any { it.text == "message-4" } })
            try { reader.prompt("Never send from a late offline view", "00000000-0000-4000-8000-000000000004"); fail("Offline command accepted") }
            catch (error: DshFailure) { assertEquals("session.read_only", error.code) }
            assertTrue(transport.calls.tryReceive().isFailure)
        } finally { reader.close(); owner.cancel() }
    }

    @Test fun transientAssistantAndPendingApprovalPayloadsNeverEnterDurableCache() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cache = MemoryTranscriptCache()
        val transport = TranscriptTransport()
        val reader = DshSessionReader(transport, transcriptRoute.sessionId, owner, cache = cache)
        try {
            val connection = withTimeout(5000) { transport.connections.receive() }
            connection.item(nativeTranscriptSnapshot(0, 0)); reader.ready(0)
            withTimeout(5000) { cache.saved.receive() }
            connection.item(assistantFrame(jsonObject("type" to "start".json(), "attemptId" to "live-attempt".json(), "revision" to 1L.json(), "startedAfterSeq" to 0L.json(), "turn" to 1L.json(), "step" to 1L.json())))
            connection.item(assistantFrame(jsonObject("type" to "chunk".json(), "attemptId" to "live-attempt".json(), "revision" to 2L.json(), "index" to 0L.json(), "time" to 1000L.json(), "chunk" to jsonObject("type" to "block-start".json(), "index" to 0L.json(), "blockType" to "text".json()))))
            connection.item(assistantFrame(jsonObject("type" to "chunk".json(), "attemptId" to "live-attempt".json(), "revision" to 3L.json(), "index" to 1L.json(), "time" to 1001L.json(), "chunk" to jsonObject("type" to "text-delta".json(), "index" to 0L.json(), "text" to "unsaved assistant secret".json()))))
            connection.item(nativeTranscriptEvent(jsonObject("type" to "approval/asked".json(), "seq" to 1L.json(), "time" to 1002L.json(), "data" to jsonObject("reason" to "pending approval secret".json()))))
            withTimeout(5000) { reader.state.first { it.window?.cursor == 1L && it.items.any { item -> item.live } } }
            val persisted = withTimeout(5000) { cache.saved.receive() }
            assertEquals(1L, persisted.cursor)
            val clear = SessionJson.encode(persisted.json()).toString(Charsets.UTF_8)
            assertFalse(clear.contains("unsaved assistant secret")); assertFalse(clear.contains("pending approval secret"))
            assertFalse(persisted.display().items.any { it.live })
        } finally { reader.close(); owner.cancel() }
    }
}
