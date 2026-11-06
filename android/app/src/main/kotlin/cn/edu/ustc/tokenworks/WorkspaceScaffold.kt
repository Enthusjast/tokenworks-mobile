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
import io.github.enthusjast.tokenworks.ui.TokenWorksSpacing
import kotlin.math.roundToInt

internal data class WorkspaceViewport(val widthDp: Int, val heightDp: Int)

internal enum class WorkspaceSize { COMPACT, MEDIUM, EXPANDED }

internal data class WorkspaceLayoutSpec(
    val size: WorkspaceSize,
    val navigationWidth: Dp,
    val inspectorWidth: Dp,
)

internal fun workspaceLayout(
    viewport: WorkspaceViewport,
    inspectorRequested: Boolean,
    navigationExpanded: Boolean,
): WorkspaceLayoutSpec {
    if (viewport.widthDp <= 0 || viewport.heightDp <= 0 || viewport.heightDp < 480 || viewport.widthDp < 600) {
        return WorkspaceLayoutSpec(WorkspaceSize.COMPACT, 0.dp, 0.dp)
    }
    if (viewport.widthDp < 840) return WorkspaceLayoutSpec(WorkspaceSize.MEDIUM, 0.dp, 0.dp)
    val compactNavigation = !navigationExpanded || (inspectorRequested && viewport.widthDp < 1120)
    return WorkspaceLayoutSpec(
        size = WorkspaceSize.EXPANDED,
        navigationWidth = if (compactNavigation) 72.dp else TokenWorksSpacing.navigationWidth,
        inspectorWidth = if (inspectorRequested) TokenWorksSpacing.inspectorWidth else 0.dp,
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
    navigationOpen: Boolean,
    onNavigationOpenChange: (Boolean) -> Unit,
    chromeVisible: Boolean,
    navigationContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!chromeVisible) {
        Box(Modifier.fillMaxSize().testTag("workspace-content")) { content() }
        return
    }
    if (layout.navigationWidth > 0.dp) {
        LaunchedEffect(navigationOpen) { if (navigationOpen) onNavigationOpenChange(false) }
        Row(Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.width(layout.navigationWidth).fillMaxHeight().testTag("workspace-navigation"),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Box {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) { navigationContent() }
                    ColumnDivider(Modifier.align(Alignment.CenterEnd))
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight().testTag("workspace-content")) { content() }
        }
        return
    }
    val latestOnOpenChange by rememberUpdatedState(onNavigationOpenChange)
    val latestNavigationOpen by rememberUpdatedState(navigationOpen)
    val drawerState = rememberDrawerState(
        initialValue = if (navigationOpen) DrawerValue.Open else DrawerValue.Closed,
        confirmStateChange = { value ->
            if (value == DrawerValue.Closed && latestNavigationOpen) latestOnOpenChange(false)
            true
        },
    )
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val inputMode = LocalInputModeManager.current
    val navigationFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    var openedBefore by remember { mutableStateOf(false) }
    LaunchedEffect(navigationOpen, drawerState) {
        if (navigationOpen) {
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val drawerWidth = minOf(360.dp, (maxWidth - TokenWorksSpacing.large).coerceAtLeast(0.dp))
        ModalNavigationDrawer(
            drawerState = drawerState,
            // Never steal horizontal code/table gestures to open a closed drawer.
            gesturesEnabled = navigationOpen,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.width(drawerWidth).fillMaxHeight(),
                    drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    windowInsets = WindowInsets.safeDrawing,
                ) {
                    // One LazyListState must never be mounted here and in the directory together.
                    if (navigationOpen) Box(Modifier.fillMaxSize().testTag("workspace-drawer")
                        .focusRequester(navigationFocus).focusProperties { onExit = { cancelFocusChange() } }.focusGroup()) { navigationContent() }
                }
            },
        ) {
            Box(
                Modifier.fillMaxSize().testTag("workspace-content")
                    .focusRequester(contentFocus).focusRestorer().focusGroup()
                    .then(if (navigationOpen) blockedBackground() else Modifier),
            ) { content() }
        }
    }
    // A child route can mount after this scaffold. Re-register when opened so it cannot win Back.
    key(navigationOpen) {
        BackHandler(enabled = navigationOpen) { onNavigationOpenChange(false) }
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
                sheetMaxWidth = TokenWorksSpacing.chatMaxWidth,
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
                    layout.inspectorWidth.takeIf { it > 0.dp } ?: TokenWorksSpacing.inspectorWidth,
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

