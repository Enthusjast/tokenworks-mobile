package cn.edu.ustc.tokenworks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.min

/**
 * A rounded rectangle whose corners leave the straight edge earlier and arrive flatter than a
 * circular arc, which reads as continuous curvature at large radii.
 *
 * Only valid as a **fill** shape: the path is emitted as [Outline.Generic], and generic outlines do
 * not support elevation shadows, so elevated surfaces keep [androidx.compose.foundation.shape.RoundedCornerShape].
 */
@Composable
fun tokenWorksSquircle(radius: Dp, smoothing: Float = 0.6f): Shape {
    val radiusPx = with(LocalDensity.current) { radius.toPx() }
    val smoothingValue = smoothing.coerceIn(0f, 1f)
    return remember(radiusPx, smoothingValue) { SquircleShape(radiusPx, smoothingValue) }
}

private class SquircleShape(
    private val radiusPx: Float,
    private val smoothing: Float,
) : Shape {

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        if (size.isEmpty()) return Outline.Rectangle(size.toRect())
        // Extend every corner along both straight edges by `radius * (1 + smoothing)`.
        val halfMin = min(size.width, size.height) / 2f
        val radius = min(radiusPx, halfMin / (1f + smoothing))
        val extent = radius * (1f + smoothing)
        if (extent <= 0f) return Outline.Rectangle(size.toRect())
        // 0.5523 is the cubic offset that reproduces a quarter circle at smoothing = 0.
        val control = extent * 0.5523f
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(extent, 0f)
            lineTo(w - extent, 0f)
            cubicTo(w - extent + control, 0f, w, extent - control, w, extent)
            lineTo(w, h - extent)
            cubicTo(w, h - extent + control, w - extent + control, h, w - extent, h)
            lineTo(extent, h)
            cubicTo(extent - control, h, 0f, h - extent + control, 0f, h - extent)
            lineTo(0f, extent)
            cubicTo(0f, extent - control, extent - control, 0f, extent, 0f)
            close()
        }
        return Outline.Generic(path)
    }

    override fun equals(other: Any?): Boolean =
        other is SquircleShape && other.radiusPx == radiusPx && other.smoothing == smoothing

    override fun hashCode(): Int = 31 * radiusPx.hashCode() + smoothing.hashCode()
}

private fun Size.toRect() = androidx.compose.ui.geometry.Rect(0f, 0f, width, height)
