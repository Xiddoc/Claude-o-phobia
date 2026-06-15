package com.xiddoc.claudeophobia.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val ClaudeDarkColors = darkColorScheme(
    primary = ClaudeClay,
    onPrimary = Color(0xFF1A0D07),
    primaryContainer = SurfaceBright,
    onPrimaryContainer = ClaudeClayBright,
    secondary = WarmGrey,
    onSecondary = Color(0xFF1A1815),
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnBackground,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceMuted,
    error = DangerRed,
    onError = Color(0xFF1A0D07),
    outline = TrackColor,
)

@Composable
fun ClaudeophobiaTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }
    MaterialTheme(
        colorScheme = ClaudeDarkColors,
        typography = Typography(),
        content = content,
    )
}
