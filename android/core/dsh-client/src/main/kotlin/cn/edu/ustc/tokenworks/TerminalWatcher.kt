package cn.edu.ustc.tokenworks

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class TerminalTabState internal constructor(
    val info: TerminalInfo,
    val connected: Boolean = true,
    val display: TerminalDisplay? = null,
    val writable: Boolean = false,
    val reconnecting: Boolean = false,
    val error: String? = null,
)

/** One immutable projection of the session's terminal directory and independent attachments. */
data class TerminalUiState(
    val terminals: List<TerminalInfo> = emptyList(),
    val selectedId: String? = null,
    val tabs: Map<String, TerminalTabState> = emptyMap(),
    val busy: Boolean = false,
    val actionError: String? = null,
    val environment: TerminalEnvironment? = null,
) {
    val selected get() = tabs[selectedId]
    val attached get() = selected?.takeIf { it.connected }?.info
    val display get() = selected?.display
    val screen get() = display?.text.orEmpty()
    val writable get() = selected?.writable == true
    val reconnecting get() = selected?.reconnecting == true
    val error get() = actionError ?: selected?.error
}

/** User-created host processes outlive their local attachments. No input crosses a tab or reconnect. */
class TerminalWatcher(
    private val transport: DshTransport,
    private val sessionId: String,
    ownerScope: CoroutineScope,
) : AutoCloseable {
    private class Slot {
        lateinit var job: Job
        var mux: DshMux? = null
        var terminal: DshTerminal? = null
    }
    private val lock = Any()
    private var closed = false
    private val owner = SupervisorJob(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + owner + Dispatchers.Default)
    private val operations = Mutex()
    private val mutable = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = mutable.asStateFlow()
    private val slots = mutableMapOf<String, Slot>()

    init {
        require(sessionId.isNotBlank())
        owner.invokeOnCompletion { close() }
    }

    suspend fun refresh() = action {
        val available = list()
        synchronized(lock) { if (active()) updateDirectory(available) }
    }

    /** Create is explicit even when other host terminals already exist. */
    suspend fun open(id: String = UUID.randomUUID().toString(), cols: Int = 80, rows: Int = 24) = action {
        if (!id.matches(Regex("[\\w-]{1,128}"))) throw DshFailure("terminal.invalid_identity")
        val available = list()
        val environment = environment()
        val width = minOf(cols, environment.maxCols)
        val height = minOf(rows, environment.maxRows)
        if (!environment.accepts(width, height)) throw DshFailure("terminal.invalid_dimensions")
        val info = DshTerminal.decodeInfo(transport.native("terminal/create", agentArgs().with("request" to jsonObject(
            "id" to id.json(), "cols" to width.toLong().json(), "rows" to height.toLong().json(),
        ))) as? JsonObject ?: throw ProtocolException())
        synchronized(lock) {
            if (!active()) return@synchronized
            updateDirectory(available.filterNot { it.id == id } + info)
            startAttach(info, environment)
        }
    }

    /** Switching to an existing local view does not acquire control or replay anything. */
    suspend fun select(id: String) = action {
        val found = synchronized(lock) {
            if (id in mutable.value.tabs) {
                if (active()) mutable.value = mutable.value.copy(selectedId = id)
                true
            } else false
        }
        if (!found) attachFromHost(id)
    }

    /** Missing identities are never replaced by new processes on attach or reconnect. */
    suspend fun attach(id: String) = action { attachFromHost(id) }

    private suspend fun attachFromHost(id: String) {
        val available = list()
        val info = available.firstOrNull { it.id == id } ?: throw DshFailure("terminal/unavailable")
        val environment = environment()
        synchronized(lock) {
            if (!active()) return@synchronized
            updateDirectory(available)
            startAttach(info, environment)
        }
    }

    private fun active() = !closed && owner.isActive

    private suspend fun action(block: suspend () -> Unit) = operations.withLock {
        synchronized(lock) {
            if (!active()) return@withLock
            mutable.value = mutable.value.copy(busy = true, actionError = null)
        }
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            synchronized(lock) { if (active()) mutable.value = mutable.value.copy(actionError = code(error)) }
        } finally {
            synchronized(lock) { mutable.value = mutable.value.copy(busy = false) }
        }
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

    // The native 'agent' lookup resolves the selected Session id, never an inferred child Agent id.
    private fun agentArgs() = jsonObject("agentId" to sessionId.json())

    // Directory, slot identity, writable checks and publication are serialized under lock.
    private fun updateDirectory(available: List<TerminalInfo>) {
        val current = mutable.value
        val ids = available.mapTo(mutableSetOf()) { it.id }
        slots.keys.toList().filterNot { it in ids }.forEach(::stop)
        mutable.value = current.copy(
            terminals = available.map { current.tabs[it.id]?.info?.takeIf { _ -> it.id in slots } ?: it },
            tabs = current.tabs.filterKeys { it in ids },
            selectedId = current.selectedId?.takeIf { it in ids },
        )
    }

    private fun startAttach(info: TerminalInfo, environment: TerminalEnvironment) {
        val id = info.id
        stop(id)
        val current = mutable.value
        mutable.value = current.copy(selectedId = id, environment = environment,
            tabs = current.tabs + (id to TerminalTabState(info, display = current.tabs[id]?.display)))
        val slot = Slot()
        slot.job = scope.launch(start = CoroutineStart.LAZY) { follow(slot, info, environment) }
        slots[id] = slot
        slot.job.start()
    }

    private suspend fun follow(slot: Slot, info: TerminalInfo, environment: TerminalEnvironment) {
        val id = info.id
        var attempt = 0
        while (slot.job.isActive) {
            var mux: DshMux? = null
            var attached: DshTerminal? = null
            try {
                val opened = transport.openMux()
                mux = opened
                val attachment = synchronized(lock) {
                    if (!active() || slots[id] !== slot) return
                    slot.mux = opened
                    val stream = opened.open("terminal/attach", jsonObject("args" to agentArgs().with("id" to id.json())))
                    DshTerminal(stream, info, environment).also { slot.terminal = it; attached = it }
                }
                var emulator: TerminalEmulator? = null
                while (slot.job.isActive) {
                    val frame = attachment.receive() ?: break
                    val display = when (frame) {
                        is TerminalFrame.Snapshot -> TerminalEmulator(frame.info.cols, frame.info.rows, environment.scrollback).also {
                            emulator = it
                            it.feed(frame.screen)
                            attempt = 0
                        }.display()
                        is TerminalFrame.Output -> emulator!!.apply { feed(frame.data) }.display()
                        is TerminalFrame.State -> emulator!!.let {
                            val previous = mutable.value.tabs[id]?.display
                            if (previous?.cols == frame.info.cols && previous.rows == frame.info.rows) null
                            else it.apply { resize(frame.info.cols, frame.info.rows) }.display()
                        }
                        is TerminalFrame.Ack -> null
                    }
                    synchronized(lock) {
                        if (!active() || slots[id] !== slot) return
                        val live = attachment.info.value
                        val current = mutable.value
                        val tab = current.tabs.getValue(id)
                        mutable.value = current.copy(
                            terminals = if (live == tab.info) current.terminals else current.terminals.map { if (it.id == id) live else it },
                            tabs = current.tabs + (id to tab.copy(info = live, display = display ?: tab.display,
                                writable = attachment.writable.value, reconnecting = false, error = attachment.issue.value)),
                        )
                    }
                }
                attachment.issue.value?.let { throw DshFailure(it) }
                if (!attachment.info.value.running) return
                throw DshFailure("relay.disconnected")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val failure = code(error)
                val retry = failure == "relay.disconnected" || failure == "network.unavailable" || failure.startsWith("wire/") || failure == "terminal.output_gap"
                synchronized(lock) {
                    if (!active() || slots[id] !== slot) return
                    updateTab(id) { it.copy(writable = false, reconnecting = retry, error = failure) }
                }
                if (!retry) return
            } finally {
                attached?.close()
                mux?.close()
                synchronized(lock) { slot.terminal = null; slot.mux = null }
            }
            delay(minOf(30_000L, 1_000L shl minOf(attempt++, 5)))
        }
    }

    fun send(text: String): Boolean {
        val id = state.value.selectedId ?: return false
        return sendTo(id) { text }
    }

    fun sendKey(id: String, key: TerminalKey, modifiers: TerminalModifiers = TerminalModifiers()): Boolean =
        sendTo(id) { it.encode(key, modifiers) }

    fun submit(id: String, draft: String): Boolean = sendTo(id) { draft + it.encode(TerminalKey.ENTER) }

    fun type(id: String, text: String, modifiers: TerminalModifiers = TerminalModifiers()): Boolean =
        sendTo(id) { terminalTypedText(text, modifiers) }

    /** Only explicit user input reaches this function; output never triggers a terminal write. */
    fun paste(id: String, text: String): Boolean = sendTo(id) { modes ->
        val safe = text.filter { it >= ' ' && it !in '\u007f'..'\u009f' || it == '\t' || it == '\n' || it == '\r' }
        if (safe.isEmpty()) null
        else if (modes.bracketedPaste) "\u001b[200~$safe\u001b[201~"
        else safe.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ')
    }

    private fun sendTo(id: String, data: (TerminalKeyModes) -> String?): Boolean = synchronized(lock) {
        val current = mutable.value
        if (!active() || current.selectedId != id) return@synchronized false
        val tab = current.tabs[id] ?: return@synchronized false
        val terminal = slots[id]?.terminal
        val text = data(tab.display?.keyModes ?: TerminalKeyModes()) ?: return@synchronized false
        val accepted = tab.writable && terminal?.write(text) == true
        if (!accepted) updateTab(id) { it.copy(writable = it.writable && terminal?.writable?.value == true,
            error = terminal?.issue?.value ?: "terminal.input_refused") }
        accepted
    }

    fun resize(cols: Int, rows: Int, id: String? = state.value.selectedId): Boolean = synchronized(lock) {
        val current = mutable.value
        if (!active() || id == null || id != current.selectedId) return@synchronized false
        val tab = current.tabs[id] ?: return@synchronized false
        val terminal = slots[id]?.terminal
        val accepted = tab.writable && terminal?.resize(cols, rows) == true
        if (!accepted) updateTab(id) { it.copy(writable = it.writable && terminal?.writable?.value == true,
            error = terminal?.issue?.value ?: "terminal.resize_refused") }
        accepted
    }

    /** Local detach only (including owner cancellation); an explicit close is a separate RPC. */
    fun detach() = synchronized(lock) {
        val id = mutable.value.selectedId ?: return@synchronized
        stop(id)
        updateTab(id) { it.copy(connected = false, writable = false, reconnecting = false, error = null) }
    }

    private fun stop(id: String) {
        val slot = slots.remove(id) ?: return
        slot.job.cancel()
        slot.terminal?.close()
        slot.mux?.close()
    }

    private fun updateTab(id: String, change: (TerminalTabState) -> TerminalTabState) {
        val current = mutable.value
        val tab = current.tabs[id] ?: return
        mutable.value = current.copy(tabs = current.tabs + (id to change(tab)))
    }

    /** Destructive only by explicit user action; a successful close removes exactly that tab. */
    suspend fun closeTerminal(id: String = state.value.selectedId.orEmpty()) = action {
        val known = synchronized(lock) {
            val exists = mutable.value.terminals.any { it.id == id }
            if (exists) {
                stop(id)
                updateTab(id) { it.copy(connected = false, writable = false, reconnecting = false) }
            }
            exists
        }
        if (!known) return@action
        transport.native("terminal/close", agentArgs().with("id" to id.json()))
        synchronized(lock) { if (active()) updateDirectory(mutable.value.terminals.filterNot { it.id == id }) }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            slots.keys.toList().forEach(::stop)
            mutable.value = TerminalUiState()
        }
        owner.cancel()
    }

    private fun code(error: Exception) = when (error) {
        is DshFailure -> error.code
        is ProtocolException -> "terminal.invalid_response"
        else -> "terminal.failed"
    }
}
