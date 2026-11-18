package cn.edu.ustc.tokenworks

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private class TerminalControls {
    var draft by mutableStateOf("")
    var direct by mutableStateOf(false)
    var searching by mutableStateOf(false)
    var query by mutableStateOf("")
    val viewportScroll = TerminalViewportScroll()
}

/** The UI can only act on the Session's scoped terminal coordinator. */
@Composable
internal fun TerminalScreen(watcher: TerminalWatcher, modifier: Modifier = Modifier) {
    val state by watcher.state.collectAsState()
    val actions = rememberCoroutineScope()
    val controls = remember(watcher) { mutableMapOf<String, TerminalControls>() }
    var closing by remember(watcher) { mutableStateOf<TerminalInfo?>(null) }
    val surfaces = tokenWorksSurfaces()
    val selected = state.selected
    LaunchedEffect(watcher) { watcher.refresh() }
    LaunchedEffect(state.terminals) { controls.keys.retainAll(state.terminals.map { it.id }.toSet()) }
    BoxWithConstraints(modifier.fillMaxSize().background(surfaces.codeWellHeader).imePadding()) {
        val compact = maxHeight < 550.dp
        val viewportHeight = (maxHeight * if (compact) .45f else .44f).coerceIn(116.dp, 420.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(TokenWorksSpacing.md),
            verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
            TerminalActionRow {
                Button({ actions.launch { watcher.open() } }, enabled = !state.busy, modifier = TerminalTouch) {
                    Text(stringResource(R.string.terminal_new))
                }
                OutlinedButton({ actions.launch { watcher.refresh() } }, enabled = !state.busy, modifier = TerminalTouch) {
                    Text(stringResource(R.string.terminal_refresh))
                }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.terminals.isNotEmpty()) {
                TerminalActionRow(Modifier.selectableGroup()) {
                    state.terminals.forEachIndexed { index, info -> key(info.id) {
                        val isSelected = info.id == state.selectedId
                        val tab = state.tabs[info.id]
                        val status = stringResource(terminalStateLabel(info, tab))
                        Surface(shape = tokenWorksSquircle(TokenWorksRadius.md), border = BorderStroke(1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary else surfaces.codeWellBorder),
                            color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else surfaces.codeWellBackground) {
                            Box(TerminalTouch.widthIn(min = 88.dp, max = 230.dp)
                                .selectable(isSelected, enabled = !state.busy, role = Role.Tab,
                                    onClick = { actions.launch { watcher.select(info.id) } })
                                .semantics { stateDescription = status }
                                .padding(horizontal = TokenWorksSpacing.md), contentAlignment = Alignment.CenterStart) {
                                Text(stringResource(R.string.terminal_tab_title, index + 1, info.title), maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge,
                                    color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else surfaces.codeWellForeground)
                            }
                        }
                    } }
                }
            }
            state.error?.takeUnless { state.reconnecting && state.actionError == null }?.let {
                TokenWorksStatusNotice(it, tone = TokenWorksNoticeTone.ERROR)
            }
            if (selected == null) TerminalEmpty(viewportHeight, R.string.terminal_empty_screen)
            else key(selected.info.id) {
                val tab = remember { controls.getOrPut(selected.info.id) { TerminalControls() } }
                TerminalTabContent(watcher, state, selected, tab, viewportHeight, onClose = { closing = selected.info })
            }
        }
    }
    closing?.let { info ->
        AlertDialog(onDismissRequest = { closing = null }, title = { Text(stringResource(R.string.terminal_close_title)) },
            text = { Text(stringResource(R.string.terminal_close_body, info.title)) },
            confirmButton = { TextButton({ closing = null; actions.launch { watcher.closeTerminal(info.id) } }, TerminalTouch) {
                Text(stringResource(R.string.terminal_close_process))
            } }, dismissButton = { TextButton({ closing = null }, TerminalTouch) { Text(stringResource(R.string.action_cancel)) } })
    }
}

