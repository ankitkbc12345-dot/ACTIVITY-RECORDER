package com.example.ui.screens.modules

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.connectivity.IncomingTransferConfirmation
import com.example.connectivity.RemoteConnectionStatus
import com.example.connectivity.TransferProtocol
import com.example.ui.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceConnectionsScreen(
  viewModel: MainViewModel,
  onOpenInternetConfig: () -> Unit,
  onBack: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  val btState by viewModel.bluetoothManager.state.collectAsStateWithLifecycle()
  val wifiState by viewModel.wifiDirectManager.state.collectAsStateWithLifecycle()
  val netStatus by viewModel.internetManager.status.collectAsStateWithLifecycle()
  val netConfig by viewModel.internetConfig.collectAsStateWithLifecycle()
  val pairedDevices by viewModel.pairedDevices.collectAsStateWithLifecycle()

  var selectedTab by remember { mutableIntStateOf(0) } // 0=BT, 1=Wi-Fi Direct, 2=Internet

  LaunchedEffect(Unit) {
    viewModel.bluetoothManager.refreshState()
    viewModel.wifiDirectManager.initialize()
  }

  val btPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
      viewModel.bluetoothManager.refreshState()
    }

  val wifiPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
      viewModel.wifiDirectManager.initialize()
    }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.module_device_connections)) },
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("connections_back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back_cd))
          }
        },
      )
    }
  ) { innerPadding ->
    Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
      TabRow(selectedTabIndex = selectedTab) {
        Tab(
          selected = selectedTab == 0,
          onClick = { selectedTab = 0 },
          text = { Text("Bluetooth") },
          icon = { Icon(Icons.Default.Bluetooth, contentDescription = null) },
        )
        Tab(
          selected = selectedTab == 1,
          onClick = { selectedTab = 1 },
          text = { Text("Wi-Fi Direct") },
          icon = { Icon(Icons.Default.Wifi, contentDescription = null) },
        )
        Tab(
          selected = selectedTab == 2,
          onClick = { selectedTab = 2 },
          text = { Text("Internet") },
          icon = { Icon(Icons.Default.Public, contentDescription = null) },
        )
      }

      when (selectedTab) {
        0 -> {
          LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
          ) {
            item {
              Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                  Text("Bluetooth Encrypted Transfer", style = MaterialTheme.typography.titleMedium)
                  Text(btState.statusMessage, style = MaterialTheme.typography.bodyMedium)
                  if (!btState.hasPermissions) {
                    Button(
                      onClick = {
                        btPermissionLauncher.launch(viewModel.bluetoothManager.requiredPermissions())
                      }
                    ) {
                      Text("Grant Bluetooth Permissions")
                    }
                  } else if (btState.hardwareAvailable && btState.adapterEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                      Button(
                        onClick = { viewModel.bluetoothManager.startDiscovery() },
                        modifier = Modifier.weight(1f),
                      ) {
                        Text(if (btState.isDiscovering) "Scanning..." else "Discover")
                      }
                      OutlinedButton(
                        onClick = {
                          if (btState.isListening) viewModel.bluetoothManager.stopListening()
                          else viewModel.startBluetoothListening()
                        },
                        modifier = Modifier.weight(1f),
                      ) {
                        Text(if (btState.isListening) "Stop Listener" else "Host / Receive")
                      }
                    }
                  }
                }
              }
            }

            items(btState.peers, key = { it.address }) { peer ->
              Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier =
                  Modifier.fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
              ) {
                Row(
                  modifier = Modifier.padding(14.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(peer.name, style = MaterialTheme.typography.titleMedium)
                    Text("${peer.address} • ${if (peer.isBonded) "System Paired" else "Discovered"}", style = MaterialTheme.typography.labelSmall)
                  }
                  Button(
                    onClick = {
                      scope.launch {
                        val res = viewModel.bluetoothManager.connectAndSync(peer.address)
                        viewModel.showBanner(res.getOrElse { it.message ?: "Bluetooth sync failed" })
                      }
                    }
                  ) {
                    Text("Pair & Sync")
                  }
                }
              }
            }
          }
        }

        1 -> {
          LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
          ) {
            item {
              Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                  Text("Wi-Fi Direct Offline P2P", style = MaterialTheme.typography.titleMedium)
                  Text(wifiState.statusMessage, style = MaterialTheme.typography.bodyMedium)
                  if (!wifiState.hasPermissions) {
                    Button(
                      onClick = {
                        wifiPermissionLauncher.launch(viewModel.wifiDirectManager.requiredPermissions())
                      }
                    ) {
                      Text("Grant Nearby Wi-Fi Permission")
                    }
                  } else if (wifiState.hardwareAvailable) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                      Button(
                        onClick = { viewModel.wifiDirectManager.discoverPeers() },
                        modifier = Modifier.weight(1f),
                      ) {
                        Text("Discover Peers")
                      }
                      OutlinedButton(
                        onClick = {
                          if (wifiState.isListening) viewModel.wifiDirectManager.stopSocketListener()
                          else viewModel.startWifiDirectListening()
                        },
                        modifier = Modifier.weight(1f),
                      ) {
                        Text(if (wifiState.isListening) "Stop Socket" else "Host Socket")
                      }
                    }
                    if (wifiState.isConnected && !wifiState.isGroupOwner && wifiState.groupOwnerHost != null) {
                      Button(
                        onClick = {
                          scope.launch {
                            val res = viewModel.wifiDirectManager.syncWithHost(wifiState.groupOwnerHost!!)
                            viewModel.showBanner(res.getOrElse { it.message ?: "Wi-Fi Direct sync failed" })
                          }
                        },
                        modifier = Modifier.fillMaxWidth(),
                      ) {
                        Text("Pull Encrypted Records from Group Owner (${wifiState.groupOwnerHost})")
                      }
                    }
                  }
                }
              }
            }

            items(wifiState.peers, key = { it.deviceAddress }) { peer ->
              Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier =
                  Modifier.fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
              ) {
                Row(
                  modifier = Modifier.padding(14.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(peer.deviceName, style = MaterialTheme.typography.titleMedium)
                    Text(peer.deviceAddress, style = MaterialTheme.typography.labelSmall)
                  }
                  Button(onClick = { viewModel.wifiDirectManager.connectToPeer(peer.deviceAddress) }) {
                    Text("Connect")
                  }
                }
              }
            }
          }
        }

        2 -> {
          LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
          ) {
            item {
              Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                  Text(
                    text = "Internet Remote Sync: Ready",
                    style = MaterialTheme.typography.titleMedium,
                  )
                  Text(
                    text =
                      when (netStatus) {
                        RemoteConnectionStatus.NotConfigured ->
                          "Pre-configured cloud gateway & Google STUN are active. No API key required."
                        RemoteConnectionStatus.Offline -> "Device is offline (Check Wi-Fi or Mobile Data)."
                        RemoteConnectionStatus.Connecting -> "Connecting to cloud gateway..."
                        is RemoteConnectionStatus.Connected ->
                          "Status: Online & Ready (${(netStatus as RemoteConnectionStatus.Connected).endpoint})"
                        is RemoteConnectionStatus.Error ->
                          "Status: ${(netStatus as RemoteConnectionStatus.Error).reason}"
                      },
                    style = MaterialTheme.typography.bodyMedium,
                  )
                  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                      onClick = { viewModel.testSignalingConnection(netConfig) },
                      modifier = Modifier.weight(1f),
                    ) {
                      Text("Quick Test")
                    }
                    OutlinedButton(
                      onClick = onOpenInternetConfig,
                      modifier = Modifier.weight(1f),
                    ) {
                      Text("Internet Settings")
                    }
                  }
                }
              }
            }

            if (netConfig.isConfigured) {
              items(pairedDevices, key = { it.deviceId }) { dev ->
                Surface(
                  shape = RoundedCornerShape(16.dp),
                  color = MaterialTheme.colorScheme.surface,
                  modifier =
                    Modifier.fillMaxWidth()
                      .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
                ) {
                  Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Column(modifier = Modifier.weight(1f)) {
                      Text(dev.deviceName, style = MaterialTheme.typography.titleMedium)
                      Text(dev.deviceId, style = MaterialTheme.typography.labelSmall)
                    }
                    Button(
                      onClick = { viewModel.syncPairedDevice(dev, TransferProtocol.METHOD_INTERNET) }
                    ) {
                      Text("Remote Sync")
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  val pendingAuth: IncomingTransferConfirmation? = btState.pendingAuth ?: wifiState.pendingAuth
  if (pendingAuth != null) {
    AlertDialog(
      onDismissRequest = {},
      title = { Text("Authorize Incoming Device Request") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text("Device: ${pendingAuth.remoteDeviceName} (${pendingAuth.remoteDeviceModel})")
          Text("Device ID: ${pendingAuth.remoteDeviceId}", style = MaterialTheme.typography.labelSmall)
          Text("Transport: ${pendingAuth.transportMethod}")
          Text("Verification Code: ${pendingAuth.verificationCode}", style = MaterialTheme.typography.titleLarge)
          if (pendingAuth.actionType == "REMOTE_UNINSTALL") {
            Text(
              "Requesting UNINSTALL of package: ${pendingAuth.packageToUninstall}",
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.titleMedium,
            )
          } else {
            Text("Requesting encrypted copy of local activity records & app inventory.")
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            if (pendingAuth.transportMethod == TransferProtocol.METHOD_BLUETOOTH) {
              viewModel.bluetoothManager.resolvePendingAuthorization(true)
            } else {
              viewModel.wifiDirectManager.resolvePendingAuthorization(true)
            }
          }
        ) {
          Text("Approve")
        }
      },
      dismissButton = {
        TextButton(
          onClick = {
            if (pendingAuth.transportMethod == TransferProtocol.METHOD_BLUETOOTH) {
              viewModel.bluetoothManager.resolvePendingAuthorization(false)
            } else {
              viewModel.wifiDirectManager.resolvePendingAuthorization(false)
            }
          }
        ) {
          Text("Deny")
        }
      },
    )
  }
}
