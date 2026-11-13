package cn.edu.ustc.tokenworks

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun AppearanceSettingsScreen(owner: CommandOwner, onBack: () -> Unit) {
    val session = rememberAppearanceSession(owner)
    val state by session.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    AppearanceSettingsContent(
        state = state,
        onChange = { preferences -> scope.launch { session.save(preferences) } },
        onRetry = {
            scope.launch {
                if (state.persistence == AppearancePersistence.READ_ERROR) session.load()
                else session.save(state.preferences)
            }
        },
        onBack = onBack,
    )
}

@Composable
internal fun AppearanceSettingsContent(
    state: AppearanceState,
    onChange: (AppearancePreferences) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val preferences = state.preferences
    Scaffold(topBar = {
        TokenWorksTopBar(
            title = stringResource(R.string.appearance_title),
            contentMaxWidth = TokenWorksSpacing.readingWidth,
            navigationIcon = {
                IconButton(onBack, Modifier.sizeIn(minWidth = TokenWorksSpacing.minimumTouchTarget, minHeight = TokenWorksSpacing.minimumTouchTarget)) {
                    Icon(painterResource(R.drawable.ic_back), stringResource(R.string.appearance_back))
                }
            },
        )
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = TokenWorksSpacing.readingWidth).fillMaxWidth().testTag("appearance-settings-list"),
                contentPadding = PaddingValues(TokenWorksSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.lg),
            ) {
                item("scope") { Text(stringResource(R.string.appearance_scope), style = MaterialTheme.typography.bodyMedium) }
                item("persistence") {
                    Column(verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                        val status = when (state.persistence) {
                            AppearancePersistence.LOADING -> R.string.appearance_loading
                            AppearancePersistence.READY -> R.string.appearance_ready
                            AppearancePersistence.SAVING -> R.string.appearance_saving
                            AppearancePersistence.SAVED -> R.string.appearance_saved
                            AppearancePersistence.READ_ERROR -> R.string.appearance_read_error
                            AppearancePersistence.WRITE_ERROR -> R.string.appearance_write_error
                        }
                        val failed = state.persistence == AppearancePersistence.READ_ERROR || state.persistence == AppearancePersistence.WRITE_ERROR
                        Text(
                            stringResource(status),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("appearance-persistence").semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        if (state.persistence == AppearancePersistence.LOADING || state.persistence == AppearancePersistence.SAVING) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        if (failed) {
                            OutlinedButton(onRetry, Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                                Text(stringResource(if (state.persistence == AppearancePersistence.READ_ERROR) R.string.appearance_retry_read else R.string.appearance_retry_save))
                            }
                        }
                    }
                }
                item("chat-preview") {
                    AppearanceChatPreview(preferences)
                }
                item("theme") {
                    AppearanceSection(stringResource(R.string.appearance_theme)) {
                        Column(Modifier.selectableGroup()) {
                            AppearanceTheme.entries.forEach { theme ->
                                val label = stringResource(when (theme) {
                                    AppearanceTheme.SYSTEM -> R.string.appearance_theme_system
                                    AppearanceTheme.LIGHT -> R.string.appearance_theme_light
                                    AppearanceTheme.DARK -> R.string.appearance_theme_dark
                                })
                                Row(
                                    modifier = Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget)
                                        .testTag("appearance-theme-${theme.name.lowercase()}")
                                        .selectable(
                                            selected = preferences.theme == theme,
                                            enabled = state.editable,
                                            role = Role.RadioButton,
                                            onClick = { onChange(preferences.copy(theme = theme)) },
                                        ).padding(vertical = TokenWorksSpacing.xs),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md),
                                ) {
                                    RadioButton(selected = preferences.theme == theme, onClick = null, enabled = state.editable)
                                    Text(label, Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
                item("reading-hint") {
                    Text(stringResource(R.string.appearance_reading_hint), style = MaterialTheme.typography.bodyMedium)
                }
                item("body") {
                    AppearanceSection(stringResource(R.string.appearance_body_scale)) {
                        AppearanceScaleControls(
                            label = stringResource(R.string.appearance_body_scale),
                            value = preferences.bodyScale,
                            enabled = state.editable,
                            tag = "body",
                            onChange = { onChange(preferences.copy(bodyScale = it)) },
                        )
                    }
                }
                item("code") {
                    AppearanceSection(stringResource(R.string.appearance_code_scale)) {
                        AppearanceScaleControls(
                            label = stringResource(R.string.appearance_code_scale),
                            value = preferences.codeScale,
                            enabled = state.editable,
                            tag = "code",
                            onChange = { onChange(preferences.copy(codeScale = it)) },
                        )
                        val wrapLabel = stringResource(R.string.appearance_wrap_code)
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm),
                        ) {
                            Text(wrapLabel, Modifier.weight(1f))
                            Switch(
                                checked = preferences.wrapCode,
                                onCheckedChange = { onChange(preferences.copy(wrapCode = it)) },
                                enabled = state.editable,
                                modifier = Modifier.sizeIn(minWidth = TokenWorksSpacing.minimumTouchTarget, minHeight = TokenWorksSpacing.minimumTouchTarget)
                                    .testTag("appearance-wrap-code").semantics { contentDescription = wrapLabel },
                            )
                        }
                        Text(stringResource(R.string.appearance_wrap_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
                item("reset") {
                    OutlinedButton(
                        onClick = { onChange(AppearancePreferences()) },
                        enabled = state.editable,
                        modifier = Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget),
                    ) { Text(stringResource(R.string.appearance_reset)) }
                }
            }
        }
    }
}

@Composable
private fun AppearanceSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun AppearanceChatPreview(preferences: AppearancePreferences) {
    val dark = when (preferences.theme) {
        AppearanceTheme.SYSTEM -> isSystemInDarkTheme()
        AppearanceTheme.LIGHT -> false
        AppearanceTheme.DARK -> true
    }
    Text(stringResource(R.string.workspace_appearance_chat_preview), style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() })
    TokenWorksTheme(darkTheme = dark) {
        Surface(color = MaterialTheme.colorScheme.background, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(TokenWorksSpacing.lg), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.lg)) {
                Surface(Modifier.align(Alignment.End).widthIn(max = 280.dp),
                    color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
                    val style = MaterialTheme.typography.bodyLarge
                    Text(stringResource(R.string.workspace_appearance_sample_user), Modifier.padding(TokenWorksSpacing.md),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = style.copy(fontSize = style.fontSize * preferences.bodyScale,
                            lineHeight = style.lineHeight * preferences.bodyScale))
                }
                val body = MaterialTheme.typography.bodyLarge
                Text(stringResource(R.string.appearance_body_preview),
                    style = body.copy(fontSize = body.fontSize * preferences.bodyScale, lineHeight = body.lineHeight * preferences.bodyScale),
                    modifier = Modifier.fillMaxWidth().testTag("appearance-body-preview"))
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.small) {
                    Box(Modifier.fillMaxWidth().padding(TokenWorksSpacing.md)) {
                        val code = MaterialTheme.typography.bodyMedium
                        Text(stringResource(R.string.appearance_code_preview), fontFamily = FontFamily.Monospace,
                            style = code.copy(fontSize = code.fontSize * preferences.codeScale, lineHeight = code.lineHeight * preferences.codeScale),
                            softWrap = preferences.wrapCode,
                            modifier = (if (preferences.wrapCode) Modifier.fillMaxWidth()
                                else Modifier.horizontalScroll(rememberScrollState())).testTag("appearance-code-preview"))
                    }
                }
                Text(stringResource(R.string.workspace_appearance_sample_only), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AppearanceScaleControls(label: String, value: Float, enabled: Boolean, tag: String, onChange: (Float) -> Unit) {
    val percentage = stringResource(R.string.appearance_scale_value, (value * 100).roundToInt())
    Text(percentage, Modifier.testTag("appearance-$tag-value").semantics { stateDescription = percentage })
    Text(stringResource(R.string.appearance_scale_range), style = MaterialTheme.typography.bodySmall)
    val smaller = stringResource(R.string.appearance_smaller_label, label)
    val larger = stringResource(R.string.appearance_larger_label, label)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
        OutlinedButton(
            onClick = { onChange(boundedReadingScale(((value * 10).roundToInt() - 1) / 10f)) },
            enabled = enabled && value > MIN_READING_SCALE,
            modifier = Modifier.weight(1f).heightIn(min = TokenWorksSpacing.minimumTouchTarget).testTag("appearance-$tag-smaller")
                .semantics { contentDescription = smaller },
        ) { Text(stringResource(R.string.appearance_smaller)) }
        OutlinedButton(
            onClick = { onChange(boundedReadingScale(((value * 10).roundToInt() + 1) / 10f)) },
            enabled = enabled && value < MAX_READING_SCALE,
            modifier = Modifier.weight(1f).heightIn(min = TokenWorksSpacing.minimumTouchTarget).testTag("appearance-$tag-larger")
                .semantics { contentDescription = larger },
        ) { Text(stringResource(R.string.appearance_larger)) }
    }
}
