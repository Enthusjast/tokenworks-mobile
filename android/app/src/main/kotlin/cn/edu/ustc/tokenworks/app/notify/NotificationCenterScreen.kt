package cn.edu.ustc.tokenworks

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

@Composable
fun NotificationCenterScreen(state: NotificationCenterState, onClose: () -> Unit, onOpen: (String) -> Unit) {
    Scaffold(topBar = {
        TokenWorksTopBar(title = stringResource(R.string.notification_center_title), contentMaxWidth = TokenWorksSpacing.readingWidth,
            navigationIcon = {
                IconButton(onClose, Modifier.sizeIn(minWidth = TokenWorksSpacing.minimumTouchTarget,
                    minHeight = TokenWorksSpacing.minimumTouchTarget)) {
                    Icon(painterResource(R.drawable.ic_back), stringResource(R.string.notification_center_back))
                }
            })
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = TokenWorksSpacing.readingWidth).fillMaxWidth().testTag("notification-center"),
                contentPadding = PaddingValues(TokenWorksSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md)) {
                if (state.unavailable) item {
                    TokenWorksStatusNotice(stringResource(R.string.notification_center_unavailable),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, tone = TokenWorksNoticeTone.ERROR)
                }
                if (state.entries.isEmpty()) item {
                    TokenWorksEmptyState(stringResource(R.string.notification_center_empty),
                        detail = stringResource(R.string.notification_center_empty_detail))
                } else {
                    item { Text(stringResource(R.string.notification_center_disclaimer), style = MaterialTheme.typography.bodyMedium) }
                    items(state.entries, key = { it.id }) { entry ->
                        val label = stringResource(when (entry.kind) {
                            NotificationCenterState.Kind.INTERACTION -> R.string.notification_center_interaction
                            NotificationCenterState.Kind.COMPLETED -> R.string.notification_center_completed
                        })
                        Surface(onClick = { onOpen(entry.id) }, enabled = state.openingId == null,
                            modifier = Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget),
                            shape = tokenWorksSquircle(TokenWorksRadius.lg), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.padding(TokenWorksSpacing.md), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xs)) {
                                Text(label, style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(if (entry.id == state.openingId) R.string.notification_center_checking
                                    else R.string.notification_center_open), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            }
                        }
                    }
                }
            }
        }
    }
}
