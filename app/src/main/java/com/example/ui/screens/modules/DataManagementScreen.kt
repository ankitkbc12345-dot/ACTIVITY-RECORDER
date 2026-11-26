package com.example.ui.screens.modules

import android.text.format.Formatter
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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

  var pruneCallLogs by remember { mutableStateOf(true) }
  var pruneActivityEvents by remember { mutableStateOf(true) }
  var pruneUnlockLogs by remember { mutableStateOf(true) }
  var selectedWindowDays by remember { mutableStateOf<Int?>(30) } // 7, 30, 90, null (All)

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
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      // Storage Metrics Card
      Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier =
          Modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)),
      ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text("Local SQLite Storage Metrics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
          Text(
            "Database Size: ${Formatter.formatFileSize(context, metrics.databaseSizeBytes)}",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text("App Activity Events: ${metrics.totalEvents}", style = MaterialTheme.typography.bodyMedium)
          Text("Call Log Records: ${metrics.totalCallLogs}", style = MaterialTheme.typography.bodyMedium)
          Text("Device Unlock Events: ${metrics.totalUnlockLogs}", style = MaterialTheme.typography.bodyMedium)
          Text("Tracked Inventory Entries: ${metrics.totalInventory}", style = MaterialTheme.typography.bodyMedium)
          Text("Paired Devices: ${metrics.totalPairedDevices}", style = MaterialTheme.typography.bodyMedium)
        }
      }

      // Granular Pruning & Retention Card (Point 13)
      Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier =
          Modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)),
      ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.FilterList, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("Granular Data Retention & Pruning", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
          }

          Text(
            "Select data categories to include in pruning:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          // Category Checkboxes
          Row(
            modifier = Modifier.fillMaxWidth().clickable { pruneCallLogs = !pruneCallLogs },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text("Call Log Records (${metrics.totalCallLogs})", style = MaterialTheme.typography.bodyLarge)
            Checkbox(
              checked = pruneCallLogs,
              onCheckedChange = { pruneCallLogs = it },
              modifier = Modifier.testTag("check_prune_calls"),
            )
          }

          Row(
            modifier = Modifier.fillMaxWidth().clickable { pruneActivityEvents = !pruneActivityEvents },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text("App Activity Events (${metrics.totalEvents})", style = MaterialTheme.typography.bodyLarge)
            Checkbox(
              checked = pruneActivityEvents,
              onCheckedChange = { pruneActivityEvents = it },
              modifier = Modifier.testTag("check_prune_activity"),
            )
          }

          Row(
            modifier = Modifier.fillMaxWidth().clickable { pruneUnlockLogs = !pruneUnlockLogs },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text("Device Screen Unlock History (${metrics.totalUnlockLogs})", style = MaterialTheme.typography.bodyLarge)
            Checkbox(
              checked = pruneUnlockLogs,
              onCheckedChange = { pruneUnlockLogs = it },
              modifier = Modifier.testTag("check_prune_unlocks"),
            )
          }

          HorizontalDivider()

          Text(
            "Select age threshold / time-window to purge:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          // Age Threshold FilterChips
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            listOf(
              7 to "Older > 7d",
              30 to "Older > 30d",
              90 to "Older > 90d",
              null to "All Records",
            ).forEach { (days, label) ->
              FilterChip(
                selected = selectedWindowDays == days,
                onClick = { selectedWindowDays = days },
                label = { Text(label) },
                modifier = Modifier.weight(1f),
              )
            }
          }

          val hasSelection = pruneCallLogs || pruneActivityEvents || pruneUnlockLogs
          val windowLabel = if (selectedWindowDays == null) "ALL records" else "records older than $selectedWindowDays days"

          Button(
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            enabled = hasSelection,
            onClick = {
              val cats = mutableListOf<String>()
              if (pruneCallLogs) cats.add("Call Logs")
              if (pruneActivityEvents) cats.add("App Activity Events")
              if (pruneUnlockLogs) cats.add("Device Unlock History")
              val summary = "Purge $windowLabel for selected categories:\n• ${cats.joinToString("\n• ")} for device $activeId?"
              confirmAction = summary to {
                viewModel.pruneData(
                  pruneCallLogs = pruneCallLogs,
                  pruneActivity = pruneActivityEvents,
                  pruneUnlocks = pruneUnlockLogs,
                  olderThanDays = selectedWindowDays,
                )
              }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("prune_selected_button"),
          ) {
            Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Prune Selected Data ($windowLabel)")
          }

          if (activeId != localId) {
            OutlinedButton(
              colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
              onClick = {
                confirmAction =
                  "Delete all imported snapshot records for paired device $activeId?" to {
                    viewModel.clearDeviceSnapshotData(activeId)
                  }
              },
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text("Clear Imported Snapshot Data ($activeId)")
            }
          }
        }
      }

      // Compact & Optimize Local Database (VACUUM)
      Button(
        onClick = { viewModel.vacuumDatabase() },
        modifier = Modifier.fillMaxWidth().height(50.dp).testTag("vacuum_db_button"),
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
