package cn.edu.ustc.tokenworks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.enthusjast.tokenworks.ui.TokenWorksSpacing

/** Only caller-authorized display values and actions; no directory, model or connection owner. */
@Composable
internal fun SessionHeader(
    title: String,
    connectionLabel: String,
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
        Column(Modifier.fillMaxWidth().padding(horizontal = TokenWorksSpacing.extraSmall,
            vertical = TokenWorksSpacing.extraSmall), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.extraSmall)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.extraSmall)) {
                HeaderIcon(R.drawable.ic_menu, stringResource(R.string.workspace_open_navigation), onNavigation)
                Surface(onClick = onHosts, modifier = Modifier.weight(1f).heightIn(min = TokenWorksSpacing.minimumTouchTarget)
                    .semantics { contentDescription = hostDescription },
                    shape = RoundedCornerShape(TokenWorksSpacing.pillRadius), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(horizontal = TokenWorksSpacing.medium, vertical = TokenWorksSpacing.small)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (!twoRows) Text(connectionLabel, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!twoRows) HeaderModels(modelsEnabled, onModels)
                HeaderIcon(R.drawable.ic_add, stringResource(R.string.action_create_session), onCreate, createEnabled)
                HeaderIcon(R.drawable.ic_more, stringResource(R.string.chat_task_more), onMore)
            }
            if (twoRows) Row(Modifier.fillMaxWidth().padding(horizontal = TokenWorksSpacing.small),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.small)) {
                Surface(onClick = onHosts, modifier = Modifier.weight(1f).heightIn(min = TokenWorksSpacing.minimumTouchTarget)
                    .semantics { contentDescription = hostDescription },
                    shape = RoundedCornerShape(TokenWorksSpacing.pillRadius), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Text(connectionLabel, Modifier.padding(horizontal = TokenWorksSpacing.medium, vertical = TokenWorksSpacing.small),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HeaderModels(modelsEnabled, onModels)
            }
        }
    }
}

@Composable
private fun HeaderModels(enabled: Boolean, onModels: () -> Unit) {
    Surface(onClick = onModels, enabled = enabled,
        modifier = Modifier.sizeIn(minWidth = TokenWorksSpacing.minimumTouchTarget, minHeight = TokenWorksSpacing.minimumTouchTarget),
        shape = RoundedCornerShape(TokenWorksSpacing.pillRadius), color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) {
        Row(Modifier.padding(horizontal = TokenWorksSpacing.medium, vertical = TokenWorksSpacing.small),
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.workspace_model), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun HeaderIcon(icon: Int, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick, enabled = enabled, modifier = Modifier.size(TokenWorksSpacing.minimumTouchTarget)) {
        Icon(painterResource(icon), label, Modifier.size(TokenWorksSpacing.iconSize))
    }
}
