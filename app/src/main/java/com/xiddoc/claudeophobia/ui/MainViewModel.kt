package com.xiddoc.claudeophobia.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiddoc.claudeophobia.data.AppSettings
import com.xiddoc.claudeophobia.data.LiveUsage
import com.xiddoc.claudeophobia.data.ModuleStatus
import com.xiddoc.claudeophobia.data.ResetConfig
import com.xiddoc.claudeophobia.data.SettingsRepository
import com.xiddoc.claudeophobia.data.UsageResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application)

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

        // The LSPosed module pushes fresh usage into our DataStore, so we just
        // mirror whatever the latest settings say — no requests of our own.
        viewModelScope.launch {
            settings.collect { _usageResult.value = resolve(it) }
        }
    }

    /** Re-evaluates the live-usage state (e.g. after the user taps Refresh). */
    fun refreshUsage() {
        _usageResult.value = resolve(settings.value)
    }

    private fun resolve(s: AppSettings): UsageResult = LiveUsage.resolve(
        enabled = s.liveUsageEnabled,
        moduleActive = ModuleStatus.isActive(),
        capturedAtMs = s.liveCapturedAtMs,
        ok = s.liveOk,
        error = s.liveError,
        snapshot = s.liveSnapshot,
    )

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
}
