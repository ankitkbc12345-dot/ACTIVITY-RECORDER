package com.example.ui.screens.modules

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Save
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(
  viewModel: MainViewModel,
  onBack: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  var passphrase by remember { mutableStateOf("") }
  var statusMessage by remember { mutableStateOf<String?>(null) }

  val exportLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
      if (uri != null) {
        scope.launch {
          val res = viewModel.exportBackupToUri(uri, passphrase)
          statusMessage =
            res.fold(
              onSuccess = {
                "Encrypted backup saved: ${it.eventsCount} events, ${it.inventoryCount} apps, ${it.callLogsCount} calls, ${it.pairedDevicesCount} paired devices."
              },
              onFailure = { "Backup export failed: ${it.message}" },
            )
        }
      }
    }

  val restoreLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        scope.launch {
          val res = viewModel.restoreBackupFromUri(uri, passphrase)
          statusMessage =
            res.fold(
              onSuccess = {
                "Verified & restored: ${it.eventsCount} events, ${it.inventoryCount} apps, ${it.callLogsCount} calls, ${it.pairedDevicesCount} paired devices."
              },
              onFailure = { "Restore failed (wrong passphrase or corrupted file): ${it.message}" },
            )
        }
      }
    }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.module_backup_restore)) },
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("backup_back")) {
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
      Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          "Backups are strictly offline local files encrypted with AES-256-GCM + PBKDF2WithHmacSHA256 and verified with SHA-256 checksums. No cloud storage is used.",
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(14.dp),
        )
      }

      Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier =
          Modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text("Encryption Passphrase (min 6 chars)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("backup_passphrase_field"),
          )

          Button(
            onClick = {
              try {
                exportLauncher.launch("device_recorder_backup_${System.currentTimeMillis()}.pdrbak")
              } catch (e: Exception) {
                statusMessage = "Unable to open file saver: ${e.message}"
              }
            },
            enabled = passphrase.length >= 6,
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag("backup_export_button"),
          ) {
            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text("Export Encrypted Backup (.pdrbak)")
          }

          OutlinedButton(
            onClick = {
              try {
                restoreLauncher.launch(arrayOf("*/*"))
              } catch (e: Exception) {
                statusMessage = "Unable to open file picker: ${e.message}"
              }
            },
            enabled = passphrase.length >= 6,
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag("backup_restore_button"),
          ) {
            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text("Restore From Encrypted Backup")
          }
        }
      }

      if (statusMessage != null) {
        Text(statusMessage!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
      }
    }
  }
}
