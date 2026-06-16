package com.xiddoc.claudeophobia.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiddoc.claudeophobia.data.AppSettings
import com.xiddoc.claudeophobia.data.ClaudePrefs
import com.xiddoc.claudeophobia.data.LiveUsageReader
import com.xiddoc.claudeophobia.data.ModuleStatus
import com.xiddoc.claudeophobia.data.ResetConfig
import com.xiddoc.claudeophobia.data.SettingsRepository
import com.xiddoc.claudeophobia.data.UsageLog
import com.xiddoc.claudeophobia.data.UsageResult
import com.xiddoc.claudeophobia.widget.UsageWidget
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application)
    private val reader = LiveUsageReader()

    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** Ticks once per second so the countdown stays live. */
    private val _now = MutableStateFlow(Instant.now())
    val now: StateFlow<Instant> = _now.asStateFlow()

    private val _usageResult = MutableStateFlow<UsageResult>(UsageResult.Idle)
    val usageResult: StateFlow<UsageResult> = _usageResult.asStateFlow()

    /**
     * True while a refresh/retry is in flight. Surfaced in the UI as a spinner so
     * a tap on Retry is always acknowledged — even when the read resolves
     * instantly (e.g. no credentials yet) and the resulting card looks unchanged.
     */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
        // Live clock.
        viewModelScope.launch {
            while (true) {
                _now.value = Instant.now()
                delay(1_000)
            }
        }

        // Refresh whenever the toggle flips or the module hands over fresher
        // credentials, and on a gentle background cadence while enabled.
        viewModelScope.launch {
            settings
                .map { Triple(it.liveUsageEnabled, it.credentialsCapturedAtMs, it.cookieHeader) }
                .distinctUntilChanged()
                .collect { (enabled, _, _) ->
                    if (enabled) refreshUsage() else _usageResult.value = UsageResult.Disabled
                }
        }
        viewModelScope.launch {
            while (true) {
                val intervalMs = settings.value.syncIntervalMinutes
                    .coerceAtLeast(1) * 60_000L
                delay(intervalMs)
                if (settings.value.liveUsageEnabled) refreshUsage()
            }
        }
    }

    /** Re-fetches live usage from Claude using the module-supplied credentials. */
    fun refreshUsage() {
        val current = settings.value
        if (!current.liveUsageEnabled) {
            _usageResult.value = UsageResult.Disabled
            return
        }
        viewModelScope.launch {
            UsageLog.d("refreshUsage(): triggered")
            _refreshing.value = true
            val startedAt = System.currentTimeMillis()
            try {
                if (_usageResult.value !is UsageResult.Found) {
                    _usageResult.value = UsageResult.Idle
                }
                val rebootNeeded = ModuleStatus.rebootNeededSinceUpdate(getApplication())
                val result = reader.read(current, rebootNeeded)
                _usageResult.value = result
                if (result is UsageResult.Found) {
                    // Cache for the widget + daily nudge so neither makes its own
                    // request, and push the fresh figure to any placed widget.
                    repository.cacheLiveUsage(result.snapshot.weeklyUtilizationPercent)
                    UsageWidget.refresh(getApplication<Application>())
                }
            } finally {
                // Hold the spinner just long enough to read as a deliberate retry
                // even when the read returns immediately (e.g. no credentials).
                val elapsed = System.currentTimeMillis() - startedAt
                if (elapsed < MIN_SPINNER_MS) delay(MIN_SPINNER_MS - elapsed)
                _refreshing.value = false
            }
        }
    }

    fun updateResetConfig(config: ResetConfig) {
        viewModelScope.launch { repository.updateResetConfig(config) }
    }

    /** Aligns the manual countdown to a real reset instant from the live data. */
    fun syncResetTo(epochMillis: Long) {
        val zoned = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.of("UTC"))
        updateResetConfig(
            ResetConfig(
                dayOfWeek = zoned.dayOfWeek,
                time = LocalTime.of(zoned.hour, zoned.minute),
                zone = ZoneId.of("UTC"),
            )
        )
    }

    fun setLiveUsageEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setLiveUsageEnabled(enabled) }
    }

    fun setFiveHourWindowEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setFiveHourWindowEnabled(enabled) }
    }

    /** Updates how often live usage is refreshed while the app is open. */
    fun setSyncIntervalMinutes(minutes: Int) {
        viewModelScope.launch { repository.setSyncIntervalMinutes(minutes) }
    }

    fun setWidgetPacingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setWidgetPacingEnabled(enabled)
            // Re-render any placed widget so the cue appears/disappears at once.
            UsageWidget.refresh(getApplication())
        }
    }

    /**
     * Launches the Claude app. Opening it makes the module re-run its capture
     * (on Application start / activity resume), which is how a session reaches us
     * in the first place — so this doubles as a "hand over my session now" button.
     */
    fun openClaude() {
        val app = getApplication<Application>()
        val intent = app.packageManager.getLaunchIntentForPackage(ClaudePrefs.CLAUDE_PACKAGE)
        if (intent == null) {
            UsageLog.w("openClaude(): Claude app not installed or not visible to us")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(intent) }
            .onFailure { UsageLog.e("openClaude(): failed to launch Claude", it) }
    }

    companion object {
        // Minimum time the retry spinner stays up, so an instant result still
        // gives the tap visible feedback.
        private const val MIN_SPINNER_MS = 600L
    }
}
