package cn.edu.ustc.tokenworks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.FileNotFoundException

/** Hosts production surfaces only; fixture callbacks never implement navigation or session reducers. */
@Composable
internal fun SyntheticSessionScreen(
    state: HomeState,
    onBack: () -> Unit,
    onOlder: () -> Unit,
    onSubmit: (MessageComposerState) -> Unit = {},
    onCancelSession: () -> Unit = {},
    readingPosition: ChatReadingPosition? = null,
    onComposerChange: (MessageComposerState) -> Unit = {},
    onModels: () -> Unit = {},
    viewport: WorkspaceViewport? = null,
    onCreateSession: () -> Unit = {},
) {
    val context = LocalContext.current
    val controller = remember {
        LoginController(context, LoginHttp.create(), LoginEnvironment(configuration = { throw FileNotFoundException() }))
    }
    DisposableEffect(controller) { onDispose { controller.close() } }
    val model = remember(controller) { HomeViewModel(controller) }
    val position = readingPosition ?: remember { ChatReadingPosition() }
    var expanded by remember { mutableStateOf(true) }
    val actions = SessionContentActions(
        editDraft = { value -> state.composer?.let { onComposerChange(it.copy(value = value)) } },
        send = { state.composer?.let(onSubmit) }, cancel = onCancelSession,
        openModels = onModels, openCreation = onCreateSession, loadOlder = onOlder,
    )
    BackHandler(onBack = onBack)
    Box(if (viewport == null) Modifier.fillMaxSize() else Modifier.requiredSize(viewport.widthDp.dp, viewport.heightDp.dp)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val measured = viewport ?: WorkspaceViewport(maxWidth.value.toInt(), maxHeight.value.toInt())
            val layout = workspaceLayout(measured, state.selectedSessionId != null, false, expanded)
            WorkspaceScaffold(layout, chromeVisible = true,
                railContent = { open -> WorkspaceRail(open, {}, {}) },
                navigationContent = { close ->
                    TokenWorksAppShell(state, model, {}, {}, {
                        if (layout.leftPane == WorkspaceLeftPane.SIDEBAR) expanded = false else close()
                    })
                },
            ) { toggle ->
                NativeSessionContent(state, position, actions,
                    onNavigation = { if (layout.leftPane == WorkspaceLeftPane.SIDEBAR) expanded = false else toggle() },
                    onHosts = {})
            }
        }
    }
}
