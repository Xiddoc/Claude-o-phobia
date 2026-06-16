package com.xiddoc.claudeophobia.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiddoc.claudeophobia.data.ResetConfig
import com.xiddoc.claudeophobia.ui.MainViewModel
import com.xiddoc.claudeophobia.ui.components.InfoCard
import com.xiddoc.claudeophobia.ui.theme.ClaudeClay
import com.xiddoc.claudeophobia.ui.theme.OnSurfaceMuted
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val config = settings.resetConfig

    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            // Header.
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            Spacer(Modifier.height(16.dp))

            // ---- Reset schedule ------------------------------------------
            InfoCard(title = "Weekly reset day") {
                DayPicker(
                    selected = config.dayOfWeek,
                    onSelected = { viewModel.updateResetConfig(config.copy(dayOfWeek = it)) },
                )
            }

            Spacer(Modifier.height(16.dp))

            InfoCard(title = "Reset time") {
                TimeStepper(
                    time = config.time,
                    onChange = { viewModel.updateResetConfig(config.copy(time = it)) },
                )
            }

            Spacer(Modifier.height(16.dp))

            InfoCard(title = "Time zone") {
                ZonePicker(
                    selected = config.zone,
                    onSelected = { viewModel.updateResetConfig(config.copy(zone = it)) },
                )
            }

            Spacer(Modifier.height(16.dp))

            // ---- Live usage (LSPosed) ------------------------------------
            InfoCard(title = "Live usage via LSPosed") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Read my real Claude usage",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = settings.liveUsageEnabled,
                        onCheckedChange = { viewModel.setLiveUsageEnabled(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = ClaudeClay),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "An LSPosed module runs inside the Claude app and hands this " +
                        "app your Claude session, which it uses to ask claude.ai for your " +
                        "live usage — the same call the app makes. The session stays on " +
                        "your device.\n\n" +
                        "Setup: enable the Claude-o-phobia module in the LSPosed manager, " +
                        "tick both Claude-o-phobia and Claude in its scope, reboot, then " +
                        "open Claude once so it can hand over your session.\n\n" +
                        "Trouble? The whole flow is traced to logcat — filter by the tag " +
                        "ClaudeUsage (secret values are redacted, never logged in full).",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMuted,
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Default: Thursday 08:00 UTC — when the weekly limit rolls over. " +
                    "Set it to match your own reset and the countdown follows.",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPicker(
    selected: DayOfWeek,
    onSelected: (DayOfWeek) -> Unit,
) {
    Column {
        // Two rows of chips so labels stay readable.
        val days = DayOfWeek.entries
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            days.take(4).forEach { day -> DayChip(day, selected, onSelected, Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            days.drop(4).forEach { day -> DayChip(day, selected, onSelected, Modifier.weight(1f)) }
            // Pad the last row to keep equal widths.
            repeat(4 - days.drop(4).size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayChip(
    day: DayOfWeek,
    selected: DayOfWeek,
    onSelected: (DayOfWeek) -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = day == selected,
        onClick = { onSelected(day) },
        label = {
            Text(
                day.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = ClaudeClay,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

@Composable
private fun TimeStepper(
    time: LocalTime,
    onChange: (LocalTime) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Stepper(
            value = time.hour,
            label = "hour",
            onMinus = { onChange(time.minusHours(1)) },
            onPlus = { onChange(time.plusHours(1)) },
        )
        Text(
            text = ":",
            fontFamily = FontFamily.Monospace,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = OnSurfaceMuted,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Stepper(
            value = time.minute,
            label = "min",
            step = 5,
            onMinus = { onChange(time.minusMinutes(5)) },
            onPlus = { onChange(time.plusMinutes(5)) },
        )
    }
}

@Composable
private fun Stepper(
    value: Int,
    label: String,
    step: Int = 1,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton(Icons.Outlined.Remove, "minus $label", onMinus)
            Text(
                text = value.toString().padStart(2, '0'),
                fontFamily = FontFamily.Monospace,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(64.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            StepButton(Icons.Outlined.Add, "plus $label", onPlus)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = OnSurfaceMuted)
    }
}

@Composable
private fun StepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
    ) {
        Icon(icon, contentDescription = description, tint = ClaudeClay)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZonePicker(
    selected: ZoneId,
    onSelected: (ZoneId) -> Unit,
) {
    val zones = remember {
        linkedSetOf(
            "UTC",
            ZoneId.systemDefault().id,
            "America/Los_Angeles",
            "America/New_York",
            "Europe/London",
            "Europe/Berlin",
            "Asia/Jerusalem",
            "Asia/Kolkata",
            "Asia/Tokyo",
            "Australia/Sydney",
        ).toList()
    }
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selected.id,
            onValueChange = {},
            readOnly = true,
            label = { Text("Zone") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            zones.forEach { id ->
                DropdownMenuItem(
                    text = { Text(id) },
                    onClick = {
                        onSelected(ZoneId.of(id))
                        expanded = false
                    },
                )
            }
        }
    }
}
