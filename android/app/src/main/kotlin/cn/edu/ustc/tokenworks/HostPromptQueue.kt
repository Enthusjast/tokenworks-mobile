package cn.edu.ustc.tokenworks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal data class HostQueuedPrompt(val id: String, val nextTurn: Boolean, val text: String, val textOnly: Boolean)

/** The inbox is opaque in the wire schema. Only project the captured native user-message shape. */
internal fun hostQueueItems(turn: List<JsonValue>, step: List<JsonValue>): List<HostQueuedPrompt> {
    if (turn.size > 256 || step.size > 256) throw ProtocolException()
    val seen = HashSet<String>()
    return buildList {
        fun project(items: List<JsonValue>, nextTurn: Boolean) {
            for (item in items) {
                val value = item as? JsonObject ?: continue
                val id = (value.fields["id"] as? JsonString)?.value?.takeIf {
                    it.isNotEmpty() && it.length <= 128 && it.none(Char::isISOControl)
                } ?: continue
                if (!seen.add(id)) throw ProtocolException()
                if (value.fields["role"] != JsonString("user")) continue
                val source = value.fields["source"] as? JsonObject ?: continue
                if (source.fields["kind"] != JsonString("user")) continue
                val parts = (value.fields["content"] as? JsonArray)?.values ?: continue
                if (parts.isEmpty() || parts.size > 64) continue
                val text = StringBuilder()
                var safeToEdit = true
                for (part in parts) {
                    val fields = (part as? JsonObject)?.fields
                    val body = (fields?.get("text") as? JsonString)?.value
                    if (fields == null || fields["type"] != JsonString("text") || body == null || fields.keys != setOf("type", "text")) {
                        safeToEdit = false
                    } else {
                        if (text.isNotEmpty()) text.append('\n')
                        if (body.length > MAX_DRAFT_CHARACTERS - text.length) safeToEdit = false
                        text.append(body.take((MAX_DRAFT_CHARACTERS - text.length).coerceAtLeast(0)))
                    }
                }
                add(HostQueuedPrompt(id, nextTurn, text.toString(), safeToEdit))
            }
        }
        project(turn, true)
        project(step, false)
    }
}

internal class HostPromptQueue(private val transport: DshTransport, private val sessionId: String) {
    suspend fun load(): List<HostQueuedPrompt> {
        val method = DshMethods.SessionProjections
        val request = method.input.encode(DshSessionProjectionsInput(DshSessionProjectionsInputRequest(sessionId))) as JsonObject
        return when (val snapshot = method.output.decode(transport.native(method.endpoint, request))) {
            is DshSessionProjectionsResult.Case2 -> (snapshot.value.values.inbox as? DshOptional.Value)?.value?.let {
                hostQueueItems(it.`next-turn`, it.`next-step`)
            }
            else -> null
        } ?: throw DshFailure("queue.unavailable")
    }

    /** Refresh before editing to avoid replacing new content with a stale dialog. No mutation is retried. */
    suspend fun change(expected: HostQueuedPrompt, replacement: String?, isCurrent: () -> Boolean): Boolean {
        if (!isCurrent() || load().none { it == expected } || !isCurrent()) return false
        val action = if (replacement == null) DshSessionUpdateQueueInputRequestAction.Case2(
            DshSessionUpdateQueueInputRequestActionCase2("remove"),
        ) else {
            if (!expected.textOnly || replacement.isBlank() || replacement.length > MAX_DRAFT_CHARACTERS || replacement == expected.text) return false
            DshSessionUpdateQueueInputRequestAction.Case1(DshSessionUpdateQueueInputRequestActionCase1("edit", listOf(
                DshSessionUpdateQueueInputRequestActionCase1ContentItem("text", replacement),
            )))
        }
        val method = DshMethods.SessionUpdateQueue
        val request = method.input.encode(DshSessionUpdateQueueInput(DshSessionUpdateQueueInputRequest(sessionId, expected.id, action))) as JsonObject
        return method.output.decode(transport.native(method.endpoint, request)).accepted
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HostQueueSection(transport: DshTransport, sessionId: String, writable: Boolean) = key(transport, sessionId) {
    val client = remember { HostPromptQueue(transport, sessionId) }
    val currentWritable by rememberUpdatedState(writable)
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<HostQueuedPrompt>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf<Int?>(null) }
    var candidate by remember { mutableStateOf<HostQueuedPrompt?>(null) }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    suspend fun refresh() {
        try { messages = client.load() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { messages = emptyList(); notice = R.string.host_queue_unavailable }
        finally { busy = false }
    }
    LaunchedEffect(client) { refresh() }
    Column(verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xs)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
            Text(stringResource(R.string.host_queue_title), style = MaterialTheme.typography.titleMedium)
            TextButton({ busy = true; notice = null; scope.launch { refresh() } }, enabled = !busy) { Text(stringResource(R.string.action_refresh)) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        notice?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
        if (!busy && messages.isEmpty() && notice == null) Text(stringResource(R.string.host_queue_empty))
        for (entry in messages.take(32)) {
            Text(stringResource(if (entry.nextTurn) R.string.host_queue_next_turn else R.string.host_queue_next_step), style = MaterialTheme.typography.labelMedium)
            Text(entry.text.ifBlank { stringResource(R.string.host_queue_non_text) }, maxLines = 3, overflow = TextOverflow.Ellipsis)
            FlowRow {
                if (entry.textOnly) TextButton({ candidate = entry; editing = true; draft = entry.text }, enabled = writable && !busy) {
                    Text(stringResource(R.string.chat_audit_edit_pending))
                }
                TextButton({ candidate = entry; editing = false }, enabled = writable && !busy) {
                    Text(stringResource(R.string.chat_outbox_remove))
                }
            }
        }
        if (messages.size > 32) Text(stringResource(R.string.host_queue_truncated), style = MaterialTheme.typography.bodySmall)
    }
    val entry = candidate
    if (entry != null && writable) AlertDialog(onDismissRequest = { candidate = null; draft = "" },
        title = { Text(stringResource(if (editing) R.string.chat_audit_edit_pending else R.string.chat_outbox_remove)) },
        text = {
            if (editing) OutlinedTextField(draft, { if (it.length <= MAX_DRAFT_CHARACTERS) draft = it }, label = { Text(stringResource(R.string.chat_input)) })
            else Text(stringResource(R.string.host_queue_confirm_remove))
        },
        confirmButton = { TextButton({
            val replacement = draft.takeIf { editing }
            draft = ""
            candidate = null
            notice = null
            busy = true
            scope.launch {
                try {
                    if (!client.change(entry, replacement) { coroutineContext.ensureActive(); currentWritable }) {
                        notice = R.string.host_queue_changed
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { notice = R.string.host_queue_outcome_unknown }
                refresh()
            }
        }, enabled = !busy && (!editing || (draft.isNotBlank() && draft != entry.text))) {
            Text(stringResource(if (editing) R.string.chat_audit_save else R.string.chat_outbox_remove))
        } },
        dismissButton = { TextButton({ candidate = null; draft = "" }) { Text(stringResource(R.string.action_cancel)) } })
}
