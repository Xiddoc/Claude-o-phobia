package com.xiddoc.claudeophobia.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.xiddoc.claudeophobia.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Re-arms the daily nudge and the progress sampler after a reboot, since alarms don't survive one. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val app = context.applicationContext
            Log.d("ClaudeUsage", "BootReceiver: re-arming nudge + history sampler after boot")
            NudgeScheduler.scheduleNext(app)
            // Only re-arm the sampler if it's actually enabled — otherwise a reboot
            // would silently resurrect a sampler the user disabled (alarms don't
            // survive reboot, so a disabled sampler simply has none to restore).
            val settings = runBlocking { SettingsRepository(app).settings.first() }
            if (settings.historySamplingEnabled) {
                HistorySampler.scheduleNext(app, HistorySampler.intervalMsOf(settings))
            }
        }
    }
}
