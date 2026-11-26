package com.example.ui.screens.modules

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.AppActivityEventEntity
import com.example.data.local.AppInventorySnapshotEntity
import com.example.monitoring.DateTimeUtils
import com.example.monitoring.PackageInventoryScanner
import com.example.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppActivityRecordsScreen(
  viewModel: MainViewModel,
  onBack: () -> Unit,
) {
  val context = LocalContext.current
  val inventory by viewModel.activeInventory.collectAsStateWithLifecycle()
  val events by viewModel.activeActivityEvents.collectAsStateWithLifecycle()
  val isScanning by viewModel.isScanningInventory.collectAsStateWithLifecycle()
  val activeDeviceId by viewModel.activeDeviceId.collectAsStateWithLifecycle()
  val localDeviceId by viewModel.localDeviceId.collectAsStateWithLifecycle()
  val isLocal = activeDeviceId == localDeviceId

  var selectedTab by remember { mutableIntStateOf(0) }
  var searchQuery by remember { mutableStateOf("") }
  var eventFilter by remember { mutableStateOf("ALL") }
  var sourceFilter by remember { mutableStateOf("ALL") }
  var dateFilter by remember { mutableStateOf("") }
  var sortAscending by remember { mutableStateOf(false) }
  var selectedAppForHistory by remember { mutableStateOf<String?>(null) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.module_app_activity)) },
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("app_activity_back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back_cd))
          }
        },
        actions = {
          if (isLocal) {
            IconButton(
              onClick = { viewModel.refreshLocalInventory() },
              enabled = !isScanning,
              modifier = Modifier.testTag("refresh_inventory_button"),
            ) {
              Icon(Icons.Default.Refresh, contentDescription = "Refresh Inventory")
            }
          }
        },
      )
    }
  ) { innerPadding ->
    Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
      if (isScanning) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      }

      val totalApps = inventory.size
      val currentlyInstalled = inventory.count { it.isInstalled }
      val totalInstalls = events.count { it.eventType == "INSTALLED" }
      val totalUninstalls = events.count { it.eventType == "UNINSTALLED" }

      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        StatChip("Total", totalApps.toString(), Modifier.weight(1f))
        StatChip("Installed", currentlyInstalled.toString(), Modifier.weight(1f))
        StatChip("Installs", totalInstalls.toString(), Modifier.weight(1f))
        StatChip("Uninstalls", totalUninstalls.toString(), Modifier.weight(1f))
      }

      TabRow(selectedTabIndex = selectedTab) {
        Tab(
          selected = selectedTab == 0,
          onClick = { selectedTab = 0 },
          text = { Text("Inventory ($totalApps)") },
          modifier = Modifier.testTag("tab_inventory"),
        )
        Tab(
          selected = selectedTab == 1,
          onClick = { selectedTab = 1 },
          text = { Text("Activity History (${events.size})") },
          modifier = Modifier.testTag("tab_events"),
        )
      }

      // Search & Filters
      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        OutlinedTextField(
          value = searchQuery,
          onValueChange = { searchQuery = it },
          placeholder = { Text("Search app name, package, or installer...") },
          leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("app_activity_search"),
        )
        Row(
          modifier = Modifier.horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          if (selectedTab == 1) {
            listOf("ALL", "INSTALLED", "UNINSTALLED", "UPDATED").forEach { type ->
              FilterChip(
                selected = eventFilter == type,
                onClick = { eventFilter = type },
                label = { Text(type) },
              )
            }
          }
          listOf("ALL" to "Any Source", "PLAY" to "Play Store", "KNOWN" to "Other Known", "UNKNOWN" to "Unknown")
            .forEach { (key, label) ->
              FilterChip(
                selected = sourceFilter == key,
                onClick = { sourceFilter = key },
                label = { Text(label) },
              )
            }
          FilterChip(
            selected = sortAscending,
            onClick = { sortAscending = !sortAscending },
            label = { Text(if (sortAscending) "Sort: Oldest / A-Z" else "Sort: Newest / Z-A") },
          )
        }
        if (selectedTab == 1) {
          OutlinedTextField(
            value = dateFilter,
            onValueChange = { dateFilter = it },
            placeholder = { Text("Filter by date (yyyy-MM-dd)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }

      if (selectedTab == 0) {
        val filteredInv =
          remember(inventory, searchQuery, sourceFilter, sortAscending) {
            inventory
              .filter { item ->
                val matchesQ =
                  searchQuery.isBlank() ||
                    item.appName.contains(searchQuery, ignoreCase = true) ||
                    item.packageName.contains(searchQuery, ignoreCase = true) ||
                    item.installerLabel.contains(searchQuery, ignoreCase = true)
                val matchesSource =
                  when (sourceFilter) {
                    "PLAY" -> item.installerLabel == "Google Play Store"
                    "KNOWN" -> item.installerLabel.startsWith("Known") || (item.installerLabel != "Google Play Store" && item.installerLabel != "Unknown")
                    "UNKNOWN" -> item.installerLabel == "Unknown"
                    else -> true
                  }
                matchesQ && matchesSource
              }
              .let { list ->
                if (sortAscending) list.sortedBy { it.appName.lowercase() }
                else list.sortedByDescending { it.firstInstallTimeMillis ?: 0L }
              }
          }

        LazyColumn(
          contentPadding = PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
          modifier = Modifier.fillMaxSize(),
        ) {
          if (filteredInv.isEmpty()) {
            item {
              Text(
                "No applications match the current filter.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          } else {
            items(filteredInv, key = { it.id }) { item ->
              val iconDrawable =
                remember(item.packageName, isLocal) {
                  if (isLocal) PackageInventoryScanner.loadAppIcon(context, item.packageName) else null
                }
              InventoryRowCard(
                item = item,
                iconDrawable = iconDrawable,
                isLocal = isLocal,
                onClick = { selectedAppForHistory = item.packageName },
                onUninstall = {
                  try {
                    val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
                      data = Uri.parse("package:${item.packageName}")
                      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(uninstallIntent)
                  } catch (e: Exception) {
                    viewModel.showBanner("Cannot trigger uninstall: ${e.message}")
                  }
                },
              )
            }
          }
        }
      } else {
        val filteredEvents =
          remember(events, searchQuery, eventFilter, sourceFilter, dateFilter, sortAscending) {
            events
              .filter { ev ->
                val matchesQ =
                  searchQuery.isBlank() ||
                    ev.appName.contains(searchQuery, ignoreCase = true) ||
                    ev.packageName.contains(searchQuery, ignoreCase = true) ||
                    ev.installerLabel.contains(searchQuery, ignoreCase = true)
                val matchesType = eventFilter == "ALL" || ev.eventType == eventFilter
                val matchesDate = dateFilter.isBlank() || ev.eventDate.contains(dateFilter.trim())
                val matchesSource =
                  when (sourceFilter) {
                    "PLAY" -> ev.installerLabel == "Google Play Store"
                    "KNOWN" -> ev.installerLabel != "Google Play Store" && ev.installerLabel != "Unknown"
                    "UNKNOWN" -> ev.installerLabel == "Unknown"
                    else -> true
                  }
                matchesQ && matchesType && matchesDate && matchesSource
              }
              .let { list ->
                if (sortAscending) list.sortedBy { it.timestampMillis }
                else list.sortedByDescending { it.timestampMillis }
              }
          }

        LazyColumn(
          contentPadding = PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
          modifier = Modifier.fillMaxSize(),
        ) {
          if (filteredEvents.isEmpty()) {
            item {
              Text(
                "No recorded installation or uninstallation events yet. Real-time events are captured when Master Recording and App Tracking are ON.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          } else {
            items(filteredEvents, key = { it.id }) { ev ->
              ActivityEventRowCard(ev) { selectedAppForHistory = ev.packageName }
            }
          }
        }
      }
    }
  }

  selectedAppForHistory?.let { pkg ->
    val appEvents = events.filter { it.packageName == pkg }
    val invItem = inventory.firstOrNull { it.packageName == pkg }
    AlertDialog(
      onDismissRequest = { selectedAppForHistory = null },
      title = { Text(invItem?.appName ?: pkg) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text("Package: $pkg", style = MaterialTheme.typography.labelSmall)
          Text("Installer: ${invItem?.installerLabel ?: "Unknown"}", style = MaterialTheme.typography.bodyMedium)
          Text(
            "First Install: ${invItem?.firstInstallTimeMillis?.let { DateTimeUtils.formatDateTime(it) } ?: "Unavailable"}",
            style = MaterialTheme.typography.bodyMedium,
          )
          Spacer(modifier = Modifier.height(6.dp))
          Text("Application-wise History (${appEvents.size})", style = MaterialTheme.typography.titleMedium)
          if (appEvents.isEmpty()) {
            Text("No install/uninstall events recorded since monitoring began.", style = MaterialTheme.typography.bodyMedium)
          } else {
            appEvents.take(10).forEach { ev ->
              Text(
                "${ev.eventDate} ${ev.eventTime} • ${ev.eventType} (${ev.installerLabel})",
                style = MaterialTheme.typography.labelSmall,
              )
            }
          }
        }
      },
      confirmButton = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          if (isLocal && invItem?.isInstalled == true) {
            Button(
              colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
              onClick = {
                try {
                  val intent = Intent(Intent.ACTION_DELETE).apply {
                    data = Uri.parse("package:$pkg")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                  }
                  context.startActivity(intent)
                  selectedAppForHistory = null
                } catch (e: Exception) {
                  viewModel.showBanner("Cannot launch uninstall: ${e.message}")
                }
              },
              modifier = Modifier.testTag("app_activity_dialog_uninstall"),
            ) {
              Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("Uninstall")
            }
          }
          TextButton(onClick = { selectedAppForHistory = null }) { Text("Close") }
        }
      },
    )
  }
}

@Composable
private fun StatChip(label: String, value: String, modifier: Modifier = Modifier) {
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surface,
    modifier = modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
  ) {
    Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
      Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun InventoryRowCard(
  item: AppInventorySnapshotEntity,
  iconDrawable: Drawable?,
  isLocal: Boolean,
  onClick: () -> Unit,
  onUninstall: () -> Unit,
) {
  val bitmap =
    remember(iconDrawable) {
      iconDrawable?.let { drawableToBitmap(it) }
    }
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surface,
    modifier =
      Modifier.fillMaxWidth()
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
        .clickable(onClick = onClick),
  ) {
    Row(
      modifier = Modifier.padding(14.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (bitmap != null) {
        Image(
          bitmap = bitmap.asImageBitmap(),
          contentDescription = item.appName,
          modifier = Modifier.size(40.dp),
        )
      } else {
        Icon(Icons.Default.Android, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
      }
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Text(item.appName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
          Text(
            if (item.isInstalled) "Installed" else "Uninstalled",
            style = MaterialTheme.typography.labelSmall,
            color = if (item.isInstalled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
          )
        }
        Text("${item.packageName} • v${item.versionName}", style = MaterialTheme.typography.labelSmall)
        Text(
          "Installed: ${item.installDate} ${item.installTime} • Source: ${item.installerLabel}",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (isLocal && item.isInstalled) {
        IconButton(
          onClick = onUninstall,
          modifier = Modifier.size(40.dp).testTag("row_uninstall_${item.packageName}"),
        ) {
          Icon(
            imageVector = Icons.Default.DeleteForever,
            contentDescription = "Uninstall ${item.appName}",
            tint = MaterialTheme.colorScheme.error,
          )
        }
      }
    }
  }
}

@Composable
private fun ActivityEventRowCard(ev: AppActivityEventEntity, onClick: () -> Unit) {
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surface,
    modifier =
      Modifier.fillMaxWidth()
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
        .clickable(onClick = onClick),
  ) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(ev.appName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(
          ev.eventType,
          style = MaterialTheme.typography.labelSmall,
          color =
            when (ev.eventType) {
              "INSTALLED" -> MaterialTheme.colorScheme.secondary
              "UNINSTALLED" -> MaterialTheme.colorScheme.error
              else -> MaterialTheme.colorScheme.primary
            },
        )
      }
      Text(ev.packageName, style = MaterialTheme.typography.labelSmall)
      Text(
        "${ev.eventDate} at ${ev.eventTime} • Source: ${ev.installerLabel}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

private fun drawableToBitmap(drawable: Drawable): Bitmap? {
  return try {
    if (drawable is BitmapDrawable) {
      val src = drawable.bitmap
      if (src != null && !src.isRecycled) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && src.config == Bitmap.Config.HARDWARE) {
          return src.copy(Bitmap.Config.ARGB_8888, false)
        }
        return src
      }
    }
    val w = drawable.intrinsicWidth.coerceIn(48, 128)
    val h = drawable.intrinsicHeight.coerceIn(48, 128)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    bmp  } catch (_: Throwable) {
    null
  }
}
