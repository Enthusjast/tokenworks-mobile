package cn.edu.ustc.tokenworks

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.view.WindowInsets as PlatformWindowInsets
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal data class WorkspaceViewport(val widthDp: Int, val heightDp: Int)

internal enum class WorkspaceSize { COMPACT, MEDIUM, EXPANDED }

internal enum class WorkspaceLeftPane { NONE, RAIL, SIDEBAR }

internal data class WorkspaceLayoutSpec(
    val size: WorkspaceSize,
    val leftPane: WorkspaceLeftPane,
    val navigationWidth: Dp,
    val panelWidth: Dp,
    val panelShown: Boolean,
)

/**
 * Splits an expanded window into navigation, chat and panel columns. The panel keeps 30% of the
 * window (clamped to 360..420 dp) but never starves chat below 400 dp; the sidebar only unfolds
 * when chat still keeps 600 dp next to it.
 */
internal fun workspaceLayout(
    viewport: WorkspaceViewport,
    hasSession: Boolean,
    panelRequested: Boolean,
    navigationExpanded: Boolean,
): WorkspaceLayoutSpec {
    if (viewport.widthDp <= 0 || viewport.heightDp <= 0) {
        return WorkspaceLayoutSpec(WorkspaceSize.COMPACT, WorkspaceLeftPane.NONE, 0.dp, 0.dp, false)
    }
    val size = when {
        viewport.heightDp < 480 || viewport.widthDp < 600 -> WorkspaceSize.COMPACT
        viewport.widthDp < 840 -> WorkspaceSize.MEDIUM
        else -> WorkspaceSize.EXPANDED
    }
    if (size != WorkspaceSize.EXPANDED) {
        return WorkspaceLayoutSpec(size, WorkspaceLeftPane.NONE, 0.dp, 0.dp, false)
    }
    val width = viewport.widthDp.dp
    val room = (width - TokenWorksMetrics.railWidth - TokenWorksMetrics.chatMin).coerceAtLeast(0.dp)
    val panelWidth = minOf(TokenWorksMetrics.panelIdeal(viewport.widthDp), room)
    val available = hasSession && panelWidth >= TokenWorksMetrics.panelMin
    val panelShown = available && panelRequested
    val fits = width - TokenWorksMetrics.sidebarWidth -
        (if (panelShown) panelWidth else 0.dp) >= TokenWorksMetrics.chatComfort
    val leftPane = if (navigationExpanded && fits) WorkspaceLeftPane.SIDEBAR else WorkspaceLeftPane.RAIL
    return WorkspaceLayoutSpec(
        size = size,
        leftPane = leftPane,
        navigationWidth = if (leftPane == WorkspaceLeftPane.SIDEBAR) TokenWorksMetrics.sidebarWidth else TokenWorksMetrics.railWidth,
        panelWidth = panelWidth,
        panelShown = panelShown,
    )
}

internal enum class ChatDetailSection { INFO, COMMANDS, FILES, IMAGES }

internal data class ChatDetailTarget(
    val ownerKey: String,
    val hostId: String,
    val runtimeId: String,
    val sessionId: String,
    val section: ChatDetailSection,
    val requestId: String? = null,
)

/** Classification only: this does not consume any Compose layout insets, especially the IME. */
@Composable
internal fun rememberWorkspaceViewport(): WorkspaceViewport {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current.density
    return remember(context, configuration, density) {
        measureWorkspaceViewport(context, configuration, density)
    }
}

private fun measureWorkspaceViewport(context: Context, configuration: Configuration, density: Float): WorkspaceViewport {
    val fallback = WorkspaceViewport(configuration.screenWidthDp, configuration.screenHeightDp)
    if (Build.VERSION.SDK_INT < 30) return fallback
    var current = context
    while (current !is Activity && current is ContextWrapper) {
        val base = current.baseContext
        if (base === current) return fallback
        current = base
    }
    val activity = current as? Activity ?: return fallback
    val manager = activity.getSystemService(WindowManager::class.java) ?: return fallback
    val metrics = manager.currentWindowMetrics
    val permanent = metrics.windowInsets.getInsetsIgnoringVisibility(
        PlatformWindowInsets.Type.systemBars() or PlatformWindowInsets.Type.displayCutout(),
    )
    val bounds = metrics.bounds
    return WorkspaceViewport(
        ((bounds.width() - permanent.left - permanent.right) / density).roundToInt(),
        ((bounds.height() - permanent.top - permanent.bottom) / density).roundToInt(),
    )
}

