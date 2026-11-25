package com.example.ui.screens.modules

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingControlScreen(
  viewModel: MainViewModel,
  onBack: () -> Unit,
) {
  val recording by viewModel.recordingSettings.collectAsStateWithLifecycle()
  val hasCallPerm by viewModel.callLogPermissionGranted.collectAsStateWithLifecycle()

  val callPermLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      viewModel.refreshPermissionStates()
      if (granted) {
        viewModel.setCallLogRecordingEnabled(true)
      }
    }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.module_recording_control)) },
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("recording_back")) {
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
      // Master Switch
      Surface(
        shape = RoundedCornerShape(18.dp),
        color =
          if (recording.masterEnabled) MaterialTheme.colorScheme.primaryContainer
          else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Row(
          modifier = Modifier.padding(18.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text("MASTER RECORDING", style = MaterialTheme.typography.titleLarge)
            Text(
              if (recording.masterEnabled) "ON — Authorized recording modules are operational."
              else "OFF — All local recording is paused.",
              style = MaterialTheme.typography.bodyMedium,
            )
          }
          Switch(
            checked = recording.masterEnabled,
            onCheckedChange = { viewModel.setMasterRecording(it) },
            modifier = Modifier.testTag("switch_master_recording"),
          )
        }
      }

      // 1. App Install / Uninstall Tracking
      val appOperational = recording.masterEnabled && recording.appTrackingEnabled
      Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier =
          Modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)),
      ) {
        Row(
          modifier = Modifier.padding(18.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column(modifier = Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Application Installation / Uninstallation Recording", style = MaterialTheme.typography.titleMedium)
            Text(
              text =
                when {
                  !recording.masterEnabled -> "Status: Paused (Master Recording is OFF)"
                  !recording.appTrackingEnabled -> "Status: Disabled"
                  else -> "Status: Active (Listening to package events)"
                },
              style = MaterialTheme.typography.labelSmall,
              color =
                if (appOperational) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(
            checked = recording.appTrackingEnabled,
            onCheckedChange = { viewModel.setAppTrackingEnabled(it) },
            modifier = Modifier.testTag("switch_app_tracking"),
          )
        }
      }

      // 2. Call Log Recording
      val callOperational = recording.masterEnabled && recording.callLogEnabled && hasCallPerm
      Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier =
          Modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)),
      ) {
        Row(
          modifier = Modifier.padding(18.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column(modifier = Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Call Log Recording (Metadata Only)", style = MaterialTheme.typography.titleMedium)
            Text(
              text =
                when {
                  !hasCallPerm -> "Status: Unavailable (READ_CALL_LOG permission missing)"
                  !recording.masterEnabled -> "Status: Paused (Master Recording is OFF)"
                  !recording.callLogEnabled -> "Status: Disabled"
                  else -> "Status: Active (Metadata only, no audio)"
                },
              style = MaterialTheme.typography.labelSmall,
              color =
                when {
                  callOperational -> MaterialTheme.colorScheme.secondary
                  !hasCallPerm -> MaterialTheme.colorScheme.error
                  else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
          }
          Switch(
            checked = recording.callLogEnabled && hasCallPerm,
            onCheckedChange = { wantEnable ->
              if (wantEnable && !hasCallPerm) {
                callPermLauncher.launch(Manifest.permission.READ_CALL_LOG)
              } else {
                viewModel.setCallLogRecordingEnabled(wantEnable)
              }
            },
            modifier = Modifier.testTag("switch_call_log"),
          )
        }
      }

      Text(
        text = "Disabled features stop collecting new events immediately. Events occurring while recording is off are never reconstructed or fabricated.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
