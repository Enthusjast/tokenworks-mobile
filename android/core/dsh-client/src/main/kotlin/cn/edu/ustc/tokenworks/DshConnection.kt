package cn.edu.ustc.tokenworks

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/** A native event subscription and its unary outcomes must use the same authenticated route. */
interface DshConnection : AutoCloseable {
    suspend fun native(endpoint: String, args: JsonObject): JsonValue
    suspend fun openMux(): DshMux
}

/** Closing a connection retires only its sockets, never the shared runtime transport. */
class DshRouteConnection(
    private val transport: DshTransport,
    private val isCurrent: () -> Boolean = { true },
    private val failed: suspend (Exception) -> Unit = {},
) : DshConnection {
    private val closed = AtomicBoolean()
    private val sockets = ConcurrentHashMap.newKeySet<DshMux>()
    private fun live() {
        if (closed.get() || !isCurrent() || transport.state.value.phase == DshTransportPhase.CLOSED)
            throw DshFailure("transport/changed")
    }
    private suspend fun <T> invoke(operation: suspend () -> T): T {
        live()
        try { return operation().also { live() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failed(error); throw error }
    }
    override suspend fun native(endpoint: String, args: JsonObject): JsonValue = invoke { transport.native(endpoint, args) }
    override suspend fun openMux(): DshMux = invoke {
        transport.openMux().also {
            sockets.removeAll { socket -> socket.closed() }
            sockets.add(it)
            if (closed.get() || !isCurrent()) { sockets.remove(it); it.close(); live() }
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        sockets.forEach(DshMux::close); sockets.clear()
    }
}
