package com.example.ui.screens.modules

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.monitoring.DateTimeUtils
import com.example.monitoring.UnlockTracker
import com.example.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnlockLogsScreen(
  viewModel: MainViewModel,
  onOpenExport: () -> Unit,
  onBack: () -> Unit,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val localId by viewModel.localDeviceId.collectAsStateWithLifecycle()
  val activeId by viewModel.activeDeviceId.collectAsStateWithLifecycle()
  val unlockEvents by viewModel.activeUnlockEvents.collectAsStateWithLifecycle()
  val batteryStatus by viewModel.batteryStatus.collectAsStateWithLifecycle()
  val recording by viewModel.recordingSettings.collectAsStateWithLifecycle()
  val cloudPrefs by viewModel.cloudSyncPreferences.collectAsStateWithLifecycle()

  val isLocal = activeId == localId
  var hasUsageAccess by remember { mutableStateOf(UnlockTracker.hasUsageStatsPermission(context)) }
  var methodQuery by remember { mutableStateOf("") }
  var dateFilter by remember { mutableStateOf("") }
  var categoryFilter by remember { mutableStateOf("ALL") }

  val todayStr = remember { DateTimeUtils.formatDate(System.currentTimeMillis()) }
  val todayCount = remember(unlockEvents, todayStr) { unlockEvents.count { it.unlockDate == todayStr } }
  val detectedProfile = remember(context) { UnlockTracker.detectUnlockMethod(context).second }

  DisposableEffect(lifecycleOwner) {
    val observer =
      LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
          hasUsageAccess = UnlockTracker.hasUsageStatsPermission(context)
          viewModel.refreshUnlockLogs()
        }
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  LaunchedEffect(Unit) {
    hasUsageAccess = UnlockTracker.hasUsageStatsPermission(context)
    viewModel.refreshUnlockLogs()
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.unlock_logs_title)) },
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("unlock_logs_back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back_cd))
          }
        },
        actions = {
          IconButton(onClick = onOpenExport, modifier = Modifier.testTag("unlock_logs_export")) {
            Icon(Icons.Default.PictureAsPdf, contentDescription = stringResource(R.string.export_report_action))
          }
          if (isLocal) {
            IconButton(
              onClick = { viewModel.refreshUnlockLogs() },
              modifier = Modifier.testTag("unlock_logs_refresh"),
            ) {
              Icon(Icons.Default.Refresh, contentDescription = "Refresh Unlock Logs")
            }
          }
        },
      )
    }
  ) { innerPadding ->
    Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
      // Status & Daily Summary Card
      Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column {
              Text(
                text = "Today's Unlocks: $todayCount  •  Total: ${unlockEvents.size}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
              )
              Text(
                text = "Lock Profile: $detectedProfile",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
              )
            }
          }
          Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              Icon(
                Icons.Default.BatteryChargingFull,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
              )
              Text(
                text = "${stringResource(R.string.battery_status_label)}: ${batteryStatus.statusLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
              )
            }
            Text(
              text = "${stringResource(R.string.sync_status_label)}: ${cloudPrefs.schedule.name}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
          }
        }
      }

      if (isLocal && !hasUsageAccess) {
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
          Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(
              text = "Live screen unlocks are active. Grant Usage Access to also recover unlocks recorded when the app was closed.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSecondaryContainer,
              modifier = Modifier.weight(1f),
            )
            OutlinedButton(
              onClick = {
                try {
                  context.startActivity(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                  )
                } catch (_: Exception) {
                  viewModel.showBanner("Open System Settings -> Security -> Usage Access.")
                }
              },
              modifier = Modifier.testTag("unlock_grant_usage_access"),
            ) {
              Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.padding(horizontal = 2.dp))
              Text("Allow")
            }
          }
        }
      }

      if (isLocal && (!recording.masterEnabled || !recording.unlockTrackingEnabled)) {
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
          Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(
              "Unlock tracking is currently paused.",
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.weight(1f),
            )
            OutlinedButton(
              onClick = {
                if (!recording.masterEnabled) viewModel.setMasterRecording(true)
                viewModel.setUnlockTrackingEnabled(true)
              },
            ) {
              Text("Enable")
            }
          }
        }
      }

      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        OutlinedTextField(
          value = methodQuery,
          onValueChange = { methodQuery = it },
          placeholder = { Text("Search unlock method or source...") },
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
          listOf(
              "ALL" to "All",
              "BIOMETRIC_ENROLLED" to "Biometric",
              "PIN_PATTERN_PASSWORD" to "PIN / Pattern",
              "SWIPE_OR_NONE" to "Swipe",
            )
            .forEach { (key, label) ->
              FilterChip(
                selected = categoryFilter == key,
                onClick = { categoryFilter = key },
                label = { Text(label) },
              )
            }
        }
      }

      val filtered =
        remember(unlockEvents, methodQuery, dateFilter, categoryFilter) {
          unlockEvents.filter { ev ->
            val qOk =
              methodQuery.isBlank() ||
                ev.unlockMethod.contains(methodQuery, ignoreCase = true) ||
                ev.source.contains(methodQuery, ignoreCase = true)
            val dOk = dateFilter.isBlank() || ev.unlockDate.contains(dateFilter.trim())
            val cOk = categoryFilter == "ALL" || ev.unlockCategory == categoryFilter
            qOk && dOk && cOk
          }
        }

      LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize().testTag("unlock_logs_list"),
      ) {
        if (filtered.isEmpty()) {
          item {
            Surface(
              shape = RoundedCornerShape(16.dp),
              color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(
                text = "No screen unlock events recorded yet. Lock and unlock your phone once — it will appear here automatically with time, method, and battery %.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
              )
            }
          }
        } else {
          items(filtered, key = { it.id }) { ev ->
            val icon =
              when (ev.unlockCategory) {
                "BIOMETRIC_ENROLLED" -> Icons.Default.Fingerprint
                "PIN_PATTERN_PASSWORD" -> Icons.Default.Password
                else -> Icons.Default.LockOpen
              }
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
                Icon(icon, contentDescription = ev.unlockMethod, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                  Text(ev.unlockMethod, style = MaterialTheme.typography.titleMedium)
                  Text(
                    "${ev.unlockDate} • ${ev.unlockTime} • ${if (ev.source == "USAGE_STATS") "System History" else "Live Unlock"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                ev.batteryPercent?.let { pct ->
                  Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                  ) {
                    Text(
                      text = "$pct%",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSecondaryContainer,
                      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
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
}
