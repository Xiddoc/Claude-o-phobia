package com.xiddoc.claudeophobia.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.xiddoc.claudeophobia.MainActivity
import com.xiddoc.claudeophobia.R
import com.xiddoc.claudeophobia.data.Pacing
import com.xiddoc.claudeophobia.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.format.TextStyle
import java.util.Locale

/**
 * A home-screen widget showing weekly pacing at a glance.
 *
 * Crucially, the widget never talks to Claude. It renders the locally-computed
 * "percent of the week elapsed" (pure schedule math) and, when available, the
 * last live usage figure the LSPosed module captured from the Claude app. That way
 * it can sit on the home screen without ever generating a usage request.
 *
 * It is also fully push-driven: [usage_widget_info] sets `updatePeriodMillis=0`,
 * so Android never wakes the device to poll us on a timer. Every redraw comes from
 * an explicit [refresh] — when a fetch caches a fresher figure, when the daily nudge
 * fires, or when a widget setting changes — so the home screen only does work when
 * the numbers it shows have actually moved, instead of waking the phone every half
 * hour to re-draw unchanged cached data.
 */
class UsageWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { renderInto(context, appWidgetManager, it) }
    }

    companion object {

        /** Pushes a fresh render to every placed instance of the widget. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(
                ComponentName(context, UsageWidget::class.java)
            )
            ids.forEach { renderInto(context, manager, it) }
        }

        private fun renderInto(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int,
        ) {
            val settings = runBlocking { SettingsRepository(context).settings.first() }
            val progress = settings.resetConfig.progress(Instant.now())
            val elapsedPct = (progress.fraction * 100).toInt()
            val resetDay = settings.resetConfig.dayOfWeek
                .getDisplayName(TextStyle.SHORT, Locale.getDefault())

            val views = RemoteViews(context.packageName, R.layout.widget_usage)

            val live = settings.lastWeeklyUsagePercent
            // The pacing cue only makes sense once there's real usage to compare
            // against the clock; without it the bar already *is* the week's pace.
            val showPacing = settings.widgetPacingEnabled && live != null

            if (live != null) {
                views.setTextViewText(R.id.widget_value, "${live.toInt()}%")
                views.setTextViewText(R.id.widget_label, "used this week")
                views.setProgressBar(R.id.widget_progress, 100, live.toInt(), false)
            } else {
                views.setTextViewText(R.id.widget_value, "$elapsedPct%")
                views.setTextViewText(R.id.widget_label, "of week elapsed")
                views.setProgressBar(R.id.widget_progress, 100, elapsedPct, false)
            }

            // Mark where a steady pace would put you. Off the bar (0) unless we're
            // showing the cue, so a stale band never lingers after the toggle flips.
            views.setInt(
                R.id.widget_progress,
                "setSecondaryProgress",
                if (showPacing) elapsedPct else 0,
            )

            if (showPacing) {
                val delta = Pacing.delta(live!!.toInt(), elapsedPct)
                views.setTextViewText(
                    R.id.widget_caption,
                    "${Pacing.glyph(delta)} ${Pacing.shortLabel(delta)} · " +
                        "$elapsedPct% of week elapsed",
                )
            } else {
                views.setTextViewText(
                    R.id.widget_caption,
                    "$elapsedPct% of week done · resets $resetDay",
                )
            }

            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )

            manager.updateAppWidget(widgetId, views)
        }
    }
}
