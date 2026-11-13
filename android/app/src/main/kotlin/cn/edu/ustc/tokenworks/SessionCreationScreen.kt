package cn.edu.ustc.tokenworks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource

@Composable
internal fun SessionCreationScreen(state: HomeState, viewModel: HomeViewModel) {
    val creation = state.creation ?: return
    val creating = creation.phase == SessionCreationPhase.CREATING
    var workspace by rememberSaveable(state.subject, state.selectedRuntimeId) { mutableStateOf("") }
    var preset by rememberSaveable(state.subject, state.selectedRuntimeId) { mutableStateOf("") }
    val runtime = state.runtimes.firstOrNull { it.id == state.selectedRuntimeId }

    BackHandler { if (!creating) viewModel.closeCreation() }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TokenWorksTopBar(
                title = stringResource(R.string.flow_creation_title),
                subtitle = runtime?.hostAlias ?: stringResource(R.string.chat_authorized_computer),
                contentMaxWidth = TokenWorksSpacing.readingWidth,
                navigationIcon = {
                    IconButton(
                        onClick = viewModel::closeCreation,
                        enabled = !creating,
                        modifier = Modifier.size(TokenWorksSpacing.minimumTouchTarget),
                    ) {
                        Icon(painterResource(R.drawable.ic_back), stringResource(R.string.flow_creation_back))
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(TokenWorksSpacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm),
                ) {
                    Button(
                        onClick = {
                            viewModel.createSession(
                                workspace.trim().takeIf { it.isNotEmpty() },
                                preset.trim().takeIf { it.isNotEmpty() },
                            )
                        },
                        enabled = !creating,
                        modifier = Modifier.widthIn(max = TokenWorksSpacing.readingWidth).fillMaxWidth()
                            .heightIn(min = TokenWorksSpacing.minimumTouchTarget),
                        shape = RoundedCornerShape(TokenWorksRadius.xl),
                    ) {
                        Text(stringResource(if (creating) R.string.creation_creating else R.string.flow_creation_title))
                    }
                    TextButton(
                        onClick = viewModel::closeCreation,
                        enabled = !creating,
                        modifier = Modifier.widthIn(max = TokenWorksSpacing.readingWidth).fillMaxWidth()
                            .heightIn(min = TokenWorksSpacing.minimumTouchTarget),
                    ) { Text(stringResource(R.string.action_cancel)) }
                }
            }
        },
    ) { insets ->
        Box(
            Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = TokenWorksSpacing.readingWidth).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(TokenWorksSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.lg),
            ) {
                if (creating) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (creation.phase == SessionCreationPhase.FAILED) {
                    creation.error?.let { TokenWorksStatusNotice(it, isError = true) }
                }
                Surface(
                    shape = tokenWorksSquircle(TokenWorksRadius.lg),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(TokenWorksSpacing.lg),
                        verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.lg),
                    ) {
                        OutlinedTextField(
                            value = workspace,
                            onValueChange = { workspace = it },
                            label = { Text(stringResource(R.string.creation_workspace_directory)) },
                            supportingText = { Text(stringResource(R.string.creation_default_help)) },
                            enabled = !creating,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = preset,
                            onValueChange = { preset = it },
                            label = { Text(stringResource(R.string.creation_agent_preset)) },
                            supportingText = { Text(stringResource(R.string.creation_default_help)) },
                            enabled = !creating,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
