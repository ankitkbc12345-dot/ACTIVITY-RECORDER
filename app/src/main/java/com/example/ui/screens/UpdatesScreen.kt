package com.example.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.R
import com.example.connectivity.UpdateCheckResult
import com.example.ui.MainViewModel
import com.example.updates.ApkDownloadState

private val UpdateRed = Color(0xFFD32F2F)
private val UpdateUpToDateGreen = Color(0xFF2E7D32)

@Composable
fun rememberApkUpdateAction(viewModel: MainViewModel): () -> Unit {
  val context = LocalContext.current
  val launchInstaller: (Intent) -> Unit = { installIntent ->
    try {
      context.startActivity(installIntent)
    } catch (e: Exception) {
      viewModel.showBanner(e.message ?: "Unable to launch Android package installer.")
    }
  }
  val unknownAppsLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
      viewModel.onUnknownAppsPermissionResult(onLaunchInstall = launchInstaller)
    }

  return {
    viewModel.startUpdateDownloadAndInstall(
      onLaunchInstall = launchInstaller,
      onRequestUnknownAppsPermission = { settingsIntent ->
        unknownAppsLauncher.launch(settingsIntent)
      },
    )
  }
}

@Composable
fun rememberCircularUpdateAction(viewModel: MainViewModel): () -> Unit {
  val context = LocalContext.current
  val launchInstaller: (Intent) -> Unit = { installIntent ->
    try {
      context.startActivity(installIntent)
    } catch (e: Exception) {
      viewModel.showBanner(e.message ?: "Unable to launch Android package installer.")
    }
  }
  val unknownAppsLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
      viewModel.onUnknownAppsPermissionResult(onLaunchInstall = launchInstaller)
    }

  return {
    viewModel.triggerOneTapUpdateCheckAndInstall(
      onLaunchInstall = launchInstaller,
      onRequestUnknownAppsPermission = { settingsIntent ->
        unknownAppsLauncher.launch(settingsIntent)
      },
    )
  }
}

