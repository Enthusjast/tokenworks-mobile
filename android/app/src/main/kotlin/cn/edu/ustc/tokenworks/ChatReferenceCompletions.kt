package cn.edu.ustc.tokenworks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow

internal data class ChatReferenceToken(val from: Int, val to: Int, val trigger: Char, val query: String)

/** Only complete explicit tokens, not email addresses, URLs, or a command inside prose. */
internal fun activeChatReference(value: TextFieldValue): ChatReferenceToken? {
    if (!value.selection.collapsed || value.composition != null) return null
    val text = value.text
    val cursor = value.selection.start
    var from = cursor
    while (from > 0 && !text[from - 1].isWhitespace() && cursor - from <= 128) from--
    if (from == cursor || cursor - from > 128) return null
    val trigger = text[from]
    if (trigger != '@' && trigger != '/') return null
    if (trigger == '/' && (from > 0 && !text.substring(0, from).isBlank() || text.getOrNull(from + 1) == '/')) return null
    if (trigger == '@' && text.getOrNull(from + 1) == '"') return null
    var to = cursor
    while (to < text.length && !text[to].isWhitespace()) to++
    return ChatReferenceToken(from, to, trigger, text.substring(from + 1, cursor))
}

internal fun TextFieldValue.acceptChatReference(token: ChatReferenceToken, reference: String): TextFieldValue =
    copy(selection = TextRange(token.from, token.to)).insertReference(reference)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatReferenceCompletions(client: DiscoveryClient, session: String, value: TextFieldValue,
    skills: Boolean, files: Boolean, onPick: (ChatReferenceToken, String) -> Unit) {
    val token = remember(value.text, value.selection, value.composition) { activeChatReference(value) } ?: return
    val file = token.trigger == '@'
    if (if (file) !files else !skills) return
    val result = rememberDiscoveryQuery(Triple(client, session, file), token.query, debounce = true) {
        if (file) client.references(session, token.query).asSequence().mapNotNull { row ->
            val path = row.path
            if (path.isBlank() || path.any { it.isISOControl() || it == '"' }) null else {
                val name = if (row.kind == "directory") path.trimEnd('/') + "/" else path
                PromptReference(path, null, if (name.any(Char::isWhitespace)) "@\"$name\"" else "@$name")
            }
        }.take(3).toList() else client.skills(session).asSequence()
            .filter { it.name.startsWith(token.query, ignoreCase = true) }
            .take(3).map { PromptReference(it.name, it.description, "/${it.name}") }.toList()
    }
    val matches = result.value.orEmpty()
    if (matches.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xxs)) {
        Text(stringResource(if (file) R.string.chat_audit_mention else R.string.chat_audit_slash),
            style = MaterialTheme.typography.labelMedium)
        FlowRow {
            matches.forEach { reference ->
                TextButton({ onPick(token, reference.text) }, Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                    Text(reference.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
