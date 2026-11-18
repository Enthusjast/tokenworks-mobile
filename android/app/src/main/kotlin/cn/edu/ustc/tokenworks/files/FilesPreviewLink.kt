package cn.edu.ustc.tokenworks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/** A literal absolute host path, not a URL, relative path, picker URI, or authorization token. */
internal class FilesPreviewLink private constructor(internal val path: String) {
    val name: String = FilePreview.displayName(path)
    val isPdf: Boolean = name.endsWith(".pdf", ignoreCase = true)

    companion object {
        fun parse(target: String): FilesPreviewLink? {
            if (target.length !in 2..4096 || !target.startsWith('/') || target.endsWith('/') ||
                target.contains("//") || target.any {
                    it.isISOControl() || it in "?#%\\\u202a\u202b\u202c\u202d\u202e\u2066\u2067\u2068\u2069"
                }) return null
            try { requireText(target); FilePreview.exactPath(target) }
            catch (_: ProtocolException) { return null }
            catch (_: RemoteRequestException) { return null }
            return FilesPreviewLink(target)
        }
    }
}

/** Metadata only: composition never loads content and only an explicit click opens the preview. */
@Composable
internal fun FilesLinkPreviewCard(link: FilesPreviewLink, onPreview: (FilesPreviewLink) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = tokenWorksSquircle(TokenWorksRadius.md), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(TokenWorksSpacing.md), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xs)) {
            Text(link.name, style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.files_audit_host_link_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedButton({ onPreview(link) }, Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                Text(stringResource(if (link.isPdf) R.string.files_audit_preview_pdf else R.string.files_audit_preview_file, link.name))
            }
        }
    }
}

internal const val MAX_GALLERY_IMAGES = 128

/** Only references from the loaded, persisted user transcript; never draft or live media. */
internal fun sentImageGallery(items: List<ConversationItem>): List<NativeImageReference> = buildList {
    val seen = HashSet<String>()
    for (index in items.indices.reversed()) {
        val item = items[index]
        if (!item.persisted || item.live || item.role != ConversationRole.USER) continue
        for (block in item.blocks) {
            val image = block.media ?: continue
            if (seen.add(image.attachmentId)) add(image)
            if (size == MAX_GALLERY_IMAGES) return@buildList
        }
    }
}
