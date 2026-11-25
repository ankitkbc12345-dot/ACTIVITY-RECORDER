package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.DeviceRecorderApp
import com.example.connectivity.RemoteConnectionStatus
import com.example.connectivity.TransferProtocol
import com.example.connectivity.UpdateCheckResult
import com.example.data.local.AppActivityEventEntity
import com.example.data.local.AppDatabase
import com.example.data.local.AppInventorySnapshotEntity
import com.example.data.local.CallLogEntity
import com.example.data.local.PairedDeviceEntity
import com.example.data.local.RemoteUninstallRequestEntity
import com.example.data.preferences.InternetConnectivityConfig
import com.example.data.preferences.LoginOutcome
import com.example.data.preferences.PasswordChangeOutcome
import com.example.data.preferences.RecordingSettings
import com.example.data.preferences.SecuritySettingsState
import com.example.export.BackupRestoreSummary
import com.example.export.EncryptedBackupManager
import com.example.export.PdfReportGenerator
import com.example.export.PdfReportRequest
import com.example.monitoring.CallLogReader
import com.example.monitoring.CallLogSyncResult
import com.example.monitoring.PackageInventoryScanner
import com.example.security.CryptoManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StorageMetrics(
  val databaseSizeBytes: Long = 0L,
  val totalEvents: Int = 0,
  val totalInventory: Int = 0,
  val totalCallLogs: Int = 0,
  val totalPairedDevices: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
  private val app = application as DeviceRecorderApp
  private val db = app.database
  private val settingsStore = app.settingsStore

  val bluetoothManager = app.bluetoothManager
  val wifiDirectManager = app.wifiDirectManager
  val internetManager = app.internetManager

  private val _localDeviceId = MutableStateFlow("DEV-LOCAL")
  val localDeviceId: StateFlow<String> = _localDeviceId.asStateFlow()

  private val _localDeviceName = MutableStateFlow(CryptoManager.getLocalDeviceModel())
  val localDeviceName: StateFlow<String> = _localDeviceName.asStateFlow()

  private val _activeDeviceId = MutableStateFlow("DEV-LOCAL")
  val activeDeviceId: StateFlow<String> = _activeDeviceId.asStateFlow()

  private val _isAdminAuthenticated = MutableStateFlow(false)
  val isAdminAuthenticated: StateFlow<Boolean> = _isAdminAuthenticated.asStateFlow()
  @Volatile private var authStateExplicitlySet = false

  private val _lastActivityMillis = MutableStateFlow(System.currentTimeMillis())

  private val _callLogPermissionGranted = MutableStateFlow(CallLogReader.hasCallLogPermission(app))
  val callLogPermissionGranted: StateFlow<Boolean> = _callLogPermissionGranted.asStateFlow()

  private val _callLogSyncStatus = MutableStateFlow("")
  val callLogSyncStatus: StateFlow<String> = _callLogSyncStatus.asStateFlow()

  private val _isScanningInventory = MutableStateFlow(false)
  val isScanningInventory: StateFlow<Boolean> = _isScanningInventory.asStateFlow()

  private val _updateCheckResult = MutableStateFlow<UpdateCheckResult>(UpdateCheckResult.NotChecked)
  val updateCheckResult: StateFlow<UpdateCheckResult> = _updateCheckResult.asStateFlow()

  private val _isCheckingUpdates = MutableStateFlow(false)
  val isCheckingUpdates: StateFlow<Boolean> = _isCheckingUpdates.asStateFlow()

  private val _storageMetrics = MutableStateFlow(StorageMetrics())
  val storageMetrics: StateFlow<StorageMetrics> = _storageMetrics.asStateFlow()

  private val _statusBanner = MutableStateFlow<String?>(null)
  val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

  val recordingSettings: StateFlow<RecordingSettings> =
    settingsStore.recordingSettingsFlow.stateIn(
      viewModelScope,
      SharingStarted.Eagerly,
      RecordingSettings(),
    )

  val securitySettings: StateFlow<SecuritySettingsState> =
    settingsStore.securitySettingsFlow.stateIn(
      viewModelScope,
      SharingStarted.Eagerly,
      SecuritySettingsState(),
    )

  val languageCode: StateFlow<String> =
    settingsStore.languageCodeFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "en")

  val themeMode: StateFlow<String> =
    settingsStore.themeModeFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "system")

  val remoteUninstallMode: StateFlow<String> =
    settingsStore.remoteUninstallModeFlow.stateIn(
      viewModelScope,
      SharingStarted.Eagerly,
      "MODE_B_INTERACTIVE",
    )

  val internetConfig: StateFlow<InternetConnectivityConfig> =
    settingsStore.internetConfigFlow.stateIn(
      viewModelScope,
      SharingStarted.Eagerly,
      InternetConnectivityConfig(),
    )

  val pairedDevices: StateFlow<List<PairedDeviceEntity>> =
    db.pairedDeviceDao()
      .observePairedDevices()
      .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  val activeInventory: StateFlow<List<AppInventorySnapshotEntity>> =
    _activeDeviceId
      .flatMapLatest { devId -> db.appInventoryDao().observeInventoryForDevice(devId) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val activeActivityEvents: StateFlow<List<AppActivityEventEntity>> =
    _activeDeviceId
      .flatMapLatest { devId -> db.appActivityDao().observeEventsForDevice(devId) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val activeCallLogs: StateFlow<List<CallLogEntity>> =
    _activeDeviceId
      .flatMapLatest { devId ->
        db.callLogDao().observeCallLogsForDevice(devId).map { list ->
          list.map { it.copy(phoneNumber = CryptoManager.decryptField(app, it.phoneNumber)) }
        }
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val remoteUninstallRequests: StateFlow<List<RemoteUninstallRequestEntity>> =
    db.remoteUninstallDao()
      .observeRequests()
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val activePairedDevice: StateFlow<PairedDeviceEntity?> =
    combine(_activeDeviceId, pairedDevices) { activeId, list ->
      list.firstOrNull { it.deviceId == activeId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

  init {
    viewModelScope.launch {
      settingsStore.ensureInitialProvisioning()
      val persistedLoggedIn = settingsStore.isAdminLoggedInFlow.first()
      if (!authStateExplicitlySet) {
        _isAdminAuthenticated.value = persistedLoggedIn
      }
      val localId = settingsStore.getLocalDeviceId()
      val localName = settingsStore.getLocalDeviceName()
      _localDeviceId.value = localId
      _localDeviceName.value = localName
      _activeDeviceId.value = localId
      refreshLocalInventory()
      refreshStorageMetrics()
    }
  }

  fun clearBanner() {
    _statusBanner.value = null
  }

  fun showBanner(message: String) {
    _statusBanner.value = message
  }

  fun recordUserActivity() {
    _lastActivityMillis.value = System.currentTimeMillis()
  }

  suspend fun login(adminId: String, password: String): LoginOutcome {
    val outcome = settingsStore.verifyAdminLogin(adminId, password)
    if (outcome is LoginOutcome.Success) {
      authStateExplicitlySet = true
      _lastActivityMillis.value = System.currentTimeMillis()
      _isAdminAuthenticated.value = true
      refreshPermissionStates()
      refreshLocalInventory()
      refreshStorageMetrics()
    }
    return outcome
  }

  fun logout() {
    authStateExplicitlySet = true
    _isAdminAuthenticated.value = false
    viewModelScope.launch { settingsStore.setAdminLoggedIn(false) }
    bluetoothManager.stopListening()
    wifiDirectManager.stopSocketListener()
    internetManager.disconnect()
  }

  fun startBluetoothListening() {
    recordUserActivity()
    bluetoothManager.startListening(viewModelScope)
  }

  fun startWifiDirectListening() {
    recordUserActivity()
    wifiDirectManager.startSocketListener(viewModelScope)
  }

  suspend fun changePassword(
    adminId: String,
    current: String,
    newPass: String,
  ): PasswordChangeOutcome {
    recordUserActivity()
    return settingsStore.changePassword(adminId, current, newPass)
  }

  fun setInactivityTimeout(minutes: Int) {
    recordUserActivity()
    viewModelScope.launch { settingsStore.setInactivityTimeoutMinutes(minutes) }
  }

  fun setLocalDeviceName(name: String) {
    recordUserActivity()
    viewModelScope.launch {
      settingsStore.setLocalDeviceName(name)
      _localDeviceName.value = settingsStore.getLocalDeviceName()
    }
  }

  fun selectLocalDevice() {
    recordUserActivity()
    _activeDeviceId.value = _localDeviceId.value
  }

  fun selectPairedDevice(deviceId: String) {
    recordUserActivity()
    _activeDeviceId.value = deviceId
  }

  fun revokePairedDevice(deviceId: String) {
    recordUserActivity()
    viewModelScope.launch {
      db.pairedDeviceDao().deleteDevice(deviceId)
      if (_activeDeviceId.value == deviceId) {
        _activeDeviceId.value = _localDeviceId.value
      }
      refreshStorageMetrics()
      _statusBanner.value = "Paired device removed."
    }
  }

  fun setMasterRecording(enabled: Boolean) {
    recordUserActivity()
    viewModelScope.launch {
      settingsStore.setMasterRecording(enabled)
      if (enabled && recordingSettings.value.appTrackingEnabled) {
        refreshLocalInventory()
      }
    }
  }

  fun setAppTrackingEnabled(enabled: Boolean) {
    recordUserActivity()
    viewModelScope.launch {
      settingsStore.setAppTrackingEnabled(enabled)
      if (enabled && recordingSettings.value.masterEnabled) {
        refreshLocalInventory()
      }
    }
  }

  fun setCallLogRecordingEnabled(enabled: Boolean) {
    recordUserActivity()
    viewModelScope.launch {
      settingsStore.setCallLogRecordingEnabled(enabled)
      refreshPermissionStates()
      if (enabled && recordingSettings.value.masterEnabled && _callLogPermissionGranted.value) {
        syncLocalCallLogs()
      }
    }
  }

  fun refreshPermissionStates() {
    _callLogPermissionGranted.value = CallLogReader.hasCallLogPermission(app)
  }

  fun refreshLocalInventory() {
    viewModelScope.launch {
      _isScanningInventory.value = true
      try {
        PackageInventoryScanner.scanAndSyncLocalInventory(
          context = app,
          settingsStore = settingsStore,
          inventoryDao = db.appInventoryDao(),
          activityDao = db.appActivityDao(),
        )
        refreshStorageMetrics()
      } finally {
        _isScanningInventory.value = false
      }
    }
  }

  fun syncLocalCallLogs() {
    recordUserActivity()
    viewModelScope.launch {
      refreshPermissionStates()
      when (val res = CallLogReader.syncLocalCallLogs(app, settingsStore, db.callLogDao())) {
        is CallLogSyncResult.Success -> {
          _callLogSyncStatus.value = "Synced ${res.syncedCount} call log records from device."
          refreshStorageMetrics()
        }
        CallLogSyncResult.PermissionDenied -> {
          _callLogSyncStatus.value = "READ_CALL_LOG permission is not granted."
        }
        CallLogSyncResult.RecordingDisabled -> {
          _callLogSyncStatus.value = "Call Log Recording is currently paused or disabled."
        }
        is CallLogSyncResult.Error -> {
          _callLogSyncStatus.value = "Error: ${res.message}"
        }
      }
    }
  }

  fun checkUpdates() {
    viewModelScope.launch {
      _isCheckingUpdates.value = true
      try {
        val cfg = settingsStore.getInternetConfigOnce()
        _updateCheckResult.value = internetManager.checkForUpdates(cfg.updateManifestUrl)
      } finally {
        _isCheckingUpdates.value = false
      }
    }
  }

  fun setLanguageCode(code: String) {
    recordUserActivity()
    viewModelScope.launch { settingsStore.setLanguageCode(code) }
  }

  fun setThemeMode(mode: String) {
    recordUserActivity()
    viewModelScope.launch { settingsStore.setThemeMode(mode) }
  }

  fun setRemoteUninstallMode(mode: String) {
    recordUserActivity()
    viewModelScope.launch { settingsStore.setRemoteUninstallMode(mode) }
  }

  fun saveInternetConfig(config: InternetConnectivityConfig) {
    recordUserActivity()
    viewModelScope.launch {
      settingsStore.saveInternetConfig(config)
      _statusBanner.value = "Internet connectivity configuration saved securely."
    }
  }

  fun clearInternetConfig() {
    recordUserActivity()
    viewModelScope.launch {
      settingsStore.clearInternetConfig()
      internetManager.testSignalingConnection(InternetConnectivityConfig())
      _statusBanner.value = "Internet connectivity configuration cleared."
    }
  }

  fun testSignalingConnection(config: InternetConnectivityConfig) {
    recordUserActivity()
    viewModelScope.launch {
      val status = internetManager.testSignalingConnection(config)
      _statusBanner.value =
        when (status) {
          is RemoteConnectionStatus.Connected -> "Signaling handshake verified: ${status.endpoint}"
          RemoteConnectionStatus.NotConfigured -> "Internet Connectivity: Not Configured"
          RemoteConnectionStatus.Offline -> "Device is offline. Cannot reach signaling server."
          is RemoteConnectionStatus.Error -> "Connection failed: ${status.reason}"
          RemoteConnectionStatus.Connecting -> "Connecting..."
        }
    }
  }

  fun syncPairedDevice(device: PairedDeviceEntity, method: String) {
    recordUserActivity()
    viewModelScope.launch {
      val result =
        when (method) {
          TransferProtocol.METHOD_BLUETOOTH -> {
            val addr = device.bluetoothAddress
            if (addr.isNullOrBlank()) Result.failure(IllegalStateException("No Bluetooth address stored for this device."))
            else bluetoothManager.connectAndSync(addr)
          }
          TransferProtocol.METHOD_WIFI_DIRECT -> {
            val host = wifiDirectManager.state.value.groupOwnerHost ?: device.wifiDirectAddress
            if (host.isNullOrBlank()) Result.failure(IllegalStateException("Establish Wi-Fi Direct group connection first."))
            else wifiDirectManager.syncWithHost(host)
          }
          TransferProtocol.METHOD_INTERNET -> {
            val cfg = settingsStore.getInternetConfigOnce()
            val secret = CryptoManager.decryptLocalString(app, device.sharedSecretEncrypted)
            internetManager.requestRemoteDeviceSync(cfg, device.deviceId, secret)
          }
          else -> Result.failure(IllegalArgumentException("Unsupported connection method"))
        }
      _statusBanner.value =
        result.fold(
          onSuccess = { it },
          onFailure = { "Sync failed: ${it.message ?: "Device unreachable"}" },
        )
      refreshStorageMetrics()
    }
  }

  fun sendRemoteUninstall(
    targetDevice: PairedDeviceEntity,
    packageName: String,
    appName: String,
    mode: String,
    method: String,
  ) {
    recordUserActivity()
    viewModelScope.launch {
      val reqId = UUID.randomUUID().toString()
      val now = System.currentTimeMillis()
      val isManagedCheckOk =
        if (mode == "MODE_A_MANAGED") {
          false // Remote peer must hold Device Owner; handled transparently below
        } else true

      if (!isManagedCheckOk && method == TransferProtocol.METHOD_INTERNET && !internetConfig.value.isConfigured) {
        db.remoteUninstallDao()
          .upsertRequest(
            RemoteUninstallRequestEntity(
              requestId = reqId,
              targetDeviceId = targetDevice.deviceId,
              packageName = packageName,
              appName = appName,
              mode = mode,
              status = "UNSUPPORTED",
              resultMessage = "Internet Connectivity: Not Configured",
              timestampMillis = now,
            )
          )
        return@launch
      }

      val result =
        when (method) {
          TransferProtocol.METHOD_BLUETOOTH -> {
            val addr = targetDevice.bluetoothAddress
            if (addr.isNullOrBlank()) Result.failure(IllegalStateException("Bluetooth address unavailable."))
            else bluetoothManager.sendRemoteUninstall(addr, packageName)
          }
          TransferProtocol.METHOD_WIFI_DIRECT -> {
            val host = wifiDirectManager.state.value.groupOwnerHost ?: targetDevice.wifiDirectAddress
            if (host.isNullOrBlank()) Result.failure(IllegalStateException("Wi-Fi Direct connection not active."))
            else wifiDirectManager.sendRemoteUninstall(host, packageName)
          }
          else -> Result.failure(IllegalStateException("Active Bluetooth, Wi-Fi Direct, or configured Internet session required."))
        }

      val status = if (result.isSuccess) "SENT" else "FAILED"
      val msg = result.getOrElse { it.message ?: "Target device unreachable" }
      db.remoteUninstallDao()
        .upsertRequest(
          RemoteUninstallRequestEntity(
            requestId = reqId,
            targetDeviceId = targetDevice.deviceId,
            packageName = packageName,
            appName = appName,
            mode = mode,
            status = status,
            resultMessage = msg,
            timestampMillis = now,
          )
        )
      _statusBanner.value = msg
    }
  }

  suspend fun getInventoryForDevice(deviceId: String): List<AppInventorySnapshotEntity> =
    db.appInventoryDao().getInventoryForDeviceOnce(deviceId)

  suspend fun exportPdfToUri(
    uri: Uri,
    deviceId: String,
    deviceLabel: String,
    includeInventory: Boolean,
    includeEvents: Boolean,
    includeCallLogs: Boolean,
    dateStart: String?,
    dateEnd: String?,
  ): Result<Int> =
    withContext(Dispatchers.IO) {
      try {
        val inv = if (includeInventory) db.appInventoryDao().getInventoryForDeviceOnce(deviceId) else emptyList()
        val ev =
          if (includeEvents) {
            db.appActivityDao().getEventsForDeviceOnce(deviceId).filter { e ->
              (dateStart.isNullOrBlank() || e.eventDate >= dateStart) &&
                (dateEnd.isNullOrBlank() || e.eventDate <= dateEnd)
            }
          } else emptyList()
        val calls =
          if (includeCallLogs) {
            db.callLogDao().getCallLogsForDeviceOnce(deviceId)
              .filter { c ->
                (dateStart.isNullOrBlank() || c.callDate >= dateStart) &&
                  (dateEnd.isNullOrBlank() || c.callDate <= dateEnd)
              }
              .map { it.copy(phoneNumber = CryptoManager.decryptField(app, it.phoneNumber)) }
          } else emptyList()

        val stream =
          app.contentResolver.openOutputStream(uri)
            ?: return@withContext Result.failure(IllegalStateException("Unable to open output stream"))
        val pages =
          PdfReportGenerator.generatePdfReport(
            request =
              PdfReportRequest(
                deviceId = deviceId,
                deviceLabel = deviceLabel,
                isImportedSnapshot = deviceId != _localDeviceId.value,
                includeInventory = includeInventory,
                includeEvents = includeEvents,
                includeCallLogs = includeCallLogs,
                dateFilterStart = dateStart,
                dateFilterEnd = dateEnd,
              ),
            inventory = inv,
            events = ev,
            callLogs = calls,
            outputStream = stream,
          )
        Result.success(pages)
      } catch (e: Exception) {
        Result.failure(e)
      }
    }

  suspend fun exportPdfToInternalCache(
    deviceId: String,
    deviceLabel: String,
    includeInventory: Boolean,
    includeEvents: Boolean,
    includeCallLogs: Boolean,
  ): Result<File> =
    withContext(Dispatchers.IO) {
      try {
        val dir = File(app.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "device_activity_report_${System.currentTimeMillis()}.pdf")
        val inv = if (includeInventory) db.appInventoryDao().getInventoryForDeviceOnce(deviceId) else emptyList()
        val ev = if (includeEvents) db.appActivityDao().getEventsForDeviceOnce(deviceId) else emptyList()
        val calls =
          if (includeCallLogs) {
            db.callLogDao().getCallLogsForDeviceOnce(deviceId)
              .map { it.copy(phoneNumber = CryptoManager.decryptField(app, it.phoneNumber)) }
          } else emptyList()

        PdfReportGenerator.generatePdfReport(
          request =
            PdfReportRequest(
              deviceId = deviceId,
              deviceLabel = deviceLabel,
              isImportedSnapshot = deviceId != _localDeviceId.value,
              includeInventory = includeInventory,
              includeEvents = includeEvents,
              includeCallLogs = includeCallLogs,
            ),
          inventory = inv,
          events = ev,
          callLogs = calls,
          outputStream = file.outputStream(),
        )
        Result.success(file)
      } catch (e: Exception) {
        Result.failure(e)
      }
    }

  suspend fun exportBackupToUri(uri: Uri, passphrase: String): Result<BackupRestoreSummary> {
    recordUserActivity()
    return try {
      val stream =
        app.contentResolver.openOutputStream(uri)
          ?: return Result.failure(IllegalStateException("Cannot write to selected file"))
      val summary =
        EncryptedBackupManager.exportEncryptedBackup(app, db, _localDeviceId.value, passphrase, stream)
      Result.success(summary)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  suspend fun restoreBackupFromUri(uri: Uri, passphrase: String): Result<BackupRestoreSummary> {
    recordUserActivity()
    return try {
      val stream =
        app.contentResolver.openInputStream(uri)
          ?: return Result.failure(IllegalStateException("Cannot read selected file"))
      val summary = EncryptedBackupManager.restoreEncryptedBackup(app, db, passphrase, stream)
      refreshStorageMetrics()
      Result.success(summary)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  fun clearActivityEvents(olderThanDays: Int?) {
    recordUserActivity()
    viewModelScope.launch {
      val targetId = _activeDeviceId.value
      val deleted =
        if (olderThanDays == null) {
          db.appActivityDao().deleteEventsForDevice(targetId)
        } else {
          val cutoff = System.currentTimeMillis() - (olderThanDays * 86_400_000L)
          db.appActivityDao().deleteEventsOlderThan(targetId, cutoff)
        }
      refreshStorageMetrics()
      _statusBanner.value = "Removed $deleted application activity event(s)."
    }
  }

  fun clearCallLogs(olderThanDays: Int?) {
    recordUserActivity()
    viewModelScope.launch {
      val targetId = _activeDeviceId.value
      val deleted =
        if (olderThanDays == null) {
          db.callLogDao().deleteCallLogsForDevice(targetId)
        } else {
          val cutoff = System.currentTimeMillis() - (olderThanDays * 86_400_000L)
          db.callLogDao().deleteCallLogsOlderThan(targetId, cutoff)
        }
      refreshStorageMetrics()
      _statusBanner.value = "Removed $deleted call log record(s)."
    }
  }

  fun clearDeviceSnapshotData(deviceId: String) {
    recordUserActivity()
    viewModelScope.launch {
      db.appActivityDao().deleteEventsForDevice(deviceId)
      db.appInventoryDao().deleteInventoryForDevice(deviceId)
      db.callLogDao().deleteCallLogsForDevice(deviceId)
      db.remoteUninstallDao().deleteRequestsForDevice(deviceId)
      refreshStorageMetrics()
      _statusBanner.value = "Cleared stored records for device $deviceId."
    }
  }

  fun vacuumDatabase() {
    recordUserActivity()
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val sqlDb = db.openHelper.writableDatabase
        sqlDb.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
        sqlDb.execSQL("VACUUM")
        refreshStorageMetrics()
        _statusBanner.value = "Database compacted and optimized."
      } catch (e: Exception) {
        _statusBanner.value = "Optimization failed: ${e.message ?: "Busy"}"
      }
    }
  }

  fun refreshStorageMetrics() {
    viewModelScope.launch(Dispatchers.IO) {
      _storageMetrics.value =
        StorageMetrics(
          databaseSizeBytes = AppDatabase.getDatabaseSizeBytes(app),
          totalEvents = db.appActivityDao().getTotalEventCount(),
          totalInventory = db.appInventoryDao().getTotalInventoryCount(),
          totalCallLogs = db.callLogDao().getTotalCallLogCount(),
          totalPairedDevices = db.pairedDeviceDao().getTotalPairedDevicesCount(),
        )
    }
  }

  override fun onCleared() {
    super.onCleared()
    bluetoothManager.cleanup()
    wifiDirectManager.cleanup()
    internetManager.disconnect()
  }
}
