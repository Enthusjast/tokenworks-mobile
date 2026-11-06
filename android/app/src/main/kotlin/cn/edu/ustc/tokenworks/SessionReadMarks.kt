package cn.edu.ustc.tokenworks

import android.content.Context

/** Storage seam for local read watermarks, so the retention rules stay testable without a device. */
internal interface WatermarkStore {
    fun read(): Map<String, Long>
    fun write(values: Map<String, Long>)
}

/**
 * The highest native sequence this device has already shown per session. It is a client-side
 * convenience only: it never claims, requests or implies server-side read state.
 */
internal class ReadWatermarks(private val store: WatermarkStore) {
    fun marks(): Map<String, Long> = store.read().filterValues { it > 0 }

    fun mark(sessionId: String, lastPersisted: Long) {
        if (lastPersisted <= 0) return
        val current = store.read()
        if ((current[sessionId] ?: 0L) >= lastPersisted) return
        val next = LinkedHashMap(current).apply { put(sessionId, lastPersisted) }.pruned()
        store.write(next)
    }

    fun isUnread(sessionId: String, lastPersisted: Long): Boolean =
        lastPersisted > 0 && (store.read()[sessionId] ?: 0L) < lastPersisted

    private fun Map<String, Long>.pruned(): Map<String, Long> {
        if (size <= MAX_ENTRIES) return this
        return entries.sortedByDescending { it.value }.take(MAX_ENTRIES)
            .associate { it.key to it.value }
    }

    companion object {
        const val MAX_ENTRIES = 200
    }
}

/** One owner-scoped preference file; losing it only costs unread marks, never authority. */
internal class SessionReadMarks(context: Context, ownerKey: String) {
    private val prefs = context.getSharedPreferences("session-read-v1-$ownerKey", Context.MODE_PRIVATE)
    private val watermarks = ReadWatermarks(object : WatermarkStore {
        override fun read(): Map<String, Long> = buildMap {
            for ((key, value) in prefs.all) {
                if (!key.startsWith(KEY_PREFIX)) continue
                val seq = (value as? Long) ?: (value as? String)?.toLongOrNull() ?: continue
                put(key.removePrefix(KEY_PREFIX), seq)
            }
        }

        override fun write(values: Map<String, Long>) {
            prefs.edit().apply {
                clear()
                values.forEach { (sessionId, seq) -> putLong(KEY_PREFIX + sessionId, seq) }
            }.apply()
        }
    })

    fun mark(sessionId: String, lastPersisted: Long) = watermarks.mark(sessionId, lastPersisted)
    fun isUnread(sessionId: String, lastPersisted: Long): Boolean = watermarks.isUnread(sessionId, lastPersisted)

    private companion object {
        const val KEY_PREFIX = "s."
    }
}