fun formatByteCount(bytes: Long): String {
  if (bytes <= 0L) return "0 KB"
  val kb = bytes / 1024.0
  val mb = kb / 1024.0
  return if (mb >= 1.0) String.format("%.2f MB", mb) else String.format("%.0f KB", kb)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesScreen(
  viewModel: MainViewModel,
  onBack: () -> Unit,
) {
  val result by viewModel.updateCheckResult.collectAsStateWithLifecycle()
  val isChecking by viewModel.isCheckingUpdates.collectAsStateWithLifecycle()
  val downloadState by viewModel.apkDownloadState.collectAsStateWithLifecycle()
  val isOnline = viewModel.internetManager.isInternetAvailable()
  val onTriggerUpdate = rememberApkUpdateAction(viewModel)

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
            Text(
              text = "GitHub: ${BuildConfig.GITHUB_REPO}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        // Preserve original Check Updates button
        Button(
          onClick = { viewModel.checkUpdates() },
          enabled = !isChecking && downloadState !is ApkDownloadState.Downloading,
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
          Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (val res = result) {
              UpdateCheckResult.NotChecked -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                  Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                  Text(stringResource(R.string.updates_prompt_check), style = MaterialTheme.typography.bodyMedium)
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
                  Icon(Icons.Default.CheckCircle, contentDescription = null, tint = UpdateUpToDateGreen)
                  Text(stringResource(R.string.updates_up_to_date), style = MaterialTheme.typography.bodyMedium)
                }
              }
              is UpdateCheckResult.Checked -> {
                if (res.updateAvailable) {
                  Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = UpdateRed)
                    Text(
                      text = "v${res.latestVersionName} (Build ${res.latestVersionCode})",
                      style = MaterialTheme.typography.titleMedium,
                      fontWeight = FontWeight.Bold,
                      color = UpdateRed,
                    )
                  }
                  if (res.apkSizeBytes > 0L) {
                    Text(
                      text = "APK: ${res.apkAssetName} (${formatByteCount(res.apkSizeBytes)})",
                      style = MaterialTheme.typography.labelMedium,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                  if (res.releaseNotes.isNotBlank()) {
                    Text(res.releaseNotes, style = MaterialTheme.typography.bodyMedium)
                  }
                } else {
                  Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = UpdateUpToDateGreen)
                    Text(
                      text = stringResource(R.string.updates_up_to_date),
                      style = MaterialTheme.typography.titleMedium,
                      color = UpdateUpToDateGreen,
                      fontWeight = FontWeight.SemiBold,
                    )
                  }
                  Text(
                    text = "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
              is UpdateCheckResult.Failed -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                  Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                  Text(
                    text = res.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                  )
                }
              }
            }
          }
        }

        // Large RED Update Action Section when a newer release is available
        val checkedResult = result as? UpdateCheckResult.Checked
        if (checkedResult != null && checkedResult.updateAvailable) {
          when (val dlState = downloadState) {
            ApkDownloadState.Idle -> {
              Button(
                onClick = onTriggerUpdate,
                colors =
                  ButtonDefaults.buttonColors(
                    containerColor = UpdateRed,
                    contentColor = Color.White,
                  ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp).testTag("updates_install_button"),
              ) {
                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.padding(end = 10.dp))
                Text(
                  text = stringResource(R.string.updates_update_now_button),
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                )
              }
            }
            is ApkDownloadState.Downloading -> {
              Surface(
                shape = RoundedCornerShape(16.dp),
                color = UpdateRed.copy(alpha = 0.08f),
                modifier =
                  Modifier.fillMaxWidth()
                    .border(1.dp, UpdateRed.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                    .testTag("updates_download_progress_card"),
              ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Text(
                      text = stringResource(R.string.updates_downloading_title),
                      style = MaterialTheme.typography.titleSmall,
                      color = UpdateRed,
                      fontWeight = FontWeight.Bold,
                    )
                    if (dlState.progressPercent >= 0) {
                      Text(
                        text = "${dlState.progressPercent}%",
                        style = MaterialTheme.typography.titleSmall,
                        color = UpdateRed,
                        fontWeight = FontWeight.Bold,
                      )
                    }
                  }
                  if (dlState.progressPercent >= 0) {
                    LinearProgressIndicator(
                      progress = { (dlState.progressPercent / 100f).coerceIn(0f, 1f) },
                      color = UpdateRed,
                      trackColor = UpdateRed.copy(alpha = 0.2f),
                      modifier = Modifier.fillMaxWidth().height(8.dp),
                    )
                  } else {
                    LinearProgressIndicator(
                      color = UpdateRed,
                      trackColor = UpdateRed.copy(alpha = 0.2f),
                      modifier = Modifier.fillMaxWidth().height(8.dp),
                    )
                  }
                  val sizeInfo =
                    if (dlState.totalBytes > 0L) {
                      "${formatByteCount(dlState.downloadedBytes)} / ${formatByteCount(dlState.totalBytes)}"
                    } else {
                      formatByteCount(dlState.downloadedBytes)
                    }
                  Text(
                    text = sizeInfo,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
            is ApkDownloadState.PermissionRequired -> {
              Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                  Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = UpdateRed)
                    Text(
                      text = stringResource(R.string.updates_permission_required),
                      style = MaterialTheme.typography.bodyMedium,
                      fontWeight = FontWeight.Medium,
                    )
                  }
                  Button(
                    onClick = onTriggerUpdate,
                    colors =
                      ButtonDefaults.buttonColors(
                        containerColor = UpdateRed,
                        contentColor = Color.White,
                      ),
                    shape = RoundedCornerShape(14.dp),
                    modifier =
                      Modifier.fillMaxWidth()
                        .height(54.dp)
                        .testTag("updates_allow_permission_button"),
                  ) {
                    Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(
                      text = stringResource(R.string.updates_allow_permission_button),
                      style = MaterialTheme.typography.titleMedium,
                      fontWeight = FontWeight.Bold,
                    )
                  }
                }
              }
            }
            is ApkDownloadState.ReadyToInstall -> {
              Surface(
                shape = RoundedCornerShape(16.dp),
                color = UpdateUpToDateGreen.copy(alpha = 0.1f),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                  Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.InstallMobile, contentDescription = null, tint = UpdateUpToDateGreen)
                    Text(
                      text = stringResource(R.string.updates_ready_to_install),
                      style = MaterialTheme.typography.bodyMedium,
                      fontWeight = FontWeight.SemiBold,
                    )
                  }
                  Button(
                    onClick = onTriggerUpdate,
                    colors =
                      ButtonDefaults.buttonColors(
                        containerColor = UpdateRed,
                        contentColor = Color.White,
                      ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(56.dp).testTag("updates_install_button"),
                  ) {
                    Icon(Icons.Default.InstallMobile, contentDescription = null, modifier = Modifier.padding(end = 10.dp))
                    Text(
                      text = stringResource(R.string.updates_install_apk_button),
                      style = MaterialTheme.typography.titleMedium,
                      fontWeight = FontWeight.Bold,
                    )
                  }
                }
              }
            }
            is ApkDownloadState.Failed -> {
              Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                  Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = UpdateRed)
                    Text(
                      text = stringResource(R.string.updates_download_failed, dlState.message),
                      style = MaterialTheme.typography.bodyMedium,
                      color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                  }
                  Button(
                    onClick = {
                      viewModel.resetUpdateDownloadState()
                      onTriggerUpdate()
                    },
                    colors =
                      ButtonDefaults.buttonColors(
                        containerColor = UpdateRed,
                        contentColor = Color.White,
                      ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(54.dp).testTag("updates_retry_button"),
                  ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(
                      text = stringResource(R.string.updates_retry_button),
                      style = MaterialTheme.typography.titleMedium,
                      fontWeight = FontWeight.Bold,
                    )
                  }
                }
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(4.dp))
      }
    }
  }
}
