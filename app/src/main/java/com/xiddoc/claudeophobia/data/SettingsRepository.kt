package com.xiddoc.claudeophobia.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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
    /** Whether the 5-hour rolling window card is shown on the home screen. */
    val fiveHourWindowEnabled: Boolean = true,
    /**
     * How often, in minutes, to refresh live usage from Claude while the app is
     * open. The widget and daily nudge reuse the cached figure, so this only
     * governs the in-app background cadence. Defaults to every 15 minutes.
     */
    val syncIntervalMinutes: Int = DEFAULT_SYNC_INTERVAL_MINUTES,
    /**
     * How many pacing nudges to post across each week. The daily alarm still wakes
     * once a day; this just thins which days actually notify, evenly spread (see
     * [WeeklyNudges.shouldNudgeOn]). 7 means every day (the original behaviour),
     * 1 means once a week. Defaults to every day.
     */
    val notificationsPerWeek: Int = DEFAULT_NOTIFICATIONS_PER_WEEK,
    /**
     * Whether the home-screen widget shows a pacing cue — a faint "where you
     * should be" marker on the bar plus an ahead/under-pace verdict in the
     * caption — comparing live usage against how much of the week has elapsed.
     */
    val widgetPacingEnabled: Boolean = true,
    /**
     * The Claude session cookie header the LSPosed module captured from inside
     * the Claude process and handed to us. The app forwards it to claude.ai to
     * read live usage (the same call the app makes). Null until the module has
     * captured it at least once.
     */
    val cookieHeader: String? = null,
    /** The Claude organization id the module captured alongside the cookie. */
    val orgId: String? = null,
    /**
     * The Claude app's own wire `User-Agent`, captured in-process by the module.
     * We replay it verbatim so the forwarded `cf_clearance` cookie stays valid for
     * Cloudflare (it's bound to this UA). Null until the module has captured it.
     */
    val userAgent: String? = null,
    /** The Claude app version the module captured (for the `Anthropic-Client-Version` header). */
    val clientVersion: String? = null,
    /** When the module last refreshed the credentials (epoch millis), 0 if never. */
    val credentialsCapturedAtMs: Long = 0L,
    /**
     * Last live weekly utilization (0..100) from a successful fetch, cached so the
     * widget and the daily nudge can show a real figure without hitting Claude.
     */
    val lastWeeklyUsagePercent: Double? = null,
    /** When [lastWeeklyUsagePercent] was captured (epoch millis), or 0 if never. */
    val lastUsageTimestampMs: Long = 0L,
    /**
     * Whether the every-3-hours weekly-progress sampler runs. When off, the alarm
     * still re-arms (so re-enabling needs no app launch) but records nothing. The
     * sampler only ever stores the already-cached live figure — no extra battery
     * or network — so this defaults on.
     */
    val historySamplingEnabled: Boolean = true,
    /**
     * Curve smoothing for the progress graph, fed straight into
     * [GraphMath.smoothPath] as the Catmull-Rom tension. 0 = straight polyline,
     * higher = curvier. Defaults to a slight Bezier curve.
     */
    val graphCurveTension: Float = GraphMath.TENSION_SLIGHT,
    /** Whether the "gained per day" derivative overlay is drawn on the graph. */
    val showDerivative: Boolean = true,
    /**
     * Whether the graph widget's bitmap gets the warm LED bloom. Off by default:
     * the blur is the heaviest part of a bitmap render and the graph's fill is
     * wide, so we keep the widget battery-light unless the user opts in.
     */
    val graphWidgetGlow: Boolean = false,
    /** Whether the circular widget's arc gets the glow (on, for parity with the in-app ring). */
    val circleWidgetGlow: Boolean = true,
) {
    /** Whether the module has handed us a usable session cookie yet. */
    val hasCredentials: Boolean get() = !cookieHeader.isNullOrBlank()

    companion object {
        /** Default in-app refresh cadence, in minutes. */
        const val DEFAULT_SYNC_INTERVAL_MINUTES = 15

        /** The cadences offered in Settings, in minutes. */
        val SYNC_INTERVAL_OPTIONS = listOf(5, 15, 30, 60)

        /** Default nudge frequency: one a day, matching the original behaviour. */
        const val DEFAULT_NOTIFICATIONS_PER_WEEK = 7

        /** The per-week nudge counts offered in Settings (1..7). */
        val NOTIFICATIONS_PER_WEEK_OPTIONS = listOf(1, 2, 3, 5, 7)

        /** Graph curve-tension presets surfaced in Settings: Straight / Slight / Smooth. */
        val CURVE_TENSION_OPTIONS = listOf(
            GraphMath.TENSION_STRAIGHT,
            GraphMath.TENSION_SLIGHT,
            GraphMath.TENSION_SMOOTH,
        )
    }
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
            fiveHourWindowEnabled = prefs[KEY_FIVE_HOUR_ENABLED] ?: true,
            syncIntervalMinutes = prefs[KEY_SYNC_INTERVAL]
                ?: AppSettings.DEFAULT_SYNC_INTERVAL_MINUTES,
            notificationsPerWeek = prefs[KEY_NOTIFS_PER_WEEK]
                ?: AppSettings.DEFAULT_NOTIFICATIONS_PER_WEEK,
            widgetPacingEnabled = prefs[KEY_WIDGET_PACING] ?: true,
            cookieHeader = prefs[KEY_COOKIE],
            orgId = prefs[KEY_ORG],
            userAgent = prefs[KEY_USER_AGENT],
            clientVersion = prefs[KEY_CLIENT_VERSION],
            credentialsCapturedAtMs = prefs[KEY_CRED_AT] ?: 0L,
            lastWeeklyUsagePercent = prefs[KEY_LAST_WEEKLY_PCT],
            lastUsageTimestampMs = prefs[KEY_LAST_USAGE_TS] ?: 0L,
            historySamplingEnabled = prefs[KEY_HISTORY_SAMPLING] ?: true,
            graphCurveTension = prefs[KEY_GRAPH_TENSION] ?: GraphMath.TENSION_SLIGHT,
            showDerivative = prefs[KEY_SHOW_DERIVATIVE] ?: true,
            graphWidgetGlow = prefs[KEY_GRAPH_GLOW] ?: false,
            circleWidgetGlow = prefs[KEY_CIRCLE_GLOW] ?: true,
        )
    }

    /**
     * Stores the session credentials the LSPosed module captured. Called from the
     * [UsageProvider] when the module publishes into our process.
     */
    suspend fun publishCredentials(
        cookieHeader: String,
        orgId: String?,
        userAgent: String? = null,
        clientVersion: String? = null,
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        context.dataStore.edit { prefs ->
            prefs[KEY_COOKIE] = cookieHeader
            if (!orgId.isNullOrBlank()) prefs[KEY_ORG] = orgId else prefs.remove(KEY_ORG)
            if (!userAgent.isNullOrBlank()) prefs[KEY_USER_AGENT] = userAgent else prefs.remove(KEY_USER_AGENT)
            if (!clientVersion.isNullOrBlank()) prefs[KEY_CLIENT_VERSION] = clientVersion else prefs.remove(KEY_CLIENT_VERSION)
            prefs[KEY_CRED_AT] = timestampMs
        }
    }

    /** Persists the most recent successful live read for the widget & nudge. */
    suspend fun cacheLiveUsage(
        weeklyPercent: Double?,
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        context.dataStore.edit { prefs ->
            if (weeklyPercent != null) {
                prefs[KEY_LAST_WEEKLY_PCT] = weeklyPercent
                prefs[KEY_LAST_USAGE_TS] = timestampMs
            } else {
                prefs.remove(KEY_LAST_WEEKLY_PCT)
                prefs.remove(KEY_LAST_USAGE_TS)
            }
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

    suspend fun setFiveHourWindowEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_FIVE_HOUR_ENABLED] = enabled }
    }

    /** Sets how often live usage is refreshed while the app is open, in minutes. */
    suspend fun setSyncIntervalMinutes(minutes: Int) {
        context.dataStore.edit { it[KEY_SYNC_INTERVAL] = minutes.coerceAtLeast(1) }
    }

    /** Sets how many pacing nudges to post per week (clamped to 0..7). */
    suspend fun setNotificationsPerWeek(perWeek: Int) {
        context.dataStore.edit { it[KEY_NOTIFS_PER_WEEK] = perWeek.coerceIn(0, 7) }
    }

    suspend fun setWidgetPacingEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_WIDGET_PACING] = enabled }
    }

    /** Pauses/resumes the 3-hour weekly-progress sampler. */
    suspend fun setHistorySamplingEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_HISTORY_SAMPLING] = enabled }
    }

    /** Sets the progress-graph curve tension (clamped to a sane 0..1). */
    suspend fun setGraphCurveTension(tension: Float) {
        context.dataStore.edit { it[KEY_GRAPH_TENSION] = tension.coerceIn(0f, 1f) }
    }

    /** Toggles the "gained per day" derivative overlay on the graph. */
    suspend fun setShowDerivative(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SHOW_DERIVATIVE] = enabled }
    }

    /** Toggles the warm glow on the graph widget bitmap. */
    suspend fun setGraphWidgetGlow(enabled: Boolean) {
        context.dataStore.edit { it[KEY_GRAPH_GLOW] = enabled }
    }

    /** Toggles the warm glow on the circular widget arc. */
    suspend fun setCircleWidgetGlow(enabled: Boolean) {
        context.dataStore.edit { it[KEY_CIRCLE_GLOW] = enabled }
    }

    companion object {
        private val KEY_DAY = intPreferencesKey("reset_day_of_week")
        private val KEY_HOUR = intPreferencesKey("reset_hour")
        private val KEY_MINUTE = intPreferencesKey("reset_minute")
        private val KEY_ZONE = stringPreferencesKey("reset_zone")
        private val KEY_LIVE_ENABLED = booleanPreferencesKey("live_usage_enabled")
        private val KEY_FIVE_HOUR_ENABLED = booleanPreferencesKey("five_hour_window_enabled")
        private val KEY_SYNC_INTERVAL = intPreferencesKey("sync_interval_minutes")
        private val KEY_NOTIFS_PER_WEEK = intPreferencesKey("notifications_per_week")
        private val KEY_WIDGET_PACING = booleanPreferencesKey("widget_pacing_enabled")
        private val KEY_COOKIE = stringPreferencesKey("live_cookie_header")
        private val KEY_ORG = stringPreferencesKey("live_org_id")
        private val KEY_USER_AGENT = stringPreferencesKey("live_user_agent")
        private val KEY_CLIENT_VERSION = stringPreferencesKey("live_client_version")
        private val KEY_CRED_AT = longPreferencesKey("live_cred_captured_at")
        private val KEY_LAST_WEEKLY_PCT = doublePreferencesKey("last_weekly_pct")
        private val KEY_LAST_USAGE_TS = longPreferencesKey("last_usage_ts")
        private val KEY_HISTORY_SAMPLING = booleanPreferencesKey("history_sampling_enabled")
        private val KEY_GRAPH_TENSION = floatPreferencesKey("graph_curve_tension")
        private val KEY_SHOW_DERIVATIVE = booleanPreferencesKey("graph_show_derivative")
        private val KEY_GRAPH_GLOW = booleanPreferencesKey("graph_widget_glow")
        private val KEY_CIRCLE_GLOW = booleanPreferencesKey("circle_widget_glow")
    }
}
