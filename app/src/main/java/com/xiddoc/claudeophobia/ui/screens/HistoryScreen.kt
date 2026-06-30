package com.xiddoc.claudeophobia.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiddoc.claudeophobia.data.WeekBucket
import com.xiddoc.claudeophobia.data.bucketsFor
import com.xiddoc.claudeophobia.data.weekKeyFor
import com.xiddoc.claudeophobia.ui.MainViewModel
import com.xiddoc.claudeophobia.ui.components.GraphLegend
import com.xiddoc.claudeophobia.ui.components.InfoCard
import com.xiddoc.claudeophobia.ui.components.UsageHistoryGraph
import com.xiddoc.claudeophobia.ui.theme.OnSurfaceMuted
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_FMT = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())

/**
 * The full weekly-progress history: a swipeable pager over every recorded week,
 * oldest on the left and the current week on the right. Each page draws the same
 * curved [UsageHistoryGraph] as the home card. The pager's own bounds make it
 * impossible to swipe into the future or before the earliest data, satisfying the
 * navigation constraint structurally.
 */
@Composable
fun HistoryScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()

    val weeks = remember(history, settings.resetConfig) {
        bucketsFor(history, settings.resetConfig)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Weekly progress",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            Spacer(Modifier.height(16.dp))

            if (weeks.isEmpty()) {
                InfoCard(title = "No history yet") {
                    Text(
                        text = "Your weekly Claude usage is recorded at your Live Usage " +
                            "sync interval once live usage is enabled. As soon as the first " +
                            "few points land, they'll curve here — swipe to browse past weeks.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceMuted,
                    )
                }
            } else {
                WeekPager(weeks = weeks, settings = settings, nowMs = now.toEpochMilli())
            }
        }
    }
}

@Composable
private fun WeekPager(
    weeks: List<WeekBucket>,
    settings: com.xiddoc.claudeophobia.data.AppSettings,
    nowMs: Long,
) {
    val pagerState = rememberPagerState(
        initialPage = (weeks.size - 1).coerceAtLeast(0),
    ) { weeks.size }
    val scope = rememberCoroutineScope()
    val currentKey = remember(weeks, nowMs, settings.resetConfig) {
        weekKeyFor(nowMs, settings.resetConfig)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { scope.launch { pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0)) } },
            enabled = pagerState.currentPage > 0,
        ) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "Previous week")
        }
        val page = pagerState.currentPage.coerceIn(0, weeks.lastIndex)
        val offset = weeks.lastIndex - page
        Text(
            text = when (offset) {
                0 -> "This week"
                1 -> "Last week"
                else -> "$offset weeks ago"
            },
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        IconButton(
            onClick = { scope.launch { pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(weeks.lastIndex)) } },
            enabled = pagerState.currentPage < weeks.lastIndex,
        ) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Next week")
        }
    }

    Spacer(Modifier.height(8.dp))

    HorizontalPager(
        state = pagerState,
        // Keep adjacent pages from composing far ahead; the math is cheap but the
        // glow blur isn't free, so don't pre-rasterize a whole stack.
        beyondViewportPageCount = 1,
        modifier = Modifier.fillMaxWidth(),
    ) { pageIndex ->
        val week = weeks[pageIndex]
        val todayFraction = if (week.weekStartMs == currentKey) {
            val span = (week.weekEndMs - week.weekStartMs).toDouble()
            // Quantize (~0.25%) so the per-second clock tick doesn't re-rasterize
            // the blurred curve; the graph skips while this stays equal.
            if (span > 0) {
                val raw = ((nowMs - week.weekStartMs) / span).coerceIn(0.0, 1.0)
                (raw * 400).toInt() / 400f
            } else {
                null
            }
        } else {
            null
        }
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            val zone = settings.resetConfig.zone
            val start = Instant.ofEpochMilli(week.weekStartMs).atZone(zone).format(DAY_FMT)
            val end = Instant.ofEpochMilli(week.weekEndMs).atZone(zone).format(DAY_FMT)
            Text(
                text = "$start – $end",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
            )
            Spacer(Modifier.height(12.dp))
            if (week.samples.isEmpty()) {
                Text("No data for this week.", color = OnSurfaceMuted)
            } else if (week.samples.size == 1) {
                UsageHistoryGraph(
                    week = week,
                    tension = settings.graphCurveTension,
                    showDerivative = settings.showDerivative,
                    glow = true,
                    todayFraction = todayFraction,
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Just one point so far — the curve fills in as more are recorded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMuted,
                )
            } else {
                UsageHistoryGraph(
                    week = week,
                    tension = settings.graphCurveTension,
                    showDerivative = settings.showDerivative,
                    glow = true,
                    todayFraction = todayFraction,
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            GraphLegend(showDerivative = settings.showDerivative)
        }
    }
}
