package com.xiddoc.claudeophobia.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiddoc.claudeophobia.data.RootResult
import com.xiddoc.claudeophobia.ui.MainViewModel
import com.xiddoc.claudeophobia.ui.components.InfoCard
import com.xiddoc.claudeophobia.ui.components.LinearMeter
import com.xiddoc.claudeophobia.ui.components.StatRow
import com.xiddoc.claudeophobia.ui.formatCountdown
import com.xiddoc.claudeophobia.ui.formatPercent
import com.xiddoc.claudeophobia.ui.formatRelative
import com.xiddoc.claudeophobia.ui.formatResetMoment
import com.xiddoc.claudeophobia.ui.components.ProgressRing
import com.xiddoc.claudeophobia.ui.theme.ClaudeClay
import com.xiddoc.claudeophobia.ui.theme.OnSurfaceMuted
import java.time.Duration
import java.time.Instant

@Composable
fun CountdownScreen(
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val rootResult by viewModel.rootResult.collectAsStateWithLifecycle()

    val progress = settings.resetConfig.progress(now)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header row.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Claude-o-phobia",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = OnSurfaceMuted,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Settings",
                        tint = OnSurfaceMuted,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // The ring shows how much of the week has elapsed; the center holds
            // the live countdown to the next reset.
            ProgressRing(
                progress = progress.fraction.toFloat(),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "RESETS IN",
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurfaceMuted,
                        letterSpacing = 2.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = formatCountdown(progress.remaining),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = formatPercent(progress.fraction) + " of week elapsed",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClaudeClay,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Next reset: " + formatResetMoment(progress.nextReset),
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceMuted,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(24.dp))

            // Expected pace card — always available, no root needed.
            InfoCard(title = "Where you should be") {
                Text(
                    text = "About " + formatPercent(progress.fraction) +
                        " of the week has passed, so a steady pace puts your weekly " +
                        "usage right around " + formatPercent(progress.fraction) + ".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(12.dp))
                LinearMeter(progress = progress.fraction.toFloat())
            }

            Spacer(Modifier.height(16.dp))

            // Live usage from root (if enabled & available).
            RootUsageSection(
                rootResult = rootResult,
                expectedFraction = progress.fraction,
                now = now,
                onRetry = viewModel::refreshRoot,
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun RootUsageSection(
    rootResult: RootResult,
    expectedFraction: Double,
    now: Instant,
    onRetry: () -> Unit,
) {
    when (rootResult) {
        RootResult.Disabled -> InfoCard(title = "Live usage (root)") {
            Text(
                text = "Turn on root usage reading in settings to see your actual " +
                    "weekly consumption and 5-hour window, pulled straight from the " +
                    "Claude app.",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceMuted,
            )
        }

        RootResult.Idle -> InfoCard(title = "Live usage (root)") {
            Text("Reading from the Claude app…", color = OnSurfaceMuted)
        }

        RootResult.NoRoot -> InfoCard(title = "Live usage (root)", onAction = onRetry, actionLabel = "Retry") {
            Text(
                "Couldn't get a root shell. Grant this app superuser access in your " +
                    "root manager, then retry.",
                color = OnSurfaceMuted,
            )
        }

        is RootResult.NotFound -> InfoCard(title = "Live usage (root)", onAction = onRetry, actionLabel = "Retry") {
            Text(rootResult.detail, color = OnSurfaceMuted)
        }

        is RootResult.Error -> InfoCard(title = "Live usage (root)", onAction = onRetry, actionLabel = "Retry") {
            Text(rootResult.message, color = MaterialTheme.colorScheme.error)
        }

        is RootResult.Found -> {
            val snap = rootResult.snapshot
            InfoCard(
                title = "Weekly usage (live)",
                onAction = onRetry,
                actionLabel = "Refresh",
            ) {
                val actual = snap.weeklyUtilizationPercent
                if (actual != null) {
                    StatRow(label = "Used this week", value = "${actual.toInt()}%")
                    Spacer(Modifier.height(8.dp))
                    LinearMeter(progress = (actual / 100.0).toFloat())
                    Spacer(Modifier.height(10.dp))
                    val expectedPct = (expectedFraction * 100).toInt()
                    val delta = actual.toInt() - expectedPct
                    val verdict = when {
                        delta > 5 -> "You're ${delta}% ahead of pace — easy does it. 🙂"
                        delta < -5 -> "You're ${-delta}% under pace — plenty of room. 🚀"
                        else -> "Right on pace. ✨"
                    }
                    Text(
                        text = "Expected ~$expectedPct%. $verdict",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceMuted,
                    )
                } else {
                    Text(
                        "Found Claude data, but no weekly percentage in it yet.",
                        color = OnSurfaceMuted,
                    )
                }
                snap.sourceLabel?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "source: $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurfaceMuted,
                    )
                }
            }

            // 5-hour rolling window.
            val end = snap.fiveHourResetEpochMs
            val fiveUtil = snap.fiveHourUtilizationPercent
            if (end != null || fiveUtil != null) {
                Spacer(Modifier.height(16.dp))
                InfoCard(title = "5-hour rolling window") {
                    if (end != null) {
                        val endInstant = Instant.ofEpochMilli(end)
                        val windowStart = endInstant.minus(Duration.ofHours(5))
                        val elapsed = Duration.between(windowStart, now).toMillis()
                            .coerceAtLeast(0)
                        val frac = (elapsed.toDouble() / Duration.ofHours(5).toMillis())
                            .coerceIn(0.0, 1.0)
                        StatRow(
                            label = "Window progress",
                            value = formatPercent(frac),
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearMeter(progress = frac.toFloat())
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Window ends " + formatRelative(endInstant, now),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceMuted,
                        )
                    }
                    if (fiveUtil != null) {
                        Spacer(Modifier.height(8.dp))
                        StatRow(label = "Used in window", value = "${fiveUtil.toInt()}%")
                    }
                }
            }
        }
    }
}
