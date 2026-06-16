package com.xiddoc.claudeophobia.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiddoc.claudeophobia.data.AppSettings
import com.xiddoc.claudeophobia.data.ResetConfig
import com.xiddoc.claudeophobia.data.RootResult
import com.xiddoc.claudeophobia.data.RootUsageReader
import com.xiddoc.claudeophobia.data.SettingsRepository
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

    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** Ticks once per second so the countdown stays live. */
    private val _now = MutableStateFlow(Instant.now())
    val now: StateFlow<Instant> = _now.asStateFlow()

    private val _rootResult = MutableStateFlow<RootResult>(RootResult.Idle)
    val rootResult: StateFlow<RootResult> = _rootResult.asStateFlow()

    init {
        // Live clock.
        viewModelScope.launch {
            while (true) {
                _now.value = Instant.now()
                delay(1_000)
            }
        }

        // Refresh root usage when the toggle / package changes, and on a
        // gentle background cadence while enabled.
        viewModelScope.launch {
            settings
                .map { it.rootEnabled to it.claudePackage }
                .distinctUntilChanged()
                .collect { (enabled, _) ->
                    if (enabled) refreshRoot() else _rootResult.value = RootResult.Disabled
                }
        }
        viewModelScope.launch {
            while (true) {
                delay(BACKGROUND_REFRESH_MS)
                if (settings.value.rootEnabled) refreshRoot()
            }
        }
    }

    fun refreshRoot() {
        val current = settings.value
        if (!current.rootEnabled) {
            _rootResult.value = RootResult.Disabled
            return
        }
        viewModelScope.launch {
            if (_rootResult.value !is RootResult.Found) {
                _rootResult.value = RootResult.Idle
            }
            val result = RootUsageReader(current.claudePackage).read()
            _rootResult.value = result
            if (result is RootResult.Found) {
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

    fun setRootEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setRootEnabled(enabled) }
    }

    fun setClaudePackage(pkg: String) {
        viewModelScope.launch { repository.setClaudePackage(pkg) }
    }

    companion object {
        // Gentle background cadence so we don't hammer Claude for usage. The
        // widget and daily nudge reuse the cached figure rather than refetch.
        private const val BACKGROUND_REFRESH_MS = 15 * 60 * 1000L
    }
}
