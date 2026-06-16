package com.xiddoc.claudeophobia.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiddoc.claudeophobia.data.AppSettings
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
                delay(BACKGROUND_REFRESH_MS)
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
            if (_usageResult.value !is UsageResult.Found) {
                _usageResult.value = UsageResult.Idle
            }
            val result = reader.read(current, ModuleStatus.isActive())
            _usageResult.value = result
            if (result is UsageResult.Found) {
                // Cache for the widget + daily nudge so neither makes its own
                // request, and push the fresh figure to any placed widget.
                repository.cacheLiveUsage(result.snapshot.weeklyUtilizationPercent)
                UsageWidget.refresh(getApplication<Application>())
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

    companion object {
        // Gentle background cadence so we don't hammer Claude for usage. The
        // widget and daily nudge reuse the cached figure rather than refetch.
        private const val BACKGROUND_REFRESH_MS = 15 * 60 * 1000L
    }
}
