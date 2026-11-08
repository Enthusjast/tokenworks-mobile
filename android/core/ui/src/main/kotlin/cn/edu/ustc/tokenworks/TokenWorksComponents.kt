package cn.edu.ustc.tokenworks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A status-bar-aware title row that grows with its text instead of clipping large fonts. */
@Composable
fun TokenWorksTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    contentMaxWidth: Dp = 800.dp,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BoxWithConstraints(Modifier.widthIn(max = contentMaxWidth).fillMaxWidth()) {
                val stackedActions = maxWidth < 360.dp || LocalDensity.current.fontScale >= 1.5f
                Column(Modifier.fillMaxWidth().padding(vertical = TokenWorksSpacing.xs)) {
                    Row(
                        Modifier.fillMaxWidth()
                            .heightIn(min = TokenWorksMetrics.chatBarHeight)
                            .padding(horizontal = TokenWorksSpacing.lg),
                        horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        navigationIcon()
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f)
                                .padding(vertical = TokenWorksSpacing.sm)
                                .semantics { heading() },
                        )
                        if (!stackedActions) actions()
                    }
                    if (!subtitle.isNullOrEmpty()) {
                        Text(
                            text = subtitle,
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = TokenWorksSpacing.lg)
                                .padding(bottom = TokenWorksSpacing.sm),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (stackedActions) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = TokenWorksSpacing.lg),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                            content = actions,
                        )
                    }
                }
            }
        }
    }
}

/** An inline status message; the supplied text must explain the status without color alone. */
@Composable
fun TokenWorksStatusNotice(
    text: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                if (isError) error(text)
            },
        shape = tokenWorksSquircle(TokenWorksRadius.md),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = TokenWorksSpacing.md, vertical = TokenWorksSpacing.sm),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
