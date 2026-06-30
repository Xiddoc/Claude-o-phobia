package com.xiddoc.claudeophobia.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.util.SizeF
import android.widget.RemoteViews
import com.xiddoc.claudeophobia.MainActivity
import com.xiddoc.claudeophobia.R
import com.xiddoc.claudeophobia.data.GraphMath
import com.xiddoc.claudeophobia.data.ResetConfig
import com.xiddoc.claudeophobia.data.SettingsRepository
import com.xiddoc.claudeophobia.data.UsageHistoryRepository
import com.xiddoc.claudeophobia.data.WeekBucket
import com.xiddoc.claudeophobia.data.bucketsFor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Home-screen widget plotting one week's Claude-usage progress as a Bezier-curved
 * line with an optional "gained per day" derivative overlay. Matches the existing
 * [UsageWidget] design language (the `widget_bg` card, clay palette, caps header).
 *
 * RemoteViews can't host a `Canvas`, so the curve is rasterized to a bitmap
 * ([HistoryGraphRenderer]); RemoteViews also can't swipe, so week navigation is two
 * tap arrows that broadcast back here. The viewed week is stored per-widget as an
 * *absolute* reset-boundary key (so a pinned historical view never drifts as new
 * weeks accrue) and clamped to the weeks that actually exist (no future, no
 * pre-earliest). Fully push-driven (`updatePeriodMillis=0`): every redraw is an
 * explicit [refresh] or a nav tap.
 */
class HistoryGraphWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { renderInto(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        newOptions: Bundle?,
    ) {
        renderInto(context, manager, widgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_NAV) {
            val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val delta = intent.getIntExtra(EXTRA_DELTA, 0)
            if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
            // Decoding the (potentially years-long) history blob + rasterizing the
            // bitmap must not block the main thread on a tap. goAsync() keeps the
            // process alive while we do it on a background thread.
            val pending = goAsync()
            val appContext = context.applicationContext
            Thread {
                try {
                    navigateAndRender(appContext, widgetId, delta)
                } catch (t: Throwable) {
                    Log.e("ClaudeUsage", "HistoryGraphWidget: nav failed", t)
                } finally {
                    pending.finish()
                }
            }.start()
        } else {
            // Let APPWIDGET_UPDATE / APPWIDGET_DELETED dispatch normally so
            // onUpdate / onDeleted still run.
            super.onReceive(context, intent)
        }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val repo = UsageHistoryRepository(context)
        runBlocking { ids.forEach { repo.clearWidgetWeek(it) } }
        ids.forEach { WidgetBitmaps.release(TAG, it) }
    }

