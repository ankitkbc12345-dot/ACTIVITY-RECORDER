package com.example.ui.screens.modules

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.monitoring.DateTimeUtils
import com.example.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
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
  val syncStatus by viewModel.callLogSyncStatus.collectAsStateWithLifecycle()

  val isLocal = activeId == localId
  var numberQuery by remember { mutableStateOf("") }
  var dateFilter by remember { mutableStateOf("") }
  var typeFilter by remember { mutableStateOf("ALL") }
  var permissionDeniedAttempt by remember { mutableStateOf(false) }

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

  LaunchedEffect(isLocal, hasPermission, recording.masterEnabled, recording.callLogEnabled) {
    viewModel.refreshPermissionStates()
    if (isLocal && hasPermission && recording.masterEnabled && recording.callLogEnabled) {
      permissionDeniedAttempt = false
      viewModel.syncLocalCallLogs()
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.module_call_logs)) },
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("call_logs_back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back_cd))
          }
        },
        actions = {
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
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
          ) {
            Row(
              modifier = Modifier.padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text(
                "Call Log Recording is currently paused.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
              )
              OutlinedButton(
                onClick = {
                  if (!recording.masterEnabled) viewModel.setMasterRecording(true)
                  viewModel.onCallLogPermissionGranted()
                },
              ) {
                Text("Enable")
              }
            }
          }
        }

        if (syncStatus.isNotBlank()) {
          Text(
            text = syncStatus,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
          )
        }

        Column(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedTextField(
            value = numberQuery,
            onValueChange = { numberQuery = it },
            placeholder = { Text("Search phone number...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
          OutlinedTextField(
            value = dateFilter,
            onValueChange = { dateFilter = it },
            placeholder = { Text("Filter by date (yyyy-MM-dd)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
          Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            listOf("ALL", "INCOMING", "OUTGOING", "MISSED", "REJECTED").forEach { t ->
              FilterChip(selected = typeFilter == t, onClick = { typeFilter = t }, label = { Text(t) })
            }
          }
        }

        val filtered =
          remember(callLogs, numberQuery, dateFilter, typeFilter) {
            callLogs.filter { log ->
              (numberQuery.isBlank() || log.phoneNumber.contains(numberQuery, ignoreCase = true) || log.contactName.contains(numberQuery, ignoreCase = true)) &&
                (dateFilter.isBlank() || log.callDate.contains(dateFilter.trim())) &&
                (typeFilter == "ALL" || log.callType == typeFilter)
            }
          }

        LazyColumn(
          contentPadding = PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
          modifier = Modifier.fillMaxSize().testTag("call_log_records_list"),
        ) {
          item {
            Text(
              text = "Metadata only (no call audio or microphone access). Showing ${filtered.size} record(s).",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          if (filtered.isEmpty()) {
            item {
              Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Text(
                  text = "No call log records found for the current filter. Real records appear automatically once synced from the device call log.",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(16.dp),
                )
              }
            }
          } else {
            items(filtered, key = { it.id }) { log ->
              Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier =
                  Modifier.fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
              ) {
                Row(
                  modifier = Modifier.padding(14.dp),
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  val icon =
                    when (log.callType) {
                      "INCOMING" -> Icons.AutoMirrored.Filled.CallReceived
                      "OUTGOING" -> Icons.AutoMirrored.Filled.CallMade
                      else -> Icons.AutoMirrored.Filled.CallMissed
                    }
                  val tint =
                    when (log.callType) {
                      "MISSED",
                      "REJECTED" -> MaterialTheme.colorScheme.error
                      "INCOMING" -> MaterialTheme.colorScheme.secondary
                      else -> MaterialTheme.colorScheme.primary
                    }
                  Icon(icon, contentDescription = log.callType, tint = tint)
                  Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val titleText = if (log.contactName.isNotBlank()) "${log.contactName} (${log.phoneNumber})" else log.phoneNumber
                    Text(titleText, style = MaterialTheme.typography.titleMedium)
                    Text(
                      "${log.callDate} • ${log.callTime} • Duration: ${DateTimeUtils.formatDuration(log.durationSeconds)}",
                      style = MaterialTheme.typography.bodyMedium,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                  Text(log.callType, style = MaterialTheme.typography.labelSmall, color = tint)
                }
              }
            }
          }
        }
      }
    }
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
