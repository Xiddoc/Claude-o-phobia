package com.xiddoc.claudeophobia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ClaudeophobiaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    var showSettings by remember { mutableStateOf(false) }
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
}
