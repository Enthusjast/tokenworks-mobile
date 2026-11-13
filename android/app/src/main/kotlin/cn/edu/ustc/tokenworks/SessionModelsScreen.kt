package cn.edu.ustc.tokenworks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** Catalog identifiers are passed through unchanged; current selection comes from the caller. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionModelsScreen(state: HomeState, viewModel: HomeViewModel, currentKey: String?) {
    if (!state.modelSelectionOpen) return
    var expandedKey by remember(state.selectedRuntimeId, state.selectedSessionId) { mutableStateOf<String?>(null) }
    val catalog = state.modelCatalog
    val defaultKey = catalog?.default?.key
    val unavailableCount = catalog?.unavailable?.size ?: 0

    ModalBottomSheet(
        onDismissRequest = viewModel::closeModelSelection,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = TokenWorksSpacing.readingWidth,
        shape = RoundedCornerShape(topStart = TokenWorksRadius.xl, topEnd = TokenWorksRadius.xl),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = TokenWorksSpacing.lg),
                horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.models_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                TextButton(viewModel::closeModelSelection, Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                    Text(stringResource(R.string.action_close))
                }
            }
            if (state.modelBusy) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().padding(horizontal = TokenWorksSpacing.lg),
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                contentPadding = PaddingValues(TokenWorksSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm),
            ) {
                state.modelError?.let { error ->
                    item("catalog-error") {
                        Column(verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                            TokenWorksStatusNotice(stringResource(R.string.models_error), isError = true)
                            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            TextButton(
                                onClick = viewModel::openModelSelection,
                                enabled = !state.modelBusy,
                                modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget),
                            ) { Text(stringResource(R.string.action_retry)) }
                        }
                    }
                }
                if (!state.modelBusy && state.modelError == null && catalog?.groups.orEmpty().all { it.models.isEmpty() }) {
                    item("catalog-empty") {
                        Text(
                            stringResource(R.string.models_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokenWorksSurfaces().textTertiary,
                        )
                    }
                }
                catalog?.groups?.forEachIndexed { index, group ->
                    item("group:$index") {
                        Text(
                            group.name,
                            modifier = Modifier.padding(top = TokenWorksSpacing.sm).semantics { heading() },
                            style = MaterialTheme.typography.titleSmall,
                            color = tokenWorksSurfaces().textTertiary,
                        )
                    }
                    items(group.models, key = { "model:$index:${it.key}" }) { option ->
                        NativeModelRow(
                            option = option,
                            current = currentKey == option.key,
                            default = defaultKey == option.key,
                            expanded = expandedKey == option.key,
                            busy = state.modelBusy,
                            onClick = {
                                if (option.reasoningEfforts.isEmpty()) viewModel.selectModel(option, null)
                                else expandedKey = option.key.takeUnless { expandedKey == it }
                            },
                            onConfirm = { effort -> viewModel.selectModel(option, effort) },
                        )
                    }
                }
                if (unavailableCount > 0) {
                    item("unavailable-models") {
                        Text(
                            stringResource(R.string.models_unavailable_count, unavailableCount),
                            modifier = Modifier.padding(vertical = TokenWorksSpacing.sm),
                            style = MaterialTheme.typography.bodySmall,
                            color = tokenWorksSurfaces().textTertiary,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NativeModelRow(
    option: DshModelOption,
    current: Boolean,
    default: Boolean,
    expanded: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var effort by remember(option.key, option.defaultReasoningEffort, option.reasoningEfforts) {
        mutableStateOf(option.defaultReasoningEffort)
    }
    val expansionLabel = stringResource(if (expanded) R.string.detail_expanded else R.string.detail_collapsed)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = tokenWorksSquircle(TokenWorksRadius.md),
        color = if (current) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .heightIn(min = TokenWorksSpacing.minimumTouchTarget)
                    .selectable(current, enabled = !busy, role = Role.RadioButton, onClick = onClick)
                    .semantics { if (option.reasoningEfforts.isNotEmpty()) stateDescription = expansionLabel }
                    .padding(TokenWorksSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xs)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xs),
                    ) {
                        Text(option.name, style = MaterialTheme.typography.bodyLarge)
                        if (default) Surface(
                            shape = tokenWorksSquircle(TokenWorksRadius.sm),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            Text(
                                stringResource(R.string.model_default_badge),
                                Modifier.padding(horizontal = TokenWorksSpacing.sm, vertical = TokenWorksSpacing.xxs),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    option.description?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = tokenWorksSurfaces().textTertiary)
                    }
                }
                if (current) {
                    val color = MaterialTheme.colorScheme.primary
                    Canvas(Modifier.size(TokenWorksSpacing.iconSize)) {
                        val stroke = size.width / 10f
                        drawLine(color, Offset(size.width * .15f, size.height * .5f), Offset(size.width * .4f, size.height * .75f), stroke, StrokeCap.Round)
                        drawLine(color, Offset(size.width * .4f, size.height * .75f), Offset(size.width * .85f, size.height * .25f), stroke, StrokeCap.Round)
                    }
                }
            }
            if (expanded && option.reasoningEfforts.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = TokenWorksSpacing.md).padding(bottom = TokenWorksSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm),
                ) {
                    Text(stringResource(R.string.models_effort), style = MaterialTheme.typography.bodySmall)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm),
                    ) {
                        option.reasoningEfforts.forEach { id ->
                            FilterChip(
                                selected = effort == id,
                                onClick = { effort = id },
                                enabled = !busy,
                                label = { Text(id.replaceFirstChar { it.titlecase() }) },
                                modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget),
                            )
                        }
                    }
                    Button(
                        onClick = { onConfirm(effort) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget),
                        shape = RoundedCornerShape(TokenWorksRadius.xl),
                    ) { Text(stringResource(R.string.models_apply)) }
                }
            }
        }
    }
}

/** Only the outer page owns system insets; adaptive containers supply their own insets. */
@Composable
internal fun WorkspacePanelFrame(
    title: String,
    onBack: () -> Unit,
    embedded: Boolean,
    subtitle: String? = null,
    backLabel: String = stringResource(R.string.workspace_panel_back),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (embedded) {
        Column(Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(end = TokenWorksSpacing.md),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onBack, Modifier.size(TokenWorksSpacing.minimumTouchTarget)) {
                        Icon(painterResource(R.drawable.ic_back), backLabel)
                    }
                    Column(Modifier.weight(1f).padding(vertical = TokenWorksSpacing.sm)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth(), content = content)
        }
    } else {
        Scaffold(topBar = {
            TokenWorksTopBar(title, subtitle = subtitle, navigationIcon = {
                IconButton(onBack, Modifier.size(TokenWorksSpacing.minimumTouchTarget)) {
                    Icon(painterResource(R.drawable.ic_back), backLabel)
                }
            })
        }) { insets ->
            Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), content = content)
        }
    }
}
