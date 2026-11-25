package com.example.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Sync
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.MainViewModel
import com.example.ui.NavRoutes

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
        "Call metadata records",
        Icons.Default.Call,
        NavRoutes.CALL_LOGS,
        "mod_call_logs",
      ),
      DashboardModuleItem(
        R.string.module_recording_control,
        if (recording.masterEnabled) "Master: ON" else "Master: OFF",
        Icons.Default.FiberManualRecord,
        NavRoutes.RECORDING_CONTROL,
        "mod_recording_control",
      ),
      DashboardModuleItem(
        R.string.module_device_connections,
        "Bluetooth, Wi-Fi Direct & Internet",
        Icons.Default.Sync,
        NavRoutes.DEVICE_CONNECTIONS,
        "mod_device_connections",
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
        R.string.module_backup_restore,
        "Encrypted offline .pdrbak",
        Icons.Default.Backup,
        NavRoutes.BACKUP_RESTORE,
        "mod_backup_restore",
      ),
      DashboardModuleItem(
        R.string.module_data_management,
        "Database storage & pruning",
        Icons.Default.DeleteSweep,
        NavRoutes.DATA_MANAGEMENT,
        "mod_data_management",
      ),
      DashboardModuleItem(
        R.string.module_language,
        "Offline interface localization",
        Icons.Default.Language,
        NavRoutes.LANGUAGE_SETTINGS,
        "mod_language",
      ),
      DashboardModuleItem(
        R.string.module_security,
        "Password & manual lock",
        Icons.Default.Security,
        NavRoutes.SECURITY_SETTINGS,
        "mod_security",
      ),
      DashboardModuleItem(
        R.string.module_internet_config,
        "Optional signaling, STUN & TURN",
        Icons.Default.Public,
        NavRoutes.INTERNET_CONFIG,
        "mod_internet_config",
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
      item(span = { GridItemSpan(maxLineSpan) }) {
        val isLocal = activeId == localId
        Surface(
          shape = RoundedCornerShape(16.dp),
          color =
            if (isLocal) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.tertiaryContainer,
          modifier =
            Modifier.fillMaxWidth()
              .clickable {
                viewModel.recordUserActivity()
                onNavigateModule(NavRoutes.DEVICE_SELECTION)
              },
        ) {
          Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = stringResource(R.string.dashboard_active_scope),
                style = MaterialTheme.typography.labelSmall,
                color =
                  if (isLocal) MaterialTheme.colorScheme.onPrimaryContainer
                  else MaterialTheme.colorScheme.onTertiaryContainer,
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text =
                  if (isLocal) "$localName ($localId) • Live Local"
                  else "${activePaired?.deviceName ?: activeId} • Imported Snapshot",
                style = MaterialTheme.typography.titleMedium,
                color =
                  if (isLocal) MaterialTheme.colorScheme.onPrimaryContainer
                  else MaterialTheme.colorScheme.onTertiaryContainer,
              )
            }
            Icon(
              Icons.Default.Devices,
              contentDescription = null,
              tint =
                if (isLocal) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onTertiaryContainer,
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