/**
 * Main content retains inset ownership (and, for chat, the only IME owner). Navigation owns
 * its own safe drawing insets. Dividers are overlays inside each column, not extra columns.
 */
@Composable
internal fun WorkspaceScaffold(
    layout: WorkspaceLayoutSpec,
    chromeVisible: Boolean,
    railContent: @Composable (openNavigation: () -> Unit) -> Unit,
    navigationContent: @Composable (closeNavigation: () -> Unit) -> Unit,
    content: @Composable (toggleNavigation: () -> Unit) -> Unit,
) {
    var drawerRequested by remember { mutableStateOf(false) }
    val closeNavigation: () -> Unit = { drawerRequested = false }
    val toggleNavigation: () -> Unit = { drawerRequested = !drawerRequested }
    val drawerState = rememberDrawerState(
        initialValue = DrawerValue.Closed,
        confirmStateChange = { value ->
            if (value == DrawerValue.Closed) drawerRequested = false
            true
        },
    )
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val inputMode = LocalInputModeManager.current
    val navigationFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    var openedBefore by remember { mutableStateOf(false) }
    LaunchedEffect(drawerRequested, drawerState) {
        if (drawerRequested) {
            openedBefore = true
            focusManager.clearFocus(force = true)
            keyboard?.hide()
            drawerState.open()
            if (inputMode.inputMode == InputMode.Keyboard) navigationFocus.requestFocus()
        } else {
            drawerState.close()
            if (openedBefore) {
                if (inputMode.inputMode == InputMode.Keyboard) contentFocus.requestFocus()
                else { focusManager.clearFocus(force = true); keyboard?.hide() }
                openedBefore = false
            }
        }
    }
    // A pane change re-measures the drawer anchors; keep the drawer closed unless someone asked.
    LaunchedEffect(drawerState.isOpen, drawerRequested) {
        if (!drawerRequested && drawerState.isOpen) drawerState.close()
    }
    val columns: @Composable () -> Unit = {
        Row(Modifier.fillMaxSize()) {
            if (chromeVisible && layout.leftPane != WorkspaceLeftPane.NONE) Surface(
                modifier = Modifier.width(layout.navigationWidth).fillMaxHeight().testTag(
                    if (layout.leftPane == WorkspaceLeftPane.RAIL) "workspace-rail" else "workspace-navigation",
                ),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Box {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        if (layout.leftPane == WorkspaceLeftPane.RAIL) railContent(toggleNavigation)
                        else navigationContent(closeNavigation)
                    }
                    ColumnDivider(Modifier.align(Alignment.CenterEnd))
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight().testTag("workspace-content")
                .focusRequester(contentFocus).focusRestorer().focusGroup()) { content(toggleNavigation) }
        }
    }
    if (!chromeVisible || layout.leftPane == WorkspaceLeftPane.SIDEBAR) {
        columns()
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val drawerWidth = minOf(TokenWorksMetrics.drawerMaxWidth, (maxWidth - TokenWorksSpacing.xxl).coerceAtLeast(0.dp))
        ModalNavigationDrawer(
            drawerState = drawerState,
            // Never steal horizontal code/table gestures to open a closed drawer.
            gesturesEnabled = drawerRequested || drawerState.isOpen,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.width(drawerWidth).fillMaxHeight(),
                    drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    windowInsets = WindowInsets.safeDrawing,
                ) {
                    if (drawerRequested || drawerState.isOpen) Box(Modifier.fillMaxSize().testTag("workspace-drawer")
                        .focusRequester(navigationFocus).focusProperties { onExit = { cancelFocusChange() } }.focusGroup()) {
                        navigationContent(closeNavigation)
                    }
                }
            },
        ) {
            Box(Modifier.fillMaxSize().then(if (drawerRequested || drawerState.isOpen) blockedBackground() else Modifier)) { columns() }
        }
    }
    key(drawerRequested) {
        BackHandler(enabled = drawerRequested || drawerState.isOpen, onBack = closeNavigation)
    }
}

