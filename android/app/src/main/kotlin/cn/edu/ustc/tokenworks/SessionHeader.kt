package cn.edu.ustc.tokenworks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Connection health, expressed as a dot plus the existing spelled-out label. */
internal enum class HeaderConnection { LIVE, PENDING, DEGRADED, ERROR }

/** Only caller-authorized display values and actions; no directory, model or connection owner. */
@Composable
internal fun SessionHeader(
    title: String,
    connectionLabel: String,
    connection: HeaderConnection,
    modelsEnabled: Boolean,
    createEnabled: Boolean,
    onNavigation: () -> Unit,
    onHosts: () -> Unit,
    onModels: () -> Unit,
    onCreate: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val hostDescription = stringResource(R.string.workspace_host_connection_details)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val twoRows = maxWidth < 360.dp || largeText
        Column(
            Modifier.fillMaxWidth().padding(horizontal = TokenWorksSpacing.xs, vertical = TokenWorksSpacing.xxs),
            verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xs),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = TokenWorksMetrics.chatBarHeight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xs),
            ) {
                HeaderIcon(R.drawable.ic_menu, stringResource(R.string.workspace_open_navigation), onNavigation)
                Surface(
                    onClick = onHosts,
                    modifier = Modifier.weight(1f).heightIn(min = TokenWorksMetrics.key)
                        .semantics { contentDescription = hostDescription },
                    shape = tokenWorksSquircle(TokenWorksRadius.xl),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(Modifier.padding(horizontal = TokenWorksSpacing.md, vertical = TokenWorksSpacing.xs)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, lineHeight = 19.sp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!twoRows) ConnectionPill(connection, connectionLabel)
                    }
                }
                if (!twoRows) HeaderModels(modelsEnabled, onModels)
                HeaderIcon(R.drawable.ic_add, stringResource(R.string.action_create_session), onCreate, createEnabled)
                HeaderIcon(R.drawable.ic_more, stringResource(R.string.chat_task_more), onMore)
            }
            if (twoRows) Row(
                Modifier.fillMaxWidth().padding(horizontal = TokenWorksSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm),
            ) {
                ConnectionPill(connection, connectionLabel, Modifier.weight(1f))
                HeaderModels(modelsEnabled, onModels)
            }
        }
    }
}

@Composable
private fun ConnectionPill(connection: HeaderConnection, label: String, modifier: Modifier = Modifier) {
    val tone = when (connection) {
        HeaderConnection.LIVE -> MaterialTheme.colorScheme.primary
        HeaderConnection.PENDING, HeaderConnection.DEGRADED -> MaterialTheme.colorScheme.tertiary
        HeaderConnection.ERROR -> MaterialTheme.colorScheme.error
    }
    Surface(
        modifier = modifier.heightIn(min = TokenWorksMetrics.key),
        shape = tokenWorksSquircle(TokenWorksRadius.xl),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.padding(horizontal = TokenWorksSpacing.md, vertical = TokenWorksSpacing.xxs)
                .heightIn(min = TokenWorksMetrics.key - TokenWorksSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm),
        ) {
            Box(Modifier.size(6.dp).background(tone, CircleShape))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HeaderModels(enabled: Boolean, onModels: () -> Unit) {
    Surface(
        onClick = onModels,
        enabled = enabled,
        modifier = Modifier.sizeIn(minWidth = TokenWorksMetrics.key, minHeight = TokenWorksMetrics.key),
        shape = tokenWorksSquircle(TokenWorksRadius.xl),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            Modifier.padding(horizontal = TokenWorksSpacing.md, vertical = TokenWorksSpacing.sm)
                .heightIn(min = TokenWorksMetrics.key - TokenWorksSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xs),
        ) {
            Text(stringResource(R.string.workspace_model), style = MaterialTheme.typography.titleSmall)
            Icon(painterResource(R.drawable.ic_chevron), contentDescription = null,
                modifier = Modifier.size(TokenWorksSpacing.iconSize).rotate(90f))
        }
    }
}

@Composable
private fun HeaderIcon(icon: Int, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick, enabled = enabled, modifier = Modifier.size(TokenWorksMetrics.key)) {
        Icon(painterResource(icon), label, Modifier.size(TokenWorksSpacing.iconSize))
    }
}
