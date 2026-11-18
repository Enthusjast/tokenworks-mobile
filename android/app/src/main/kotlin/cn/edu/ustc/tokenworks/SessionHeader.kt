package cn.edu.ustc.tokenworks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Connection health has a marker shape and an independently readable label. */
internal enum class HeaderConnection { LIVE, PENDING, DEGRADED, ERROR }

@Composable
internal fun stableHeaderPhase(sessionId: String, current: SessionReadPhase): SessionReadPhase {
    var visible by remember(sessionId) { mutableStateOf(current) }
    val transient = current == SessionReadPhase.CONNECTING || current == SessionReadPhase.BACKOFF || current == SessionReadPhase.SYNCING
    LaunchedEffect(sessionId, current) {
        if (transient && visible == SessionReadPhase.READY) delay(TokenWorksMotion.standard.toLong())
        visible = current
    }
    // A flicker filter must not conceal authority loss or terminal errors.
    return if (transient) visible else current
}

@OptIn(ExperimentalLayoutApi::class)
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
    workspaceName: String? = null,
    usageLabel: String? = null,
    costLabel: String? = null,
    pendingCount: Int = 0,
    modelName: String? = null,
    approvalCount: Int = 0,
    questionCount: Int = 0,
) {
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val hostDescription = stringResource(R.string.workspace_host_connection_details)
    val modelLabel = modelName ?: stringResource(R.string.workspace_model)
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
                if (!twoRows) HeaderModels(modelLabel, modelsEnabled, onModels, Modifier.widthIn(max = 144.dp))
                HeaderIcon(R.drawable.ic_add, stringResource(R.string.action_create_session), onCreate, createEnabled)
                HeaderIcon(R.drawable.ic_more, stringResource(R.string.chat_task_more), onMore)
            }
            if (twoRows) Row(
                Modifier.fillMaxWidth().padding(horizontal = TokenWorksSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm),
            ) {
                ConnectionPill(connection, connectionLabel, Modifier.weight(1f))
                HeaderModels(modelLabel, modelsEnabled, onModels, Modifier.widthIn(max = 180.dp))
            }
            if (!workspaceName.isNullOrBlank() || usageLabel != null || costLabel != null || pendingCount > 0 || approvalCount > 0 || questionCount > 0) {
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = TokenWorksSpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xs)) {
                    val labels = listOfNotNull(
                        workspaceName?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.chat_audit_workspace, it) },
                        usageLabel, costLabel,
                        if (pendingCount > 0) pluralStringResource(R.plurals.chat_audit_pending, pendingCount, pendingCount) else null,
                    )
                    labels.forEach { label -> Text(label, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    if (approvalCount > 0) PendingMarker(pluralStringResource(R.plurals.header_pending_approvals, approvalCount, approvalCount))
                    if (questionCount > 0) PendingMarker(pluralStringResource(R.plurals.header_pending_questions, questionCount, questionCount))
                }
            }
        }
    }
}

@Composable
private fun PendingMarker(label: String) {
    Surface(shape = tokenWorksSquircle(TokenWorksRadius.sm), color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer) {
        Text(label, Modifier.padding(horizontal = TokenWorksSpacing.sm, vertical = TokenWorksSpacing.xs),
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ConnectionPill(connection: HeaderConnection, label: String, modifier: Modifier = Modifier) {
    val surfaces = tokenWorksSurfaces()
    val markerColor = when (connection) {
        HeaderConnection.LIVE -> surfaces.success
        HeaderConnection.PENDING -> MaterialTheme.colorScheme.primary
        HeaderConnection.DEGRADED -> surfaces.warning
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
            Canvas(Modifier.size(12.dp)) {
                val stroke = 1.5.dp.toPx()
                val w = size.width
                val h = size.height
                when (connection) {
                    HeaderConnection.LIVE -> {
                        val knee = Offset(w * .42f, h * .75f)
                        drawLine(markerColor, Offset(w * .15f, h * .5f), knee, stroke, StrokeCap.Round)
                        drawLine(markerColor, knee, Offset(w * .86f, h * .24f), stroke, StrokeCap.Round)
                    }
                    HeaderConnection.PENDING -> drawArc(markerColor, -90f, 265f, false, style = Stroke(stroke))
                    HeaderConnection.DEGRADED -> drawLine(markerColor, Offset(w * .18f, h * .5f), Offset(w * .82f, h * .5f), stroke, StrokeCap.Round)
                    HeaderConnection.ERROR -> {
                        drawLine(markerColor, Offset(w * .2f, h * .2f), Offset(w * .8f, h * .8f), stroke, StrokeCap.Round)
                        drawLine(markerColor, Offset(w * .8f, h * .2f), Offset(w * .2f, h * .8f), stroke, StrokeCap.Round)
                    }
                }
            }
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HeaderModels(label: String, enabled: Boolean, onModels: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onModels,
        enabled = enabled,
        modifier = modifier.sizeIn(minWidth = TokenWorksMetrics.key, minHeight = TokenWorksMetrics.key),
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
            Text(label, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
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
