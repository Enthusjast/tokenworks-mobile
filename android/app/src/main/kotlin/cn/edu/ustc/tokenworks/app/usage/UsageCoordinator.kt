package cn.edu.ustc.tokenworks

import android.content.Context
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

class UsageState internal constructor(
    internal val prices: UsagePriceStore? = null,
    internal val sessions: List<UsageSession> = emptyList(),
    internal val liveUnavailable: Boolean = false,
    internal val runtimeSelected: Boolean = false,
)

/** One authorized runtime at a time. dsh stores the totals; this device only retains observed detail while selected. */
internal class UsageCoordinator(context: Context, private val scope: CoroutineScope) {
    private class Entry {
        val ledger = UsageLedger()
        var title: String? = null
        var summary: UsageSession? = null
    }

    private val application = context.applicationContext
    private val sessions = linkedMapOf<String, Entry>()
    private val dirty = linkedSetOf<String>()
    private val mutable = MutableStateFlow(UsageState())
    val state: StateFlow<UsageState> = mutable.asStateFlow()
    private var owner: String? = null
    private var runtime: String? = null
    private var transport: DshTransport? = null
    private var prices: UsagePriceStore? = null
    private var feed: SessionProjectionFeed? = null
    private var feedJob: Job? = null
    private var publishing: Job? = null
    private var generation = 0L
    private var blocked = false
    private var liveUnavailable = false

    fun select(ownerKey: String?, runtimeId: String?, transport: DshTransport?) {
        if (owner == ownerKey && runtime == runtimeId && this.transport === transport) return
        val scopeId = ++generation
        feedJob?.cancel(); feedJob = null
        feed?.close(); feed = null
        publishing?.cancel(); publishing = null
        sessions.clear()
        dirty.clear()
        if (owner != ownerKey) prices = ownerKey?.let { UsagePriceStore(application, it) }
        owner = ownerKey
        runtime = runtimeId
        this.transport = transport
        blocked = false
        liveUnavailable = false
        mutable.value = UsageState(prices, runtimeSelected = runtimeId != null)
        if (ownerKey == null || runtimeId == null || transport == null) return
        val opened = SessionProjectionFeed(transport, scope, tokenUsage = { sessionId, snapshot ->
            if (generation == scopeId && !blocked) {
                val entry = sessions.getOrPut(sessionId, ::Entry)
                if (entry.ledger.replace(snapshot)) changed(sessionId)
            }
        })
        feed = opened
        feedJob = scope.launch {
            opened.error.collect { code ->
                if (generation != scopeId) return@collect
                val unavailable = code != null
                val lost = code != null && DshRecoveryPolicy.classify(code).action.let {
                    it == DshRecoveryAction.AUTH_REQUIRED || it == DshRecoveryAction.REVOKED
                }
                if (lost) {
                    blocked = true
                    sessions.clear()
                    dirty.clear()
                    liveUnavailable = true
                    mutable.value = UsageState(prices, liveUnavailable = true, runtimeSelected = true)
                } else if (liveUnavailable != unavailable) {
                    liveUnavailable = unavailable
                    schedulePublish()
                }
            }
        }
    }

    fun directory(rows: List<SessionDirectoryEntry>) {
        if (owner == null || runtime == null || transport == null || blocked) return
        for (row in rows) {
            val entry = sessions.getOrPut(row.id, ::Entry)
            val title = row.metadata?.title
            val updated = row.usageSnapshot?.let(entry.ledger::replace) == true
            if (title != entry.title || updated) {
                entry.title = title
                dirty += row.id
            }
        }
        if (dirty.isNotEmpty()) schedulePublish()
    }

    fun observe(sessionId: String, title: String?, transcript: SessionTranscript) {
        if (owner == null || runtime == null || transport == null || blocked) return
        val entry = sessions.getOrPut(sessionId, ::Entry)
        val wasChanged = entry.ledger.observe(transcript)
        if (title != null && entry.title != title || wasChanged) {
            if (title != null) entry.title = title
            changed(sessionId)
        }
    }

    fun clearOwner(ownerKey: String) {
        if (owner == ownerKey) select(null, null, null)
    }

    fun clearRuntime(ownerKey: String, runtimeId: String) {
        if (owner == ownerKey && runtime == runtimeId) select(ownerKey, null, null)
    }

    private fun changed(id: String) {
        dirty += id
        schedulePublish()
    }

    private fun schedulePublish() {
        if (publishing?.isActive == true) return
        publishing = scope.launch {
            yield() // Coalesce a Host baseline (one callback for every Session).
            val zone = ZoneId.systemDefault()
            for (id in dirty) {
                val entry = sessions.getValue(id)
                entry.summary = entry.ledger.summarize(id, entry.title, zone)
            }
            dirty.clear()
            mutable.value = UsageState(prices, sessions.values.mapNotNull { it.summary }, liveUnavailable, runtimeSelected = runtime != null)
        }
    }
}