@Composable
private fun TerminalTabContent(
    watcher: TerminalWatcher,
    state: TerminalUiState,
    selected: TerminalTabState,
    controls: TerminalControls,
    viewportHeight: androidx.compose.ui.unit.Dp,
    onClose: () -> Unit,
) {
    val actions = rememberCoroutineScope()
    val info = selected.info
    val id = info.id
    val surfaces = tokenWorksSurfaces()
    val display = selected.display
    var showTranscript by remember { mutableStateOf(false) }
    var viewportFit by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var modifiers by remember { mutableStateOf(TerminalModifiers()) }
    val enabled = selected.writable && !state.busy
    fun keyStroke(key: TerminalKey, physical: TerminalModifiers = TerminalModifiers()) {
        if (watcher.sendKey(id, key, modifiers + physical)) modifiers = TerminalModifiers()
    }
    fun type(text: String, physical: TerminalModifiers = TerminalModifiers()): Boolean {
        val accepted = watcher.type(id, text, modifiers + physical)
        if (accepted) modifiers = TerminalModifiers()
        return accepted
    }
    fun submit() {
        if (controls.direct) keyStroke(TerminalKey.ENTER)
        else if (watcher.submit(id, controls.draft)) controls.draft = ""
    }
    LaunchedEffect(enabled) { if (!enabled) modifiers = TerminalModifiers() }
    val label = stringResource(terminalStateLabel(info, selected))
    Text(stringResource(R.string.terminal_status_dimensions, label, info.cols, info.rows) +
        (info.exitCode?.let { stringResource(R.string.terminal_exit_code, it) } ?: ""),
        color = surfaces.codeWellHeaderForeground, style = MaterialTheme.typography.labelMedium)
    if (selected.reconnecting) TokenWorksStatusNotice(stringResource(R.string.terminal_reconnecting), tone = TokenWorksNoticeTone.WARNING)
    TerminalActionRow {
        if (selected.connected) OutlinedButton(watcher::detach, modifier = TerminalTouch, enabled = !state.busy) {
            Text(stringResource(R.string.terminal_detach))
        }
        if (!selected.connected || !selected.writable && info.running && !selected.reconnecting) {
            OutlinedButton({ actions.launch { watcher.attach(id) } }, modifier = TerminalTouch, enabled = !state.busy) {
                Text(stringResource(if (selected.connected) R.string.terminal_reconnect else R.string.terminal_connect))
            }
        }
        TextButton(onClose, modifier = TerminalTouch, enabled = !state.busy) { Text(stringResource(R.string.terminal_close_process)) }
    }
    if (display != null) {
        TerminalActionRow {
            FilterChip(controls.searching, { controls.searching = !controls.searching }, { Text(stringResource(R.string.terminal_search)) },
                modifier = TerminalTouch)
            TextButton({ showTranscript = true }, modifier = TerminalTouch) { Text(stringResource(R.string.terminal_select_output)) }
        }
        TerminalViewport(display, viewportHeight, controls.searching, controls.query, { controls.query = it }, controls.viewportScroll,
            onFit = { cols, rows -> viewportFit = cols to rows })
    } else {
        TerminalEmpty(viewportHeight, R.string.terminal_waiting)
    }
    TerminalComposer(id, enabled, controls, modifiers, ::submit, ::keyStroke, ::type, watcher::paste)
    TerminalActionRow {
        listOf(Triple("Ctrl+C", TerminalModifiers(ctrl = true), "c"), Triple("Ctrl+D", TerminalModifiers(ctrl = true), "d")).forEach { (label, mod, text) ->
            OutlinedButton({ if (watcher.type(id, text, mod)) modifiers = TerminalModifiers() }, enabled = enabled, modifier = TerminalTouch) { Text(label) }
        }
        TerminalKeyButton("Tab", TerminalKey.TAB, enabled, ::keyStroke)
        TerminalKeyButton("Esc", TerminalKey.ESCAPE, enabled, ::keyStroke)
        TerminalKeyButton(stringResource(R.string.terminal_key_up), TerminalKey.UP, enabled, ::keyStroke)
        TerminalKeyButton(stringResource(R.string.terminal_key_down), TerminalKey.DOWN, enabled, ::keyStroke)
        TerminalKeyButton(stringResource(R.string.terminal_key_left), TerminalKey.LEFT, enabled, ::keyStroke)
        TerminalKeyButton(stringResource(R.string.terminal_key_right), TerminalKey.RIGHT, enabled, ::keyStroke)
        TerminalKeyButton(stringResource(R.string.terminal_key_backspace), TerminalKey.BACKSPACE, enabled, ::keyStroke)
        TerminalKeyButton(stringResource(R.string.terminal_key_page_up), TerminalKey.PAGE_UP, enabled, ::keyStroke)
        TerminalKeyButton(stringResource(R.string.terminal_key_page_down), TerminalKey.PAGE_DOWN, enabled, ::keyStroke)
    }
    TerminalActionRow {
        FilterChip(controls.direct, { controls.direct = !controls.direct; modifiers = TerminalModifiers() },
            { Text(stringResource(R.string.terminal_direct_input)) }, enabled = enabled, modifier = TerminalTouch)
        FilterChip(modifiers.ctrl, { modifiers = modifiers.copy(ctrl = !modifiers.ctrl) }, { Text("Ctrl") },
            enabled = enabled, modifier = TerminalTouch)
        FilterChip(modifiers.shift, { modifiers = modifiers.copy(shift = !modifiers.shift) }, { Text("Shift") },
            enabled = enabled, modifier = TerminalTouch)
        FilterChip(modifiers.alt, { modifiers = modifiers.copy(alt = !modifiers.alt) }, { Text("Alt") },
            enabled = enabled, modifier = TerminalTouch)
    }
    TerminalActionRow {
        for ((cols, rows) in listOf(80 to 24, 100 to 32, 120 to 40)) {
            OutlinedButton({ watcher.resize(cols, rows, id) }, modifier = TerminalTouch,
                enabled = enabled && state.environment?.accepts(cols, rows) == true && (cols != info.cols || rows != info.rows)) {
                Text("${cols}×$rows")
            }
        }
        viewportFit?.let { (cols, rows) ->
            val env = state.environment
            val width = cols.coerceIn(2, env?.maxCols ?: 2)
            val height = rows.coerceIn(1, env?.maxRows ?: 1)
            OutlinedButton({ watcher.resize(width, height, id) }, modifier = TerminalTouch,
                enabled = enabled && env != null && (width != info.cols || height != info.rows)) {
                Text(stringResource(R.string.terminal_fit, width, height))
            }
        }
    }
    Text(stringResource(R.string.terminal_detach_hint), style = MaterialTheme.typography.labelSmall, color = surfaces.codeWellHeaderForeground)
    if (showTranscript && display != null) TerminalTranscript(display) { showTranscript = false }
}

