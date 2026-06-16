package com.xiddoc.claudeophobia.ui.components

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiddoc.claudeophobia.ui.theme.ClaudeClay
import com.xiddoc.claudeophobia.ui.theme.ClaudeClayBright
import com.xiddoc.claudeophobia.ui.theme.ClaudeGlow
import com.xiddoc.claudeophobia.ui.theme.TrackColor

/**
 * A circular gauge that sweeps from the top. [progress] is 0f..1f.
 * Arbitrary [center] content (text, etc.) is drawn in the middle.
 */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    diameter: Dp = 260.dp,
    strokeWidth: Dp = 16.dp,
    center: @Composable () -> Unit = {},
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        label = "ringProgress",
    )
    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(diameter)) {
            val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
            val inset = strokeWidth.toPx() / 2f
            val arcSize = androidx.compose.ui.geometry.Size(
                size.width - strokeWidth.toPx(),
                size.height - strokeWidth.toPx(),
            )
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            // Track.
            drawArc(
                color = TrackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
            // Warm LED-like bloom — blurred clay light radiating from the
            // progress arc, layered from a soft halo to a hot core so the ring
            // reads as genuinely lit rather than casting a flat wider arc.
            if (animated > 0f) {
                drawRingGlow(
                    left = topLeft.x,
                    top = topLeft.y,
                    right = topLeft.x + arcSize.width,
                    bottom = topLeft.y + arcSize.height,
                    sweepAngle = 360f * animated,
                    strokeWidthPx = strokeWidth.toPx(),
                )
            }
            // Progress.
            drawArc(
                brush = Brush.sweepGradient(listOf(ClaudeClay, ClaudeClayBright, ClaudeClay)),
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
        }
        center()
    }
}

/**
 * Paints a warm, blurred bloom along the progress arc: a wide soft clay halo, a
 * brighter ring, and a hot near-white core, layered so the light falls off
 * smoothly to either side of the stroke like an LED behind frosted glass. Mirrors
 * the bloom used by [LinearMeter] so both gauges glow consistently.
 */
private fun DrawScope.drawRingGlow(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    sweepAngle: Float,
    strokeWidthPx: Float,
) {
    drawIntoCanvas { canvas ->
        val paint = Paint().asFrameworkPaint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeWidth = strokeWidthPx
        }
        // (blur radius in px, stroke colour) from widest/softest to hottest core.
        val layers = listOf(
            18.dp.toPx() to ClaudeClay.copy(alpha = 0.45f),
            10.dp.toPx() to ClaudeClayBright.copy(alpha = 0.55f),
            5.dp.toPx() to ClaudeGlow.copy(alpha = 0.75f),
        )
        layers.forEach { (radius, color) ->
            paint.color = color.toArgb()
            paint.maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
            canvas.nativeCanvas.drawArc(
                left, top, right, bottom,
                -90f, sweepAngle, false, paint,
            )
        }
        paint.maskFilter = null
    }
}
