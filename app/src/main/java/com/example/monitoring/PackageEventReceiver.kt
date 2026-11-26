package com.example.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.example.data.local.AppActivityEventEntity
import com.example.data.local.AppDatabase
import com.example.data.local.AppInventorySnapshotEntity
import com.example.data.preferences.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PackageEventReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    val action = intent.action ?: return
    val packageName = intent.data?.schemeSpecificPart ?: return
    val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)

    // Ignore intermediate ACTION_PACKAGE_REMOVED during an app update
    if (action == Intent.ACTION_PACKAGE_REMOVED && isReplacing) {
      return
    }

    val pendingResult: PendingResult? = runCatching { goAsync() }.getOrNull()
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val appCtx = context.applicationContext
        val settingsStore = SettingsStore(appCtx)
        val recording = settingsStore.getRecordingSettingsOnce()
        if (!recording.masterEnabled || !recording.appTrackingEnabled) {
          return@launch
        }

        val db = AppDatabase.getInstance(appCtx)
        val localDeviceId = settingsStore.getLocalDeviceId()
        val now = System.currentTimeMillis()
        val pm = appCtx.packageManager
        val batteryPercent = BatteryUtils.getBatteryStatus(appCtx).percent

        when (action) {
          Intent.ACTION_PACKAGE_ADDED,
          Intent.ACTION_PACKAGE_REPLACED -> {
            val eventType =
              if (action == Intent.ACTION_PACKAGE_REPLACED || isReplacing) "UPDATED"
              else "INSTALLED"
            val pkgInfo =
              try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                  pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
                } else {
                  @Suppress("DEPRECATION")
                  pm.getPackageInfo(packageName, 0)
                }
              } catch (_: Exception) {
                null
              }
            val appName =
              pkgInfo?.applicationInfo?.let {
                try {
                  pm.getApplicationLabel(it).toString()
                } catch (_: Exception) {
                  packageName
                }
              } ?: packageName
            val versionName = pkgInfo?.versionName ?: "Unavailable"
            val versionCode =
              if (pkgInfo != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkgInfo.longVersionCode
                else @Suppress("DEPRECATION") pkgInfo.versionCode.toLong()
              } else 0L
            val (installerPkg, installerLabel) =
              PackageInventoryScanner.getInstallerDetails(pm, packageName, pkgInfo?.applicationInfo)
            val firstInstall = pkgInfo?.firstInstallTime?.takeIf { it > 0L }
            val lastUpdate = pkgInfo?.lastUpdateTime?.takeIf { it > 0L }

            db.appActivityDao()
              .insertEvent(
                AppActivityEventEntity(
                  deviceId = localDeviceId,
                  appName = appName,
                  packageName = packageName,
                  versionName = versionName,
                  eventType = eventType,
                  installerPackage = installerPkg,
                  installerLabel = installerLabel,
                  eventDate = DateTimeUtils.formatDate(now),
                  eventTime = DateTimeUtils.formatTime(now),
                  timestampMillis = now,
                  isImportedSnapshot = false,
                  batteryPercent = batteryPercent,
                )
              )

            db.appInventoryDao()
              .upsertItem(
                AppInventorySnapshotEntity(
                  id = "$localDeviceId:$packageName",
                  deviceId = localDeviceId,
                  appName = appName,
                  packageName = packageName,
                  versionName = versionName,
                  versionCode = versionCode,
                  isInstalled = true,
                  firstInstallTimeMillis = firstInstall ?: now,
                  lastUpdateTimeMillis = lastUpdate ?: now,
                  installDate = DateTimeUtils.formatDate(firstInstall ?: now),
                  installTime = DateTimeUtils.formatTime(firstInstall ?: now),
                  installerPackage = installerPkg,
                  installerLabel = installerLabel,
                  isImportedSnapshot = false,
                )
              )
          }
          Intent.ACTION_PACKAGE_REMOVED,
          Intent.ACTION_PACKAGE_FULLY_REMOVED -> {
            val existing =
              db.appInventoryDao()
                .getInventoryForDeviceOnce(localDeviceId)
                .firstOrNull { it.packageName == packageName }
            val appName = existing?.appName ?: packageName
            val versionName = existing?.versionName ?: "Unavailable"

            db.appActivityDao()
              .insertEvent(
                AppActivityEventEntity(
                  deviceId = localDeviceId,
                  appName = appName,
                  packageName = packageName,
                  versionName = versionName,
                  eventType = "UNINSTALLED",
                  installerPackage = existing?.installerPackage,
                  installerLabel = existing?.installerLabel ?: "Unknown",
                  eventDate = DateTimeUtils.formatDate(now),
                  eventTime = DateTimeUtils.formatTime(now),
                  timestampMillis = now,
                  isImportedSnapshot = false,
                  batteryPercent = batteryPercent,
                )
              )

            if (existing != null) {
              db.appInventoryDao().upsertItem(existing.copy(isInstalled = false))
            }
          }
        }
      } finally {
        pendingResult?.finish()
      }
    }
  }
}
