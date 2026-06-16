package com.xiddoc.claudeophobia.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
    val rootEnabled: Boolean = false,
    val claudePackage: String = RootUsageReader.DEFAULT_PACKAGE,
)

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
            rootEnabled = prefs[KEY_ROOT_ENABLED] ?: false,
            claudePackage = prefs[KEY_CLAUDE_PKG] ?: RootUsageReader.DEFAULT_PACKAGE,
        )
    }

    suspend fun updateResetConfig(config: ResetConfig) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DAY] = config.dayOfWeek.value
            prefs[KEY_HOUR] = config.time.hour
            prefs[KEY_MINUTE] = config.time.minute
            prefs[KEY_ZONE] = config.zone.id
        }
    }

    suspend fun setRootEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_ROOT_ENABLED] = enabled }
    }

    suspend fun setClaudePackage(pkg: String) {
        context.dataStore.edit { it[KEY_CLAUDE_PKG] = pkg.trim() }
    }

    companion object {
        private val KEY_DAY = intPreferencesKey("reset_day_of_week")
        private val KEY_HOUR = intPreferencesKey("reset_hour")
        private val KEY_MINUTE = intPreferencesKey("reset_minute")
        private val KEY_ZONE = stringPreferencesKey("reset_zone")
        private val KEY_ROOT_ENABLED = booleanPreferencesKey("root_enabled")
        private val KEY_CLAUDE_PKG = stringPreferencesKey("claude_package")
    }
}
