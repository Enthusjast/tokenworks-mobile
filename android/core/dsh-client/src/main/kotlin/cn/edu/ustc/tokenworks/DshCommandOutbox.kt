package cn.edu.ustc.tokenworks

import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class OutboxCheckpoint(val revision: Long, val entries: List<PromptOutboxEntry>)

/** Blocking storage boundary. Implementations must authenticate reads and atomically commit bounded snapshots. */
interface DshOutboxStore {
    fun load(): OutboxCheckpoint
    fun commit(expectedRevision: Long, entries: List<PromptOutboxEntry>): OutboxCheckpoint
}

enum class OutboxFailure { CORRUPT, UNAVAILABLE, CONFLICT, FULL }
class OutboxException(val failure: OutboxFailure) : IllegalStateException("command outbox: ${failure.name.lowercase()}")

/** No network calls or replay scheduler: only explicit user actions can send a recovered intent. */
class DshCommandOutbox private constructor(private val store: DshOutboxStore, checkpoint: OutboxCheckpoint) {
    companion object {
        const val MAX_ENTRIES = 64
        const val MAX_TEXT_BYTES = 32 * 1024
        const val MAX_TOTAL_TEXT_BYTES = 256 * 1024
        const val MAX_TOTAL_IMAGE_BYTES = 8 * 1024 * 1024

        suspend fun open(store: DshOutboxStore): DshCommandOutbox = withContext(Dispatchers.IO) {
            val loaded = store.load()
            validate(loaded.entries)
            val recovered = loaded.entries.map { if (it.state == PromptDeliveryState.SENDING) it.copy(state = PromptDeliveryState.OUTCOME_UNKNOWN) else it }
            val checkpoint = if (recovered == loaded.entries) loaded else store.commit(loaded.revision, recovered)
            DshCommandOutbox(store, checkpoint)
        }

        internal fun validate(entries: List<PromptOutboxEntry>) {
            if (entries.size > MAX_ENTRIES || entries.sumOf { it.payloadBytes.toLong() } > MAX_TOTAL_TEXT_BYTES ||
                entries.sumOf { it.imageBytes.toLong() } > MAX_TOTAL_IMAGE_BYTES) throw OutboxException(OutboxFailure.FULL)
            if (entries.map { it.requestId }.toSet().size != entries.size) throw OutboxException(OutboxFailure.CORRUPT)
        }
    }

    private val mutex = Mutex()
    private var revision = checkpoint.revision
    private var writable = true
    private val mutable = MutableStateFlow(immutable(checkpoint.entries))
    val snapshot: StateFlow<List<PromptOutboxEntry>> = mutable.asStateFlow()

    fun forRoute(route: DshCommandRoute) = PromptOutbox(this, route)
    fun pending(route: DshCommandRoute): List<PromptOutboxEntry> = snapshot.value.filter { it.route == route }

    suspend fun prepare(route: DshCommandRoute, text: String, mode: String = "queue", receipts: List<String> = emptyList(),
                        images: List<NativeImagePart> = emptyList()): PromptOutboxEntry {
        val entry = PromptOutboxEntry(UUID.randomUUID().toString(), route, text, mode, receipts = receipts,
            images = images, neverAttempted = true)
        return change { entries -> (entries + entry) to entry }
    }

    /** Explicit retry preserves both immutable route and requestId, including after an unknown outcome. */
    suspend fun retry(requestId: String, route: DshCommandRoute): PromptOutboxEntry? = update(requestId, route) {
        check(it.state != PromptDeliveryState.SENDING) { "prompt already sending" }
        it.copy(state = PromptDeliveryState.PENDING)
    }

    /** Only a proven-unsent payload can change. Replace its identity to fence a racing send. */
    suspend fun editPendingText(requestId: String, route: DshCommandRoute, text: String): PromptOutboxEntry? = change { entries ->
        val entry = entries.firstOrNull { it.requestId == requestId && it.route == route && it.canEdit }
        if (entry == null || entry.text == text) entries to entry
        else {
            val revised = entry.copy(requestId = UUID.randomUUID().toString(), text = text)
            entries.map { if (it === entry) revised else it } to revised
        }
    }

        check(it.canEdit) { "prompt already attempted" }
    suspend fun markSending(requestId: String, route: DshCommandRoute): PromptOutboxEntry? = update(requestId, route) {
        check(it.state == PromptDeliveryState.PENDING) { "explicit retry required" }
        it.copy(state = PromptDeliveryState.SENDING)
    }

    suspend fun markUnknown(requestId: String, route: DshCommandRoute): PromptOutboxEntry? = update(requestId, route) {
        check(it.state == PromptDeliveryState.SENDING || it.state == PromptDeliveryState.OUTCOME_UNKNOWN) { "prompt not sending" }
        it.copy(state = PromptDeliveryState.OUTCOME_UNKNOWN)
    }

    suspend fun markRefused(requestId: String, route: DshCommandRoute): PromptOutboxEntry? = update(requestId, route) {
        it.copy(state = PromptDeliveryState.REFUSED)
    }

    suspend fun settle(requestId: String, route: DshCommandRoute): PromptOutboxEntry? = remove { it.requestId == requestId && it.route == route }.firstOrNull()
    suspend fun abandon(requestId: String, route: DshCommandRoute? = null): PromptOutboxEntry? = remove {
        it.requestId == requestId && (route == null || it.route == route) && it.state != PromptDeliveryState.SENDING
    }.firstOrNull()
    suspend fun clearOwner(ownerId: String): List<PromptOutboxEntry> = remove { it.route.ownerId == ownerId }
    suspend fun clearHost(ownerId: String, hostId: String): List<PromptOutboxEntry> = remove { it.route.ownerId == ownerId && it.route.hostId == hostId }
    suspend fun revokeRuntime(ownerId: String, hostId: String, runtimeId: String): List<PromptOutboxEntry> = remove {
        it.route.ownerId == ownerId && it.route.hostId == hostId && it.route.runtimeId == runtimeId
    }

    private suspend fun update(requestId: String, route: DshCommandRoute, transform: (PromptOutboxEntry) -> PromptOutboxEntry): PromptOutboxEntry? = change { entries ->
        val found = entries.firstOrNull { it.requestId == requestId && it.route == route }
        val next = found?.let(transform)
        entries.map { if (it === found) next!! else it } to next
    }

    private suspend fun remove(predicate: (PromptOutboxEntry) -> Boolean): List<PromptOutboxEntry> = change { entries ->
        entries.filterNot(predicate) to entries.filter(predicate)
    }

    // Finish a started disk transaction and publish its result even if the UI caller is cancelled.
    private suspend fun <T> change(transform: (List<PromptOutboxEntry>) -> Pair<List<PromptOutboxEntry>, T>): T = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            if (!writable) throw OutboxException(OutboxFailure.UNAVAILABLE)
            val (next, result) = transform(mutable.value)
            validate(next)
            if (next != mutable.value) {
                val saved = try { store.commit(revision, next) } catch (error: Exception) {
                    // A failed sync may have committed; only a fresh authenticated open can resolve it.
                    writable = false
                    throw error
                }
                revision = saved.revision
                mutable.value = immutable(saved.entries)
            }
            result
        }
    }

    private fun immutable(entries: List<PromptOutboxEntry>): List<PromptOutboxEntry> = Collections.unmodifiableList(ArrayList(entries))
}
