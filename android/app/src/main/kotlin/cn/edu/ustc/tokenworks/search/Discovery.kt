package cn.edu.ustc.tokenworks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal class DiscoveryClient(private val transport: DshTransport) {
    private suspend fun <I, O> call(method: DshMethod<I, O>, input: I): O = method.output.decode(
        transport.native(method.endpoint, method.input.encode(input) as JsonObject),
    )

    suspend fun search(query: String) = call(DshMethods.SessionSearch, DshSessionSearchInput(DshSessionSearchInputRequest(query)))
    suspend fun skills(session: String) = call(DshMethods.SkillsList, DshSkillsListInput(DshSkillsListInputRequest(session))).skills
    suspend fun references(session: String, query: String) = call(DshMethods.FileReferencesList, DshFileReferencesListInput(session, query))
}

internal data class DiscoveryResult<T>(val value: T? = null, val loading: Boolean = false, val error: String? = null)

@Composable
internal fun <T> rememberDiscoveryQuery(key: Any?, query: String, debounce: Boolean = false,
    request: suspend () -> T): DiscoveryResult<T> {
    var result by remember(key, query) { mutableStateOf(DiscoveryResult<T>(loading = key != null)) }
    LaunchedEffect(key, query) {
        if (key == null) return@LaunchedEffect
        if (debounce) delay(250)
        result = try { DiscoveryResult(value = request()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { DiscoveryResult(error = homeErrorCode(error)) }
    }
    return result
}

internal data class PromptReference(val label: String, val detail: String?, val text: String)

private fun fileReference(path: String, directory: Boolean): PromptReference? {
    if (path.isEmpty() || path.any { it.isISOControl() || it == '"' }) return null
    val token = if (directory) path.trimEnd('/') + "/" else path
    return PromptReference(path, null, if (token.any(Char::isWhitespace)) "@\"$token\"" else "@$token")
}

/** Insert at the selection, preserving the draft and surrounding token boundaries. */
internal fun TextFieldValue.insertReference(reference: String): TextFieldValue {
    val from = selection.min
    val to = selection.max
    val prefix = if (from > 0 && !text[from - 1].isWhitespace()) " " else ""
    val suffix = if (to == text.length || !text[to].isWhitespace()) " " else ""
    val inserted = prefix + reference + suffix
    return TextFieldValue(text.replaceRange(from, to, inserted), TextRange(from + inserted.length))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PromptReferencesSheet(client: DiscoveryClient, sessionId: String, canUseSkills: Boolean, canUseFiles: Boolean,
    onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var files by remember { mutableStateOf(!canUseSkills) }
    var query by remember { mutableStateOf("") }
    val references = rememberDiscoveryQuery(if (if (files) canUseFiles else canUseSkills) client to files else null,
        if (files) query else "", debounce = files) {
        if (files) client.references(sessionId, query).mapNotNull { fileReference(it.path, it.kind == "directory") }
        else client.skills(sessionId).map { PromptReference(it.name, it.description, "/${it.name}") }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetMaxWidth = TokenWorksSpacing.readingWidth) {
        Column(Modifier.fillMaxWidth().padding(horizontal = TokenWorksSpacing.lg, vertical = TokenWorksSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
            Text(stringResource(R.string.discovery_add), style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                FilterChip(!files, { files = false }, { Text(stringResource(R.string.discovery_skills)) }, enabled = canUseSkills)
                FilterChip(files, { files = true }, { Text(stringResource(R.string.discovery_references)) }, enabled = canUseFiles)
            }
            if (files) OutlinedTextField(query, { query = it.take(500) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.discovery_path)) })
            when {
                references.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                references.error != null -> Text(errorText(references.error), color = MaterialTheme.colorScheme.error)
                references.value.isNullOrEmpty() -> Text(stringResource(R.string.discovery_empty))
                else -> LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(references.value) { reference ->
                        Column(Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget)
                            .clickable { onPick(reference.text); onDismiss() }.padding(vertical = TokenWorksSpacing.sm)) {
                            Text(reference.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            reference.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
        }
    }
}
