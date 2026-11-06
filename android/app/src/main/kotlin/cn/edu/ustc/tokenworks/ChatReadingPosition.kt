package cn.edu.ustc.tokenworks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.enthusjast.tokenworks.session.ConversationItem

/** Presentation position only; callers must revalidate the resource and the message key before restoring. */
class ChatReadingPosition {
    var key: String? = null
    var offset: Int = 0
    var following: Boolean = true
    internal var expandedSourceIds by mutableStateOf<Set<String>>(emptySet())

    internal fun retainExpandedSources(items: List<ConversationItem>) {
        if (expandedSourceIds.isEmpty()) return
        val currentIds = items.mapTo(HashSet()) { it.id }
        if (expandedSourceIds.any { it !in currentIds }) expandedSourceIds = expandedSourceIds.filterTo(HashSet()) { it in currentIds }
    }
}
