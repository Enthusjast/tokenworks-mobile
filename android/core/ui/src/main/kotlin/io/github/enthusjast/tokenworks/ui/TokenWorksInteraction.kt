package io.github.enthusjast.tokenworks.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.launch

/**
 * Press feedback that lets the tapped surface settle slightly under the finger.
 *
 * It observes the initial pointer pass without consuming it, so taps, long presses and text
 * selection inside the element keep their own handling. When the system disables animations the
 * modifier does nothing at all.
 */
@Composable
fun Modifier.tokenWorksPress(enabled: Boolean = true): Modifier {
    if (!enabled) return this
    val view = LocalView.current
    val animate = remember(view) {
        Settings.Global.getFloat(view.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    if (!animate) return this
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    return this
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                var pressed = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val now = event.changes.any { it.pressed }
                    if (now == pressed) continue
                    pressed = now
                    val target = if (now) TokenWorksMotion.pressScale else 1f
                    scope.launch { scale.animateTo(target, tween(TokenWorksMotion.press)) }
                }
            }
        }
}
