package cn.edu.ustc.tokenworks

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** One immutable controller projection; the UI never needs a transport or Agent event. */
data class TerminalUiState(
    val terminals: List<TerminalInfo> = emptyList(),
    val attached: TerminalInfo? = null,
    val screen: String = "",
    val writable: Boolean = false,
    val busy: Boolean = false,
    val reconnecting: Boolean = false,
    val error: String? = null,
    val environment: TerminalEnvironment? = null,
)

/** At most one local attachment and one newly allocated shell per displayed Session. */
class TerminalWatcher(
    private val transport: DshTransport,
    private val sessionId: String,
    ownerScope: CoroutineScope,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val owner = SupervisorJob(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + owner)
    private val operations = Mutex()
    private val mutable = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = mutable.asStateFlow()
    @Volatile private var terminal: DshTerminal? = null
    @Volatile private var socket: DshMux? = null
    private var job: Job? = null
    private val closedTerminals = mutableSetOf<String>()

    init {
        require(sessionId.isNotBlank())
        owner.invokeOnCompletion { terminal?.close(); socket?.close(); mutable.update { it.copy(writable = false, reconnecting = false) } }
    }

    suspend fun refresh() = action {
        mutable.update { it.copy(terminals = list()) }
    }

    /** New allocation is explicit; an existing retained shell is reused, never killed. */
    suspend fun open(id: String = UUID.randomUUID().toString(), cols: Int = 80, rows: Int = 24) = action {
        val available = list()
        val environment = environment()
        val existing = available.firstOrNull()
        val info = existing ?: run {
            if (!id.matches(Regex("[\\w-]{1,128}"))) throw DshFailure("terminal.invalid_identity")
            val width = minOf(cols, environment.maxCols)
            val height = minOf(rows, environment.maxRows)
            if (!environment.accepts(width, height)) throw DshFailure("terminal.invalid_dimensions")
            DshTerminal.decodeInfo(transport.native("terminal/create", agentArgs().with("request" to jsonObject(
                "id" to id.json(), "cols" to width.toLong().json(), "rows" to height.toLong().json(),
            ))) as? JsonObject ?: throw ProtocolException())
        }
        mutable.update { it.copy(terminals = if (existing == null) listOf(info) else available, environment = environment) }
        startAttach(info, environment)
    }

    /** Recovering a view must not create a replacement process when its identity is missing. */
    suspend fun attach(id: String) = action {
        val available = list()
        val info = available.firstOrNull { it.id == id } ?: throw DshFailure("terminal/unavailable")
        val environment = environment()
        mutable.update { it.copy(terminals = available, environment = environment) }
        startAttach(info, environment)
    }

    private suspend fun action(block: suspend () -> Unit) = operations.withLock {
        if (closed.get() || !owner.isActive) return@withLock
        mutable.update { it.copy(busy = true, error = null) }
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { mutable.update { it.copy(error = code(error)) } }
        finally { mutable.update { it.copy(busy = false) } }
    }

    private suspend fun list(): List<TerminalInfo> {
        val result = transport.native("terminal/list", jsonObject("sessionId" to sessionId.json())) as? JsonArray ?: throw ProtocolException()
        return result.values.map { DshTerminal.decodeInfo(it as? JsonObject ?: throw ProtocolException()) }
    }

    private suspend fun environment(): TerminalEnvironment {
        val value = transport.native("terminal/environment", agentArgs()) as? JsonObject ?: throw ProtocolException()
        return TerminalEnvironment(value.string("cwd"), value.integer("maxInputBytes", 1, Int.MAX_VALUE.toLong()).toInt(),
            value.integer("maxBufferedBytes", 1, Int.MAX_VALUE.toLong()).toInt(), value.integer("maxCols", 2, Int.MAX_VALUE.toLong()).toInt(),
            value.integer("maxRows", 1, Int.MAX_VALUE.toLong()).toInt(), value.integer("scrollback", 0, Int.MAX_VALUE.toLong()).toInt())
    }

    // The installed native 'agent' lookup is resolveAgent(sessionId). This wire
    // name is not an Agent id inferred from a tool event or a child follow record.
    private fun agentArgs() = jsonObject("agentId" to sessionId.json())

    private suspend fun startAttach(info: TerminalInfo, environment: TerminalEnvironment) {
        stopAttachment()
        mutable.update { it.copy(attached = info, screen = "", writable = false, reconnecting = false) }
        job = scope.launch {
            var attempt = 0
            while (isActive && !closed.get()) {
                var mux: DshMux? = null
                var attached: DshTerminal? = null
                try {
                    mux = transport.openMux()
                    socket = mux
                    val stream = mux.open("terminal/attach", jsonObject("args" to agentArgs().with("id" to info.id.json())))
                    val attachment = DshTerminal(stream, info, environment)
                    attached = attachment
                    terminal = attachment
                    val screen = TerminalTextScreen(info.cols, info.rows, environment.scrollback)
                    while (isActive) {
                        val frame = attachment.receive() ?: break
                        when (frame) {
                            is TerminalFrame.Snapshot -> { screen.resize(frame.info.cols, frame.info.rows); screen.reset(); screen.feed(frame.screen); attempt = 0 }
                            is TerminalFrame.Output -> screen.feed(frame.data)
                            is TerminalFrame.State -> screen.resize(frame.info.cols, frame.info.rows)
                            is TerminalFrame.Ack -> Unit
                        }
                        mutable.update { current -> current.copy(
                            attached = attachment.info.value,
                            terminals = current.terminals.map { if (it.id == info.id) attachment.info.value else it },
                            screen = if (frame is TerminalFrame.Snapshot || frame is TerminalFrame.Output || frame is TerminalFrame.State) screen.text() else current.screen,
                            writable = attachment.writable.value,
                            reconnecting = false,
                            error = attachment.issue.value,
                        ) }
                    }
                    attachment.issue.value?.let { throw DshFailure(it) }
                    if (!attachment.info.value.running) return@launch
                    throw DshFailure("relay.disconnected")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val failure = code(error)
                    val retry = failure == "relay.disconnected" || failure == "network.unavailable" || failure.startsWith("wire/") || failure == "terminal.output_gap"
                    mutable.update { it.copy(writable = false, reconnecting = retry, error = failure) }
                    if (!retry) return@launch
                } finally {
                    attached?.close()
                    mux?.close()
                    if (terminal === attached) terminal = null
                    if (socket === mux) socket = null
                }
                delay(minOf(30_000L, 1_000L shl minOf(attempt++, 5)))
            }
        }
    }

    fun send(text: String): Boolean {
        val current = terminal
        val accepted = current?.write(text) == true
        if (!accepted) mutable.update { it.copy(writable = current?.writable?.value == true, error = current?.issue?.value ?: "terminal.input_refused") }
        return accepted
    }

    fun resize(cols: Int, rows: Int): Boolean {
        val current = terminal
        val accepted = current?.resize(cols, rows) == true
        if (!accepted) mutable.update { it.copy(writable = current?.writable?.value == true, error = current?.issue?.value ?: "terminal.resize_refused") }
        return accepted
    }

    /** Local detach only: no terminal/close RPC, including owner-scope cancellation. */
    fun detach() {
        job?.cancel(); job = null
        terminal?.close(); terminal = null
        socket?.close(); socket = null
        mutable.update { it.copy(attached = null, writable = false, reconnecting = false) }
    }

    private suspend fun stopAttachment() {
        val previous = job
        detach()
        previous?.cancelAndJoin()
    }

    /** Explicit destructive action, serialized and deduplicated after successful acknowledgement. */
    suspend fun closeTerminal(id: String = state.value.attached?.id.orEmpty()) = action {
        if (id.isEmpty() || id in closedTerminals) return@action
        if (state.value.attached?.id == id) stopAttachment()
        transport.native("terminal/close", agentArgs().with("id" to id.json()))
        closedTerminals.add(id)
        mutable.update { it.copy(terminals = it.terminals.filterNot { info -> info.id == id }) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        detach()
        scope.cancel()
    }

    private fun code(error: Exception) = when (error) {
        is DshFailure -> error.code
        is ProtocolException -> "terminal.invalid_response"
        else -> "terminal.failed"
    }
}
