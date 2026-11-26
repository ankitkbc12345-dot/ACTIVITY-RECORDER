package com.example.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.security.CryptoManager
import com.example.ui.MainViewModel
import com.example.ui.NavRoutes
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

private data class DashboardModuleItem(
  val titleRes: Int,
  val subtitle: String,
  val icon: ImageVector,
  val route: String,
  val tag: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboardScreen(
  viewModel: MainViewModel,
  onNavigateModule: (String) -> Unit,
  onLogout: () -> Unit,
) {
  val localId by viewModel.localDeviceId.collectAsStateWithLifecycle()
  val localName by viewModel.localDeviceName.collectAsStateWithLifecycle()
  val activeId by viewModel.activeDeviceId.collectAsStateWithLifecycle()
  val activePaired by viewModel.activePairedDevice.collectAsStateWithLifecycle()
  val recording by viewModel.recordingSettings.collectAsStateWithLifecycle()
  val batteryStatus by viewModel.batteryStatus.collectAsStateWithLifecycle()
  val cloudPrefs by viewModel.cloudSyncPreferences.collectAsStateWithLifecycle()

  val currentUser = Firebase.auth.currentUser
  val currentUserName = currentUser?.displayName?.takeIf { it.isNotBlank() } ?: "Google Account"
  val currentUserEmail = currentUser?.email?.takeIf { it.isNotBlank() } ?: "account@gmail.com"
  val deviceModelName = CryptoManager.getLocalDeviceModel()

  // Strict Point 9, 12, 14:
  // - Removed Device Connection Management (duplicate of Device Selection)
  // - Removed Backup & Restore
  // - Removed Theme Mode (now in navigation drawer)
  // - Removed Language Settings (now in navigation drawer)
  // - Preserved Security Settings intact
  val modules =
    listOf(
      DashboardModuleItem(
        R.string.module_device_selection,
        "This device & paired devices",
        Icons.Default.Devices,
        NavRoutes.DEVICE_SELECTION,
        "mod_device_selection",
      ),
      DashboardModuleItem(
        R.string.module_app_activity,
        "Installed inventory & events",
        Icons.Default.Apps,
        NavRoutes.APP_ACTIVITY,
        "mod_app_activity",
      ),
      DashboardModuleItem(
        R.string.module_call_logs,
        "Call metadata & contact history",
        Icons.Default.Call,
        NavRoutes.CALL_LOGS,
        "mod_call_logs",
      ),
      DashboardModuleItem(
        R.string.module_unlock_logs,
        "Daily unlocks, method & battery",
        Icons.Default.LockOpen,
        NavRoutes.UNLOCK_LOGS,
        "mod_unlock_logs",
      ),
      DashboardModuleItem(
        R.string.module_recording_control,
        if (recording.masterEnabled) "Master: ON" else "Master: OFF",
        Icons.Default.FiberManualRecord,
        NavRoutes.RECORDING_CONTROL,
        "mod_recording_control",
      ),
      DashboardModuleItem(
        R.string.module_remote_apps,
        "Authorized device uninstall",
        Icons.Default.SettingsRemote,
        NavRoutes.REMOTE_APPS,
        "mod_remote_apps",
      ),
      DashboardModuleItem(
        R.string.module_export_pdf,
        "Generate multi-page PDF reports",
        Icons.Default.PictureAsPdf,
        NavRoutes.EXPORT_PDF,
        "mod_export_pdf",
      ),
      DashboardModuleItem(
        R.string.module_data_management,
        "Database storage & pruning",
        Icons.Default.DeleteSweep,
        NavRoutes.DATA_MANAGEMENT,
        "mod_data_management",
      ),
      DashboardModuleItem(
        R.string.module_security,
        "Password & manual lock",
        Icons.Default.Security,
        NavRoutes.SECURITY_SETTINGS,
        "mod_security",
      ),
      DashboardModuleItem(
        R.string.module_cloud_sync,
        "Auto sync (${cloudPrefs.schedule.name}) & Firebase",
        Icons.Default.CloudSync,
        NavRoutes.CLOUD_SYNC,
        "mod_cloud_sync",
      ),
    )

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.dashboard_title)) },
        actions = {
          IconButton(
            onClick = {
              viewModel.logout()
              onLogout()
            },
            modifier = Modifier.testTag("dashboard_logout_button"),
          ) {
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = stringResource(R.string.dashboard_logout))
          }
        },
      )
    }
  ) { innerPadding ->
    LazyVerticalGrid(
      columns = GridCells.Adaptive(minSize = 165.dp),
      contentPadding = PaddingValues(16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.fillMaxSize().padding(innerPadding).testTag("dashboard_grid"),
    ) {
      // Point 4: Non-interactive Live Telemetry Banner (no click handler on tap, preserves aspect ratio/dimensions)
      item(span = { GridItemSpan(maxLineSpan) }) {
        val isLocal = activeId == localId
        Surface(
          shape = RoundedCornerShape(16.dp),
          color =
            if (isLocal) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.tertiaryContainer,
          modifier =
            Modifier.fillMaxWidth()
              .testTag("dashboard_telemetry_banner"),
        ) {
          Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
              Text(
                text = "$currentUserName • $currentUserEmail",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color =
                  if (isLocal) MaterialTheme.colorScheme.onPrimaryContainer
                  else MaterialTheme.colorScheme.onTertiaryContainer,
              )
              Text(
                text =
                  if (isLocal) "$localName ($deviceModelName) • Live Local"
                  else "${activePaired?.deviceName ?: activeId} ($deviceModelName) • Snapshot",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color =
                  if (isLocal) MaterialTheme.colorScheme.onPrimaryContainer
                  else MaterialTheme.colorScheme.onTertiaryContainer,
              )
              Text(
                text =
                  "Battery: ${batteryStatus.statusLabel}  •  Cloud: ${cloudPrefs.schedule.name}",
                style = MaterialTheme.typography.bodySmall,
                color =
                  if (isLocal) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                  else MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f),
              )
            }
            Icon(
              Icons.Default.Devices,
              contentDescription = null,
              tint =
                if (isLocal) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onTertiaryContainer,
              modifier = Modifier.size(32.dp),
            )
          }
        }
      }

      items(modules, key = { it.route }) { item ->
        Surface(
          shape = RoundedCornerShape(18.dp),
          color = MaterialTheme.colorScheme.surface,
          tonalElevation = 1.dp,
          modifier =
            Modifier.fillMaxWidth()
              .clip(RoundedCornerShape(18.dp))
              .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
              .clickable {
                viewModel.recordUserActivity()
                onNavigateModule(item.route)
              }
              .testTag(item.tag),
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Icon(
              imageVector = item.icon,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(28.dp),
            )
            Text(
              text = stringResource(item.titleRes),
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
              text = item.subtitle,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}
