package com.xiddoc.claudeophobia

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.xiddoc.claudeophobia.notify.NudgeScheduler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.xiddoc.claudeophobia.ui.MainViewModel
import com.xiddoc.claudeophobia.ui.screens.AboutScreen
import com.xiddoc.claudeophobia.ui.screens.CountdownScreen
import com.xiddoc.claudeophobia.ui.screens.SettingsScreen
import com.xiddoc.claudeophobia.ui.theme.ClaudeophobiaTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Arm the once-a-day pacing nudge (no-op if already scheduled) and ask
        // for notification permission on Android 13+ so it can actually show.
        NudgeScheduler.ensureScheduled(applicationContext)
        maybeRequestNotificationPermission()
        setContent {
            ClaudeophobiaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    var screen by remember { mutableStateOf(Screen.Home) }
                    // System back returns to the home screen instead of exiting.
                    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
                    AnimatedContent(
                        targetState = screen,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "screen",
                    ) { current ->
                        when (current) {
                            Screen.Settings -> SettingsScreen(
                                viewModel = viewModel,
                                onBack = { screen = Screen.Home },
                            )

                            Screen.About -> AboutScreen(
                                viewModel = viewModel,
                                onBack = { screen = Screen.Home },
                            )

                            Screen.Home -> CountdownScreen(
                                viewModel = viewModel,
                                onOpenSettings = { screen = Screen.Settings },
                                onOpenAbout = { screen = Screen.About },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun maybeRequestNotificationPermission() {
        // minSdk 34, so POST_NOTIFICATIONS is always a runtime permission.
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** Top-level destinations the single activity swaps between. */
private enum class Screen { Home, Settings, About }
