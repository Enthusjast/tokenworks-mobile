package cn.edu.ustc.tokenworks

/** The screen receives only a local row key and a closed event kind, never routing or content fields. */
data class NotificationCenterState(
    val open: Boolean = false,
    val entries: List<Entry> = emptyList(),
    val openingId: String? = null,
    val unavailable: Boolean = false,
) {
    data class Entry(val id: String, val kind: Kind)
    enum class Kind { INTERACTION, COMPLETED }
}

/** Bounded in-memory hints for the current binding; app restart does not recreate authority. */
internal class NotificationInbox(private val capacity: Int = 64) {
    private val entries = LinkedHashMap<String, PushReference>()
    private var next = 0L

    fun accept(reference: PushReference) {
        if (entries.values.any { it.notificationId == reference.notificationId }) return
        entries[(++next).toString()] = reference
        if (entries.size > capacity) entries.remove(entries.keys.first())
    }

    fun removeInvalid(owner: LocalDataOwner?, binding: String?, now: Long) {
        entries.values.removeAll { owner == null || !it.matches(owner, binding, now) }
    }

    fun resolve(id: String): PushReference? = entries[id]
    fun nextExpiry(): Long? = entries.values.minOfOrNull { it.expiresAt }
    fun projection(): List<NotificationCenterState.Entry> = entries.entries.map { (id, reference) ->
        NotificationCenterState.Entry(id, when (reference.kind) {
            "completed" -> NotificationCenterState.Kind.COMPLETED
            else -> NotificationCenterState.Kind.INTERACTION
        })
    }.asReversed()

    fun clear() = entries.clear()
}
