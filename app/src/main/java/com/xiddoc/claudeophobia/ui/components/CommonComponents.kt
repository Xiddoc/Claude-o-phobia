package com.xiddoc.claudeophobia.ui.components

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xiddoc.claudeophobia.ui.theme.ClaudeClay
import com.xiddoc.claudeophobia.ui.theme.ClaudeClayBright
import com.xiddoc.claudeophobia.ui.theme.ClaudeGlow
import com.xiddoc.claudeophobia.ui.theme.OnSurfaceMuted
import com.xiddoc.claudeophobia.ui.theme.TrackColor

/**
 * Rounded surface card with a header, optional inline action, and content.
 *
 * When [actionBusy] is true the inline action is replaced by a small spinner, so
 * tapping Retry/Refresh is always acknowledged even if the result is unchanged.
 */
@Composable
fun InfoCard(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionBusy: Boolean = false,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = OnSurfaceMuted,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (actionBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(20.dp),
                        strokeWidth = 2.dp,
                        color = ClaudeClay,
                    )
                } else if (actionLabel != null && onAction != null) {
                    TextButton(onClick = onAction) {
                        Text(actionLabel, color = ClaudeClay)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/**
 * A rounded, animated horizontal progress bar (0f..1f).
 *
 * When [glow] is on, the filled portion radiates a bright, warm LED-like halo:
 * soft blurred light that bleeds outward beyond the bar, with a hot near-white
 * core, so the bar reads as genuinely lit from within rather than casting a
 * flat drop shadow.
 */
@Composable
fun LinearMeter(
    progress: Float,
    modifier: Modifier = Modifier,
    glow: Boolean = true,
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        label = "meter",
    )
    val barShape = RoundedCornerShape(5.dp)
    // Outer Box is intentionally NOT clipped so the glow can bleed past the bar.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp),
    ) {
        // Bloom layer, drawn behind everything and sized to the filled portion so
        // the light tracks the leading edge.
        if (glow && animated > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animated)
                    .height(10.dp)
                    .drawBehind { drawBarGlow(cornerRadius = 5.dp.toPx()) },
            )
        }
        // Track + fill, clipped to the rounded bar shape.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(barShape)
                .background(TrackColor),
        ) {
            if (animated > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animated)
                        .height(10.dp)
                        .clip(barShape)
                        .background(
                            Brush.horizontalGradient(listOf(ClaudeClay, ClaudeClayBright)),
                        ),
                )
            }
        }
    }
}

/**
 * Paints a warm, blurred bloom around the current draw bounds: a wide soft clay
 * halo, a tighter brighter ring, and a hot near-white core, layered so the light
 * falls off smoothly outward like an LED behind frosted glass.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBarGlow(cornerRadius: Float) {
    val w = size.width
    val h = size.height
    drawIntoCanvas { canvas ->
        val paint = Paint().asFrameworkPaint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.FILL
        }
        // (blur radius in px, fill colour) from widest/softest to hottest core.
        val layers = listOf(
            18.dp.toPx() to ClaudeClay.copy(alpha = 0.45f),
            10.dp.toPx() to ClaudeClayBright.copy(alpha = 0.55f),
            5.dp.toPx() to ClaudeGlow.copy(alpha = 0.75f),
        )
        layers.forEach { (radius, color) ->
            paint.color = color.toArgb()
            paint.maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
            canvas.nativeCanvas.drawRoundRect(0f, 0f, w, h, cornerRadius, cornerRadius, paint)
        }
        paint.maskFilter = null
    }
}

/** Label on the left, emphasised value on the right. */
@Composable
fun StatRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceMuted,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
