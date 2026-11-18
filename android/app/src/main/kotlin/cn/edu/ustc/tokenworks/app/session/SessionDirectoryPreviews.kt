package cn.edu.ustc.tokenworks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class DirectoryTurn(val turn: Long, val step: Long?)
internal data class DirectoryPreview(val text: String? = null, val hasMessage: Boolean = false, val turn: DirectoryTurn? = null)

/** The latest visible conversation message in the committed tail, not reasoning or tool arguments. */
internal fun projectDirectoryPreview(page: DshSessionPageResult): DirectoryPreview {
    var message = DirectoryPreview()
    var position: DirectoryTurn? = null
    for ((_, event) in page.records) {
        val data = event.data as? JsonObject ?: continue
        // Compacted/replacement records are not conversation messages in the normal reader.
        if ((event.surfaceOp as? DshOptional.Value)?.value is JsonObject) continue
        val turn = (data.fields["turn"] as? JsonNumber)?.value?.directoryIndex()
        val step = (data.fields["step"] as? JsonNumber)?.value?.directoryIndex()
        when (event.type) {
            "turn/start" -> position = turn?.let { DirectoryTurn(it, null) }
            "step/start", "step/end", "assistant/message", "assistant/attempt", "tool/call", "tool/result" -> {
                if (turn != null && step != null) position = DirectoryTurn(turn, step)
            }
            "turn/end" -> position = null
        }
        when (event.type) {
            "user/message", "user/message-appended" -> {
                val source = (data.fields["source"] as? JsonObject)?.fields?.get("kind") as? JsonString
                if (source?.value !in setOf("runtime-context", "system-prompt")) messagePreview(data)?.let { message = it }
            }
            "assistant/message" -> (data.fields["message"] as? JsonObject)?.let(::messagePreview)?.let { message = it }
        }
    }
    return message.copy(turn = position)
}

private fun messagePreview(message: JsonObject): DirectoryPreview? {
    val parts = (message.fields["content"] as? JsonArray)?.values.orEmpty()
    var attachment = false
    for (part in parts) {
        val block = part as? JsonObject ?: continue
        when ((block.fields["type"] as? JsonString)?.value) {
            "text" -> (block.fields["text"] as? JsonString)?.value?.let(::directoryLine)?.let { return DirectoryPreview(it, true) }
            "image", "file" -> attachment = true
        }
    }
    return if (attachment) DirectoryPreview(hasMessage = true) else null
}

private fun Double.directoryIndex(): Long? = toLong().takeIf { it in 0..MAX_SAFE_INTEGER && it.toDouble() == this }

/** One bounded request per composed row/version; no disk cache, background scan, or live stream. */
internal class SessionDirectoryPreviewSource(
    private val route: DshTransport,
    private val isCurrent: () -> Boolean,
    private val mayRead: (String) -> Boolean,
) {
    private val concurrency = Semaphore(3)

    suspend fun load(row: SessionDirectoryEntry): DirectoryPreview? = concurrency.withPermit {
        val parent = row.parentSessionId
        if (!isCurrent() || !mayRead(row.id) || (parent != null &&
                (!mayRead(parent) || row.subagentMode !in setOf("one-shot", "continuable")))) return@withPermit null
        withTimeoutOrNull(8_000) {
            try {
                val address = sessionHistoryAddress(row.id, parent?.let { DshSubagentAddress(it, row.subagentMode) })
                val method = DshMethods.SessionPage
                val args = DshSessionPageInput(DshSessionPageInputRequest(address, maxMessages = DshOptional.Value(8.0)))
                val page = route.native(method.endpoint, method.input.encode(args) as JsonObject)
                withContext(Dispatchers.Default) { projectDirectoryPreview(method.output.decode(page)) }
                    .takeIf { isCurrent() && mayRead(row.id) && row.parentSessionId?.let { !mayRead(it) } != true }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A missing preview never grants content access or prevents opening the directory.
                null
            }
        }
    }
}

@Composable
internal fun rememberDirectoryPreview(source: SessionDirectoryPreviewSource?, row: SessionDirectoryEntry): DirectoryPreview? {
    var preview by remember(source, row.id, row.updatedAt) { mutableStateOf<DirectoryPreview?>(null) }
    LaunchedEffect(source, row.id, row.updatedAt) {
        preview = source?.load(row)
    }
    return preview
}
