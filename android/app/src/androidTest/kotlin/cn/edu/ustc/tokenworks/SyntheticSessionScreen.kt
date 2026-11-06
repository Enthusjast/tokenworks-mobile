package cn.edu.ustc.tokenworks

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.enthusjast.tokenworks.command.*
import io.github.enthusjast.tokenworks.protocol.ExportSource
import io.github.enthusjast.tokenworks.protocol.QuestionAnswers
import io.github.enthusjast.tokenworks.protocol.VisibleMediaReference
import io.github.enthusjast.tokenworks.network.*
import io.github.enthusjast.tokenworks.session.*

/** Public render fixtures only. Controller/TLS tests must render HomeRoute with their real owner. */
@Composable
internal fun SyntheticSessionScreen(
    state: HomeState, onBack: () -> Unit, onRefresh: () -> Unit, onOlder: () -> Unit, onRetry: () -> Unit,
    onSubmit: (MessageSubmission) -> Unit = {}, onSendPending: (String) -> Unit = {}, onQueryCommands: () -> Unit = {}, onCancelSession: (String) -> Unit = {},
    onAnswer: (QuestionHandle, QuestionAnswers) -> Unit = { _, _ -> }, onClaim: (QuestionHandle) -> Unit = {}, onRelease: (QuestionHandle) -> Unit = {}, onRefreshQuestions: () -> Unit = {},
    onApproval: (String) -> Unit = {}, onApprovalQuery: () -> Unit = {}, onArchive: (String, Boolean, Boolean) -> Unit = { _, _, _ -> },
    onPickFile: (String?) -> Unit = {}, onPickedFile: (String, Uri?) -> Unit = { _, _ -> }, onToggleFile: (String) -> Unit = {},
    onQueryFile: (String) -> Unit = {}, onRemoveFile: (String) -> Unit = {}, onCancelFile: () -> Unit = {},
    onBrowseExports: (ExportSource?, Boolean) -> Unit = { _, _ -> }, onExport: (ExportSource) -> Unit = {}, onQueryExports: () -> Unit = {},
    onCancelExport: (String) -> Unit = {}, onRemoveExport: (String) -> Unit = {}, onSaveExport: (String) -> Unit = {}, onExportDestination: (String, Uri?) -> Unit = { _, _ -> }, onCancelExportSave: () -> Unit = {},
    readingPosition: ChatReadingPosition? = null, onComposerChange: (MessageComposerState) -> Unit = {}, onModels: (() -> Unit)? = null,
    onChanges: (() -> Unit)? = null, onMessageAction: ((String) -> Unit)? = null,
    onPickImage: () -> Unit = {}, onPickCamera: () -> Unit = {}, onPreviewMedia: ((VisibleMediaReference) -> Unit)? = null,
    onDismissImagePreview: () -> Unit = {}, onCancelImage: () -> Unit = {}, onReselectImage: (String) -> Unit = {},
    onResolveFailedInput: (MessageComposerState, Boolean) -> Unit = { _, _ -> },
    viewport: WorkspaceViewport? = null, onCreateSession: () -> Unit = {},
) {
    val publicOwner = remember {
        CommandOwner("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222",
            "a".repeat(64), "33333333-3333-4333-8333-333333333333", "44444444-4444-4444-8444-444444444444")
    }
    val presented = if (state.presentationOwner == null) state.copy(presentationOwner = publicOwner) else state
    val navigation = remember(presented.presentationOwner, presented.selectedRuntimeId, presented.selectedSessionId) {
        HomeNavigation().apply { presented.selectedRuntimeId?.let(::selectRuntime) }
    }
    SideEffect { navigation.reconcileChatDetail(presented) }
    Box(if (viewport == null) Modifier.fillMaxSize() else Modifier.requiredSize(viewport.widthDp.dp, viewport.heightDp.dp)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val measured = viewport ?: WorkspaceViewport(maxWidth.value.toInt(), maxHeight.value.toInt())
            val layout = workspaceLayout(measured, navigation.chatDetail != null, navigation.navigationExpanded)
            val openNavigation = {
                if (layout.navigationWidth > 0.dp) navigation.navigationExpanded = !navigation.navigationExpanded
                else navigation.drawerOpen = true
            }
            TokenWorksAppShell(presented, navigation,
                onDestination = { navigation.closePresentationPanels(); navigation.destination = it },
                onRuntime = {}, onSession = { navigation.drawerOpen = false }, onRefresh = onRefresh,
                onRefreshSessions = onRefresh, onCreate = { onCreateSession() }, onBinding = {}, onNotifications = {},
                onAppearance = {}, onAppUpdates = {}, onExit = {}, onBackToComputers = {},
                layout = layout, chromeVisible = true, onOpenNavigation = openNavigation) {
                SessionsScreen(presented, onBack, onRefresh, onOlder, onRetry,
                    onSubmit, onSendPending, onQueryCommands, onCancelSession,
                    onAnswer, onClaim, onRelease, onRefreshQuestions, onApproval, onApprovalQuery, onArchive,
                    onPickFile, onPickedFile, onToggleFile, onQueryFile, onRemoveFile, onCancelFile,
                    onBrowseExports, onExport, onQueryExports, onCancelExport, onRemoveExport, onSaveExport, onExportDestination, onCancelExportSave,
                    readingPosition, onComposerChange, onModels, onChanges, onMessageAction,
                    onPickImage, onPickCamera, onPreviewMedia, onDismissImagePreview, onCancelImage, onReselectImage, onResolveFailedInput,
                    layout = layout, detailTarget = navigation.chatDetail,
                    onDetailsChange = { navigation.setChatDetail(presented, it) }, onOpenNavigation = openNavigation,
                    onOpenHosts = { navigation.hostSwitcherOpen = true }, onCreateSession = onCreateSession)
            }
        }
    }
}
