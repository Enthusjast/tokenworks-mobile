package cn.edu.ustc.tokenworks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import android.provider.Settings
import androidx.compose.ui.text.style.TextAlign
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
    tone: TokenWorksNoticeTone = if (isError) TokenWorksNoticeTone.ERROR else TokenWorksNoticeTone.NEUTRAL,
) {
    val surfaces = tokenWorksSurfaces()
    val container = when (tone) {
        TokenWorksNoticeTone.ERROR -> MaterialTheme.colorScheme.errorContainer
        TokenWorksNoticeTone.SUCCESS -> surfaces.successContainer
        TokenWorksNoticeTone.WARNING -> surfaces.warningContainer
        TokenWorksNoticeTone.INFO -> surfaces.infoContainer
        TokenWorksNoticeTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val content = when (tone) {
        TokenWorksNoticeTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        TokenWorksNoticeTone.SUCCESS -> surfaces.onSuccessContainer
        TokenWorksNoticeTone.WARNING -> surfaces.onWarningContainer
        TokenWorksNoticeTone.INFO -> surfaces.onInfoContainer
        TokenWorksNoticeTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                if (tone == TokenWorksNoticeTone.ERROR) error(text)
            },
        shape = tokenWorksSquircle(TokenWorksRadius.md),
        color = container,
        contentColor = content,
    ) {
        Row(
            Modifier.padding(horizontal = TokenWorksSpacing.md, vertical = TokenWorksSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm),
        ) {
            if (tone != TokenWorksNoticeTone.NEUTRAL) {
                Box(
                    Modifier.size(8.dp).background(
                        when (tone) {
                            TokenWorksNoticeTone.ERROR -> MaterialTheme.colorScheme.error
                            TokenWorksNoticeTone.SUCCESS -> surfaces.success
                            TokenWorksNoticeTone.WARNING -> surfaces.warning
                            TokenWorksNoticeTone.INFO -> surfaces.info
                            TokenWorksNoticeTone.NEUTRAL -> MaterialTheme.colorScheme.outline
                        },
                        CircleShape,
                    ),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Visual weight of a status notice; the text still carries the meaning. */
enum class TokenWorksNoticeTone { NEUTRAL, INFO, SUCCESS, WARNING, ERROR }

/**
 * One-shot entrance: content fades in and rises [TokenWorksMotion.enterOffsetDp].
 *
 * Respects the system animator scale (disabled animations render immediately) and runs once per
 * [key]; re-keying replays it, so callers key on the content identity they want to announce.
 */
@Composable
fun Modifier.tokenWorksEntrance(key: Any? = Unit): Modifier {
    val view = LocalView.current
    val animate = remember(view) {
        Settings.Global.getFloat(view.context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    if (!animate) return this
    val appeared = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        appeared.animateTo(1f, tween(TokenWorksMotion.fadeIn, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)))
    }
    val offset = with(LocalDensity.current) { TokenWorksMotion.enterOffsetDp.dp.toPx() }
    return graphicsLayer {
        alpha = appeared.value
        translationY = (1f - appeared.value) * offset
    }
}

/** A centered empty-state block: one calm explanation, optional follow-up action. */
@Composable
fun TokenWorksEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(TokenWorksSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = tokenWorksSurfaces().textTertiary,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            FilledTonalButton(
                onAction,
                Modifier.padding(top = TokenWorksSpacing.xs).heightIn(min = TokenWorksSpacing.minimumTouchTarget),
            ) { Text(actionLabel) }
        }
    }
}
