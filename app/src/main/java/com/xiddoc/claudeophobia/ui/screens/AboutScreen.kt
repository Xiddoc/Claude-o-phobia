package com.xiddoc.claudeophobia.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiddoc.claudeophobia.data.UpdateChecker
import com.xiddoc.claudeophobia.data.UpdateStatus
import com.xiddoc.claudeophobia.ui.MainViewModel
import com.xiddoc.claudeophobia.ui.components.InfoCard
import com.xiddoc.claudeophobia.ui.components.StatRow
import com.xiddoc.claudeophobia.ui.theme.ClaudeClay
import com.xiddoc.claudeophobia.ui.theme.OnSurfaceMuted
import com.xiddoc.claudeophobia.ui.theme.SuccessGreen

@Composable
fun AboutScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val updateStatus by viewModel.updateStatus.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    // Quietly check for a newer release the moment the screen opens.
    LaunchedEffect(Unit) { viewModel.checkForUpdate() }

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
                    text = "About",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            Spacer(Modifier.height(24.dp))

            // Hero: app name + the little signature.
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Claude-o-phobia ⏳",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Counting down to your next fresh week of Claude.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceMuted,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "With love, Xiddoc",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = ClaudeClay,
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Outlined.Favorite,
                        contentDescription = null,
                        tint = ClaudeClay,
                        modifier = Modifier.width(20.dp),
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            // ---- Version + update check ----------------------------------
            VersionCard(
                currentVersion = viewModel.appVersionName,
                status = updateStatus,
                onCheck = viewModel::checkForUpdate,
                onDownload = { url -> uriHandler.openUri(url) },
            )

            Spacer(Modifier.height(16.dp))

            // ---- Source code ---------------------------------------------
            InfoCard(title = "Source code") {
                Text(
                    text = "Claude-o-phobia is open source — peek at how it works, file an " +
                        "issue, or grab a build from the Releases tab.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { uriHandler.openUri(UpdateChecker.REPO_URL) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Text("View on GitHub", color = ClaudeClay)
                }
            }

            Spacer(Modifier.height(28.dp))

            Text(
                text = "Made with care (and Claude). Thanks for everything. 🧡",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun VersionCard(
    currentVersion: String,
    status: UpdateStatus,
    onCheck: () -> Unit,
    onDownload: (String) -> Unit,
) {
    val checking = status is UpdateStatus.Checking
    InfoCard(
        title = "Version",
        actionLabel = "Check",
        onAction = onCheck,
        actionBusy = checking,
    ) {
        StatRow(label = "Installed", value = "v$currentVersion")
        Spacer(Modifier.height(12.dp))
        when (status) {
            UpdateStatus.Idle,
            UpdateStatus.Checking -> Text(
                text = "Checking GitHub for a newer build…",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceMuted,
            )

            is UpdateStatus.UpToDate -> Text(
                text = "You're on the latest release. 🎉",
                style = MaterialTheme.typography.bodyMedium,
                color = SuccessGreen,
            )

            is UpdateStatus.Available -> Column {
                Text(
                    text = "A new version (v${status.version}) is available.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { onDownload(status.downloadUrl) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Text("Download the update", color = ClaudeClay)
                }
            }

            is UpdateStatus.Failed -> Text(
                text = "Couldn't check for updates: ${status.message}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
