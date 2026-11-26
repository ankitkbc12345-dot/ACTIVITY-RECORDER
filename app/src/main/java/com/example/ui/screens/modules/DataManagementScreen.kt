package com.example.ui.screens.modules

import android.text.format.Formatter
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
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataManagementScreen(
  viewModel: MainViewModel,
  onBack: () -> Unit,
) {
  val context = LocalContext.current
  val metrics by viewModel.storageMetrics.collectAsStateWithLifecycle()
  val activeId by viewModel.activeDeviceId.collectAsStateWithLifecycle()
  val localId by viewModel.localDeviceId.collectAsStateWithLifecycle()

  var confirmAction by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

  LaunchedEffect(Unit) { viewModel.refreshStorageMetrics() }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.module_data_management)) },
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("data_mgmt_back")) {
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
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier =
          Modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)),
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text("Local SQLite Storage Metrics", style = MaterialTheme.typography.titleMedium)
          Text(
            "Database Size: ${Formatter.formatFileSize(context, metrics.databaseSizeBytes)}",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
          )
          Text("App Activity Events: ${metrics.totalEvents}", style = MaterialTheme.typography.bodyMedium)
          Text("Tracked Inventory Entries: ${metrics.totalInventory}", style = MaterialTheme.typography.bodyMedium)
          Text("Call Log Records: ${metrics.totalCallLogs}", style = MaterialTheme.typography.bodyMedium)
          Text("Paired Devices: ${metrics.totalPairedDevices}", style = MaterialTheme.typography.bodyMedium)
        }
      }

      Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier =
          Modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)),
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text("Activity & Call History Pruning", style = MaterialTheme.typography.titleMedium)

          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
              onClick = {
                confirmAction =
                  "Delete app activity events older than 30 days for $activeId?" to
                    { viewModel.clearActivityEvents(30) }
              },
              modifier = Modifier.weight(1f),
            ) {
              Text("Events > 30d")
            }
            OutlinedButton(
              onClick = {
                confirmAction =
                  "Delete ALL app activity events for $activeId?" to
                    { viewModel.clearActivityEvents(null) }
              },
              modifier = Modifier.weight(1f),
            ) {
              Text("All Events")
            }
          }

          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
              onClick = {
                confirmAction =
                  "Delete call log records older than 30 days for $activeId?" to
                    { viewModel.clearCallLogs(30) }
              },
              modifier = Modifier.weight(1f),
            ) {
              Text("Calls > 30d")
            }
            OutlinedButton(
              onClick = {
                confirmAction =
                  "Delete ALL call log records for $activeId?" to
                    { viewModel.clearCallLogs(null) }
              },
              modifier = Modifier.weight(1f),
            ) {
              Text("All Calls")
            }
          }

          if (activeId != localId) {
            Button(
              colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
              onClick = {
                confirmAction =
                  "Delete all imported snapshot records for paired device $activeId?" to
                    { viewModel.clearDeviceSnapshotData(activeId) }
              },
              modifier = Modifier.fillMaxWidth(),
            ) {
              Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
              Text("Clear Imported Snapshot Data")
            }
          }
        }
      }

      Button(
        onClick = { viewModel.vacuumDatabase() },
        modifier = Modifier.fillMaxWidth().testTag("vacuum_db_button"),
      ) {
        Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
        Text("Compact & Optimize Local Database (VACUUM)")
      }
    }
  }

  confirmAction?.let { (message, action) ->
    AlertDialog(
      onDismissRequest = { confirmAction = null },
      title = { Text("Confirm Data Deletion") },
      text = { Text(message) },
      confirmButton = {
        Button(
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
          onClick = {
            action()
            confirmAction = null
          },
        ) {
          Text("Delete")
        }
      },
      dismissButton = { TextButton(onClick = { confirmAction = null }) { Text("Cancel") } },
    )
  }
}
