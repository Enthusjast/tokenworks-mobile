package cn.edu.ustc.tokenworks

import java.util.Collections

/** Untrusted display hints only: never use a name as a resource or permission. */
class DirectoryDisplayMetadata private constructor(
    val title: String?, val workspaceTitle: String?, val titleSource: String?, val titleAsOfSeq: Long?, val blank: Boolean,
) {
    companion object {
        /** Titles come from native projections; hostile control characters are dropped, never escaped. */
        fun native(title: String?, workspaceTitle: String?, source: String?, asOfSeq: Long?, blank: Boolean): DirectoryDisplayMetadata {
            fun plain(value: String?): String? = when {
                value == null -> null
                value.length > 256 -> null
                !value.any { it <= '\u001f' || it in '\u007f'..'\u009f' || it == '\u061c' || it == '\u200e' || it == '\u200f' ||
                    it == '\u2028' || it == '\u2029' || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' } -> value
                else -> null
            }
            val shown = plain(title)
            return DirectoryDisplayMetadata(
                shown, plain(workspaceTitle), if (shown == null) null else source, if (shown == null) null else asOfSeq, blank,
            )
        }
    }
}

/** The native directory is a single unpaginated list; dsh already filtered it. */
class SessionDirectoryPage(val items: List<SessionDirectoryEntry>) {
    init {
        if (items.size > 2000) throw RemoteRequestException("network.invalid_response")
        Collections.unmodifiableList(items)
    }

    override fun toString() = "SessionDirectoryPage(${items.size})"
}
