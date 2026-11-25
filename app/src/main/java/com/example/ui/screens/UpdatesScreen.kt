package com.example.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.R
import com.example.connectivity.UpdateCheckResult
import com.example.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesScreen(
  viewModel: MainViewModel,
  onBack: () -> Unit,
) {
  val result by viewModel.updateCheckResult.collectAsStateWithLifecycle()
  val isChecking by viewModel.isCheckingUpdates.collectAsStateWithLifecycle()
  val isOnline = viewModel.internetManager.isInternetAvailable()

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.updates_title)) },
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("updates_back_button")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back_cd))
          }
        },
      )
    }
  ) { innerPadding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(innerPadding)
          .verticalScroll(rememberScrollState())
          .padding(20.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Column(
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Surface(
          shape = RoundedCornerShape(20.dp),
          color = MaterialTheme.colorScheme.surface,
          tonalElevation = 1.dp,
          modifier =
            Modifier.fillMaxWidth()
              .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)),
        ) {
          Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
              Text(stringResource(R.string.updates_installed_version), style = MaterialTheme.typography.titleMedium)
            }
            Text(
              text = "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
              style = MaterialTheme.typography.headlineMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
              text = if (isOnline) "Network: Online" else "Network: Offline (Airplane / Local Mode)",
              style = MaterialTheme.typography.labelSmall,
              color = if (isOnline) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        Button(
          onClick = { viewModel.checkUpdates() },
          enabled = !isChecking,
          modifier = Modifier.fillMaxWidth().height(52.dp).testTag("updates_check_button"),
        ) {
          if (isChecking) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(end = 8.dp).size(20.dp))
          } else {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
          }
          Text(stringResource(R.string.updates_check_button))
        }

        Surface(
          shape = RoundedCornerShape(16.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (val res = result) {
              UpdateCheckResult.NotChecked -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                  Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                  Text("Tap Check Now to verify against your configured update manifest.", style = MaterialTheme.typography.bodyMedium)
                }
              }
              UpdateCheckResult.Offline -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                  Icon(Icons.Default.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                  Text(stringResource(R.string.updates_offline_status), style = MaterialTheme.typography.bodyMedium)
                }
              }
              UpdateCheckResult.NotConfigured -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                  Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                  Text(stringResource(R.string.updates_not_configured), style = MaterialTheme.typography.bodyMedium)
                }
              }
              is UpdateCheckResult.Checked -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                  Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                  Text(
                    text =
                      if (res.updateAvailable)
                        "Update available: v${res.latestVersionName} (Build ${res.latestVersionCode})"
                      else "You are running the latest version (v${res.latestVersionName}).",
                    style = MaterialTheme.typography.titleMedium,
                  )
                }
                if (res.releaseNotes.isNotBlank()) {
                  Text(res.releaseNotes, style = MaterialTheme.typography.bodyMedium)
                }
              }
              is UpdateCheckResult.Failed -> {
                Text("Update check error: ${res.reason}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = "Update results are displayed exclusively on this screen. No system notifications are used.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
