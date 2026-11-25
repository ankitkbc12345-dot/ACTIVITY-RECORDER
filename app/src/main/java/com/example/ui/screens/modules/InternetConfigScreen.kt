package com.example.ui.screens.modules

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.connectivity.RemoteConnectionStatus
import com.example.data.preferences.InternetConnectivityConfig
import com.example.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InternetConfigScreen(
  viewModel: MainViewModel,
  onBack: () -> Unit,
) {
  val savedConfig by viewModel.internetConfig.collectAsStateWithLifecycle()
  val connStatus by viewModel.internetManager.status.collectAsStateWithLifecycle()

  var signalingUrl by remember(savedConfig) { mutableStateOf(savedConfig.signalingUrl) }
  var stunUri by remember(savedConfig) { mutableStateOf(savedConfig.stunUri) }
  var turnUri by remember(savedConfig) { mutableStateOf(savedConfig.turnUri) }
  var turnUsername by remember(savedConfig) { mutableStateOf(savedConfig.turnUsername) }
  var turnCredential by remember(savedConfig) { mutableStateOf(savedConfig.turnCredential) }
  var connectionToken by remember(savedConfig) { mutableStateOf(savedConfig.connectionToken) }
  var updateManifestUrl by remember(savedConfig) { mutableStateOf(savedConfig.updateManifestUrl) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.module_internet_config)) },
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("internet_cfg_back")) {
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
          .padding(16.dp)
          .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            text =
              if (!savedConfig.isConfigured) "Internet Connectivity: Not Configured"
              else "Internet Connectivity: Configured",
            style = MaterialTheme.typography.titleMedium,
          )
          Text(
            text =
              when (connStatus) {
                RemoteConnectionStatus.NotConfigured ->
                  "Signaling server is optional. Local database, calendar, Bluetooth, and Wi-Fi Direct operate independently without Internet."
                RemoteConnectionStatus.Offline -> "Status: Device Offline"
                RemoteConnectionStatus.Connecting -> "Status: Testing WebSocket Handshake..."
                is RemoteConnectionStatus.Connected ->
                  "Status: Connected to ${(connStatus as RemoteConnectionStatus.Connected).endpoint}"
                is RemoteConnectionStatus.Error ->
                  "Status: ${(connStatus as RemoteConnectionStatus.Error).reason}"
              },
            style = MaterialTheme.typography.bodyMedium,
          )
        }
      }

      Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier =
          Modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          OutlinedTextField(
            value = signalingUrl,
            onValueChange = { signalingUrl = it },
            label = { Text("Signaling Server URL (wss://...)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("inet_signaling_url"),
          )
          OutlinedTextField(
            value = stunUri,
            onValueChange = { stunUri = it },
            label = { Text("STUN Server URI (stun:host:port)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
          OutlinedTextField(
            value = turnUri,
            onValueChange = { turnUri = it },
            label = { Text("TURN Relay URI (turn:host:port)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
          OutlinedTextField(
            value = turnUsername,
            onValueChange = { turnUsername = it },
            label = { Text("TURN Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
          OutlinedTextField(
            value = turnCredential,
            onValueChange = { turnCredential = it },
            label = { Text("TURN Credential") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
          OutlinedTextField(
            value = connectionToken,
            onValueChange = { connectionToken = it },
            label = { Text("Signaling Auth Token (encrypted locally)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
          OutlinedTextField(
            value = updateManifestUrl,
            onValueChange = { updateManifestUrl = it },
            label = { Text("Optional Update Manifest JSON URL (https://...)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )

          val draft =
            InternetConnectivityConfig(
              signalingUrl = signalingUrl,
              stunUri = stunUri,
              turnUri = turnUri,
              turnUsername = turnUsername,
              turnCredential = turnCredential,
              connectionToken = connectionToken,
              updateManifestUrl = updateManifestUrl,
            )

          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
              onClick = { viewModel.saveInternetConfig(draft) },
              modifier = Modifier.weight(1f).testTag("inet_save_button"),
            ) {
              Text("Save Config")
            }
            OutlinedButton(
              onClick = { viewModel.testSignalingConnection(draft) },
              modifier = Modifier.weight(1f).testTag("inet_test_button"),
            ) {
              Text("Test Connection")
            }
          }

          OutlinedButton(
            onClick = { viewModel.clearInternetConfig() },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text("Clear Configuration")
          }
        }
      }
    }
  }
}
