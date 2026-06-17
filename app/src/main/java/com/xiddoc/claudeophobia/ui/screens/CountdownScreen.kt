package com.xiddoc.claudeophobia.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.xiddoc.claudeophobia.data.Pacing
import com.xiddoc.claudeophobia.data.UsageResult
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
    onOpenAbout: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val usageResult by viewModel.usageResult.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()

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
                IconButton(onClick = onOpenAbout) {
                    Icon(
                        imageVector = Icons.Outlined.FavoriteBorder,
                        contentDescription = "About",
                        tint = OnSurfaceMuted,
                    )
                }
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

            // Live usage captured by the LSPosed module (if enabled & available).
            LiveUsageSection(
                usageResult = usageResult,
                refreshing = refreshing,
                expectedFraction = progress.fraction,
                showFiveHourWindow = settings.fiveHourWindowEnabled,
                now = now,
                configuredNextReset = progress.nextReset.toInstant(),
                onRetry = viewModel::refreshUsage,
                onOpenClaude = viewModel::openClaude,
                onSync = viewModel::syncResetTo,
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun LiveUsageSection(
    usageResult: UsageResult,
    refreshing: Boolean,
    expectedFraction: Double,
    showFiveHourWindow: Boolean,
    now: Instant,
    configuredNextReset: Instant,
    onRetry: () -> Unit,
    onOpenClaude: () -> Unit,
    onSync: (Long) -> Unit,
) {
    when (usageResult) {
        UsageResult.Disabled -> InfoCard(title = "Live usage (LSPosed)") {
            Text(
                text = "Turn on live usage in settings to see your actual weekly " +
                    "consumption and 5-hour window, captured straight from the Claude " +
                    "app by the LSPosed module.",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceMuted,
            )
        }

        UsageResult.Idle -> InfoCard(title = "Live usage (LSPosed)") {
            Text("Reading your live usage…", color = OnSurfaceMuted)
        }

        is UsageResult.RebootRequired -> InfoCard(title = "Live usage (LSPosed)", onAction = onRetry, actionLabel = "Retry", actionBusy = refreshing) {
            Text(usageResult.detail, color = MaterialTheme.colorScheme.error)
        }

        is UsageResult.NotFound -> InfoCard(title = "Live usage (LSPosed)", onAction = onRetry, actionLabel = "Retry", actionBusy = refreshing) {
            Text(usageResult.detail, color = OnSurfaceMuted)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenClaude, contentPadding = PaddingValues(0.dp)) {
                Text("Open Claude", color = ClaudeClay)
            }
        }

        is UsageResult.Error -> InfoCard(title = "Live usage (LSPosed)", onAction = onRetry, actionLabel = "Retry", actionBusy = refreshing) {
            Text(usageResult.message, color = MaterialTheme.colorScheme.error)
        }

        is UsageResult.Found -> {
            val snap = usageResult.snapshot
            InfoCard(
                title = "Weekly usage (live)",
                onAction = onRetry,
                actionLabel = "Refresh",
                actionBusy = refreshing,
            ) {
                val actual = snap.weeklyUtilizationPercent
                if (actual != null) {
                    StatRow(label = "Used this week", value = "${actual.toInt()}%")
                    Spacer(Modifier.height(8.dp))
                    LinearMeter(progress = (actual / 100.0).toFloat())
                    Spacer(Modifier.height(10.dp))
                    val expectedPct = (expectedFraction * 100).toInt()
                    val delta = Pacing.delta(actual.toInt(), expectedPct)
                    Text(
                        text = "Expected ~$expectedPct%. " + Pacing.verdict(delta),
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceMuted,
                    )
                } else {
                    Text(
                        "Connected to Claude, but no weekly percentage was returned.",
                        color = OnSurfaceMuted,
                    )
                }

                // The API tells us the real reset moment — offer to align the
                // countdown to it when it differs from the configured one.
                snap.weeklyResetEpochMs?.let { resetMs ->
                    val resetInstant = Instant.ofEpochMilli(resetMs)
                    val resetZoned = resetInstant.atZone(java.time.ZoneId.of("UTC"))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Actual reset: " + formatResetMoment(resetZoned),
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceMuted,
                    )
                    val driftMs = kotlin.math.abs(resetMs - configuredNextReset.toEpochMilli())
                    if (driftMs > 60_000) {
                        TextButton(onClick = { onSync(resetMs) }) {
                            Text("Sync countdown to this", color = ClaudeClay)
                        }
                    }
                }
            }

            // 5-hour rolling window — hidden entirely when turned off in settings.
            if (showFiveHourWindow) {
                val end = snap.fiveHourResetEpochMs
                val fiveUtil = snap.fiveHourUtilizationPercent
                Spacer(Modifier.height(16.dp))
                InfoCard(title = "5-hour rolling window") {
                    if (fiveUtil != null) {
                        StatRow(label = "Used in window", value = "${fiveUtil.toInt()}%")
                        Spacer(Modifier.height(8.dp))
                        LinearMeter(progress = (fiveUtil / 100.0).toFloat())
                    }
                    if (end != null) {
                        val endInstant = Instant.ofEpochMilli(end)
                        val windowStart = endInstant.minus(Duration.ofHours(5))
                        val elapsed = Duration.between(windowStart, now).toMillis()
                            .coerceAtLeast(0)
                        val frac = (elapsed.toDouble() / Duration.ofHours(5).toMillis())
                            .coerceIn(0.0, 1.0)
                        Spacer(Modifier.height(if (fiveUtil != null) 16.dp else 10.dp))
                        StatRow(label = "How far you should be", value = formatPercent(frac))
                        Spacer(Modifier.height(8.dp))
                        LinearMeter(progress = frac.toFloat())
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Window ends " + formatRelative(endInstant, now),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceMuted,
                        )
                    } else {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "No active 5-hour window right now — it starts the moment you " +
                                "send your next message to Claude.",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceMuted,
                        )
                    }
                }
            }
        }
    }
}
