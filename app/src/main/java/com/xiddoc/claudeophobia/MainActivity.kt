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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
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
import com.xiddoc.claudeophobia.ui.screens.HistoryScreen
import com.xiddoc.claudeophobia.ui.screens.SettingsScreen
import com.xiddoc.claudeophobia.ui.theme.ClaudeophobiaTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Arm the once-a-day pacing nudge (no-op if already scheduled), reconcile
        // the progress sampler alarm with its on/off setting, and ask for
        // notification permission on Android 13+ so the nudge can actually show.
        NudgeScheduler.ensureScheduled(applicationContext)
        viewModel.ensureHistorySamplerScheduled()
        maybeRequestNotificationPermission()

        // Run the live countdown tick and the periodic usage sync only while the
        // app is actually in the foreground. repeatOnLifecycle(STARTED) cancels
        // both when we're backgrounded and restarts them on return, so a stopped
        // app never burns battery ticking every second or waking the radio to
        // poll Claude. (The widget and daily nudge keep working off the cache.)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.runClock() }
                launch { viewModel.runPeriodicSync() }
            }
        }

        setContent {
            ClaudeophobiaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val startScreen = if (intent?.action == ACTION_OPEN_HISTORY) {
                        Screen.History
                    } else {
                        Screen.Home
                    }
                    var screen by remember { mutableStateOf(startScreen) }
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

                            Screen.History -> HistoryScreen(
                                viewModel = viewModel,
                                onBack = { screen = Screen.Home },
                            )

                            Screen.Home -> CountdownScreen(
                                viewModel = viewModel,
                                onOpenSettings = { screen = Screen.Settings },
                                onOpenAbout = { screen = Screen.About },
                                onOpenHistory = { screen = Screen.History },
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

    companion object {
        /** Deep-link action the graph widget uses to open straight to the history screen. */
        const val ACTION_OPEN_HISTORY = "com.xiddoc.claudeophobia.OPEN_HISTORY"
    }
}

/** Top-level destinations the single activity swaps between. */
private enum class Screen { Home, Settings, About, History }