    /**
     * Resolves the navigation target, persists the new pinned week, and re-renders
     * — all on the caller's background thread, decoding the history blob exactly
     * once and reusing those weeks for the render (no second decode).
     */
    private fun navigateAndRender(context: Context, widgetId: Int, delta: Int) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val repo = UsageHistoryRepository(context)
        val settings = runBlocking { SettingsRepository(context).settings.first() }
        val weeks = runBlocking { bucketsFor(repo.snapshot(), settings.resetConfig) }
        var pinned = runBlocking { repo.getWidgetWeek(widgetId) }
        if (weeks.isNotEmpty()) {
            val resolved = GraphMath.resolveWeek(weeks, pinned)
            val basis = resolved?.weekStartMs
            val target = if (delta < 0) GraphMath.olderWeek(weeks, basis) else GraphMath.newerWeek(weeks, basis)
            if (target != null) {
                // Snap-to-current (store null) when we land on the newest week so the
                // widget follows the live week from then on.
                val store = if (target == weeks.last().weekStartMs) null else target
                runBlocking { repo.setWidgetWeek(widgetId, store) }
                pinned = store
            }
        }
        render(context, manager, widgetId, settings, weeks, pinned)
    }

    companion object {
        private const val TAG = "graph"
        const val ACTION_NAV = "com.xiddoc.claudeophobia.GRAPH_NAV"
        const val EXTRA_WIDGET_ID = "widget_id"
        const val EXTRA_DELTA = "delta"

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, HistoryGraphWidget::class.java))
            ids.forEach { renderInto(context, manager, it) }
        }

        private fun renderInto(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val settings = runBlocking { SettingsRepository(context).settings.first() }
            val repo = UsageHistoryRepository(context)
            val weeks = runBlocking { bucketsFor(repo.snapshot(), settings.resetConfig) }
            val pinned = runBlocking { repo.getWidgetWeek(widgetId) }
            render(context, manager, widgetId, settings, weeks, pinned)
        }

        /** Builds and pushes the widget from already-decoded data (no further history decode). */
        private fun render(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int,
            settings: com.xiddoc.claudeophobia.data.AppSettings,
            weeks: List<WeekBucket>,
            pinned: Long?,
        ) {
            val resolved = GraphMath.resolveWeek(weeks, pinned)
            val basis = resolved?.weekStartMs

            val canOlder = GraphMath.olderWeek(weeks, basis) != null
            val canNewer = GraphMath.newerWeek(weeks, basis) != null

            val (wPx, hPx) = graphSizePx(context, manager, widgetId)
            WidgetBitmaps.recyclePrevious(TAG, widgetId)
            val bmp = HistoryGraphRenderer.render(
                widthPx = wPx,
                heightPx = hPx,
                week = resolved,
                tension = settings.graphCurveTension,
                showDerivative = settings.showDerivative,
                glow = settings.graphWidgetGlow,
            )

            val label = weekLabel(weeks, resolved)
            val caption = captionFor(resolved, settings.resetConfig, settings.showDerivative)

            fun build(layout: Int): RemoteViews {
                val views = RemoteViews(context.packageName, layout)
                views.setImageViewBitmap(R.id.graph_image, bmp)
                views.setTextViewText(R.id.graph_week_label, label)
                views.setTextViewText(R.id.graph_caption, caption)
                views.setInt(R.id.graph_prev, "setImageAlpha", if (canOlder) 255 else 70)
                views.setInt(R.id.graph_next, "setImageAlpha", if (canNewer) 255 else 70)
                views.setOnClickPendingIntent(R.id.graph_prev, navIntent(context, widgetId, -1))
                views.setOnClickPendingIntent(R.id.graph_next, navIntent(context, widgetId, +1))
                views.setOnClickPendingIntent(R.id.graph_image, openHistoryIntent(context))
                return views
            }

            val views = RemoteViews(
                mapOf(
                    SizeF(180f, 110f) to build(R.layout.widget_graph_compact),
                    SizeF(180f, 200f) to build(R.layout.widget_graph),
                )
            )
            manager.updateAppWidget(widgetId, views)
            WidgetBitmaps.remember(TAG, widgetId, bmp)
        }

        private fun navIntent(context: Context, widgetId: Int, delta: Int): PendingIntent {
            val intent = Intent(context, HistoryGraphWidget::class.java)
                .setAction(ACTION_NAV)
                .putExtra(EXTRA_WIDGET_ID, widgetId)
                .putExtra(EXTRA_DELTA, delta)
            // Unique per (widgetId, direction) so widgets never navigate in lockstep.
            val requestCode = widgetId * 2 + if (delta > 0) 1 else 0
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun openHistoryIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                1,
                Intent(context, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_OPEN_HISTORY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun weekLabel(weeks: List<WeekBucket>, resolved: WeekBucket?): String {
            if (resolved == null || weeks.isEmpty()) return "No data yet"
            val idx = weeks.indexOfFirst { it.weekStartMs == resolved.weekStartMs }
            val offset = if (idx < 0) 0 else (weeks.size - 1) - idx
            return when (offset) {
                0 -> "This week"
                1 -> "Last week"
                else -> "$offset weeks ago"
            }
        }

        private val DAY_FMT = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())

        private fun captionFor(week: WeekBucket?, config: ResetConfig, showDerivative: Boolean): String {
            if (week == null) return "No history yet"
            val zone = config.zone
            val start = Instant.ofEpochMilli(week.weekStartMs).atZone(zone).format(DAY_FMT)
            val end = Instant.ofEpochMilli(week.weekEndMs).atZone(zone).format(DAY_FMT)
            val legend = if (showDerivative) "progress % · gained/day" else "progress %"
            return "$start – $end · $legend"
        }

        private fun graphSizePx(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int,
        ): Pair<Int, Int> {
            val opts = manager.getAppWidgetOptions(widgetId)
            val density = context.resources.displayMetrics.density
                .takeIf { it > 0f } ?: DisplayMetrics.DENSITY_DEFAULT.toFloat() / 160f
            val maxW = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0) ?: 0
            val maxH = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0
            val w = WidgetBitmaps.boundDimPx(maxW, density, minPx = 120, maxPx = 1024, fallbackDp = 300)
            val h = WidgetBitmaps.boundDimPx(maxH, density, minPx = 80, maxPx = 512, fallbackDp = 150)
            return w to h
        }
    }
}
