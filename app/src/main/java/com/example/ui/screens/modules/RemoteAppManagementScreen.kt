package com.example.ui.screens.modules

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.connectivity.TransferProtocol
import com.example.data.local.AppInventorySnapshotEntity
import com.example.data.local.PairedDeviceEntity
import com.example.monitoring.DateTimeUtils
import com.example.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteAppManagementScreen(
  viewModel: MainViewModel,
  onBack: () -> Unit,
) {
  val context = LocalContext.current
  val pairedDevices by viewModel.pairedDevices.collectAsStateWithLifecycle()
  val mode by viewModel.remoteUninstallMode.collectAsStateWithLifecycle()
  val requests by viewModel.remoteUninstallRequests.collectAsStateWithLifecycle()
  val isDeviceOwner = remember { TransferProtocol.isDeviceOwnerCapable(context) }

  var selectedDeviceId by remember { mutableStateOf<String?>(null) }
  val selectedDevice =
    remember(pairedDevices, selectedDeviceId) {
      pairedDevices.firstOrNull { it.deviceId == selectedDeviceId } ?: pairedDevices.firstOrNull()
    }
  var deviceInventory by remember { mutableStateOf<List<AppInventorySnapshotEntity>>(emptyList()) }
  var selectedAppForUninstall by remember { mutableStateOf<AppInventorySnapshotEntity?>(null) }
  var selectedTransport by remember { mutableStateOf(TransferProtocol.METHOD_BLUETOOTH) }

  LaunchedEffect(selectedDevice?.deviceId) {
    val dev = selectedDevice
    deviceInventory =
      if (dev != null) viewModel.getInventoryForDevice(dev.deviceId).filter { it.isInstalled }
      else emptyList()
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.module_remote_apps)) },
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("remote_apps_back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back_cd))
          }
        },
      )
    }
  ) { innerPadding ->
    LazyColumn(
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.fillMaxSize().padding(innerPadding),
    ) {
      item {
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Remote Uninstall Authorization Mode", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              FilterChip(
                selected = mode == "MODE_A_MANAGED",
                onClick = { viewModel.setRemoteUninstallMode("MODE_A_MANAGED") },
                label = { Text("Mode A: Managed") },
              )
              FilterChip(
                selected = mode == "MODE_B_INTERACTIVE",
                onClick = { viewModel.setRemoteUninstallMode("MODE_B_INTERACTIVE") },
                label = { Text("Mode B: Owner Confirm") },
              )
            }
            Text(
              text =
                if (mode == "MODE_A_MANAGED")
                  "Mode A requires Android Device Owner / enterprise enrollment on the source device (this device DeviceOwner=$isDeviceOwner). Standard Android devices enforce user-confirmed uninstall."
                else
                  "Mode B sends an authenticated uninstall request to the paired source device, triggering an explicit owner approval & Android system uninstall prompt.",
              style = MaterialTheme.typography.bodyMedium,
            )
          }
        }
      }

      if (pairedDevices.isEmpty()) {
        item {
          Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier =
              Modifier.fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
          ) {
            Text(
              "No authorized paired devices found. Pair a device in Device Connection Management first to manage its installed applications.",
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.padding(16.dp),
            )
          }
        }
      } else {
        item {
          Text("Select Authorized Paired Device", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
          Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()),
          ) {
            pairedDevices.forEach { dev: PairedDeviceEntity ->
              FilterChip(
                selected = selectedDevice?.deviceId == dev.deviceId,
                onClick = { selectedDeviceId = dev.deviceId },
                label = { Text(dev.deviceName) },
              )
            }
          }
        }

        item {
          Text("Transport for Uninstall Request", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
          Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()),
          ) {
            listOf(TransferProtocol.METHOD_BLUETOOTH, TransferProtocol.METHOD_WIFI_DIRECT, TransferProtocol.METHOD_INTERNET).forEach { m ->
              FilterChip(
                selected = selectedTransport == m,
                onClick = { selectedTransport = m },
                label = { Text(m) },
              )
            }
          }
        }

        item {
          Text("Installed Inventory (${deviceInventory.size})", style = MaterialTheme.typography.titleMedium)
        }

        items(deviceInventory, key = { it.id }) { app ->
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier =
              Modifier.fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                .clickable { selectedAppForUninstall = app },
          ) {
            Row(
              modifier = Modifier.padding(14.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(app.appName, style = MaterialTheme.typography.titleMedium)
                Text("${app.packageName} • v${app.versionName}", style = MaterialTheme.typography.labelSmall)
              }
              IconButton(onClick = { selectedAppForUninstall = app }) {
                Icon(Icons.Default.DeleteForever, contentDescription = "Uninstall ${app.appName}", tint = MaterialTheme.colorScheme.error)
              }
            }
          }
        }
      }

      if (requests.isNotEmpty()) {
        item {
          Text("Uninstall Operation Log", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        }
        items(requests, key = { it.requestId }) { req ->
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
              Text("${req.appName} (${req.packageName}) • [${req.status}]", style = MaterialTheme.typography.titleMedium)
              Text("${DateTimeUtils.formatDateTime(req.timestampMillis)} • ${req.mode}", style = MaterialTheme.typography.labelSmall)
              Text(req.resultMessage, style = MaterialTheme.typography.bodyMedium)
            }
          }
        }
      }
    }
  }

  selectedAppForUninstall?.let { app ->
    val dev = selectedDevice
    if (dev != null) {
      AlertDialog(
        onDismissRequest = { selectedAppForUninstall = null },
        title = { Text("Confirm Remote Uninstall Request") },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Target Device: ${dev.deviceName} (${dev.deviceId})")
            Text("Application: ${app.appName}")
            Text("Package: ${app.packageName}", style = MaterialTheme.typography.labelSmall)
            Text("Mode: $mode via $selectedTransport")
          }
        },
        confirmButton = {
          Button(
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            onClick = {
              viewModel.sendRemoteUninstall(dev, app.packageName, app.appName, mode, selectedTransport)
              selectedAppForUninstall = null
            },
          ) {
            Text("Send Uninstall Request")
          }
        },
        dismissButton = { TextButton(onClick = { selectedAppForUninstall = null }) { Text("Cancel") } },
      )
    }
  }
}
