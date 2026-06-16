package com.xiddoc.claudeophobia.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/**
 * Schedules the once-a-day pacing nudge for a *random* moment inside a daytime
 * window (10:00–18:00 local). The alarm is one-shot and re-arms itself when it
 * fires (see [NudgeReceiver]), so each day gets a freshly-randomised time.
 *
 * We use an inexact, idle-friendly alarm on purpose: a daily encouragement
 * doesn't need to-the-second precision, and inexact alarms don't require the
 * special "exact alarm" permission and are kinder to the battery.
 */
object NudgeScheduler {

    private const val TAG = "ClaudeUsage"
    private const val REQUEST_CODE = 0xC1AD
    const val ACTION_FIRE = "com.xiddoc.claudeophobia.NUDGE_FIRE"

    /** Inclusive start / exclusive end of the daily window, in hours (local). */
    const val WINDOW_START_HOUR = 10
    const val WINDOW_END_HOUR = 18

    /** Arms tomorrow-or-today's nudge only if one isn't already pending. */
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
            Log.d(TAG, "NudgeScheduler: a nudge alarm is already pending — leaving it be")
        }
    }

    /** (Re)arms the next nudge at a fresh random time in the window. */
    fun scheduleNext(context: Context) {
        val triggerAt = nextTriggerMillis(System.currentTimeMillis())
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            fireIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        Log.d(TAG, "NudgeScheduler: next nudge scheduled for ${Instant.ofEpochMilli(triggerAt)}")
    }

    private fun fireIntent(context: Context): Intent =
        Intent(context, NudgeReceiver::class.java).setAction(ACTION_FIRE)

    /**
     * Computes the next trigger instant: a random minute within today's
     * [WINDOW_START_HOUR]..[WINDOW_END_HOUR) window if it's still ahead of
     * [now], otherwise the same kind of random pick tomorrow. Pure & testable.
     */
    fun nextTriggerMillis(
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        random: Random = Random.Default,
    ): Long {
        val nowInstant = Instant.ofEpochMilli(now)
        val today = nowInstant.atZone(zone).toLocalDate()
        val startMinute = WINDOW_START_HOUR * 60
        val endMinute = WINDOW_END_HOUR * 60

        fun pick(dayOffset: Long): Instant {
            val minuteOfDay = random.nextInt(startMinute, endMinute)
            return today.plusDays(dayOffset)
                .atStartOfDay(zone)
                .plusMinutes(minuteOfDay.toLong())
                .toInstant()
        }

        var candidate = pick(0)
        if (!candidate.isAfter(nowInstant)) {
            candidate = pick(1)
        }
        return candidate.toEpochMilli()
    }
}
