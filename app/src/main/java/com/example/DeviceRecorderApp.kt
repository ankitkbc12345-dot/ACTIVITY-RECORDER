package com.example

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import androidx.core.content.ContextCompat
import com.example.connectivity.BluetoothTransportManager
import com.example.connectivity.InternetSignalingManager
import com.example.connectivity.WifiDirectTransportManager
import com.example.data.local.AppDatabase
import com.example.data.preferences.CloudSyncSchedule
import com.example.data.preferences.SettingsStore
import com.example.data.remote.CloudSyncScheduler
import com.example.monitoring.CallLogReader
import com.example.monitoring.CallLogSyncJobService
import com.example.monitoring.PackageEventReceiver
import com.example.monitoring.UnlockTracker
import android.util.Log
import com.example.ui.auth.safeCurrentFirebaseUser
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.FlowPreview::class)
class DeviceRecorderApp : Application() {

  private val appExceptionHandler = CoroutineExceptionHandler { _, throwable ->
    Log.e("DeviceRecorderApp", "Uncaught background coroutine error", throwable)
  }

  val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + appExceptionHandler)

  val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
  val settingsStore: SettingsStore by lazy { SettingsStore(this) }
  val bluetoothManager: BluetoothTransportManager by lazy {
    BluetoothTransportManager(this, database, settingsStore)
  }
  val wifiDirectManager: WifiDirectTransportManager by lazy {
    WifiDirectTransportManager(this, database, settingsStore)
  }
  val internetManager: InternetSignalingManager by lazy {
    InternetSignalingManager(this, database, settingsStore)
  }

  private val dynamicPackageReceiver = PackageEventReceiver()
  private val unlockReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_USER_PRESENT) {
          val pending = runCatching { goAsync() }.getOrNull()
          appScope.launch {
            try {
              UnlockTracker.recordLiveUnlock(
                context = this@DeviceRecorderApp,
                settingsStore = settingsStore,
                unlockEventDao = database.unlockEventDao(),
              )
            } finally {
              pending?.finish()
            }
          }
        }
      }
    }

  @Volatile private var callLogObserverRegistered = false
  private var callLogObserver: ContentObserver? = null
  private var oneMinuteTickerJob: Job? = null

  override fun onCreate() {
    super.onCreate()
    try {
      if (com.google.firebase.FirebaseApp.getApps(this).isEmpty()) {
        com.google.firebase.FirebaseApp.initializeApp(this)
      }
    } catch (_: Exception) {
    }
    appScope.launch {
      runCatching { settingsStore.ensureInitialProvisioning() }
      val localId = runCatching { settingsStore.getLocalDeviceId() }.getOrDefault("DEV-LOCAL")
      runCatching {
        if (CallLogReader.hasCallLogPermission(this@DeviceRecorderApp)) {
          CallLogReader.syncLocalCallLogs(
            context = this@DeviceRecorderApp,
            settingsStore = settingsStore,
            callLogDao = database.callLogDao(),
          )
          CallLogSyncJobService.scheduleContentTrigger(this@DeviceRecorderApp)
        }
      }
      runCatching {
        UnlockTracker.syncUsageStatsUnlocks(
          context = this@DeviceRecorderApp,
          settingsStore = settingsStore,
          unlockEventDao = database.unlockEventDao(),
        )
      }
      runCatching { setupCloudSyncObservers(localId) }
    }

    val packageFilter =
      IntentFilter().apply {
        addAction(Intent.ACTION_PACKAGE_ADDED)
        addAction(Intent.ACTION_PACKAGE_REMOVED)
        addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
        addAction(Intent.ACTION_PACKAGE_REPLACED)
        addDataScheme("package")
      }
    runCatching {
      ContextCompat.registerReceiver(
        this,
        dynamicPackageReceiver,
        packageFilter,
        ContextCompat.RECEIVER_EXPORTED,
      )
    }

    runCatching {
      ContextCompat.registerReceiver(
        this,
        unlockReceiver,
        IntentFilter(Intent.ACTION_USER_PRESENT),
        ContextCompat.RECEIVER_EXPORTED,
      )
    }

    runCatching { ensureCallLogObserverRegistered() }
  }

  private fun setupCloudSyncObservers(localDeviceId: String) {
    // 1. Keep JobScheduler + 1-min ticker synced with user preferences
    appScope.launch {
      settingsStore.cloudSyncPreferencesFlow
        .distinctUntilChanged { old, new ->
          old.schedule == new.schedule &&
            old.dailyHour == new.dailyHour &&
            old.dailyMinute == new.dailyMinute
        }
        .collectLatest { prefs ->
          CloudSyncScheduler.applySchedule(
            context = this@DeviceRecorderApp,
            schedule = prefs.schedule,
            dailyHour = prefs.dailyHour,
            dailyMinute = prefs.dailyMinute,
          )
          oneMinuteTickerJob?.cancel()
          if (prefs.schedule == CloudSyncSchedule.EVERY_1_MIN) {
            oneMinuteTickerJob =
              appScope.launch {
                while (true) {
                  delay(60_000L)
                  val hasUser = safeCurrentFirebaseUser(this@DeviceRecorderApp) != null
                  if (hasUser) {
                    CloudSyncScheduler.runSyncNow(this@DeviceRecorderApp, summaryNote = "Auto 1-min sync")
                  }
                }
              }
          }
        }
    }

    // 2. When schedule is ALWAYS, sync cleanly whenever local data changes (debounced 4s)
    appScope.launch {
      combine(
          database.callLogDao().observeCallLogsForDevice(localDeviceId),
          database.appActivityDao().observeEventsForDevice(localDeviceId),
          database.unlockEventDao().observeUnlockEventsForDevice(localDeviceId),
          settingsStore.cloudSyncPreferencesFlow,
        ) { calls, events, unlocks, prefs ->
          val hasUser = safeCurrentFirebaseUser(this@DeviceRecorderApp) != null
          Triple(
            prefs.schedule == CloudSyncSchedule.ALWAYS && hasUser,
            "${calls.size}:${events.size}:${unlocks.size}:${prefs.syncCallLogs}:${prefs.syncAppActivity}:${prefs.syncUnlockLogs}",
            prefs,
          )
        }
        .distinctUntilChanged { a, b -> a.first == b.first && a.second == b.second }
        .debounce(4_000L)
        .collectLatest { (shouldAutoSync, _, _) ->
          if (shouldAutoSync) {
            CloudSyncScheduler.runSyncNow(this@DeviceRecorderApp, summaryNote = "Real-time auto sync")
          }
        }
    }
  }

  @Synchronized
  fun ensureCallLogObserverRegistered() {
    if (callLogObserverRegistered || !CallLogReader.hasCallLogPermission(this)) return
    val observer =
      object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
          super.onChange(selfChange)
          appScope.launch {
            CallLogReader.syncLocalCallLogs(
              context = this@DeviceRecorderApp,
              settingsStore = settingsStore,
              callLogDao = database.callLogDao(),
            )
          }
        }
      }
    try {
      contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
      callLogObserver = observer
      callLogObserverRegistered = true
      CallLogSyncJobService.scheduleContentTrigger(this)
    } catch (_: SecurityException) {
      callLogObserverRegistered = false
    } catch (_: Exception) {
      callLogObserverRegistered = false
    }
  }
}
