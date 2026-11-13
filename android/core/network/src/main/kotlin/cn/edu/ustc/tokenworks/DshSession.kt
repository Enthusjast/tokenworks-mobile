package cn.edu.ustc.tokenworks

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One native session follower. Relay and mux carry dsh frames verbatim; the
 * display window only sees native records and assistant-stream frames.
 */
class DshSessionReader(
    private val api: RemoteApi,
    private val runtimeId: String,
    val sessionId: String,
    owner: CoroutineScope,
    private val maxMessages: Int = 30,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob(owner.coroutineContext[Job]) + Dispatchers.Default)
    private val mutable = MutableStateFlow(SessionReadState(SessionReadPhase.CONNECTING))
    val state: StateFlow<SessionReadState> = mutable.asStateFlow()
    private val window = SessionWindow("native")
    private val closed = AtomicBoolean()
    private var mux: DshMux? = null
    private var stream: DshStream? = null
    private var openingCursor = -1L

    init {
        Contract.uuid(runtimeId)
        Contract.boundedText(sessionId, 128)
        scope.launch { follow() }
        scope.coroutineContext[Job]!!.invokeOnCompletion { close() }
    }

    suspend fun prompt(text: String, requestId: String = UUID.randomUUID().toString()) {
        val part = DshSessionPromptInputRequestContentItem.Case1(DshSessionPromptInputRequestContentItemCase1("text", text))
        val request = DshSessionPromptInputRequest(requestId, sessionId, "queue", listOf(part))
        unit("session/prompt", DshMethods.SessionPrompt.input, DshSessionPromptInput(request))
    }

    suspend fun cancel() = unit("session/cancel", DshMethods.SessionCancel.input, DshSessionCancelInput(DshSessionCancelInputRequest(sessionId)))
    suspend fun rename(title: String) = unit("session/rename", DshMethods.SessionRename.input, DshSessionRenameInput(DshSessionRenameInputRequest(sessionId, title)))
    suspend fun archive() = unit("workspace/archiveSession", DshMethods.WorkspaceArchiveSession.input, DshWorkspaceArchiveSessionInput(DshWorkspaceArchiveSessionInputRequest(sessionId)))
    suspend fun selectModel(provider: String, model: String, reasoningEffort: String? = null) =
        unit("session/selectModel", DshMethods.SessionSelectModel.input,
            DshSessionSelectModelInput(DshSessionSelectModelInputRequest(sessionId, provider, model, reasoningEffort?.let { DshOptional.Value(it) } ?: DshOptional.Missing)))

    suspend fun fork(): String {
        val value = call("session/fork", DshMethods.SessionFork.input, DshSessionForkInput(DshSessionForkInputRequest(sessionId)))
        return DshMethods.SessionFork.output.decode(value).`sessionId`
    }

    private suspend fun <I> unit(endpoint: String, codec: DshCodec<I>, input: I) { call(endpoint, codec, input) }
    private suspend fun <I> call(endpoint: String, codec: DshCodec<I>, input: I): JsonValue =
        api.native(endpoint, codec.encode(input) as JsonObject, runtimeId, api.nativeLease(runtimeId))

    /** Older history through the native page call, addressed by the follow cursor. */
    suspend fun loadOlder() {
        val snapshot = window.snapshot()
        val before = snapshot.records.firstOrNull()?.seq ?: return
        if (!snapshot.hasMore) return
        publish(SessionReadState(SessionReadPhase.READY, snapshot, loadingOlder = true, pageError = mutable.value.pageError))
        try {
            val request = DshSessionPageInputRequest(pageAddress(), snapshot.openingCursor.toDouble(), DshOptional.Value(before.toDouble()), DshOptional.Value(maxMessages.toDouble()))
            val value = call("session/page", DshMethods.SessionPage.input, DshSessionPageInput(request))
            DshMethods.SessionPage.output.decode(value)
            val page = value as? JsonObject ?: throw ProtocolException()
            val payload = jsonObject(
                "records" to JsonArray(page.array("records").map { (it as? JsonObject)?.obj("event") ?: throw ProtocolException() }),
                "has_more" to page["hasMore"],
                "through_seq" to snapshot.openingCursor.json(),
            )
            window.accept(SessionContent("session.page.result", "native", "native", payload))
            publish(SessionReadState(SessionReadPhase.READY, window.snapshot()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            publish(SessionReadState(SessionReadPhase.READY, window.snapshot(), pageError = safeCode(error)))
        }
    }

    private fun address() = DshSessionFollowInputRequestAddress.Case1(DshSessionFollowInputRequestAddressCase1("session", sessionId))
    private fun pageAddress() = DshSessionPageInputRequestAddress.Case1(DshSessionPageInputRequestAddressCase1("session", sessionId))

    private suspend fun follow() {
        var attempt = 0
        while (!closed.get() && scope.isActive) {
            try {
                if (attempt > 0) publish(SessionReadState(SessionReadPhase.BACKOFF, window.snapshot().takeIf { it.records.isNotEmpty() }))
                val socket = api.openMux(runtimeId, api.nativeLease(runtimeId))
                mux = socket
                val request = DshSessionFollowInputRequest(address(), DshOptional.Value(true), DshOptional.Value(maxMessages.toDouble()))
                val opened = socket.open("session/follow", DshMethods.SessionFollow.input.encode(DshSessionFollowInput(request)))
                stream = opened
                attempt = 0
                while (!closed.get()) {
                    when (val frame = opened.next() ?: break) {
                        is DshMuxFrame.Item -> {
                            val value = (frame.value as? DshOptional.Value)?.value ?: continue
                            if (window.accept(nativeContent(value))) publish(SessionReadState(SessionReadPhase.READY, window.snapshot()))
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
                if (closed.get()) return
                publish(SessionReadState(SessionReadPhase.BACKOFF, window.snapshot().takeIf { it.records.isNotEmpty() }, error = safeCode(error)))
                delay(minOf(30_000L, 1_000L shl minOf(attempt++, 5)))
            } finally {
                runCatching { stream?.close() }; stream = null
                runCatching { mux?.close() }; mux = null
            }
        }
    }

    private fun safeCode(error: Exception): String = when (error) {
        is DshFailure -> error.code
        is ProtocolException, is SessionDataException -> "protocol.unsupported"
        else -> "relay.disconnected"
    }

    /** Translate one native follow frame into the retained display vocabulary. */
    private fun nativeContent(value: JsonValue): SessionContent {
        val root = value as? JsonObject ?: throw ProtocolException()
        return when (root.string("type")) {
            "snapshot" -> {
                val cursor = root.integer("cursor", -1)
                openingCursor = cursor
                SessionContent("session.snapshot", "native", "native", jsonObject(
                    "cursor" to root["cursor"],
                    "has_more" to root["hasMore"],
                    "records" to JsonArray(root.array("records").map { (it as? JsonObject)?.obj("event") ?: throw ProtocolException() }),
                    "assistant" to assistant(root.fields["assistantStream"] ?: JsonNull),
                ))
            }
            "event" -> SessionContent("session.event", "native", "native", root.obj("event"))
            "assistant-stream" -> {
                val frame = root.obj("frame")
                val kind = frame.string("type")
                if (kind !in setOf("start", "chunk", "end")) throw ProtocolException()
                SessionContent("assistant.$kind", "native", "native", attempt(frame))
            }
            else -> throw ProtocolException()
        }
    }

    private fun assistant(value: JsonValue): JsonValue {
        if (value === JsonNull) return JsonNull
        val stream = value as? JsonObject ?: throw ProtocolException()
        val active = stream.fields["activeAttempt"] ?: JsonNull
        return jsonObject("revision" to stream["revision"], "activeAttempt" to (if (active === JsonNull) JsonNull else attempt(active as JsonObject)))
    }

    /** The display window reads `startedAfterSeq` as a number; native uses `{seq}` or null. */
    private fun attempt(frame: JsonObject): JsonObject {
        val started = frame.fields["startedAfterSeq"]
        val normalized = when {
            started == null || started === JsonNull -> JsonNumber(-1.0)
            started is JsonObject -> started.fields["seq"] ?: JsonNumber(-1.0)
            else -> started
        }
        return frame.with("startedAfterSeq" to normalized)
    }

    private fun publish(value: SessionReadState) { if (!closed.get() && scope.isActive) mutable.value = value }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { stream?.close() }; stream = null
        runCatching { mux?.close() }; mux = null
        scope.cancel()
        mutable.value = SessionReadState(SessionReadPhase.CLOSED)
    }
}

private fun Double.json(): JsonValue = JsonNumber(this)
