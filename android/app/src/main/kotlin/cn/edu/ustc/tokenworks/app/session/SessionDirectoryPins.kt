package cn.edu.ustc.tokenworks

import android.content.Context
import java.security.MessageDigest

/** Personal directory ordering only. This does not change desktop or grant access to a session. */
internal class SessionDirectoryPins(context: Context, owner: String, runtime: String) {
    private val prefs = context.getSharedPreferences(directoryPinFile(owner, runtime), Context.MODE_PRIVATE)

    fun read(): Set<String> = prefs.getStringSet("pins", null)?.toSet().orEmpty()

    fun toggle(id: String): Set<String> = read().toMutableSet().apply {
        if (!remove(id)) add(id)
        prefs.edit().putStringSet("pins", toSet()).apply()
    }
}

internal fun directoryPinFile(owner: String, runtime: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest("$owner\u0000$runtime".toByteArray(Charsets.UTF_8))
    return "session-directory-pins-v1-" + digest.joinToString("") { "%02x".format(it) }
}
