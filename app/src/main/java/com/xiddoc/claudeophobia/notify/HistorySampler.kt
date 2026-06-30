package com.xiddoc.claudeophobia.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Schedules the every-3-hours weekly-progress sample. Modelled on
 * [NudgeScheduler]: a self-rearming one-shot **inexact** alarm, so there is no
 * `SCHEDULE_EXACT_ALARM` permission and the OS is free to batch us with other
 * wakeups.
 *
 * Two deliberate battery choices distinguish it from the nudge:
 *
 *  - We use `RTC` (NOT `RTC_WAKEUP`). Recording an already-cached number never
 *    justifies waking a sleeping phone; Doze coalesces the sample to the next
 *    natural wake. `setAndAllowWhileIdle` still lets it fire during Doze windows.
 *  - The receiver it fires ([HistorySampleReceiver]) does **no** network — it only
 *    reads the figure the live-usage path already cached.
 *
 * A distinct request code/action keeps it from ever colliding with the nudge alarm.
 */
object HistorySampler {

    private const val TAG = "ClaudeUsage"
    private const val REQUEST_CODE = 0x5A11
    const val ACTION_SAMPLE = "com.xiddoc.claudeophobia.SAMPLE_FIRE"

    /** Sampling cadence: once every 3 hours, per the feature spec. */
    const val SAMPLE_INTERVAL_MS = 3L * 60 * 60 * 1000

    /** Arms the next sample only if one isn't already pending. */
    fun ensureScheduled(context: Context) {
        val existing = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            fireIntent(context),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (existing == null) {
            scheduleNext(context)
        } else {
            Log.d(TAG, "HistorySampler: a sample alarm is already pending — leaving it be")
        }
    }

    /** (Re)arms the next sample 3 hours out. */
    fun scheduleNext(context: Context) {
        val triggerAt = nextTriggerMillis(System.currentTimeMillis())
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            fireIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // RTC (non-wakeup) + allow-while-idle: idle-friendly, never wakes the device.
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC, triggerAt, pending)
        Log.d(TAG, "HistorySampler: next sample scheduled in 3h")
    }

    /** Cancels any pending sample alarm (used when sampling is paused in settings). */
    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            fireIntent(context),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pending != null) {
            alarmManager.cancel(pending)
            pending.cancel()
            Log.d(TAG, "HistorySampler: sample alarm cancelled")
        }
    }

    private fun fireIntent(context: Context): Intent =
        Intent(context, HistorySampleReceiver::class.java).setAction(ACTION_SAMPLE)

    /** Next trigger instant: a fixed 3 hours out. Pure & always strictly future. */
    fun nextTriggerMillis(now: Long): Long = now + SAMPLE_INTERVAL_MS
}
