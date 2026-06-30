package com.xiddoc.claudeophobia.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.DisplayMetrics
import android.widget.RemoteViews
import com.xiddoc.claudeophobia.MainActivity
import com.xiddoc.claudeophobia.R
import com.xiddoc.claudeophobia.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.Instant

/**
 * A home-screen widget showing the same weekly figures as [UsageWidget] — live
 * "used this week" when available, otherwise "% of week elapsed", with the optional
 * pacing marker — but drawn as a circular gauge instead of a flat bar. It is the
 * RemoteViews twin of the in-app `ProgressRing`.
 *
 * Like [UsageWidget] it never contacts Claude (pure local figures + the cached live
 * percent) and is fully push-driven (`updatePeriodMillis=0`): every redraw comes
 * from an explicit [refresh]. Only the arc is rasterized to a bitmap; the centered
 * percentage/label are crisp overlaid TextViews that honor system font scaling.
 */
class CircleUsageWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { renderInto(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        newOptions: android.os.Bundle?,
    ) {
        // The bitmap can't reflow like text, so re-rasterize at the new size.
        renderInto(context, manager, widgetId)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { WidgetBitmaps.release(TAG, it) }
    }

    companion object {
        private const val TAG = "circle"

        /** Pushes a fresh render to every placed instance. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, CircleUsageWidget::class.java))
            ids.forEach { renderInto(context, manager, it) }
        }

        private fun renderInto(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val settings = runBlocking { SettingsRepository(context).settings.first() }
            val progress = settings.resetConfig.progress(Instant.now())
            val elapsedPct = (progress.fraction * 100).toInt()
            val live = settings.lastWeeklyUsagePercent
            // Mirror UsageWidget exactly for visual parity (it shows the live figure
            // regardless of the in-app live toggle), so the two widgets always agree.
            val showPacing = settings.widgetPacingEnabled && live != null
            val barPct = (live?.toInt() ?: elapsedPct).coerceIn(0, 100)
            val pacePct = if (showPacing) elapsedPct.coerceIn(0, 100) else null
            val value = "$barPct%"
            val label = if (live != null) "used" else "elapsed"

            val side = squareSidePx(context, manager, widgetId)

            // Deferred recycle: free the prior bitmap before allocating this render's.
            WidgetBitmaps.recyclePrevious(TAG, widgetId)
            val bmp = CircleRenderer.render(side, barPct, pacePct, settings.circleWidgetGlow)

            val views = RemoteViews(context.packageName, R.layout.widget_circle)
            views.setImageViewBitmap(R.id.circle_image, bmp)
            views.setTextViewText(R.id.circle_value, value)
            views.setTextViewText(R.id.circle_label, label)
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
            WidgetBitmaps.remember(TAG, widgetId, bmp)
        }

        /** Square side in px: the smaller reported max dimension, bounded for memory. */
        private fun squareSidePx(context: Context, manager: AppWidgetManager, widgetId: Int): Int {
            val opts = manager.getAppWidgetOptions(widgetId)
            val density = context.resources.displayMetrics.density
                .takeIf { it > 0f } ?: DisplayMetrics.DENSITY_DEFAULT.toFloat() / 160f
            val maxW = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0) ?: 0
            val maxH = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0
            val maxDp = when {
                maxW > 0 && maxH > 0 -> minOf(maxW, maxH)
                maxW > 0 -> maxW
                maxH > 0 -> maxH
                else -> 0
            }
            return WidgetBitmaps.boundDimPx(
                maxDp = maxDp,
                density = density,
                minPx = 96,
                maxPx = 512,
                fallbackDp = 160,
            )
        }
    }
}
