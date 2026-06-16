package com.xiddoc.claudeophobia

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
                    var showSettings by remember { mutableStateOf(false) }
                    // System back leaves Settings instead of exiting the app.
                    BackHandler(enabled = showSettings) { showSettings = false }
                    AnimatedContent(
                        targetState = showSettings,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "screen",
                    ) { settings ->
                        if (settings) {
                            SettingsScreen(
                                viewModel = viewModel,
                                onBack = { showSettings = false },
                            )
                        } else {
                            CountdownScreen(
                                viewModel = viewModel,
                                onOpenSettings = { showSettings = true },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
