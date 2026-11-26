package com.example.ui.screens.modules

import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.horizontalScroll
import com.example.data.preferences.CloudSyncSchedule
import com.example.data.preferences.CloudSyncPreferences
import com.example.data.remote.CloudSyncScheduler
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.DeviceRecorderApp
import com.example.R
import com.example.data.remote.CloudActivityRecord
import com.example.data.remote.CloudDeviceSnapshot
import com.example.data.remote.CloudRecordCategory
import com.example.data.remote.CloudSyncStatus
import com.example.data.remote.FirestoreSyncRepository
import com.example.data.remote.formatUserFacingFirestoreError
import com.example.ui.auth.GoogleSignInButton
import com.example.ui.auth.attemptAutoSignIn
import com.example.ui.auth.signOutFromGoogle
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface CloudUiState<out T> {
  data object Loading : CloudUiState<Nothing>

  data class Success<T>(val data: T) : CloudUiState<T>

  data class Error(val message: String) : CloudUiState<Nothing>
}

class CloudSyncViewModel(
  val appInstance: DeviceRecorderApp,
  private val repository: FirestoreSyncRepository,
  @Suppress("unused") private val currentUserId: String,
) : ViewModel() {

  private val databaseId = appInstance.getString(R.string.firestore_database_id)

  private val _isSyncing = MutableStateFlow(false)
  val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

  private val _statusMessage = MutableStateFlow<String?>(null)
  val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

  val snapshotsState: StateFlow<CloudUiState<List<CloudDeviceSnapshot>>> =
    repository
      .observeDeviceSnapshots()
      .map<List<CloudDeviceSnapshot>, CloudUiState<List<CloudDeviceSnapshot>>> {
        CloudUiState.Success(it)
      }
      .catch { error ->
        val firestoreError =
          generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val rootError = firestoreError ?: error
        emit(
          CloudUiState.Error(
            formatUserFacingFirestoreError(rootError, databaseId = databaseId)
          )
        )
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = CloudUiState.Loading,
      )

  val recordsState: StateFlow<CloudUiState<List<CloudActivityRecord>>> =
    repository
      .observeActivityRecords()
      .map<List<CloudActivityRecord>, CloudUiState<List<CloudActivityRecord>>> {
        CloudUiState.Success(it)
      }
      .catch { error ->
        val firestoreError =
          generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val rootError = firestoreError ?: error
        emit(
          CloudUiState.Error(
            formatUserFacingFirestoreError(rootError, databaseId = databaseId)
          )
        )
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = CloudUiState.Loading,
      )

  fun restoreFromCloud(targetDeviceId: String) {
    if (_isSyncing.value) return
    viewModelScope.launch {
      _isSyncing.value = true
      _statusMessage.value = null
      try {
        val result = repository.restoreDataFromCloud(appInstance.database, targetDeviceId)
        _statusMessage.value =
          result.fold(
            onSuccess = { count ->
              "Restored $count record(s) from Firebase Cloud into local database."
            },
            onFailure = { err ->
              "Restore failed: ${formatUserFacingFirestoreError(err, databaseId = databaseId)}"
            },
          )
      } finally {
        _isSyncing.value = false
      }
    }
  }

  fun syncLocalDevice(localDeviceId: String, localDeviceName: String, summaryNote: String?) {
    if (_isSyncing.value) return
    viewModelScope.launch {
      _isSyncing.value = true
      _statusMessage.value = null
      try {
        val result =
          repository.syncLocalDeviceToCloud(
            localDb = appInstance.database,
            localDeviceId = localDeviceId,
            localDeviceName = localDeviceName,
            summaryNote = summaryNote,
          )
        _statusMessage.value =
          result.fold(
            onSuccess = { summary ->
              "Synced ${summary.deviceId}: ${summary.uploadedRecordsCount} record(s) uploaded to Cloud Firestore."
            },
            onFailure = { err ->
              "Cloud sync failed: ${formatUserFacingFirestoreError(err, databaseId = databaseId)}"
            },
          )
      } finally {
        _isSyncing.value = false
      }
    }
  }

  fun toggleSnapshotStatus(snapshot: CloudDeviceSnapshot) {
    viewModelScope.launch {
      val res = repository.toggleSnapshotSyncStatus(snapshot)
      res.onFailure { err ->
        _statusMessage.value =
          "Status update failed: ${formatUserFacingFirestoreError(err, databaseId = databaseId)}"
      }
    }
  }

  fun deleteSnapshot(snapshotId: String) {
    viewModelScope.launch {
      val res = repository.deleteDeviceSnapshot(snapshotId)
      _statusMessage.value =
        res.fold(
          onSuccess = { "Device snapshot removed from Cloud Firestore." },
          onFailure = { err ->
            "Delete failed: ${formatUserFacingFirestoreError(err, databaseId = databaseId)}"
          },
        )
    }
  }

  fun deleteActivityRecord(recordId: String) {
    viewModelScope.launch {
      val res = repository.deleteActivityRecord(recordId)
      res.onFailure { err ->
        _statusMessage.value =
          "Delete failed: ${formatUserFacingFirestoreError(err, databaseId = databaseId)}"
      }
    }
  }

  companion object {
    fun factory(app: DeviceRecorderApp, userId: String): ViewModelProvider.Factory =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
          val repo = FirestoreSyncRepository(app)
          return CloudSyncViewModel(app, repo, userId) as T
        }
      }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudSyncScreen(
  app: DeviceRecorderApp,
  localDeviceId: String,
  localDeviceName: String,
  onBack: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val credentialManager = remember(context) { CredentialManager.create(context) }

  var currentUser by remember { mutableStateOf(Firebase.auth.currentUser) }

  DisposableEffect(Unit) {
    val listener = FirebaseAuth.AuthStateListener { auth -> currentUser = auth.currentUser }
    Firebase.auth.addAuthStateListener(listener)
    onDispose { Firebase.auth.removeAuthStateListener(listener) }
  }

  LaunchedEffect(Unit) {
    if (Firebase.auth.currentUser == null) {
      attemptAutoSignIn(
        context = context,
        credentialManager = credentialManager,
        onAuthSuccess = { currentUser = Firebase.auth.currentUser },
        onUnauthenticated = {},
        scope = scope,
      )
    }
  }

  if (currentUser == null) {
    CloudSyncAuthGateScreen(
      onAuthSuccess = { currentUser = Firebase.auth.currentUser },
      onBack = onBack,
    )
  } else {
    val uid = currentUser!!.uid
    key(uid) {
      val cloudViewModel: CloudSyncViewModel =
        viewModel(key = uid, factory = CloudSyncViewModel.factory(app, uid))
      CloudSyncAuthenticatedScreen(
        viewModel = cloudViewModel,
        user = currentUser!!,
        localDeviceId = localDeviceId,
        localDeviceName = localDeviceName,
        onSignOut = {
          signOutFromGoogle(
            credentialManager = credentialManager,
            scope = scope,
            onSignOutComplete = { currentUser = null },
          )
        },
        onBack = onBack,
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudSyncAuthGateScreen(
  onAuthSuccess: () -> Unit,
  onBack: () -> Unit,
) {
  var authError by remember { mutableStateOf<String?>(null) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.module_cloud_sync)) },
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("cloud_sync_back")) {
            Icon(
              Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.nav_back_cd),
            )
          }
        },
      )
    }
  ) { innerPadding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(innerPadding)
          .verticalScroll(rememberScrollState())
          .padding(20.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier =
          Modifier.widthIn(max = 480.dp)
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp)),
      ) {
        Column(
          modifier = Modifier.padding(24.dp),
          verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Icon(
              Icons.Default.CloudSync,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(32.dp),
            )
            Column {
              Text(
                "Firebase Cloud Sync",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
              )
              Text(
                "Private multi-device snapshot & activity sync",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Icon(
                Icons.Default.VerifiedUser,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
              )
              Text(
                "Sign in with Google to sync encrypted device summaries and activity timelines to your private Cloud Firestore workspace.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
              )
            }
          }

          if (authError != null) {
            Text(
              text = authError!!,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.error,
              modifier = Modifier.testTag("cloud_auth_error"),
            )
          }

          GoogleSignInButton(
            onAuthSuccess = {
              authError = null
              onAuthSuccess()
            },
            onAuthError = { msg -> authError = msg },
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudSyncAuthenticatedScreen(
  viewModel: CloudSyncViewModel,
  user: FirebaseUser,
  localDeviceId: String,
  localDeviceName: String,
  onSignOut: () -> Unit,
  onBack: () -> Unit,
) {
  val snapshotsState by viewModel.snapshotsState.collectAsStateWithLifecycle()
  val recordsState by viewModel.recordsState.collectAsStateWithLifecycle()
  val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
  val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()

  var summaryNote by remember { mutableStateOf("") }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.module_cloud_sync)) },
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("cloud_sync_back")) {
            Icon(
              Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.nav_back_cd),
            )
          }
        },
        actions = {
          IconButton(
            onClick = onSignOut,
            modifier = Modifier.testTag("cloud_signout_button"),
          ) {
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Sign out of Google Cloud Sync")
          }
        },
      )
    }
  ) { innerPadding ->
    LazyColumn(
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.fillMaxSize().padding(innerPadding).testTag("cloud_sync_list"),
    ) {
      item {
        Surface(
          shape = RoundedCornerShape(18.dp),
          color = MaterialTheme.colorScheme.primaryContainer,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Icon(
                Icons.Default.CloudDone,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
              )
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = user.displayName?.takeIf { it.isNotBlank() } ?: "Google Cloud Account",
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                  text = user.email ?: "UID: ${user.uid.take(12)}...",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
              }
            }

            OutlinedTextField(
              value = summaryNote,
              onValueChange = { if (it.length <= 500) summaryNote = it },
              label = { Text("Optional sync note (max 500 chars)") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth().testTag("cloud_sync_note_field"),
            )

            val prefs by viewModel.appInstance.settingsStore.cloudSyncPreferencesFlow.collectAsStateWithLifecycle(initialValue = CloudSyncPreferences())
            val coroutineScope = rememberCoroutineScope()
            val currentContext = LocalContext.current

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("Sync Frequency / Schedule:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
              }
              Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                listOf(
                  CloudSyncSchedule.ALWAYS to "Always On",
                  CloudSyncSchedule.AUTO_NETWORK to "Auto on Net",
                  CloudSyncSchedule.EVERY_1_MIN to "1 Min",
                  CloudSyncSchedule.EVERY_1_HOUR to "1 Hour",
                  CloudSyncSchedule.EVERY_5_HOURS to "5 Hours",
                  CloudSyncSchedule.EVERY_10_HOURS to "10 Hours",
                  CloudSyncSchedule.DAILY to "Daily",
                ).forEach { (sched, label) ->
                  FilterChip(
                    selected = prefs.schedule == sched,
                    onClick = {
                      coroutineScope.launch {
                        viewModel.appInstance.settingsStore.setCloudSyncSchedule(sched)
                        CloudSyncScheduler.applySchedule(currentContext, sched, prefs.dailyHour, prefs.dailyMinute)
                      }
                    },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                  )
                }
              }
            }

            Button(
              onClick = {
                viewModel.syncLocalDevice(
                  localDeviceId = localDeviceId,
                  localDeviceName = localDeviceName,
                  summaryNote = summaryNote.ifBlank { null },
                )
              },
              enabled = !isSyncing,
              modifier = Modifier.fillMaxWidth().height(52.dp).testTag("cloud_sync_upload_button"),
            ) {
              if (isSyncing) {
                CircularProgressIndicator(
                  modifier = Modifier.size(20.dp),
                  color = MaterialTheme.colorScheme.onPrimary,
                  strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text("Syncing to Cloud Firestore...")
              } else {
                Icon(Icons.Default.CloudUpload, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sync $localDeviceName to Cloud")
              }
            }

            OutlinedButton(
              onClick = {
                viewModel.restoreFromCloud(localDeviceId)
              },
              enabled = !isSyncing,
              modifier = Modifier.fillMaxWidth().height(48.dp).testTag("cloud_restore_button"),
            ) {
              Icon(Icons.Default.CloudDownload, contentDescription = null)
              Spacer(modifier = Modifier.width(8.dp))
              Text("Cloud Recovery / Restore Data from Cloud")
            }

            if (statusMessage != null) {
              Text(
                text = statusMessage!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
              )
            }
          }
        }
      }

      item {
        Text(
          text = "Synced Device Snapshots",
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.padding(top = 4.dp),
        )
      }

      when (val state = snapshotsState) {
        CloudUiState.Loading -> {
          item {
            Box(
              modifier = Modifier.fillMaxWidth().padding(24.dp),
              contentAlignment = Alignment.Center,
            ) {
              CircularProgressIndicator()
            }
          }
        }
        is CloudUiState.Error -> {
          item {
            Surface(
              shape = RoundedCornerShape(14.dp),
              color = MaterialTheme.colorScheme.errorContainer,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(
                text = state.message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(14.dp),
              )
            }
          }
        }
        is CloudUiState.Success -> {
          if (state.data.isEmpty()) {
            item {
              Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Row(
                  modifier = Modifier.padding(16.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                  Icon(Icons.Default.CloudOff, contentDescription = null)
                  Text(
                    "No cloud device snapshots yet. Tap Sync above to upload this device's encrypted summary.",
                    style = MaterialTheme.typography.bodyMedium,
                  )
                }
              }
            }
          } else {
            items(state.data, key = { it.id.ifBlank { it.deviceId } }) { snap ->
              val isSynced = snap.resolvedStatus() == CloudSyncStatus.SYNCED
              Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier =
                  Modifier.fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
              ) {
                Column(
                  modifier = Modifier.padding(14.dp),
                  verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Column(modifier = Modifier.weight(1f)) {
                      Text(snap.deviceName, style = MaterialTheme.typography.titleMedium)
                      Text(
                        "${snap.deviceModel} • ${snap.deviceId} • ${snap.resolvedStatus().name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                    }
                    Row {
                      IconButton(onClick = { viewModel.toggleSnapshotStatus(snap) }) {
                        Icon(
                          imageVector =
                            if (isSynced) Icons.Default.PauseCircleOutline
                            else Icons.Default.PlayCircleOutline,
                          contentDescription = if (isSynced) "Pause cloud sync" else "Resume cloud sync",
                        )
                      }
                      IconButton(onClick = { viewModel.deleteSnapshot(snap.id.ifBlank { snap.deviceId }) }) {
                        Icon(
                          Icons.Default.DeleteOutline,
                          contentDescription = "Delete cloud device snapshot",
                          tint = MaterialTheme.colorScheme.error,
                        )
                      }
                    }
                  }
                  Text(
                    "Apps: ${snap.appCount} • Activity Events: ${snap.activityCount} • Call Logs: ${snap.callLogCount}",
                    style = MaterialTheme.typography.bodyMedium,
                  )
                  if (!snap.summaryNote.isNullOrBlank()) {
                    Text(
                      snap.summaryNote,
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
              }
            }
          }
        }
      }

      item {
        Text(
          text = "Recent Cloud Activity Timeline",
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.padding(top = 6.dp),
        )
      }

      when (val recState = recordsState) {
        CloudUiState.Loading -> {
          item {
            OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
              Text("Loading cloud records...")
            }
          }
        }
        is CloudUiState.Error -> {
          item {
            Text(
              text = recState.message,
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodyMedium,
            )
          }
        }
        is CloudUiState.Success -> {
          if (recState.data.isEmpty()) {
            item {
              Text(
                "No activity records synced yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          } else {
            items(recState.data, key = { it.id }) { rec ->
              Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier =
                  Modifier.fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
              ) {
                Row(
                  modifier = Modifier.padding(12.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                      "${rec.title} (${
                        when (rec.resolvedCategory()) {
                          CloudRecordCategory.APP_EVENT -> "App Event"
                          CloudRecordCategory.APP_INVENTORY -> "Inventory"
                          CloudRecordCategory.CALL_LOG -> "Call Metadata"
                          CloudRecordCategory.UNLOCK_LOG -> "Unlock History"
                        }
                      })",
                      style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                      rec.subtitle,
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                      listOfNotNull(rec.detail?.takeIf { it.isNotBlank() }, "${rec.eventDate} ${rec.eventTime}")
                        .joinToString(" • "),
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                  IconButton(onClick = { viewModel.deleteActivityRecord(rec.id) }) {
                    Icon(
                      Icons.Default.DeleteOutline,
                      contentDescription = "Delete synced record",
                      tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
