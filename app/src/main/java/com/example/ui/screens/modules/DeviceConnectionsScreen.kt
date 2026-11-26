package com.example.ui.screens.modules

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.connectivity.IncomingTransferConfirmation
import com.example.connectivity.TransferProtocol
import com.example.ui.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceConnectionsScreen(
  viewModel: MainViewModel,
  onViewAppActivity: () -> Unit,
  onViewCallLogs: () -> Unit,
  onBack: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val btState by viewModel.bluetoothManager.state.collectAsStateWithLifecycle()
  val wifiState by viewModel.wifiDirectManager.state.collectAsStateWithLifecycle()
  val netRelayState by viewModel.internetManager.relayState.collectAsStateWithLifecycle()
  val pairedDevices by viewModel.pairedDevices.collectAsStateWithLifecycle()
  val localId by viewModel.localDeviceId.collectAsStateWithLifecycle()
  val activeId by viewModel.activeDeviceId.collectAsStateWithLifecycle()
  val activePaired by viewModel.activePairedDevice.collectAsStateWithLifecycle()

  var selectedTab by remember { mutableIntStateOf(0) } // 0=BT, 1=Wi-Fi Direct, 2=Internet
  var pairingCodeInput by remember { mutableStateOf("") }
  var isBtConnectingAddress by remember { mutableStateOf<String?>(null) }
  var isWifiPulling by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    viewModel.bluetoothManager.refreshState(scope)
    viewModel.wifiDirectManager.initialize(scope)
  }

  // Automatically pull encrypted snapshot once connected as Wi-Fi Direct client
  var lastAutoPulledHost by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(wifiState.isConnected, wifiState.isGroupOwner, wifiState.groupOwnerHost) {
    val host = wifiState.groupOwnerHost
    if (wifiState.isConnected && !wifiState.isGroupOwner && !host.isNullOrBlank() && lastAutoPulledHost != host) {
      lastAutoPulledHost = host
      isWifiPulling = true
      val res = viewModel.wifiDirectManager.syncWithHost(host)
      isWifiPulling = false
      res.fold(
        onSuccess = { summary -> viewModel.onRemoteDeviceSynced(summary) },
        onFailure = { viewModel.showBanner(it.message ?: "Wi-Fi Direct sync failed") },
      )
    } else if (!wifiState.isConnected) {
      lastAutoPulledHost = null
    }
  }

  val btPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
      viewModel.bluetoothManager.refreshState(scope)
    }

  val btEnableLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
      viewModel.bluetoothManager.refreshState(scope)
    }

  val wifiPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
      viewModel.wifiDirectManager.initialize(scope)
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

      // Quick access bar whenever a remote device is active/synced
      if (activeId != localId && activePaired != null) {
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f),
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
          Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
              Text(
                text = "Viewing Remote Device: ${activePaired!!.deviceName}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
              )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Button(onClick = onViewAppActivity, modifier = Modifier.weight(1f).testTag("view_remote_apps_btn")) {
                Icon(Icons.Default.Apps, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("View Apps")
              }
              OutlinedButton(onClick = onViewCallLogs, modifier = Modifier.weight(1f).testTag("view_remote_calls_btn")) {
                Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Call Logs")
              }
            }
          }
        }
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
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                  Text("Bluetooth App-to-App Connection", style = MaterialTheme.typography.titleMedium)
                  Text(btState.statusMessage, style = MaterialTheme.typography.bodyMedium)
                  Text(
                    "Connects only with devices running Device Recorder. Keep this screen open on the other phone.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  if (!btState.hasPermissions) {
                    Button(
                      onClick = { btPermissionLauncher.launch(viewModel.bluetoothManager.requiredPermissions()) }
                    ) {
                      Text("Grant Bluetooth Permissions")
                    }
                  } else if (btState.hardwareAvailable && !btState.adapterEnabled) {
                    Button(
                      onClick = {
                        try {
                          btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        } catch (_: Exception) {
                          viewModel.showBanner("Open device Bluetooth settings to turn Bluetooth ON.")
                        }
                      },
                      modifier = Modifier.fillMaxWidth(),
                    ) {
                      Text("Turn On Bluetooth")
                    }
                  } else if (btState.hardwareAvailable && btState.adapterEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                      Button(
                        onClick = { viewModel.bluetoothManager.startDiscovery(scope) },
                        modifier = Modifier.weight(1f),
                      ) {
                        Text(if (btState.isDiscovering) "Scanning..." else "Scan Nearby")
                      }
                      OutlinedButton(
                        onClick = {
                          try {
                            val discIntent =
                              Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
                              }
                            btEnableLauncher.launch(discIntent)
                          } catch (_: Exception) {
                            viewModel.startBluetoothListening()
                          }
                        },
                        modifier = Modifier.weight(1f),
                      ) {
                        Text("Make Visible")
                      }
                    }
                    Text(
                      text =
                        if (btState.isListening) "Receiver Ready: Waiting for incoming Device Recorder peers"
                        else "Receiver Paused",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.primary,
                    )
                  }
                }
              }
            }

            items(btState.peers, key = { it.address }) { peer ->
              val isConnectingThis = isBtConnectingAddress == peer.address
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
                  Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(peer.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                      "${peer.address} • ${
                        when {
                          peer.hasDeviceRecorderApp -> "Device Recorder App Detected"
                          peer.isBonded -> "System Paired"
                          else -> "Discovered"
                        }
                      }",
                      style = MaterialTheme.typography.labelSmall,
                      color =
                        if (peer.hasDeviceRecorderApp) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                  Button(
                    enabled = !isConnectingThis,
                    onClick = {
                      isBtConnectingAddress = peer.address
                      scope.launch {
                        val res = viewModel.bluetoothManager.connectAndSync(peer.address)
                        isBtConnectingAddress = null
                        res.fold(
                          onSuccess = { summary -> viewModel.onRemoteDeviceSynced(summary) },
                          onFailure = { viewModel.showBanner(it.message ?: "Bluetooth sync failed") },
                        )
                      }
                    },
                  ) {
                    if (isConnectingThis) {
                      CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                      Text("Connect & View")
                    }
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
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                  Text("Wi-Fi Direct App-to-App Connection", style = MaterialTheme.typography.titleMedium)
                  Text(wifiState.statusMessage, style = MaterialTheme.typography.bodyMedium)
                  if (!wifiState.hasPermissions) {
                    Button(
                      onClick = {
                        wifiPermissionLauncher.launch(viewModel.wifiDirectManager.requiredPermissions())
                      }
                    ) {
                      Text("Grant Nearby Wi-Fi Permission")
                    }
                  } else if (wifiState.hardwareAvailable && !wifiState.isWifiHardwareEnabled) {
                    Button(
                      onClick = {
                        try {
                          val panelIntent =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                              Intent(Settings.Panel.ACTION_WIFI)
                            } else {
                              Intent(Settings.ACTION_WIFI_SETTINGS)
                            }
                          context.startActivity(panelIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) {
                          viewModel.showBanner("Turn ON Wi-Fi in system settings.")
                        }
                      },
                      modifier = Modifier.fillMaxWidth(),
                    ) {
                      Text("Turn On Wi-Fi")
                    }
                  } else if (wifiState.hardwareAvailable) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                      Button(
                        onClick = { viewModel.wifiDirectManager.discoverPeers(scope) },
                        modifier = Modifier.weight(1f),
                      ) {
                        Text(if (wifiState.isDiscovering) "Scanning..." else "Scan Peers")
                      }
                      if (wifiState.isConnected) {
                        OutlinedButton(
                          onClick = { viewModel.wifiDirectManager.disconnectGroup() },
                          modifier = Modifier.weight(1f),
                        ) {
                          Text("Disconnect")
                        }
                      } else {
                        OutlinedButton(
                          onClick = {
                            if (wifiState.isListening) viewModel.wifiDirectManager.stopSocketListener()
                            else viewModel.startWifiDirectListening()
                          },
                          modifier = Modifier.weight(1f),
                        ) {
                          Text(if (wifiState.isListening) "Listening" else "Ready to Receive")
                        }
                      }
                    }

                    if (wifiState.isConnected && !wifiState.isGroupOwner && wifiState.groupOwnerHost != null) {
                      Button(
                        enabled = !isWifiPulling,
                        onClick = {
                          isWifiPulling = true
                          scope.launch {
                            val res = viewModel.wifiDirectManager.syncWithHost(wifiState.groupOwnerHost!!)
                            isWifiPulling = false
                            res.fold(
                              onSuccess = { summary -> viewModel.onRemoteDeviceSynced(summary) },
                              onFailure = { viewModel.showBanner(it.message ?: "Wi-Fi Direct sync failed") },
                            )
                          }
                        },
                        modifier = Modifier.fillMaxWidth(),
                      ) {
                        Text(
                          if (isWifiPulling) "Fetching Encrypted Records..."
                          else "Pull & View Device Data"
                        )
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
                  Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(peer.deviceName, style = MaterialTheme.typography.titleMedium)
                    Text(
                      "${peer.deviceAddress} • ${
                        if (peer.hasDeviceRecorderApp) "Device Recorder App Verified"
                        else "Nearby Wi-Fi Device"
                      }",
                      style = MaterialTheme.typography.labelSmall,
                      color =
                        if (peer.hasDeviceRecorderApp) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
          ) {
            // Card 1: Host / Share This Device over Internet
            item {
              Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                  Text("Share This Device Over Internet", style = MaterialTheme.typography.titleMedium)
                  Text(
                    "Automatic encrypted connection between two phones running Device Recorder. No API key or server setup required.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  if (netRelayState.isHosting && netRelayState.pairingCode.isNotBlank()) {
                    Surface(
                      shape = RoundedCornerShape(12.dp),
                      color = MaterialTheme.colorScheme.primaryContainer,
                      modifier = Modifier.fillMaxWidth(),
                    ) {
                      Column(
                        modifier = Modifier.padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                      ) {
                        Text("6-Digit Connection Code", style = MaterialTheme.typography.labelMedium)
                        Text(
                          text = netRelayState.pairingCode,
                          style = MaterialTheme.typography.headlineMedium,
                          fontWeight = FontWeight.Bold,
                          color = MaterialTheme.colorScheme.onPrimaryContainer,
                          modifier = Modifier.testTag("internet_pairing_code"),
                        )
                        Text(
                          "Enter this code on the other Device Recorder app",
                          style = MaterialTheme.typography.labelSmall,
                        )
                      }
                    }
                    OutlinedButton(
                      onClick = { viewModel.stopInternetSharing() },
                      modifier = Modifier.fillMaxWidth(),
                    ) {
                      Text("Stop Sharing")
                    }
                  } else {
                    Button(
                      onClick = { viewModel.startInternetSharing() },
                      modifier = Modifier.fillMaxWidth().testTag("internet_share_button"),
                    ) {
                      Text("Share This Device (Get Code)")
                    }
                  }
                }
              }
            }

            // Card 2: Connect to Another Device by 6-digit code
            item {
              Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier =
                  Modifier.fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)),
              ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                  Text("Connect to Remote Device", style = MaterialTheme.typography.titleMedium)
                  OutlinedTextField(
                    value = pairingCodeInput,
                    onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pairingCodeInput = it },
                    label = { Text("Enter 6-digit code from other phone") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("internet_code_input"),
                  )
                  Button(
                    enabled = pairingCodeInput.length == 6 && !netRelayState.isConnecting,
                    onClick = {
                      scope.launch {
                        val res = viewModel.internetManager.connectByPairingCode(pairingCodeInput)
                        res.fold(
                          onSuccess = { summary ->
                            pairingCodeInput = ""
                            viewModel.onRemoteDeviceSynced(summary)
                          },
                          onFailure = { viewModel.showBanner(it.message ?: "Connection failed") },
                        )
                      }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("internet_connect_button"),
                  ) {
                    if (netRelayState.isConnecting) {
                      CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                      Spacer(modifier = Modifier.width(8.dp))
                      Text("Connecting...")
                    } else {
                      Text("Connect & View Data")
                    }
                  }
                  if (netRelayState.statusMessage.isNotBlank()) {
                    Text(
                      text = netRelayState.statusMessage,
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.primary,
                    )
                  }
                }
              }
            }

            if (pairedDevices.isNotEmpty()) {
              item {
                Text(
                  "Previously Paired Devices",
                  style = MaterialTheme.typography.titleSmall,
                  color = MaterialTheme.colorScheme.primary,
                )
              }
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
                      Text("${dev.deviceModel} • ${dev.deviceId}", style = MaterialTheme.typography.labelSmall)
                    }
                    Button(
                      onClick = { viewModel.syncPairedDevice(dev, TransferProtocol.METHOD_INTERNET) }
                    ) {
                      Text("Sync & View")
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

  val pendingAuth: IncomingTransferConfirmation? =
    btState.pendingAuth ?: wifiState.pendingAuth ?: netRelayState.pendingAuth
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
            Text("Device Recorder peer is requesting your encrypted app activity & call log snapshot.")
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            when (pendingAuth.transportMethod) {
              TransferProtocol.METHOD_BLUETOOTH -> viewModel.bluetoothManager.resolvePendingAuthorization(true)
              TransferProtocol.METHOD_WIFI_DIRECT -> viewModel.wifiDirectManager.resolvePendingAuthorization(true)
              TransferProtocol.METHOD_INTERNET -> viewModel.internetManager.resolvePendingAuthorization(true)
            }
          }
        ) {
          Text("Approve")
        }
      },
      dismissButton = {
        TextButton(
          onClick = {
            when (pendingAuth.transportMethod) {
              TransferProtocol.METHOD_BLUETOOTH -> viewModel.bluetoothManager.resolvePendingAuthorization(false)
              TransferProtocol.METHOD_WIFI_DIRECT -> viewModel.wifiDirectManager.resolvePendingAuthorization(false)
              TransferProtocol.METHOD_INTERNET -> viewModel.internetManager.resolvePendingAuthorization(false)
            }
          }
        ) {
          Text("Deny")
        }
      },
    )
  }
}