@Composable
private fun TerminalComposer(
    id: String,
    enabled: Boolean,
    controls: TerminalControls,
    modifiers: TerminalModifiers,
    submit: () -> Unit,
    keyStroke: (TerminalKey, TerminalModifiers) -> Unit,
    type: (String, TerminalModifiers) -> Boolean,
    paste: (String, String) -> Boolean,
) {
    val direct = controls.direct
    val clipboard = LocalClipboardManager.current
    var composing by remember(direct, enabled) { mutableStateOf(TextFieldValue()) }
    fun onKey(event: AndroidKeyEvent): Boolean {
        if (!enabled || event.action != AndroidKeyEvent.ACTION_DOWN) return false
        val physical = TerminalModifiers(event.isShiftPressed, event.isAltPressed, event.isCtrlPressed)
        val key = terminalKey(event.keyCode)
        if (key == TerminalKey.ENTER || key == TerminalKey.KP_ENTER && !direct) { submit(); return true }
        if (key != null && (direct || key in commandKeys || controls.draft.isEmpty())) {
            keyStroke(key, physical)
            return true
        }
        if (direct || physical.ctrl || physical.alt || modifiers.ctrl || modifiers.alt || modifiers.shift) {
            val meta = event.metaState and (AndroidKeyEvent.META_SHIFT_MASK or AndroidKeyEvent.META_CAPS_LOCK_ON)
            val cp = event.getUnicodeChar(meta)
            if (cp >= 32 && cp !in 127..159 && Character.isValidCodePoint(cp)) {
                type(String(Character.toChars(if (modifiers.shift) Character.toUpperCase(cp) else cp)), physical)
                return true
            }
        }
        return false
    }
    val field = Modifier.onPreviewKeyEvent { onKey(it.nativeKeyEvent) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        if (direct) OutlinedTextField(composing, { incoming ->
            if (!enabled) return@OutlinedTextField
            if (incoming.composition != null) composing = incoming
            else {
                val safe = incoming.text.filter { it >= ' ' && it !in '\u007f'..'\u009f' }
                if (safe.isNotEmpty()) type(if (modifiers.shift) safe.uppercase() else safe, TerminalModifiers())
                composing = TextFieldValue()
            }
        }, Modifier.weight(1f).then(field), label = { Text(stringResource(R.string.terminal_direct_input_label)) }, singleLine = true,
            enabled = enabled, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }))
        else OutlinedTextField(controls.draft, { controls.draft = it }, Modifier.weight(1f).then(field),
            label = { Text(stringResource(R.string.terminal_input_label)) }, singleLine = true, enabled = enabled,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { submit() }))
        Button(submit, modifier = TerminalTouch, enabled = enabled) {
            Text(stringResource(if (direct) R.string.terminal_enter else R.string.terminal_send))
        }
    }
    if (direct) TextButton({ clipboard.getText()?.text?.let { paste(id, it) } }, modifier = TerminalTouch, enabled = enabled) {
        Text(stringResource(R.string.terminal_paste))
    }
}

