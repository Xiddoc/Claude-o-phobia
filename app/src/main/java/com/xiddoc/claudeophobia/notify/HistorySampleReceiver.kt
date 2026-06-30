package com.xiddoc.claudeophobia.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.xiddoc.claudeophobia.data.AppSettings
import com.xiddoc.claudeophobia.data.SettingsRepository
import com.xiddoc.claudeophobia.data.UsageHistoryRepository
import com.xiddoc.claudeophobia.widget.CircleUsageWidget
import com.xiddoc.claudeophobia.widget.HistoryGraphWidget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.math.roundToInt

/**
 * Fires at the Live Usage sync interval (see [HistorySampler]) to record one
 * weekly-progress data point, then re-arms.
 *
 * Battery contract — this receiver must stay cheap and **offline**:
 *
 *  - It NEVER imports or calls [com.xiddoc.claudeophobia.data.LiveUsageReader] or
 *    any claude.ai path. It records only the figure the live-usage flow already
 *    cached ([SettingsRepository] `lastWeeklyUsagePercent`). No radio wakeup is
 *    ever attributable to sampling.
 *  - It records nothing unless live usage is enabled *and* the cache is fresh,
 *    exactly mirroring how [NudgeReceiver] gates `lastWeeklyUsagePercent`, so a
 *    stale or absent figure can never fabricate a flat fake line.
 *  - It pushes a redraw to the home-screen widgets only when the appended point
 *    actually changed, avoiding cross-process bitmap marshaling for no reason.
 *
 * The `runBlocking` reads stay bounded (one settings read, one history append) —
 * the same justification [NudgeReceiver] relies on — and never await a network call.
 */
class HistorySampleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        // Default to re-arming if the settings read fails, so a transient error
        // never silently kills sampling; an explicit disable below clears it.
        var enabled = true
        var intervalMs = DEFAULT_INTERVAL_MS
        try {
            val settings = runBlocking { SettingsRepository(app).settings.first() }
            enabled = settings.historySamplingEnabled
            intervalMs = HistorySampler.intervalMsOf(settings)
            if (!enabled) {
                // Paused: do no work and let the alarm lapse (the finally re-arms
                // only while enabled), so a disabled sampler can't self-perpetuate.
                return
            }

            val nowMs = System.currentTimeMillis()
            val raw = settings.lastWeeklyUsagePercent?.takeIf { settings.liveUsageEnabled }
            val fresh = settings.lastUsageTimestampMs != 0L &&
                nowMs - settings.lastUsageTimestampMs <= STALE_MS

            if (raw != null && raw.isFinite() && fresh) {
                val pct = raw.coerceIn(0.0, 100.0).roundToInt() // clamp the Double BEFORE toInt
                val repo = UsageHistoryRepository(app)
                val last = runBlocking { repo.lastSampleMs() }
                if (UsageHistoryRepository.shouldAppend(last, nowMs, intervalMs)) {
                    val changed = runBlocking { repo.appendSample(nowMs, pct, settings.resetConfig) }
                    if (changed) {
                        HistoryGraphWidget.refresh(app)
                        CircleUsageWidget.refresh(app)
                    }
                }
            } else {
                Log.d("ClaudeUsage", "HistorySampleReceiver: no fresh live figure — recording nothing")
            }
        } catch (t: Throwable) {
            Log.e("ClaudeUsage", "HistorySampleReceiver: sample failed", t)
        } finally {
            // Re-arm only while sampling is enabled, at the current sync interval. A
            // disabled toggle also cancels the pending alarm
            // (MainViewModel.setHistorySamplingEnabled), so a late fire here must not
            // resurrect it.
            if (enabled) HistorySampler.scheduleNext(app, intervalMs)
        }
    }

    companion object {
        /** A cached figure older than this is treated as stale and not recorded (7 days). */
        const val STALE_MS = 7L * 24 * 60 * 60 * 1000

        /** Fallback cadence if the settings read fails (matches the default sync interval). */
        private val DEFAULT_INTERVAL_MS =
            AppSettings.DEFAULT_SYNC_INTERVAL_MINUTES * 60_000L
    }
}
