package com.xiddoc.claudeophobia.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.xiddoc.claudeophobia.data.SettingsRepository
import com.xiddoc.claudeophobia.data.WeeklyNudges
import com.xiddoc.claudeophobia.widget.UsageWidget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate

/**
 * Fires once a day: posts the pacing nudge, refreshes the home-screen widget
 * (locally — no Claude request), then re-arms tomorrow's alarm.
 */
class NudgeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("ClaudeUsage", "NudgeReceiver: fired (action=${intent.action})")
        val appContext = context.applicationContext

        // DataStore reads are quick; a BroadcastReceiver may block briefly in
        // onReceive, so a bounded runBlocking is acceptable here.
        val settings = runBlocking { SettingsRepository(appContext).settings.first() }
        val progress = settings.resetConfig.progress(Instant.now())
        val epochDay = LocalDate.now().toEpochDay()

        // Refresh the widget and re-arm tomorrow's alarm every day regardless, so
        // the glance card stays fresh; only the notification itself is thinned to
        // the user's chosen weekly frequency.
        if (WeeklyNudges.shouldNudgeOn(epochDay, settings.notificationsPerWeek)) {
            // Quote real progress only when the LSPosed live-usage feature is on and
            // has handed us a figure; otherwise we can only speak to the clock.
            val usagePercent = settings.lastWeeklyUsagePercent
                ?.takeIf { settings.liveUsageEnabled }
                ?.toInt()
            val nudge = WeeklyNudges.forDay(
                epochDay = epochDay,
                weekFraction = progress.fraction,
                resetDay = settings.resetConfig.dayOfWeek,
                usagePercent = usagePercent,
            )
            Notifications.show(appContext, nudge)
        }
        UsageWidget.refresh(appContext)

        NudgeScheduler.scheduleNext(appContext)
    }
}
