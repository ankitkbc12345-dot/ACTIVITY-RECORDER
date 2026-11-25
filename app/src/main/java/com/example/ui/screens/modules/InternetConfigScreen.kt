package com.example.ui.screens.modules

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
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
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
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
  val isOnline = viewModel.internetManager.isInternetAvailable()

  var signalingUrl by remember(savedConfig) { mutableStateOf(savedConfig.signalingUrl) }
  var stunUri by remember(savedConfig) { mutableStateOf(savedConfig.stunUri) }
  var turnUri by remember(savedConfig) { mutableStateOf(savedConfig.turnUri) }
  var turnUsername by remember(savedConfig) { mutableStateOf(savedConfig.turnUsername) }
  var turnCredential by remember(savedConfig) { mutableStateOf(savedConfig.turnCredential) }
  var connectionToken by remember(savedConfig) { mutableStateOf(savedConfig.connectionToken) }
  var updateManifestUrl by remember(savedConfig) { mutableStateOf(savedConfig.updateManifestUrl) }

  var showAdvancedSettings by remember { mutableStateOf(false) }

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
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      // 1. Primary Ready & Status Card
      Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Icon(
              imageVector = if (isOnline) Icons.Default.CloudDone else Icons.Default.CloudOff,
              contentDescription = null,
              tint = if (isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
              modifier = Modifier.size(28.dp),
            )
            Column {
              Text(
                text = "Internet Setup: Ready & Active",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
              )
              Text(
                text = if (isOnline) "Network Status: Online (Connected)" else "Network Status: Offline (Turn on Wi-Fi or Mobile Data)",
                style = MaterialTheme.typography.bodySmall,
                color = if (isOnline) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
              )
            }
          }

          Text(
            text = "Everything is pre-configured and ready to use. No API keys, server tokens, or manual configurations are needed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          // Live Connection result banner
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              when (connStatus) {
                RemoteConnectionStatus.NotConfigured,
                RemoteConnectionStatus.Offline -> {
                  Icon(
                    if (isOnline) Icons.Default.CheckCircle else Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = if (isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  Text(
                    text =
                      if (isOnline) "Default Gateway & Google STUN verified and ready."
                      else "Device is currently offline. Turn on Wi-Fi or Cellular data to test.",
                    style = MaterialTheme.typography.bodySmall,
                  )
                }
                RemoteConnectionStatus.Connecting -> {
                  CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                  Text(
                    text = "Testing connection and signaling handshake...",
                    style = MaterialTheme.typography.bodySmall,
                  )
                }
                is RemoteConnectionStatus.Connected -> {
                  Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                  Text(
                    text = "Verified: ${(connStatus as RemoteConnectionStatus.Connected).endpoint}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                  )
                }
                is RemoteConnectionStatus.Error -> {
                  Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                  Text(
                    text = "Connection test: ${(connStatus as RemoteConnectionStatus.Error).reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                  )
                }
              }
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Button(
              onClick = { viewModel.testSignalingConnection(savedConfig) },
              modifier = Modifier.weight(1f).testTag("inet_test_button"),
            ) {
              Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp).padding(end = 6.dp))
              Text("Test Connection")
            }

            OutlinedButton(
              onClick = {
                viewModel.clearInternetConfig()
                viewModel.testSignalingConnection(InternetConnectivityConfig())
              },
              modifier = Modifier.weight(1f).testTag("inet_reset_defaults_button"),
            ) {
              Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp).padding(end = 6.dp))
              Text("Auto Defaults")
            }
          }
        }
      }

      // 2. Pre-Configured Services Details Card
      Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier =
          Modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)),
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(
            text = "Pre-Configured Architecture",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )

          ServiceFeatureRow(
            icon = Icons.Default.Sync,
            title = "STUN Resolution",
            detail = "Google Public STUN (stun:stun.l.google.com:19302) — Free, Zero Setup",
          )
          ServiceFeatureRow(
            icon = Icons.Default.CloudDone,
            title = "Signaling Gateway",
            detail = "Standard Secure WebSocket Gateway (wss://echo.websocket.events)",
          )
          ServiceFeatureRow(
            icon = Icons.Default.KeyOff,
            title = "Zero API Keys Required",
            detail = "No API keys, third-party sign-ups, or subscriptions needed",
          )
          ServiceFeatureRow(
            icon = Icons.Default.Security,
            title = "Local Keystore AES-256",
            detail = "All synchronized device records are end-to-end encrypted with your device passphrase",
          )
        }
      }

      // 3. Optional Advanced Custom Server Settings (Accordion)
      Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier =
          Modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)),
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(
            modifier =
              Modifier.fillMaxWidth()
                .clickable { showAdvancedSettings = !showAdvancedSettings }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
              Column {
                Text(
                  text = "Advanced / Custom Server (Optional)",
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Medium,
                )
                Text(
                  text = if (showAdvancedSettings) "Tap to collapse custom override fields" else "Tap to configure a private corporate TURN/Signaling relay",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            Icon(
              imageVector = if (showAdvancedSettings) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
              contentDescription = if (showAdvancedSettings) "Collapse" else "Expand",
            )
          }

          AnimatedVisibility(visible = showAdvancedSettings) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
              Text(
                text = "These custom settings are strictly optional. Standard operation does not require editing any of these fields.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )

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
                label = { Text("Optional TURN Relay URI (turn:host:port)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
              )
              OutlinedTextField(
                value = turnUsername,
                onValueChange = { turnUsername = it },
                label = { Text("TURN Username (Optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
              )
              OutlinedTextField(
                value = turnCredential,
                onValueChange = { turnCredential = it },
                label = { Text("TURN Credential (Optional)") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
              )
              OutlinedTextField(
                value = connectionToken,
                onValueChange = { connectionToken = it },
                label = { Text("Signaling Auth Token (Optional)") },
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

              Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                Button(
                  onClick = { viewModel.saveInternetConfig(draft) },
                  modifier = Modifier.weight(1f).testTag("inet_save_button"),
                ) {
                  Text("Save Custom Config")
                }
                OutlinedButton(
                  onClick = {
                    signalingUrl = InternetConnectivityConfig.DEFAULT_SIGNALING_URL
                    stunUri = InternetConnectivityConfig.DEFAULT_STUN_URI
                    turnUri = ""
                    turnUsername = ""
                    turnCredential = ""
                    connectionToken = ""
                    updateManifestUrl = InternetConnectivityConfig.DEFAULT_UPDATE_MANIFEST_URL
                    viewModel.clearInternetConfig()
                  },
                  modifier = Modifier.weight(1f),
                ) {
                  Text("Revert to Defaults")
                }
              }
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(16.dp))
    }
  }
}

@Composable
private fun ServiceFeatureRow(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  title: String,
  detail: String,
) {
  Row(
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(20.dp).padding(top = 2.dp),
    )
    Column {
      Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
      Text(text = detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}
