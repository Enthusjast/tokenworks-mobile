package cn.edu.ustc.tokenworks

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** An addressed child is read through its durable parent link, never as an ordinary session. */
data class DshSubagentAddress(val parentSessionId: String, val mode: String)

/** One native follower. Cached history is display-only; every command needs a live baseline. */
class DshSessionReader(
    private val transport: DshTransport,
    val sessionId: String,
    owner: CoroutineScope,
    private val maxMessages: Int = 30,
    private val cache: DshTranscriptCache? = null,
    private val subagent: DshSubagentAddress? = null,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob(owner.coroutineContext[Job]) + Dispatchers.Default)
    private val mutable = MutableStateFlow(SessionReadState(SessionReadPhase.CONNECTING))
    val state: StateFlow<SessionReadState> = mutable.asStateFlow()
    private val windowLock = Any()
    private var window = SessionWindow("native", retainTail = true)
    private val nativeRecords = TreeMap<Long, DshTranscriptRecord>()
    private var identity: DshTranscriptIdentity? = null
    @Volatile private var retained: DshTranscriptSnapshot? = null
    private val cacheDisabled = AtomicBoolean(cache == null)
    private val saves = Channel<DshTranscriptSnapshot>(Channel.CONFLATED)
    private val closed = AtomicBoolean()
    private val authorityLost = AtomicBoolean()
    private var mux: DshMux? = null
    private var stream: DshStream? = null

    init {
        Contract.uuid(transport.runtimeId)
        Contract.boundedText(sessionId, 128)
        require(maxMessages > 0)
        require(cache == null || cache.route.sessionId == sessionId && cache.route.runtimeId == transport.runtimeId)
        scope.launch {
            if (cache != null) scope.launch(Dispatchers.IO) {
                for (snapshot in saves) {
                    if (!cacheDisabled.get()) try { cache.save(snapshot) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { cacheDisabled.set(true) }
                }
            }
            restore()
            try { follow() } finally { saves.close() }
        }
        scope.coroutineContext[Job]!!.invokeOnCompletion { close() }
    }

    private suspend fun restore() {
        val store = cache ?: return
        // Live follow waits at most 250 ms; slower Keystore reads can still restore an offline view.
        val pending = scope.launch(Dispatchers.IO) {
            val saved = try { store.load() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null } ?: return@launch
            synchronized(windowLock) {
                if (closed.get() || authorityLost.get() || cacheDisabled.get() || window.ready) return@launch
                if (saved.identity.id != sessionId) { discardCache(); return@launch }
                retained = saved
                publish(mutable.value.copy(window = saved.display(), stale = true))
            }
        }
        withTimeoutOrNull(250) { pending.join() }
    }

    /** Caller owns requestId; the reader never replays an offline command. */
    suspend fun prompt(text: String, requestId: String, mode: String = "queue", receipts: List<String> = emptyList(),
                       images: List<NativeImagePart> = emptyList()) {
        requireReady()
        require(mode in setOf("queue", "steer")) { "prompt mode invalid" }
        val savedReceipts = receipts.toList()
        val savedImages = images.toList()
        validateNativePrompt(text, savedReceipts, savedImages)
        val content = buildList<JsonValue> {
            if (text.isNotEmpty()) add(jsonObject("type" to "text".json(), "text" to text.json()))
            savedReceipts.forEach { add(jsonObject("type" to "file".json(), "receiptId" to it.json())) }
            savedImages.forEach { add(it.promptPart()) }
        }
        val args = jsonObject("request" to jsonObject("requestId" to requestId.json(), "sessionId" to sessionId.json(),
            "mode" to mode.json(), "content" to JsonArray(content)))
        DshMethods.SessionPrompt.input.decode(args)
        transport.native("session/prompt", args)
    }

    suspend fun cancel() = unit("session/cancel", DshMethods.SessionCancel.input, DshSessionCancelInput(DshSessionCancelInputRequest(sessionId)))
    suspend fun rename(title: String) = unit("session/rename", DshMethods.SessionRename.input, DshSessionRenameInput(DshSessionRenameInputRequest(sessionId, title)))
    suspend fun archive() = unit("workspace/archiveSession", DshMethods.WorkspaceArchiveSession.input, DshWorkspaceArchiveSessionInput(DshWorkspaceArchiveSessionInputRequest(sessionId)))
    suspend fun selectModel(provider: String, model: String, reasoningEffort: String? = null) =
        unit("session/selectModel", DshMethods.SessionSelectModel.input,
            DshSessionSelectModelInput(DshSessionSelectModelInputRequest(sessionId, provider, model, reasoningEffort?.let { DshOptional.Value(it) } ?: DshOptional.Missing)))

    suspend fun fork(): String {
        requireReady()
        val value = call("session/fork", DshMethods.SessionFork.input, DshSessionForkInput(DshSessionForkInputRequest(sessionId)))
        return DshMethods.SessionFork.output.decode(value).sessionId
    }

    private fun requireReady() {
        if (closed.get() || mutable.value.readOnly || subagent != null) throw DshFailure("session.read_only")
    }
    private suspend fun <I> unit(endpoint: String, codec: DshCodec<I>, input: I) { requireReady(); call(endpoint, codec, input) }
    private suspend fun <I> call(endpoint: String, codec: DshCodec<I>, input: I): JsonValue =
        transport.native(endpoint, codec.encode(input) as JsonObject)

    /** Older history is tied to the fresh follow opening cursor, never a persisted cursor. */
    suspend fun loadOlder() {
        if (mutable.value.readOnly) return
        val id = UUID.randomUUID().toString()
        val pending = synchronized(windowLock) {
            val request = window.page(id) ?: return
            Triple(window, request.payload.integer("through_seq", -1), request.payload.integer("before_seq", 0))
        }
        publish(mutable.value.copy(loadingOlder = true, pageError = null))
        try {
            // Include one already-held native event, authenticating the page boundary even after a wipe.
            val page = nativePage(pending.second, pending.third + 1)
            synchronized(windowLock) {
                if (window !== pending.first || mutable.value.readOnly) { pending.first.pageFailed(id); return }
                val boundary = nativeRecords[pending.third] ?: throw ProtocolException()
                if (page.records.last().seq != boundary.seq || !page.records.last().same(boundary) || page.records.size < 2) {
                    discardCache(); throw SessionDataException()
                }
                val entries = page.records.dropLast(1)
                window.accept(SessionContent("session.page.result", id, window.syncId!!, jsonObject(
                    "records" to JsonArray(entries.map { it.record.raw }), "has_more" to JsonBoolean(page.hasMore),
                    "through_seq" to pending.second.json())))
                entries.forEach { nativeRecords[it.seq] = it }
                publish(SessionReadState(SessionReadPhase.READY, window.snapshot()))
                remember()
            }
        } catch (cancelled: CancellationException) {
            synchronized(windowLock) { pending.first.pageFailed(id) }
            throw cancelled
        } catch (error: Exception) {
            synchronized(windowLock) {
                pending.first.pageFailed(id)
                val code = safeCode(error)
                val phase = phaseFor(code)
                if (phase == SessionReadPhase.REVOKED || phase == SessionReadPhase.AUTH_REQUIRED || code == "session/not-found") {
                    authorityLost.set(true); retained = null; discardCache()
                    publish(SessionReadState(phase, error = code))
                    runCatching { stream?.close() }
                } else if (window === pending.first && !mutable.value.readOnly) publish(SessionReadState(SessionReadPhase.READY, window.snapshot(), pageError = code))
            }
        }
    }

    private fun address(): DshSessionFollowInputRequestAddress = subagent?.let {
        DshSessionFollowInputRequestAddress.Case2(DshSessionFollowInputRequestAddressCase2("subagent", it.parentSessionId, sessionId, it.mode))
    } ?: DshSessionFollowInputRequestAddress.Case1(DshSessionFollowInputRequestAddressCase1("session", sessionId))
    private fun pageAddress(): DshSessionPageInputRequestAddress = subagent?.let {
        DshSessionPageInputRequestAddress.Case2(DshSessionPageInputRequestAddressCase2("subagent", it.parentSessionId, sessionId, it.mode))
    } ?: DshSessionPageInputRequestAddress.Case1(DshSessionPageInputRequestAddressCase1("session", sessionId))
    private suspend fun nativePage(openingCursor: Long, beforeSeq: Long): DshTranscriptPage {
        val request = DshSessionPageInputRequest(pageAddress(), DshOptional.Value(openingCursor.toDouble()), DshOptional.Value(beforeSeq.toDouble()), DshOptional.Value(maxOf(2, maxMessages).toDouble()))
        return DshTranscriptPage.decode(call("session/page", DshMethods.SessionPage.input, DshSessionPageInput(request)))
    }

    private suspend fun follow() {
        var attempt = 0
        while (!closed.get() && !authorityLost.get() && scope.isActive) {
            try {
                if (attempt > 0) publish(SessionReadState(SessionReadPhase.BACKOFF, retained?.display(), stale = true))
                val socket = transport.openMux()
                mux = socket
                // The generated native request is authoritative; there is no afterCursor extension.
                val request = DshSessionFollowInputRequest(address = address(), assistantStream = DshOptional.Value(true), maxMessages = DshOptional.Value(maxMessages.toDouble()))
                val opened = socket.open("session/follow", jsonObject("args" to DshMethods.SessionFollow.input.encode(DshSessionFollowInput(request))))
                stream = opened
                while (!closed.get() && !authorityLost.get()) {
                    when (val frame = opened.next() ?: break) {
                        is DshMuxFrame.Item -> {
                            val value = (frame.value as? DshOptional.Value)?.value ?: continue
                            DshMethods.SessionFollow.output.decode(value)
                            val root = value as? JsonObject ?: throw ProtocolException()
                            if (root.string("type") == "snapshot") baseline(root) else synchronized(windowLock) {
                                if (!window.ready) throw ProtocolException()
                                when (root.string("type")) {
                                    "event" -> {
                                        val record = DshTranscriptRecord.fromNative(root.obj("event"))
                                        nativeRecords[record.seq]?.let { if (!it.same(record)) { discardCache(); throw SessionDataException() } }
                                        window.accept(SessionContent("session.event", "native", window.syncId!!, record.record.raw))
                                        nativeRecords[record.seq] = record
                                        checkpoint(window)
                                        val first = window.firstSeq
                                        if (first != null) nativeRecords.headMap(first).clear()
                                        remember()
                                    }
                                    "assistant-stream" -> {
                                        val frameValue = root.obj("frame")
                                        val kind = frameValue.string("type")
                                        window.accept(SessionContent("assistant.$kind", "native", window.syncId!!, attempt(frameValue)))
                                        if (kind == "end") remember()
                                    }
                                    else -> throw ProtocolException()
                                }
                                publish(SessionReadState(SessionReadPhase.READY, window.snapshot()))
                            }
                            attempt = 0
                        }
                        is DshMuxFrame.Error -> throw DshFailure(frame.error.code)
                        is DshMuxFrame.End -> break
                    }
                }
                if (closed.get()) return
                throw DshFailure("session.stream_ended")
            } catch (cancelled: CancellationException) {
                return
            } catch (error: Exception) {
                if (closed.get() || authorityLost.get()) return
                val code = safeCode(error)
                val phase = phaseFor(code)
                synchronized(windowLock) {
                    if (phase == SessionReadPhase.REVOKED || phase == SessionReadPhase.AUTH_REQUIRED || code == "session/not-found") {
                        authorityLost.set(true); retained = null; discardCache()
                        publish(SessionReadState(phase, error = code))
                    } else publish(SessionReadState(phase, retained?.display(), error = code, stale = true))
                }
                if (phase != SessionReadPhase.BACKOFF) return
                delay(DshRecoveryPolicy.backoffMillis(attempt++))
            } finally {
                runCatching { stream?.close() }; stream = null
                runCatching { mux?.close() }; mux = null
            }
        }
    }

    private suspend fun baseline(root: JsonObject) {
        val header = root.obj("header")
        val nextIdentity = DshTranscriptIdentity.parse(header)
        val child = (header.fields["origin"] as? JsonString)?.value == "subagent"
        if (nextIdentity.id != sessionId || child != (subagent != null) ||
            (subagent != null && (header.fields["parentSession"] as? JsonString)?.value != subagent.parentSessionId)) {
            discardCache()
            throw ProtocolException()
        }
        val cursor = root.integer("cursor", -1)
        val records = root.array("records").map { DshTranscriptRecord.fromNative((it as? JsonObject ?: throw ProtocolException()).obj("event")) }
        val assistant = assistant(root.fields["assistantStream"] ?: JsonNull)
        val previous = retained
        val fresh = newWindow(records, cursor, assistant)
        val firstAtBaseline = fresh.firstSeq
        if (SessionJson.boolean(root, "hasMore") != (records.firstOrNull()?.seq?.let { it > 0 } ?: false)) throw ProtocolException()
        synchronized(windowLock) {
            identity = nextIdentity; window = fresh
            installRecords(records)
            publish(SessionReadState(SessionReadPhase.READY, window.snapshot()))
        }
        if (previous != null) {
            var repairError: String? = null
            val repaired = try {
                withTimeoutOrNull(3000) {
                    DshTranscriptRepair.resume(previous, nextIdentity, cursor, records) { before -> nativePage(cursor, before) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val action = DshRecoveryPolicy.classify(error).action
                if (action == DshRecoveryAction.REVOKED || action == DshRecoveryAction.AUTH_REQUIRED) throw error
                repairError = safeCode(error)
                null
            }
            if (authorityLost.get()) return
            if (repaired == null || repaired.conflict) {
                discardCache()
                if (repairError != null) publish(SessionReadState(SessionReadPhase.READY, window.snapshot(), pageError = repairError))
            }
            else synchronized(windowLock) {
                // loadOlder can run after the authoritative baseline; do not overwrite a page it installed.
                if (window === fresh && !window.paging && window.firstSeq == firstAtBaseline) {
                    val joined = newWindow(repaired.records, cursor, assistant)
                    window = joined
                    installRecords(repaired.records)
                    publish(SessionReadState(SessionReadPhase.READY, window.snapshot()))
                }
            }
        }
        synchronized(windowLock) { if (!authorityLost.get()) remember() }
    }

    private fun newWindow(records: List<DshTranscriptRecord>, cursor: Long, assistant: JsonValue): SessionWindow {
        if (records.lastOrNull()?.seq != cursor.takeIf { it >= 0 }) throw ProtocolException()
        for (i in 1 until records.size) if (records[i].seq != records[i - 1].seq + 1) throw ProtocolException()
        var first = records.size
        var weight = 0L
        while (first > 0 && records.size - first < SessionWindow.MAX_RECORDS && weight + records[first - 1].record.weight <= SessionWindow.MAX_RETAINED_WEIGHT) {
            weight += records[--first].record.weight
        }
        val tail = records.subList(first, records.size)
        return SessionWindow("native", retainTail = true).apply {
            accept(SessionContent("session.snapshot", "native", UUID.randomUUID().toString(), jsonObject(
                "cursor" to cursor.json(), "has_more" to JsonBoolean(tail.firstOrNull()?.seq?.let { it > 0 } ?: false),
                "records" to JsonArray(tail.map { it.record.raw }), "assistant" to assistant)))
            checkpoint(this)
        }
    }

    private fun installRecords(records: List<DshTranscriptRecord>) {
        nativeRecords.clear()
        val first = window.firstSeq ?: return
        records.forEach { if (it.seq >= first) nativeRecords[it.seq] = it }
    }

    /** Native follow/page records are committed history; assistant-stream frames are not. */
    private fun checkpoint(target: SessionWindow) {
        target.accept(SessionContent("session.checkpoint", "native", target.syncId!!, jsonObject(
            "state" to "persisted".json(), "through_seq" to target.committedCursor.json())))
    }
    private fun remember() {
        val header = identity ?: return
        val durable = window.durableRecords().map { nativeRecords[it.seq] ?: throw ProtocolException() }
        val snapshot = DshTranscriptSnapshot.tail(header, durable)
        retained = snapshot
        if (!cacheDisabled.get()) saves.trySend(snapshot)
    }
    private fun discardCache() {
        val store = cache ?: return
        cacheDisabled.set(true)
        saves.tryReceive()
        // Cleanup survives the UI closing this reader during an authority failure.
        scope.launch(NonCancellable + Dispatchers.IO) { try { store.clear() } catch (_: Exception) { } }
    }

    companion object {
        internal fun phaseFor(code: String): SessionReadPhase = when (DshRecoveryPolicy.classify(code).action) {
            DshRecoveryAction.AUTH_REQUIRED -> SessionReadPhase.AUTH_REQUIRED
            DshRecoveryAction.REVOKED -> SessionReadPhase.REVOKED
            DshRecoveryAction.BACKOFF -> SessionReadPhase.BACKOFF
            DshRecoveryAction.MANUAL_RETRY -> SessionReadPhase.ERROR
            DshRecoveryAction.CANCELLED -> SessionReadPhase.CLOSED
        }
    }
    private fun safeCode(error: Exception): String = DshRecoveryPolicy.classify(error).code

    private fun assistant(value: JsonValue): JsonValue {
        if (value === JsonNull) return JsonNull
        val stream = value as? JsonObject ?: throw ProtocolException()
        val active = stream.fields["activeAttempt"]
        val root = jsonObject("revision" to stream["revision"])
        return if (active is JsonObject) root.with("activeAttempt" to attempt(active)) else root
    }

    /** The local display window uses a number; tolerate the native seq wrapper at this boundary. */
    private fun attempt(frame: JsonObject): JsonObject {
        val started = frame.fields["startedAfterSeq"]
        val normalized = when {
            started == null || started === JsonNull -> JsonNumber(-1.0)
            started is JsonObject -> started.fields["seq"] ?: JsonNumber(-1.0)
            else -> started
        }
        // chunk/end frames do not have startedAfterSeq in their generated native union.
        return if ((frame.fields["type"] as? JsonString)?.value == "start" || "startedAfterSeq" in frame.fields) frame.with("startedAfterSeq" to normalized) else frame
    }

    private fun publish(value: SessionReadState) { if (!closed.get() && scope.isActive) mutable.value = value }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { stream?.close() }; stream = null
        runCatching { mux?.close() }; mux = null
        saves.close(); scope.cancel()
        synchronized(windowLock) { mutable.value = SessionReadState(SessionReadPhase.CLOSED) }
    }
}
