package cn.edu.ustc.tokenworks

import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream

/** One authenticated route to one dsh runtime. It never retries writes implicitly. */
interface DshTransport : AutoCloseable {
    val runtimeId: String
    val state: StateFlow<DshTransportState>
    suspend fun native(endpoint: String, args: JsonObject): JsonValue
    suspend fun openMux(): DshMux

    /** One owned connection pins native event outcomes to the Peer that opened their stream. */
    suspend fun connect(): DshConnection = DshRouteConnection(this)

    /** Exact-route bodies stream into a caller-owned private sink; the transport never publishes a file. */
    suspend fun download(path: String, sink: OutputStream, maxBytes: Long, range: DshByteRange? = null): DshDownload

    /** Exact-route upload. Redirects and implicit retries must remain disabled. */
    suspend fun upload(path: String, mediaType: String, source: InputStream, sizeBytes: Long): JsonValue
}

enum class DshTransportPath { LAN, P2P, TURN, RELAY }
enum class DshTransportPhase { CONNECTING, READY, BACKOFF, AUTH_REQUIRED, REVOKED, UNSUPPORTED, CLOSED }

data class DshTransportState(val path: DshTransportPath, val phase: DshTransportPhase, val error: String? = null, val generation: Long = 0)

data class DshByteRange(val start: Long, val endInclusive: Long? = null) {
    init { require(start >= 0 && (endInclusive == null || endInclusive >= start)) }
    fun header(): String = "bytes=$start-${endInclusive?.toString().orEmpty()}"
}

data class DshDownload(val mediaType: String, val sizeBytes: Long, val etag: String?, val contentRange: String?)
