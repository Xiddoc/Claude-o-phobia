package com.xiddoc.claudeophobia.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.SizeF
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

            val live = settings.lastWeeklyUsagePercent
            // The pacing cue only makes sense once there's real usage to compare
            // against the clock; without it the bar already *is* the week's pace.
            val showPacing = settings.widgetPacingEnabled && live != null

            val barPct = (live?.toInt() ?: elapsedPct)
            val value = "$barPct%"
            val label = if (live != null) "used this week" else "of week elapsed"
            // Mark where a steady pace would put you. Off the bar (0) unless we're
            // showing the cue, so a stale band never lingers after the toggle flips.
            val pacePct = if (showPacing) elapsedPct else 0
            val caption = if (showPacing) {
                val delta = Pacing.delta(live!!.toInt(), elapsedPct)
                "${Pacing.glyph(delta)} ${Pacing.shortLabel(delta)} · " +
                    "$elapsedPct% of week elapsed"
            } else {
                "$elapsedPct% of week done · resets $resetDay"
            }

            // Render the same figures into two layouts and let the launcher swap
            // between them by size: a wide single-row card when short (~5x1), the
            // taller centered card once there's a second row of height (~5x2+).
            // The tall breakpoint sits at ~100dp so a two-cell-tall widget (whose
            // usable height lands near there) flips to the stacked design instead
            // of staying stuck on the compact row.
            fun build(layout: Int) =
                buildViews(context, layout, value, label, barPct, pacePct, caption)

            val views = RemoteViews(
                mapOf(
                    SizeF(180f, 50f) to build(R.layout.widget_usage_compact),
                    SizeF(180f, 100f) to build(R.layout.widget_usage),
                )
            )

            manager.updateAppWidget(widgetId, views)
        }

        /** Binds the computed figures into one layout and wires its tap target. */
        private fun buildViews(
            context: Context,
            layout: Int,
            value: String,
            label: String,
            barPct: Int,
            pacePct: Int,
            caption: String,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, layout)
            views.setTextViewText(R.id.widget_value, value)
            views.setTextViewText(R.id.widget_label, label)
            views.setProgressBar(R.id.widget_progress, 100, barPct, false)
            views.setInt(R.id.widget_progress, "setSecondaryProgress", pacePct)
            views.setTextViewText(R.id.widget_caption, caption)
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
            return views
        }
    }
}
