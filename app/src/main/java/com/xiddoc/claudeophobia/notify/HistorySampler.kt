package com.xiddoc.claudeophobia.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.xiddoc.claudeophobia.data.AppSettings

/**
 * Schedules the weekly-progress sample. Modelled on [NudgeScheduler]: a
 * self-rearming one-shot **inexact** alarm, so there is no `SCHEDULE_EXACT_ALARM`
 * permission and the OS is free to batch us with other wakeups.
 *
 * The cadence is the user's **Live Usage sync interval** ([AppSettings.syncIntervalMinutes]) —
 * the same knob that governs the in-app live refresh — rather than a separate
 * hardcoded value, so one setting controls both. Because the alarm is inexact and
 * non-wakeup, that interval is only an upper bound on frequency *when the device is
 * active*: Doze coalesces and throttles background fires, so a short interval can't
 * actually wake a sleeping phone every few minutes.
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

    /** The sampling cadence in millis: the Live Usage sync interval (clamped sane). */
    fun intervalMsOf(settings: AppSettings): Long =
        settings.syncIntervalMinutes.coerceAtLeast(1) * 60_000L

    /** Arms the next sample only if one isn't already pending. */
    fun ensureScheduled(context: Context, intervalMs: Long) {
        val existing = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            fireIntent(context),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (existing == null) {
            scheduleNext(context, intervalMs)
        } else {
            Log.d(TAG, "HistorySampler: a sample alarm is already pending — leaving it be")
        }
    }

    /** (Re)arms the next sample [intervalMs] out (FLAG_UPDATE_CURRENT replaces any pending one). */
    fun scheduleNext(context: Context, intervalMs: Long) {
        val triggerAt = nextTriggerMillis(System.currentTimeMillis(), intervalMs)
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            fireIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // RTC (non-wakeup) + allow-while-idle: idle-friendly, never wakes the device.
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC, triggerAt, pending)
        Log.d(TAG, "HistorySampler: next sample scheduled in ${intervalMs / 60_000}min")
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

    /** Next trigger instant: [intervalMs] out. Pure & always strictly future. */
    fun nextTriggerMillis(now: Long, intervalMs: Long): Long =
        now + intervalMs.coerceAtLeast(60_000L)
}
