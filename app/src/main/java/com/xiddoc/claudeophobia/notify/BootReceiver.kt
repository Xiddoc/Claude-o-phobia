package com.xiddoc.claudeophobia.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Re-arms the daily nudge after a reboot, since alarms don't survive one. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("ClaudeUsage", "BootReceiver: re-arming nudge after boot")
            NudgeScheduler.scheduleNext(context.applicationContext)
        }
    }
}
