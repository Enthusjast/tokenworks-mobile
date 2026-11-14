package cn.edu.ustc.tokenworks

import android.content.Context
import kotlinx.coroutines.*
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.webrtc.PeerConnectionFactory

/** Created only after consent and public SDK class availability; never loaded by LAN/Relay composition. */
internal class AndroidRtcEngine(
    private val application: Context,
    private val ownerKey: String,
    private val currentOwner: () -> Unit,
    private val controlFactory: (String) -> PairingControlClient,
) : RtcNativeOwner {
    private val guard = Any()
    private val sockets = mutableSetOf<AndroidRtcSocket>()
    @Volatile private var closed = false
    private var factory: PeerConnectionFactory? = null
    // Eight owned sockets (including pending disposal), plus a single factory disposal.
    private val disposal = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16))
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun dialer(runtime: String, path: DshTransportPath, current: () -> Unit) = CarrierOuterDialer { receiver, purpose ->
        if (purpose != "native") throw IOException("e2e/rtc_purpose")
        var opening: AndroidRtcSocket? = null
        try {
            val socket = withContext(Dispatchers.IO) {
                currentOwner(); current()
                synchronized(guard) {
                    if (closed) throw IOException("e2e/rtc_owner_changed")
                    if (!reserveOwner(ownerKey)) throw IOException("network.unavailable")
                    try {
                        if (factory == null) {
                            try {
                                initialize(application)
                                factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
                            } catch (_: LinkageError) { throw IOException("e2e/rtc_unsupported") }
                        }
                        AndroidRtcSocket(runtime, path, receiver, factory!!, current, { controlFactory(runtime) }, ::retire).also {
                            sockets.add(it); opening = it
                        }
                    } catch (failure: Throwable) { releaseOwner(ownerKey); throw failure }
                }
            }
            socket.open(); socket
        } catch (failure: Throwable) { opening?.close(); throw failure }
    }
    override fun closeRuntime(runtime: String) {
        val retiring = synchronized(guard) { sockets.filter { it.runtime == runtime } }
        retiring.forEach(AndroidRtcSocket::close)
    }
    private fun removeSocket(socket: AndroidRtcSocket) {
        synchronized(guard) { if (sockets.remove(socket)) releaseOwner(ownerKey) }
    }
    private fun retire(socket: AndroidRtcSocket) {
        // JNI/network callbacks only schedule this one bounded task; never dispose on WebRTC threads.
        disposal.execute {
            try { socket.disposeNative() }
            finally {
                if (!closed) {
                    val job = cleanup.launch { socket.deleteOffer() }
                    job.invokeOnCompletion { socket.closeControl(); removeSocket(socket) }
                } else {
                    socket.closeControl(); removeSocket(socket)
                }
            }
        }
    }
    override fun selectedCandidates(runtime: String): List<RtcCandidateFacts> = synchronized(guard) {
        sockets.filter { it.runtime == runtime && !it.closed }.mapNotNull { it.candidateFacts }
    }
    override fun close() {
        val retiring: List<AndroidRtcSocket>
        synchronized(guard) {
            if (closed) return
            closed = true; retiring = sockets.toList()
        }
        retiring.forEach { it.close(); it.closeControl() }
        disposal.execute {
            val retiringFactory = synchronized(guard) { factory.also { factory = null } }
            retiringFactory?.dispose()
        }
        disposal.shutdown(); cleanup.cancel()
    }
    companion object {
        private val ownerCounts = mutableMapOf<String, Int>()
        @Synchronized private fun reserveOwner(owner: String): Boolean {
            val count = ownerCounts[owner] ?: 0
            if (count >= 8) return false
            ownerCounts[owner] = count + 1
            return true
        }
        @Synchronized private fun releaseOwner(owner: String) {
            val count = ownerCounts[owner] ?: return
            if (count <= 1) ownerCounts.remove(owner) else ownerCounts[owner] = count - 1
        }
        private var initialized = false
        @Synchronized private fun initialize(context: Context) {
            if (initialized) return
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false).createInitializationOptions())
            initialized = true
        }
    }
}
