package com.xiddoc.claudeophobia.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** All user-tweakable settings, kept together for a single observable stream. */
data class AppSettings(
    val resetConfig: ResetConfig = ResetConfig(),
    val liveUsageEnabled: Boolean = false,
    /**
     * The last usage the LSPosed module captured from inside the Claude app and
     * published to us. It carries only non-secret figures (percentages + reset
     * times) — the session cookie never leaves the Claude process.
     */
    val liveSnapshot: UsageSnapshot = UsageSnapshot(),
    /** When [liveSnapshot] / [liveError] was published (epoch millis), 0 if never. */
    val liveCapturedAtMs: Long = 0L,
    /** Whether the most recent capture succeeded. */
    val liveOk: Boolean = false,
    /** Failure detail from the most recent capture, if it failed. */
    val liveError: String? = null,
) {
    /** Weekly utilization (0..100) from the last capture, for the widget & nudge. */
    val lastWeeklyUsagePercent: Double? get() = liveSnapshot.weeklyUtilizationPercent

    /** When the cached figure was captured, or 0 if never. */
    val lastUsageTimestampMs: Long get() = liveCapturedAtMs
}

class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            resetConfig = ResetConfig(
                dayOfWeek = prefs[KEY_DAY]?.let { DayOfWeek.of(it) } ?: DayOfWeek.THURSDAY,
                time = LocalTime.of(
                    prefs[KEY_HOUR] ?: 8,
                    prefs[KEY_MINUTE] ?: 0,
                ),
                zone = prefs[KEY_ZONE]?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                    ?: ZoneId.of("UTC"),
            ),
            liveUsageEnabled = prefs[KEY_LIVE_ENABLED] ?: false,
            liveSnapshot = UsageSnapshot(
                weeklyUtilizationPercent = prefs[KEY_WEEKLY_PCT],
                weeklyResetEpochMs = prefs[KEY_WEEKLY_RESET],
                fiveHourUtilizationPercent = prefs[KEY_FIVE_PCT],
                fiveHourResetEpochMs = prefs[KEY_FIVE_RESET],
                sourceLabel = prefs[KEY_SOURCE],
            ),
            liveCapturedAtMs = prefs[KEY_CAPTURED_AT] ?: 0L,
            liveOk = prefs[KEY_LIVE_OK] ?: false,
            liveError = prefs[KEY_LIVE_ERROR],
        )
    }

    /** Stores a successful capture published by the LSPosed module. */
    suspend fun publishUsage(
        snapshot: UsageSnapshot,
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LIVE_OK] = true
            prefs.remove(KEY_LIVE_ERROR)
            prefs[KEY_CAPTURED_AT] = timestampMs
            prefs.setOrRemove(KEY_WEEKLY_PCT, snapshot.weeklyUtilizationPercent)
            prefs.setOrRemove(KEY_WEEKLY_RESET, snapshot.weeklyResetEpochMs)
            prefs.setOrRemove(KEY_FIVE_PCT, snapshot.fiveHourUtilizationPercent)
            prefs.setOrRemove(KEY_FIVE_RESET, snapshot.fiveHourResetEpochMs)
            prefs.setOrRemove(KEY_SOURCE, snapshot.sourceLabel)
        }
    }

    /** Records that the module tried to capture usage but failed. */
    suspend fun publishError(
        detail: String,
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LIVE_OK] = false
            prefs[KEY_LIVE_ERROR] = detail
            prefs[KEY_CAPTURED_AT] = timestampMs
        }
    }

    suspend fun updateResetConfig(config: ResetConfig) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DAY] = config.dayOfWeek.value
            prefs[KEY_HOUR] = config.time.hour
            prefs[KEY_MINUTE] = config.time.minute
            prefs[KEY_ZONE] = config.zone.id
        }
    }

    suspend fun setLiveUsageEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_LIVE_ENABLED] = enabled }
    }

    companion object {
        private val KEY_DAY = intPreferencesKey("reset_day_of_week")
        private val KEY_HOUR = intPreferencesKey("reset_hour")
        private val KEY_MINUTE = intPreferencesKey("reset_minute")
        private val KEY_ZONE = stringPreferencesKey("reset_zone")
        private val KEY_LIVE_ENABLED = booleanPreferencesKey("live_usage_enabled")
        private val KEY_WEEKLY_PCT = doublePreferencesKey("live_weekly_pct")
        private val KEY_WEEKLY_RESET = longPreferencesKey("live_weekly_reset_ms")
        private val KEY_FIVE_PCT = doublePreferencesKey("live_five_pct")
        private val KEY_FIVE_RESET = longPreferencesKey("live_five_reset_ms")
        private val KEY_SOURCE = stringPreferencesKey("live_source")
        private val KEY_CAPTURED_AT = longPreferencesKey("live_captured_at")
        private val KEY_LIVE_OK = booleanPreferencesKey("live_ok")
        private val KEY_LIVE_ERROR = stringPreferencesKey("live_error")
    }
}

/** Writes [value] under [key], or clears the key when [value] is null. */
private fun <T : Any> MutablePreferences.setOrRemove(key: Preferences.Key<T>, value: T?) {
    if (value != null) set(key, value) else remove(key)
}