@Composable
private fun TerminalTranscript(display: TerminalDisplay, dismiss: () -> Unit) {
    // Freeze the selectable transcript while this dialog is open.
    val text = remember { display.transcript() }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.terminal_select_output)) },
        text = { SelectionContainer { Text(text, Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState()),
            fontFamily = FontFamily.Monospace) } },
        confirmButton = { TextButton(dismiss, TerminalTouch) { Text(stringResource(R.string.action_close)) } })
}

@Composable
private fun TerminalEmpty(height: androidx.compose.ui.unit.Dp, label: Int) {
    val surfaces = tokenWorksSurfaces()
    Surface(Modifier.fillMaxWidth().height(height), shape = tokenWorksSquircle(TokenWorksRadius.md),
        color = surfaces.codeWellBackground, border = BorderStroke(1.dp, surfaces.codeWellBorder)) {
        Box(Modifier.padding(TokenWorksSpacing.md), contentAlignment = Alignment.Center) {
            Text(stringResource(label), color = surfaces.codeWellForeground)
        }
    }
}

@Composable
private fun TerminalKeyButton(label: String, key: TerminalKey, enabled: Boolean, send: (TerminalKey, TerminalModifiers) -> Unit) {
    OutlinedButton({ send(key, TerminalModifiers()) }, enabled = enabled, modifier = TerminalTouch) { Text(label) }
}

@Composable
internal fun TerminalActionRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm), verticalAlignment = Alignment.CenterVertically, content = content)
}

internal val TerminalTouch = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)

private fun terminalStateLabel(info: TerminalInfo, tab: TerminalTabState?) = when {
    info.state == "failed" -> R.string.terminal_state_failed
    !info.running -> R.string.terminal_state_exited
    tab?.reconnecting == true -> R.string.terminal_state_reconnecting
    tab?.connected != true -> R.string.terminal_state_disconnected
    tab.writable -> R.string.terminal_state_connected
    tab.display == null -> R.string.terminal_waiting
    else -> R.string.terminal_state_read_only
}

private operator fun TerminalModifiers.plus(other: TerminalModifiers) = TerminalModifiers(shift || other.shift, alt || other.alt, ctrl || other.ctrl)

private val commandKeys = setOf(TerminalKey.UP, TerminalKey.DOWN, TerminalKey.ESCAPE, TerminalKey.TAB,
    TerminalKey.PAGE_UP, TerminalKey.PAGE_DOWN) + (TerminalKey.F1..TerminalKey.F12)

private fun terminalKey(code: Int): TerminalKey? = when (code) {
    AndroidKeyEvent.KEYCODE_DPAD_UP -> TerminalKey.UP
    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> TerminalKey.DOWN
    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> TerminalKey.LEFT
    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> TerminalKey.RIGHT
    AndroidKeyEvent.KEYCODE_MOVE_HOME -> TerminalKey.HOME
    AndroidKeyEvent.KEYCODE_MOVE_END -> TerminalKey.END
    AndroidKeyEvent.KEYCODE_INSERT -> TerminalKey.INSERT
    AndroidKeyEvent.KEYCODE_FORWARD_DEL -> TerminalKey.DELETE
    AndroidKeyEvent.KEYCODE_PAGE_UP -> TerminalKey.PAGE_UP
    AndroidKeyEvent.KEYCODE_PAGE_DOWN -> TerminalKey.PAGE_DOWN
    AndroidKeyEvent.KEYCODE_ESCAPE -> TerminalKey.ESCAPE
    AndroidKeyEvent.KEYCODE_DEL -> TerminalKey.BACKSPACE
    AndroidKeyEvent.KEYCODE_TAB -> TerminalKey.TAB
    AndroidKeyEvent.KEYCODE_ENTER -> TerminalKey.ENTER
    in AndroidKeyEvent.KEYCODE_F1..AndroidKeyEvent.KEYCODE_F12 -> TerminalKey.entries[TerminalKey.F1.ordinal + code - AndroidKeyEvent.KEYCODE_F1]
    in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 -> TerminalKey.entries[TerminalKey.KP0.ordinal + code - AndroidKeyEvent.KEYCODE_NUMPAD_0]
    AndroidKeyEvent.KEYCODE_NUMPAD_DOT -> TerminalKey.KP_DOT
    AndroidKeyEvent.KEYCODE_NUMPAD_DIVIDE -> TerminalKey.KP_DIVIDE
    AndroidKeyEvent.KEYCODE_NUMPAD_MULTIPLY -> TerminalKey.KP_MULTIPLY
    AndroidKeyEvent.KEYCODE_NUMPAD_SUBTRACT -> TerminalKey.KP_MINUS
    AndroidKeyEvent.KEYCODE_NUMPAD_ADD -> TerminalKey.KP_PLUS
    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> TerminalKey.KP_ENTER
    else -> null
}
