package com.example.ui.screens.modules

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.monitoring.DateTimeUtils
import com.example.security.CryptoManager
import com.example.ui.MainViewModel
import com.example.ui.ProvideSafeTextToolbar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSelectionScreen(
  viewModel: MainViewModel,
  onOpenConnections: () -> Unit,
  onBack: () -> Unit,
) {
  val localId by viewModel.localDeviceId.collectAsStateWithLifecycle()
  val localName by viewModel.localDeviceName.collectAsStateWithLifecycle()
  val activeId by viewModel.activeDeviceId.collectAsStateWithLifecycle()
  val pairedDevices by viewModel.pairedDevices.collectAsStateWithLifecycle()

  var showRenameDialog by remember { mutableStateOf(false) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.module_device_selection)) },
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("device_sel_back")) {
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
        Text(
          text = "THIS DEVICE — LOCAL DEVICE",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
        )
      }

      item {
        val isSelected = activeId == localId
        Surface(
          shape = RoundedCornerShape(18.dp),
          color =
            if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.surface,
          modifier =
            Modifier.fillMaxWidth()
              .border(
                width = if (isSelected) 2.dp else 1.dp,
                color =
                  if (isSelected) MaterialTheme.colorScheme.primary
                  else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(18.dp),
              )
              .clickable { viewModel.selectLocalDevice() }
              .testTag("select_local_device_card"),
        ) {
          Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column {
                  Text(localName, style = MaterialTheme.typography.titleMedium)
                  Text(CryptoManager.getLocalDeviceModel(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
              }
              Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showRenameDialog = true }) {
                  Icon(Icons.Default.Edit, contentDescription = "Rename local device")
                }
                if (isSelected) {
                  Icon(Icons.Default.CheckCircle, contentDescription = "Active", tint = MaterialTheme.colorScheme.secondary)
                }
              }
            }
            Text("Device ID: $localId", style = MaterialTheme.typography.labelSmall)
          }
        }
      }

      item {
        Spacer(modifier = Modifier.height(6.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "CONNECTED DEVICES (${pairedDevices.size})",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
          )
          OutlinedButton(onClick = onOpenConnections, modifier = Modifier.testTag("pair_new_device_button")) {
            Text("Pair / Connect")
          }
        }
      }

      if (pairedDevices.isEmpty()) {
        item {
          Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Text("No Paired Devices Yet", style = MaterialTheme.typography.titleMedium)
              Text(
                "Tap Pair / Connect above to link another phone running Device Recorder via Bluetooth, Wi-Fi Direct, or Internet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      } else {
        items(pairedDevices, key = { it.deviceId }) { dev ->
          val isSelected = activeId == dev.deviceId
          Surface(
            shape = RoundedCornerShape(18.dp),
            color =
              if (isSelected) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
              else MaterialTheme.colorScheme.surface,
            modifier =
              Modifier.fillMaxWidth()
                .border(
                  width = if (isSelected) 2.dp else 1.dp,
                  color =
                    if (isSelected) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.outlineVariant,
                  shape = RoundedCornerShape(18.dp),
                )
                .clickable { viewModel.selectPairedDevice(dev.deviceId) },
          ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(dev.deviceName, style = MaterialTheme.typography.titleMedium)
                  Text("${dev.deviceModel} • ${dev.deviceId}", style = MaterialTheme.typography.labelSmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                  if (isSelected) {
                    Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.tertiary)
                  }
                  IconButton(onClick = { viewModel.revokePairedDevice(dev.deviceId) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove paired device", tint = MaterialTheme.colorScheme.error)
                  }
                }
              }

              Text(
                text =
                  if (isSelected) "Active Scope — Viewing this device's data"
                  else "Tap card to switch view to this device's data",
                style = MaterialTheme.typography.bodyMedium,
                color =
                  if (isSelected) MaterialTheme.colorScheme.primary
                  else MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                "Last Synced: ${dev.lastTransferTimestampMillis?.let { DateTimeUtils.formatDateTime(it) } ?: "Never"}",
                style = MaterialTheme.typography.labelSmall,
              )
            }
          }
        }
      }
    }
  }

  if (showRenameDialog) {
    var nameInput by remember { mutableStateOf(localName) }
    AlertDialog(
      onDismissRequest = { showRenameDialog = false },
      title = { Text("Rename This Device") },
      text = {
        ProvideSafeTextToolbar {
          OutlinedTextField(
            value = nameInput,
            onValueChange = { nameInput = it },
            label = { Text("Device Display Name") },
            singleLine = true,
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            if (nameInput.isNotBlank()) viewModel.setLocalDeviceName(nameInput)
            showRenameDialog = false
          }
        ) {
          Text("Save")
        }
      },
      dismissButton = {
        TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
      },
    )
  }
}
