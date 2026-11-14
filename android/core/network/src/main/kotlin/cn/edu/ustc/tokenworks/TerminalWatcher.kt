package cn.edu.ustc.tokenworks

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * N-2: one attached terminal per watcher. Lists/creates through the control
 * plane, attaches on the mux, pumps frames into [screen] and sends input.
 */
class TerminalWatcher(
    private val api: RemoteApi,
    private val runtimeId: String,
    owner: CoroutineScope,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val scope = CoroutineScope(SupervisorJob(owner.coroutineContext[Job]) + Dispatchers.Default)
    private val mutableTerminals = MutableStateFlow<List<TerminalInfo>>(emptyList())
    val terminals: StateFlow<List<TerminalInfo>> = mutableTerminals.asStateFlow()
    private val mutableScreen = MutableStateFlow("")
    val screen: StateFlow<String> = mutableScreen.asStateFlow()
    private val mutableAttached = MutableStateFlow<TerminalInfo?>(null)
    val attached: StateFlow<TerminalInfo?> = mutableAttached.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()

    private val client = DshSessionClient(api)
    private val mutableAgent = MutableStateFlow("")
    val agentId: StateFlow<String> = mutableAgent.asStateFlow()
    fun updateAgent(value: String) { if (mutableAgent.value.isEmpty()) mutableAgent.value = value }
    private var terminal: DshTerminal? = null
    private var job: Job? = null

    suspend fun refresh() {
        try {
            mutableTerminals.value = client.terminals(runtimeId, mutableAgent.value)
            mutableError.value = null
        } catch (error: Exception) {
            mutableError.value = if (error is DshFailure) error.code else "relay.disconnected"
        }
    }

    /** Creates (or returns) the terminal for [id] and starts the attach pump. */
    suspend fun open(id: String, cols: Int = 80, rows: Int = 24) {
        if (closed.get()) return
        closeTerminal()
        try {
            val info = client.createTerminal(runtimeId, mutableAgent.value, id, cols, rows)
            mutableAttached.value = info
            mutableScreen.value = ""
            job = scope.launch { attach(info) }
        } catch (error: Exception) {
            mutableError.value = if (error is DshFailure) error.code else "terminal.create_failed"
        }
    }

    private suspend fun attach(info: TerminalInfo) {
        while (!closed.get() && scope.isActive) {
            try {
                val socket = api.openMux(runtimeId, api.nativeLease(runtimeId))
                val stream = socket.open("terminal/attach", jsonObject("args" to jsonObject(
                    "agentId" to mutableAgent.value.json(), "id" to info.id.json(),
                )))
                val terminal = DshTerminal(stream, info)
                this.terminal = terminal
                while (!closed.get()) {
                    val frame = terminal.receive() ?: break
                    when (frame) {
                        is TerminalFrame.Snapshot -> mutableScreen.value = frame.screen
                        is TerminalFrame.Output -> mutableScreen.value = (mutableScreen.value + frame.data).takeLast(100_000)
                        is TerminalFrame.State -> { mutableTerminals.value = mutableTerminals.value.map { if (it.id == frame.info.id) frame.info else it }; if (!frame.info.running) return }
                    }
                }
                return
            } catch (cancelled: CancellationException) {
                return
            } catch (error: Exception) {
                if (closed.get()) return
                mutableError.value = if (error is DshFailure) error.code else "relay.disconnected"
                delay(minOf(30_000L, 1_000L shl minOf(0, 5)))
            }
        }
    }

    fun send(text: String) { terminal?.write(text) }
    fun resize(cols: Int, rows: Int) { terminal?.resize(cols, rows) }

    fun detach() {
        job?.cancel(); job = null
        terminal?.close(); terminal = null
        mutableAttached.value = null
    }

    suspend fun closeTerminal(id: String = "") {
        val target = id.ifEmpty { mutableAttached.value?.id.orEmpty() }
        if (target.isEmpty()) return
        detach()
        runCatching { client.closeTerminal(runtimeId, mutableAgent.value, target) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        job?.cancel()
        terminal?.close()
        scope.cancel()
    }
}
