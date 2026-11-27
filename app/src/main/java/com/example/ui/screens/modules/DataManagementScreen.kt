package com.example.ui.screens.modules

import android.text.format.Formatter
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.monitoring.DateTimeUtils
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
  val callLogs by viewModel.activeCallLogs.collectAsStateWithLifecycle()
  val activityEvents by viewModel.activeActivityEvents.collectAsStateWithLifecycle()
  val unlockEvents by viewModel.activeUnlockEvents.collectAsStateWithLifecycle()

  var pruneCallLogs by remember { mutableStateOf(true) }
  var pruneActivityEvents by remember { mutableStateOf(true) }
  var pruneUnlockLogs by remember { mutableStateOf(true) }
  var selectedWindowDays by remember { mutableStateOf<Int?>(30) } // 7, 30, 90, null (All)

  // Step 4: Flexible Single / Multi / Select All deletion inside Data Management
  var selectiveCategory by remember { mutableIntStateOf(0) } // 0=Calls, 1=AppActivity, 2=Unlocks
  var selectedCallIds by remember { mutableStateOf(setOf<String>()) }
  var selectedEventIds by remember { mutableStateOf(setOf<Long>()) }
  var selectedUnlockIds by remember { mutableStateOf(setOf<String>()) }

  var confirmAction by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

  LaunchedEffect(Unit) { viewModel.refreshStorageMetrics() }

  val currentSelectedCount =
    when (selectiveCategory) {
      0 -> selectedCallIds.size
      1 -> selectedEventIds.size
      else -> selectedUnlockIds.size
    }

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
    LazyColumn(
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
      modifier = Modifier.fillMaxSize().padding(innerPadding).testTag("data_mgmt_list"),
    ) {
      // Storage Metrics Card (Preserved unchanged)
      item {
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
      }

      // Granular Pruning & Retention Card (Point 13)
      item {
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
      }

      // Compact & Optimize Local Database (VACUUM)
      item {
        Button(
          onClick = { viewModel.vacuumDatabase() },
          modifier = Modifier.fillMaxWidth().height(50.dp).testTag("vacuum_db_button"),
        ) {
          Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
          Text("Compact & Optimize Local Database (VACUUM)")
        }
      }

      // Step 4: Selective Single / Multi / Select All Record Deletion
      item {
        Surface(
          shape = RoundedCornerShape(18.dp),
          color = MaterialTheme.colorScheme.surface,
          modifier =
            Modifier.fillMaxWidth()
              .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)),
        ) {
          Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
              "Selective Record Cleanup (Single / Multi / Select All)",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
            )
            Row(
              modifier = Modifier.horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              FilterChip(
                selected = selectiveCategory == 0,
                onClick = { selectiveCategory = 0 },
                label = { Text("Call Logs (${callLogs.size})") },
                modifier = Modifier.testTag("dm_tab_calls"),
              )
              FilterChip(
                selected = selectiveCategory == 1,
                onClick = { selectiveCategory = 1 },
                label = { Text("App Activity (${activityEvents.size})") },
                modifier = Modifier.testTag("dm_tab_activity"),
              )
              FilterChip(
                selected = selectiveCategory == 2,
                onClick = { selectiveCategory = 2 },
                label = { Text("Unlock History (${unlockEvents.size})") },
                modifier = Modifier.testTag("dm_tab_unlocks"),
              )
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              val allSelected =
                when (selectiveCategory) {
                  0 -> callLogs.isNotEmpty() && selectedCallIds.size == callLogs.size
                  1 -> activityEvents.isNotEmpty() && selectedEventIds.size == activityEvents.size
                  else -> unlockEvents.isNotEmpty() && selectedUnlockIds.size == unlockEvents.size
                }
              OutlinedButton(
                onClick = {
                  when (selectiveCategory) {
                    0 -> selectedCallIds = if (allSelected) emptySet() else callLogs.mapTo(HashSet()) { it.id }
                    1 -> selectedEventIds = if (allSelected) emptySet() else activityEvents.mapTo(HashSet()) { it.id }
                    else -> selectedUnlockIds = if (allSelected) emptySet() else unlockEvents.mapTo(HashSet()) { it.id }
                  }
                },
                enabled =
                  when (selectiveCategory) {
                    0 -> callLogs.isNotEmpty()
                    1 -> activityEvents.isNotEmpty()
                    else -> unlockEvents.isNotEmpty()
                  },
                modifier = Modifier.weight(1f).testTag("dm_select_all_button"),
              ) {
                Icon(Icons.Default.SelectAll, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (allSelected) "Deselect All" else "Select All")
              }

              Button(
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                enabled = currentSelectedCount > 0,
                onClick = {
                  val count = currentSelectedCount
                  confirmAction = "Delete $count selected record(s) permanently?" to {
                    when (selectiveCategory) {
                      0 -> {
                        viewModel.deleteCallLogsByIds(selectedCallIds)
                        selectedCallIds = emptySet()
                      }
                      1 -> {
                        viewModel.deleteActivityEventsByIds(selectedEventIds)
                        selectedEventIds = emptySet()
                      }
                      else -> {
                        viewModel.deleteUnlockEventsByIds(selectedUnlockIds)
                        selectedUnlockIds = emptySet()
                      }
                    }
                  }
                },
                modifier = Modifier.weight(1f).testTag("dm_delete_selected_button"),
              ) {
                Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Delete ($currentSelectedCount)")
              }
            }
          }
        }
      }

      when (selectiveCategory) {
        0 -> {
          if (callLogs.isEmpty()) {
            item {
              Text(
                "No call log records for active device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          } else {
            items(callLogs, key = { "dm_call_${it.id}" }) { log ->
              val checked = log.id in selectedCallIds
              SelectiveRecordRow(
                title = log.contactName.takeIf { it.isNotBlank() } ?: log.phoneNumber,
                subtitle = "${log.callType} • ${log.callDate} ${log.callTime} • ${DateTimeUtils.formatDuration(log.durationSeconds)}",
                checked = checked,
                onToggle = {
                  selectedCallIds = if (checked) selectedCallIds - log.id else selectedCallIds + log.id
                },
                onDeleteSingle = {
                  confirmAction = "Delete this call log record?" to {
                    viewModel.deleteCallLogsByIds(setOf(log.id))
                    selectedCallIds = selectedCallIds - log.id
                  }
                },
              )
            }
          }
        }
        1 -> {
          if (activityEvents.isEmpty()) {
            item {
              Text(
                "No application activity events for active device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          } else {
            items(activityEvents, key = { "dm_ev_${it.id}" }) { ev ->
              val checked = ev.id in selectedEventIds
              SelectiveRecordRow(
                title = "${ev.appName} (${ev.eventType})",
                subtitle = "${ev.eventDate} ${ev.eventTime} • ${ev.packageName}",
                checked = checked,
                onToggle = {
                  selectedEventIds = if (checked) selectedEventIds - ev.id else selectedEventIds + ev.id
                },
                onDeleteSingle = {
                  confirmAction = "Delete this activity event?" to {
                    viewModel.deleteActivityEventsByIds(setOf(ev.id))
                    selectedEventIds = selectedEventIds - ev.id
                  }
                },
              )
            }
          }
        }
        else -> {
          if (unlockEvents.isEmpty()) {
            item {
              Text(
                "No screen unlock records for active device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          } else {
            items(unlockEvents, key = { "dm_unlock_${it.id}" }) { ev ->
              val checked = ev.id in selectedUnlockIds
              SelectiveRecordRow(
                title = ev.unlockMethod,
                subtitle = "${ev.unlockDate} ${ev.unlockTime} • ${ev.source}",
                checked = checked,
                onToggle = {
                  selectedUnlockIds = if (checked) selectedUnlockIds - ev.id else selectedUnlockIds + ev.id
                },
                onDeleteSingle = {
                  confirmAction = "Delete this unlock record?" to {
                    viewModel.deleteUnlockEventsByIds(setOf(ev.id))
                    selectedUnlockIds = selectedUnlockIds - ev.id
                  }
                },
              )
            }
          }
        }
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
          modifier = Modifier.testTag("dm_confirm_delete"),
        ) {
          Text("Delete")
        }
      },
      dismissButton = { TextButton(onClick = { confirmAction = null }) { Text("Cancel") } },
    )
  }
}

@Composable
private fun SelectiveRecordRow(
  title: String,
  subtitle: String,
  checked: Boolean,
  onToggle: () -> Unit,
  onDeleteSingle: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(14.dp),
    color =
      if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
      else MaterialTheme.colorScheme.surface,
    modifier =
      Modifier.fillMaxWidth()
        .border(
          width = if (checked) 1.5.dp else 1.dp,
          color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
          shape = RoundedCornerShape(14.dp),
        )
        .clickable(onClick = onToggle),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Checkbox(checked = checked, onCheckedChange = { onToggle() })
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
          subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      IconButton(onClick = onDeleteSingle, modifier = Modifier.size(36.dp)) {
        Icon(
          Icons.Default.DeleteForever,
          contentDescription = "Delete record",
          tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }
}