internal enum class TaskPresentation { PAGE, SHEET, SIDE_PANEL }

/**
 * Hosts one foreground content instance. Callers provide content without Scaffold/system-bar
 * padding. PAGE/SIDE_PANEL own safeDrawing (including IME); SHEET delegates IME handling to
 * Material3 and supplies system/cutout insets to its content. The backdrop has no business effects.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AdaptiveSessionPanel(
    layout: WorkspaceLayoutSpec,
    presentation: TaskPresentation,
    onDismiss: () -> Unit,
    backdrop: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    // Child form/internal-page BackHandlers are registered later and keep their precedence.
    if (presentation != TaskPresentation.SHEET) BackHandler(onBack = onDismiss)
    when (presentation) {
        TaskPresentation.PAGE -> Surface(
            modifier = Modifier.fillMaxSize().testTag("workspace-panel-page"),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) { content() }
        }
        TaskPresentation.SHEET -> BoxWithConstraints(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().then(blockedBackground())) { backdrop() }
            val safeInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
            val density = LocalDensity.current
            val availableHeight = (maxHeight - with(density) {
                (safeInsets.getTop(this) + safeInsets.getBottom(this)).toDp()
            }).coerceAtLeast(0.dp)
            var dismissSheet by remember { mutableStateOf<() -> Unit>({ latestOnDismiss() }) }
            ModalBottomSheet(
                onDismissRequest = { dismissSheet() },
                modifier = Modifier.heightIn(max = availableHeight * 0.9f).testTag("workspace-panel-sheet"),
                sheetState = rememberModalBottomSheetState(
                    skipPartiallyExpanded = true,
                    // Let the caller handle IME/internal-page back before removing the route.
                    // A veto here also prevents the sheet hiding underneath a still-open route.
                    confirmValueChange = { target ->
                        if (target == SheetValue.Hidden) {
                            dismissSheet()
                            false
                        } else true
                    },
                ),
                sheetMaxWidth = TokenWorksSpacing.readingWidth,
                containerColor = MaterialTheme.colorScheme.surface,
                contentWindowInsets = { safeInsets },
            ) {
                val sheetKeyboard = LocalSoftwareKeyboardController.current
                val sheetImeVisible = WindowInsets.isImeVisible
                SideEffect { dismissSheet = { if (sheetImeVisible) sheetKeyboard?.hide() else latestOnDismiss() } }
                Box(Modifier.fillMaxWidth()) { content() }
            }
        }
        TaskPresentation.SIDE_PANEL -> Row(Modifier.fillMaxSize()) {
            val closeLabel = stringResource(R.string.action_close)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.fillMaxSize().then(blockedBackground())) { backdrop() }
                Box(
                    Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                        .clickable(onClickLabel = closeLabel, onClick = onDismiss)
                        .semantics { contentDescription = closeLabel },
                )
            }
            Surface(
                modifier = Modifier.width(
                    layout.panelWidth.takeIf { it > 0.dp } ?: TokenWorksMetrics.panelIdealMin,
                ).fillMaxHeight().testTag("workspace-panel-side"),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Box {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) { content() }
                    ColumnDivider(Modifier.align(Alignment.CenterStart))
                }
            }
        }
    }
}

@Composable
private fun ColumnDivider(modifier: Modifier) {
    Box(modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
}

/** Blocks the whole subtree, not merely the visible message actions. */
private fun blockedBackground(): Modifier = Modifier
    .clearAndSetSemantics {}
    .focusProperties { canFocus = false }
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            }
        }
    }

