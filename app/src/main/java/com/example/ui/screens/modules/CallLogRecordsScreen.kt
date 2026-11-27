package com.example.ui.screens.modules

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.CallLogEntity
import com.example.monitoring.DateTimeUtils
import com.example.ui.MainViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CallLogRecordsScreen(
  viewModel: MainViewModel,
  onBack: () -> Unit,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val localId by viewModel.localDeviceId.collectAsStateWithLifecycle()
  val activeId by viewModel.activeDeviceId.collectAsStateWithLifecycle()
  val callLogs by viewModel.activeCallLogs.collectAsStateWithLifecycle()
  val hasPermission by viewModel.callLogPermissionGranted.collectAsStateWithLifecycle()
  val recording by viewModel.recordingSettings.collectAsStateWithLifecycle()

  val isLocal = activeId == localId
  var numberQuery by remember { mutableStateOf("") }
  var dateFilter by remember { mutableStateOf("") }
  var showDateFilter by remember { mutableStateOf(false) }
  var typeFilter by remember { mutableStateOf("ALL") }
  var permissionDeniedAttempt by remember { mutableStateOf(false) }

  // Selective delete state (Single / Multi / Select All)
  var selectionMode by remember { mutableStateOf(false) }
  var selectedIds by remember { mutableStateOf(setOf<String>()) }
  var pendingDeleteIds by remember { mutableStateOf<Set<String>?>(null) }
  var selectedCallDetail by remember { mutableStateOf<CallLogEntity?>(null) }

  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) {
        permissionDeniedAttempt = false
        viewModel.onCallLogPermissionGranted()
      } else {
        permissionDeniedAttempt = true
        viewModel.onCallLogPermissionDenied()
      }
    }

  DisposableEffect(lifecycleOwner) {
    val observer =
      LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
          viewModel.refreshPermissionStates()
        }
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  // Real-time call log sync when permitted & active
  LaunchedEffect(isLocal, hasPermission, recording.masterEnabled, recording.callLogEnabled) {
    viewModel.refreshPermissionStates()
    if (isLocal && hasPermission && recording.masterEnabled && recording.callLogEnabled) {
      permissionDeniedAttempt = false
      while (true) {
        delay(15_000L)
        viewModel.syncLocalCallLogs()
      }
    }
  }

  val filtered =
    remember(callLogs, numberQuery, dateFilter, typeFilter) {
      callLogs.filter { log ->
        val qOk =
          numberQuery.isBlank() ||
            log.phoneNumber.contains(numberQuery, ignoreCase = true) ||
            log.contactName.contains(numberQuery, ignoreCase = true)
        val dOk = !showDateFilter || dateFilter.isBlank() || log.callDate.contains(dateFilter.trim())
        val tOk = typeFilter == "ALL" || log.callType == typeFilter
        qOk && dOk && tOk
      }
    }

  val countsByType =
    remember(callLogs) {
      mapOf(
        "ALL" to callLogs.size,
        "INCOMING" to callLogs.count { it.callType == "INCOMING" },
        "OUTGOING" to callLogs.count { it.callType == "OUTGOING" },
        "MISSED" to callLogs.count { it.callType == "MISSED" },
        "REJECTED" to callLogs.count { it.callType == "REJECTED" },
      )
    }

  // Keep selection set pruned if items disappear
  LaunchedEffect(callLogs) {
    val validIds = callLogs.mapTo(HashSet()) { it.id }
    val pruned = selectedIds.intersect(validIds)
    if (pruned.size != selectedIds.size) {
      selectedIds = pruned
      if (pruned.isEmpty()) selectionMode = false
    }
  }

  Scaffold(
    topBar = {
      if (selectionMode) {
        TopAppBar(
          title = { Text("${selectedIds.size} selected") },
          navigationIcon = {
            IconButton(
              onClick = {
                selectionMode = false
                selectedIds = emptySet()
              },
              modifier = Modifier.testTag("call_logs_cancel_selection"),
            ) {
              Icon(Icons.Default.Close, contentDescription = "Cancel selection")
            }
          },
          actions = {
            val allVisibleIds = filtered.mapTo(HashSet()) { it.id }
            val allSelected = allVisibleIds.isNotEmpty() && allVisibleIds.all { it in selectedIds }
            IconButton(
              onClick = {
                selectedIds = if (allSelected) emptySet() else allVisibleIds
              },
              modifier = Modifier.testTag("call_logs_select_all"),
            ) {
              Icon(Icons.Default.SelectAll, contentDescription = "Select All")
            }
            IconButton(
              onClick = {
                if (selectedIds.isNotEmpty()) {
                  pendingDeleteIds = selectedIds
                }
              },
              enabled = selectedIds.isNotEmpty(),
              modifier = Modifier.testTag("call_logs_delete_selected"),
            ) {
              Icon(
                Icons.Default.DeleteForever,
                contentDescription = "Delete selected",
                tint = if (selectedIds.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          },
        )
      } else {
        TopAppBar(
          title = { Text(stringResource(R.string.module_call_logs)) },
          navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.testTag("call_logs_back")) {
              Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back_cd))
            }
          },
          actions = {
            if (!isLocal || hasPermission) {
              IconButton(
                onClick = { showDateFilter = !showDateFilter },
                modifier = Modifier.testTag("call_logs_date_toggle"),
              ) {
                Icon(
                  Icons.Default.DateRange,
                  contentDescription = "Toggle date filter",
                  tint = if (showDateFilter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
              }
              if (callLogs.isNotEmpty()) {
                IconButton(
                  onClick = {
                    selectionMode = true
                    selectedIds = emptySet()
                  },
                  modifier = Modifier.testTag("call_logs_select_mode"),
                ) {
                  Icon(Icons.Default.Checklist, contentDescription = "Select records")
                }
              }
            }
            if (isLocal && hasPermission) {
              IconButton(
                onClick = { viewModel.syncLocalCallLogs() },
                modifier = Modifier.testTag("call_logs_sync"),
              ) {
                Icon(Icons.Default.Refresh, contentDescription = "Sync Call Logs")
              }
            }
          },
        )
      }
    }
  ) { innerPadding ->
    if (isLocal && !hasPermission) {
      CallLogAccessRequiredContent(
        permissionDeniedAttempt = permissionDeniedAttempt,
        onGrantPermission = { permissionLauncher.launch(Manifest.permission.READ_CALL_LOG) },
        onOpenAppSettings = {
          try {
            val intent =
              Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
              ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
          } catch (_: Exception) {
            viewModel.showBanner("Unable to open system App Settings.")
          }
        },
        modifier = Modifier.fillMaxSize().padding(innerPadding),
      )
    } else {
      Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        if (isLocal && (!recording.masterEnabled || !recording.callLogEnabled)) {
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text(
                "Live Call Log Recording is paused.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
              )
              OutlinedButton(
                onClick = {
                  if (!recording.masterEnabled) viewModel.setMasterRecording(true)
                  viewModel.onCallLogPermissionGranted()
                },
              ) {
                Text("Resume")
              }
            }
          }
        }

        // Clean Calling-App Search & Standard Filters
        Column(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedTextField(
            value = numberQuery,
            onValueChange = { numberQuery = it },
            placeholder = { Text("Search contacts or numbers...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
              if (numberQuery.isNotEmpty()) {
                IconButton(onClick = { numberQuery = "" }) {
                  Icon(Icons.Default.Close, contentDescription = "Clear search")
                }
              }
            },
            shape = RoundedCornerShape(24.dp),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("call_logs_search"),
          )

          if (showDateFilter) {
            OutlinedTextField(
              value = dateFilter,
              onValueChange = { dateFilter = it },
              placeholder = { Text("Date (yyyy-MM-dd)") },
              leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
              trailingIcon = {
                if (dateFilter.isNotEmpty()) {
                  IconButton(onClick = { dateFilter = "" }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear date")
                  }
                }
              },
              shape = RoundedCornerShape(16.dp),
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
            )
          }

          Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            listOf(
              "ALL" to "All",
              "INCOMING" to "Incoming",
              "OUTGOING" to "Outgoing",
              "MISSED" to "Missed",
              "REJECTED" to "Rejected",
            ).forEach { (key, label) ->
              val count = countsByType[key] ?: 0
              FilterChip(
                selected = typeFilter == key,
                onClick = { typeFilter = key },
                label = { Text("$label ($count)") },
                modifier = Modifier.testTag("call_filter_${key.lowercase()}"),
              )
            }
          }
        }

        LazyColumn(
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxSize().testTag("call_log_records_list"),
        ) {
          if (filtered.isEmpty()) {
            item {
              Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
              ) {
                Column(
                  modifier = Modifier.padding(24.dp),
                  horizontalAlignment = Alignment.CenterHorizontally,
                  verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Icon(
                    Icons.Default.Call,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp),
                  )
                  Text(
                    text = "No call history found",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                  )
                  Text(
                    text = "Real-time incoming, outgoing, missed, and rejected calls appear here automatically.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                  )
                }
              }
            }
          } else {
            items(filtered, key = { it.id }) { log ->
              val isSelected = log.id in selectedIds
              val (callIcon, callTint, callLabel) = callTypeStyle(log.callType)
              val primaryTitle = log.contactName.takeIf { it.isNotBlank() } ?: log.phoneNumber
              val initialChar =
                primaryTitle.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"

              Surface(
                shape = RoundedCornerShape(16.dp),
                color =
                  if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                  else MaterialTheme.colorScheme.surface,
                tonalElevation = if (isSelected) 2.dp else 1.dp,
                modifier =
                  Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(
                      width = if (isSelected) 1.5.dp else 1.dp,
                      color =
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                      shape = RoundedCornerShape(16.dp),
                    )
                    .combinedClickable(
                      onClick = {
                        if (selectionMode) {
                          selectedIds =
                            if (isSelected) selectedIds - log.id else selectedIds + log.id
                        } else {
                          selectedCallDetail = log
                        }
                      },
                      onLongClick = {
                        selectionMode = true
                        selectedIds = selectedIds + log.id
                      },
                    )
                    .testTag("call_log_item_${log.id}"),
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  if (selectionMode) {
                    Checkbox(
                      checked = isSelected,
                      onCheckedChange = { checked ->
                        selectedIds = if (checked) selectedIds + log.id else selectedIds - log.id
                      },
                      modifier = Modifier.testTag("call_log_check_${log.id}"),
                    )
                  } else {
                    Box(
                      modifier =
                        Modifier.size(44.dp)
                          .clip(CircleShape)
                          .background(callTint.copy(alpha = 0.14f)),
                      contentAlignment = Alignment.Center,
                    ) {
                      if (log.contactName.isNotBlank()) {
                        Text(
                          text = initialChar,
                          style = MaterialTheme.typography.titleMedium,
                          fontWeight = FontWeight.Bold,
                          color = callTint,
                        )
                      } else {
                        Icon(
                          imageVector = callIcon,
                          contentDescription = callLabel,
                          tint = callTint,
                          modifier = Modifier.size(22.dp),
                        )
                      }
                    }
                  }

                  Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                      text = primaryTitle,
                      style = MaterialTheme.typography.titleMedium,
                      fontWeight = FontWeight.SemiBold,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                      color =
                        if (log.callType == "MISSED" || log.callType == "REJECTED")
                          MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                      Icon(
                        imageVector = callIcon,
                        contentDescription = null,
                        tint = callTint,
                        modifier = Modifier.size(14.dp),
                      )
                      val subDetails =
                        buildString {
                          if (log.contactName.isNotBlank()) {
                            append(log.phoneNumber)
                            append(" • ")
                          }
                          append(callLabel)
                          if (log.durationSeconds > 0L) {
                            append(" • ")
                            append(DateTimeUtils.formatDuration(log.durationSeconds))
                          }
                        }
                      Text(
                        text = subDetails,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                      )
                    }
                  }

                  Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                  ) {
                    Text(
                      text = log.callTime.take(5),
                      style = MaterialTheme.typography.labelMedium,
                      fontWeight = FontWeight.Medium,
                      color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                      text = log.callDate,
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  // Call History & Single-Record Delete Dialog
  selectedCallDetail?.let { detail ->
    val numberHistory =
      remember(callLogs, detail.phoneNumber) {
        callLogs.filter { it.phoneNumber == detail.phoneNumber }
      }
    val inCount = numberHistory.count { it.callType == "INCOMING" }
    val outCount = numberHistory.count { it.callType == "OUTGOING" }
    val missedCount = numberHistory.count { it.callType == "MISSED" || it.callType == "REJECTED" }

    AlertDialog(
      onDismissRequest = { selectedCallDetail = null },
      title = {
        Text(detail.contactName.takeIf { it.isNotBlank() } ?: detail.phoneNumber)
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          if (detail.contactName.isNotBlank()) {
            Text(detail.phoneNumber, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
          }
          Text(
            "Selected Call: ${detail.callType} • ${detail.callDate} at ${detail.callTime}",
            style = MaterialTheme.typography.bodySmall,
          )
          Text(
            "Duration: ${DateTimeUtils.formatDuration(detail.durationSeconds)}" +
              (detail.batteryPercent?.let { " • Battery: $it%" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
          )
          HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
          Text(
            "Call History (${numberHistory.size} total • In: $inCount, Out: $outCount, Missed/Rej: $missedCount)",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
          )
          numberHistory.take(5).forEach { h ->
            Text(
              "${h.callDate} ${h.callTime} • ${h.callType} (${DateTimeUtils.formatDuration(h.durationSeconds)})",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      },
      confirmButton = {
        Button(
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
          onClick = {
            pendingDeleteIds = setOf(detail.id)
            selectedCallDetail = null
          },
          modifier = Modifier.testTag("call_detail_delete_single"),
        ) {
          Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("Delete Record")
        }
      },
      dismissButton = {
        TextButton(onClick = { selectedCallDetail = null }) { Text("Close") }
      },
    )
  }

  // Selective / Bulk Delete Confirmation Dialog
  pendingDeleteIds?.let { idsToDelete ->
    AlertDialog(
      onDismissRequest = { pendingDeleteIds = null },
      title = { Text("Delete Call Log Records") },
      text = {
        Text("Are you sure you want to permanently delete ${idsToDelete.size} selected call log record(s) from this app's history?")
      },
      confirmButton = {
        Button(
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
          onClick = {
            viewModel.deleteCallLogsByIds(idsToDelete)
            selectedIds = emptySet()
            selectionMode = false
            pendingDeleteIds = null
          },
          modifier = Modifier.testTag("confirm_delete_calls"),
        ) {
          Text("Delete")
        }
      },
      dismissButton = {
        TextButton(onClick = { pendingDeleteIds = null }) { Text("Cancel") }
      },
    )
  }
}

@Composable
private fun callTypeStyle(callType: String): Triple<androidx.compose.ui.graphics.vector.ImageVector, Color, String> {
  return when (callType) {
    "INCOMING" -> Triple(Icons.AutoMirrored.Filled.CallReceived, MaterialTheme.colorScheme.secondary, "Incoming")
    "OUTGOING" -> Triple(Icons.AutoMirrored.Filled.CallMade, MaterialTheme.colorScheme.primary, "Outgoing")
    "MISSED" -> Triple(Icons.AutoMirrored.Filled.CallMissed, MaterialTheme.colorScheme.error, "Missed")
    "REJECTED" -> Triple(Icons.Default.PhoneDisabled, MaterialTheme.colorScheme.error, "Rejected")
    else -> Triple(Icons.Default.Call, MaterialTheme.colorScheme.tertiary, callType.lowercase().replaceFirstChar { it.uppercase() })
  }
}

@Composable
private fun CallLogAccessRequiredContent(
  permissionDeniedAttempt: Boolean,
  onGrantPermission: () -> Unit,
  onOpenAppSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.verticalScroll(rememberScrollState()).padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Surface(
      shape = RoundedCornerShape(24.dp),
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 2.dp,
      modifier =
        Modifier.widthIn(max = 520.dp)
          .fillMaxWidth()
          .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
          .testTag("call_log_access_required_card"),
    ) {
      Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.errorContainer,
          modifier = Modifier.size(64.dp),
        ) {
          Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
              imageVector = if (permissionDeniedAttempt) Icons.Default.PhoneDisabled else Icons.Default.Lock,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.error,
              modifier = Modifier.size(30.dp),
            )
          }
        }

        Text(
          text = stringResource(R.string.call_log_access_required_title),
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
          textAlign = TextAlign.Center,
        )

        Text(
          text = stringResource(R.string.call_log_access_required_desc),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )

        if (permissionDeniedAttempt) {
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
            modifier = Modifier.fillMaxWidth().testTag("call_log_denied_notice"),
          ) {
            Text(
              text = stringResource(R.string.call_log_access_denied_note),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.padding(12.dp),
              textAlign = TextAlign.Center,
            )
          }
        }

        Spacer(modifier = Modifier.height(2.dp))

        Button(
          onClick = onGrantPermission,
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier.fillMaxWidth().height(52.dp).testTag("grant_call_log_perm"),
        ) {
          Text(stringResource(R.string.call_log_grant_permission), fontWeight = FontWeight.SemiBold)
        }

        OutlinedButton(
          onClick = onOpenAppSettings,
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier.fillMaxWidth().height(48.dp).testTag("open_app_settings_call_log"),
        ) {
          Icon(
            Icons.Default.Settings,
            contentDescription = null,
            modifier = Modifier.padding(end = 8.dp).size(18.dp),
          )
          Text(stringResource(R.string.call_log_open_settings))
        }
      }
    }
  }
}
