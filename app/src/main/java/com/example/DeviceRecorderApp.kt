package com.example

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.example.connectivity.BluetoothTransportManager
import com.example.connectivity.InternetSignalingManager
import com.example.connectivity.WifiDirectTransportManager
import com.example.data.local.AppDatabase
import com.example.data.preferences.SettingsStore
import com.example.monitoring.PackageEventReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DeviceRecorderApp : Application() {

  val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

  override fun onCreate() {
    super.onCreate()
    appScope.launch { settingsStore.ensureInitialProvisioning() }

    val packageFilter =
      IntentFilter().apply {
        addAction(Intent.ACTION_PACKAGE_ADDED)
        addAction(Intent.ACTION_PACKAGE_REMOVED)
        addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
        addAction(Intent.ACTION_PACKAGE_REPLACED)
        addDataScheme("package")
      }
    ContextCompat.registerReceiver(
      this,
      dynamicPackageReceiver,
      packageFilter,
      ContextCompat.RECEIVER_EXPORTED,
    )
  }
}
