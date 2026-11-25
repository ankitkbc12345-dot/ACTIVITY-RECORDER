package com.example.ui.screens.modules

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportPdfScreen(
  viewModel: MainViewModel,
  onBack: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val localId by viewModel.localDeviceId.collectAsStateWithLifecycle()
  val localName by viewModel.localDeviceName.collectAsStateWithLifecycle()
  val activeId by viewModel.activeDeviceId.collectAsStateWithLifecycle()
  val activePaired by viewModel.activePairedDevice.collectAsStateWithLifecycle()

  val activeLabel = if (activeId == localId) localName else (activePaired?.deviceName ?: activeId)

  var includeInventory by remember { mutableStateOf(true) }
  var includeEvents by remember { mutableStateOf(true) }
  var includeCallLogs by remember { mutableStateOf(true) }
  var dateStart by remember { mutableStateOf("") }
  var dateEnd by remember { mutableStateOf("") }
  var statusText by remember { mutableStateOf<String?>(null) }

  val savePdfLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
      if (uri != null) {
        scope.launch {
          val res =
            viewModel.exportPdfToUri(
              uri = uri,
              deviceId = activeId,
              deviceLabel = activeLabel,
              includeInventory = includeInventory,
              includeEvents = includeEvents,
              includeCallLogs = includeCallLogs,
              dateStart = dateStart.takeIf { it.isNotBlank() },
              dateEnd = dateEnd.takeIf { it.isNotBlank() },
            )
          statusText =
            res.fold(
              onSuccess = { pages -> "PDF report exported ($pages page(s)) successfully." },
              onFailure = { "Export failed: ${it.message}" },
            )
        }
      }
    }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.module_export_pdf)) },
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("export_pdf_back")) {
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
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text("Target Device Scope", style = MaterialTheme.typography.labelLarge)
          Text("$activeLabel ($activeId)", style = MaterialTheme.typography.titleMedium)
          Text(
            if (activeId == localId) "Source: Live Local Encrypted Database"
            else "Source: Imported Historical Snapshot",
            style = MaterialTheme.typography.bodyMedium,
          )
        }
      }

      Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier =
          Modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
      ) {
        Column(modifier = Modifier.padding(12.dp)) {
          Text("Report Sections", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(8.dp))
          Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = includeEvents, onCheckedChange = { includeEvents = it })
            Text("Application Activity Events (Install/Uninstall/Update)")
          }
          Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = includeCallLogs, onCheckedChange = { includeCallLogs = it })
            Text("Call Log Metadata Records")
          }
          Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = includeInventory, onCheckedChange = { includeInventory = it })
            Text("Installed Application Inventory")
          }
        }
      }

      OutlinedTextField(
        value = dateStart,
        onValueChange = { dateStart = it },
        label = { Text("Start Date (optional, yyyy-MM-dd)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
        value = dateEnd,
        onValueChange = { dateEnd = it },
        label = { Text("End Date (optional, yyyy-MM-dd)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )

      Button(
        onClick = {
          try {
            savePdfLauncher.launch("DeviceActivityReport_${activeId}_${System.currentTimeMillis()}.pdf")
          } catch (e: Exception) {
            statusText = "Unable to open document picker: ${e.message}"
          }
        },
        enabled = includeEvents || includeCallLogs || includeInventory,
        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("export_pdf_save_button"),
      ) {
        Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
        Text("Save PDF Report")
      }

      OutlinedButton(
        onClick = {
          scope.launch {
            val res =
              viewModel.exportPdfToInternalCache(
                deviceId = activeId,
                deviceLabel = activeLabel,
                includeInventory = includeInventory,
                includeEvents = includeEvents,
                includeCallLogs = includeCallLogs,
              )
            res.fold(
              onSuccess = { file ->
                val uri =
                  FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                  )
                val shareIntent =
                  Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                  }
                val chooser =
                  Intent.createChooser(shareIntent, "Share PDF Report").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                  }
                try {
                  context.startActivity(chooser)
                } catch (_: Exception) {
                  statusText = "PDF saved to cache (${file.name}), no PDF viewer/share app available."
                }
              },
              onFailure = { statusText = "Share failed: ${it.message}" },
            )
          }
        },
        enabled = includeEvents || includeCallLogs || includeInventory,
        modifier = Modifier.fillMaxWidth().height(52.dp),
      ) {
        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
        Text("Generate & Share PDF")
      }

      if (statusText != null) {
        Text(statusText!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
      }
    }
  }
}
