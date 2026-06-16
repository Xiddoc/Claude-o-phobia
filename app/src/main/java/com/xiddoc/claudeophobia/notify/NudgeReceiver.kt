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

        val nudge = WeeklyNudges.forDay(
            epochDay = LocalDate.now().toEpochDay(),
            weekFraction = progress.fraction,
            resetDay = settings.resetConfig.dayOfWeek,
        )
        Notifications.show(appContext, nudge)
        UsageWidget.refresh(appContext)

        NudgeScheduler.scheduleNext(appContext)
    }
}
