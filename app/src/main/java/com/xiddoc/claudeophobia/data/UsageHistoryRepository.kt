package com.xiddoc.claudeophobia.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persists the weekly-progress [History] in its *own* DataStore, deliberately
 * separate from the hot `settings` store that [UsageWidget] reads via `runBlocking`
 * on every render: the history blob grows for years, and we never want that decode
 * cost on the settings hot path. A single string key holds the encoded blob; each
 * widget's pinned week lives under its own key in the same store.
 *
 * Every mutation goes through `DataStore.edit {}`, which serializes writes, so the
 * 3-hourly append, the per-widget navigation taps, and a manual "clear" can never
 * race or corrupt each other.
 */
private val Context.historyDataStore: DataStore<Preferences> by preferencesDataStore(name = "usage_history")

class UsageHistoryRepository(private val context: Context) {

    /** The decoded history, recomputed whenever the blob changes. */
    val history: Flow<History> = context.historyDataStore.data.map { prefs ->
        HistoryCodec.decode(prefs[KEY_BLOB].orEmpty())
    }

    /** A one-shot decoded snapshot, for the widgets' bounded `runBlocking` reads. */
    suspend fun snapshot(): History =
        HistoryCodec.decode(context.historyDataStore.data.first()[KEY_BLOB].orEmpty())

    /** Timestamp of the most recent sample across all weeks, or null if none. */
    suspend fun lastSampleMs(): Long? =
        snapshot().buckets.lastOrNull()?.samples?.lastOrNull()?.tsMs

    /**
     * Appends one clamped sample under [config] inside a single atomic transaction
     * (decode → insert → trim → encode). Returns true when the new point actually
     * differs from the previously-stored last point (or is the first ever), so the
     * caller can skip pushing an unchanged figure to the home-screen widgets.
     */
    suspend fun appendSample(nowMs: Long, pct: Int, config: ResetConfig): Boolean {
        val clampedPct = pct.coerceIn(0, 100)
        var changed = false
        context.historyDataStore.edit { prefs ->
            val current = HistoryCodec.decode(prefs[KEY_BLOB].orEmpty())
            val lastPct = current.buckets.lastOrNull()?.samples?.lastOrNull()?.pct
            changed = lastPct == null || lastPct != clampedPct
            val updated = trim(insertSample(current, Sample(nowMs, clampedPct), config))
            prefs[KEY_BLOB] = HistoryCodec.encode(updated)
        }
        return changed
    }

    /** Wipes all recorded history (the "Clear history" action). */
    suspend fun clear() {
        context.historyDataStore.edit { it.remove(KEY_BLOB) }
    }

    // --- Per-widget pinned week (absolute boundary millis; null/absent = current) ---

    suspend fun getWidgetWeek(widgetId: Int): Long? =
        context.historyDataStore.data.first()[widgetWeekKey(widgetId)]

    suspend fun setWidgetWeek(widgetId: Int, weekStartMs: Long?) {
        context.historyDataStore.edit { prefs ->
            if (weekStartMs == null) prefs.remove(widgetWeekKey(widgetId))
            else prefs[widgetWeekKey(widgetId)] = weekStartMs
        }
    }

    suspend fun clearWidgetWeek(widgetId: Int) = setWidgetWeek(widgetId, null)

    companion object {
        private val KEY_BLOB = stringPreferencesKey("history_blob")
        private fun widgetWeekKey(id: Int) = longPreferencesKey("widget_week_$id")

        /**
         * Safety valve only — "keep forever" is the norm. Even at ~520 weeks (a
         * decade) the blob is well under a megabyte; this just caps a pathological
         * stuck-sampler from growing without bound, dropping the *oldest* weeks.
         */
        const val MAX_WEEKS = 600

        private fun trim(history: History): History {
            if (history.buckets.size <= MAX_WEEKS) return history
            return History(history.buckets.sortedBy { it.weekStartMs }.takeLast(MAX_WEEKS))
        }

        /**
         * Whether a sample should be appended now: always for the first one,
         * otherwise only once at least half the cadence has elapsed since the last.
         * Pure & testable — defends against a boot/manual double-arm racing the
         * alarm into a double write.
         */
        fun shouldAppend(lastMs: Long?, nowMs: Long, intervalMs: Long): Boolean =
            lastMs == null || nowMs - lastMs >= intervalMs / 2
    }
}
