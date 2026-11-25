package com.example.monitoring

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import com.example.data.local.AppActivityDao
import com.example.data.local.AppActivityEventEntity
import com.example.data.local.AppInventoryDao
import com.example.data.local.AppInventorySnapshotEntity
import com.example.data.preferences.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PackageInventoryScanner {

  fun resolveInstallerLabel(installerPackage: String?): String {
    if (installerPackage.isNullOrBlank()) return "Unknown"
    return when (installerPackage) {
      "com.android.vending" -> "Google Play Store"
      "com.google.android.packageinstaller",
      "com.android.packageinstaller" -> "Android Package Installer"
      "com.sec.android.app.samsungapps" -> "Samsung Galaxy Store"
      "com.amazon.venezia" -> "Amazon Appstore"
      "com.xiaomi.market" -> "Xiaomi GetApps"
      "com.huawei.appmarket" -> "Huawei AppGallery"
      "com.android.shell" -> "ADB / USB Installer"
      else -> "Known Installer ($installerPackage)"
    }
  }

  @Suppress("DEPRECATION")
  fun getInstallerPackage(pm: PackageManager, packageName: String): String? {
    return try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        pm.getInstallSourceInfo(packageName).installingPackageName
      } else {
        pm.getInstallerPackageName(packageName)
      }
    } catch (_: Exception) {
      null
    }
  }

  @Suppress("DEPRECATION")
  fun getInstalledPackagesCompat(pm: PackageManager): List<PackageInfo> {
    return try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
      } else {
        pm.getInstalledPackages(0)
      }
    } catch (_: Exception) {
      emptyList()
    }
  }

  fun loadAppIcon(context: Context, packageName: String): Drawable? {
    return try {
      context.packageManager.getApplicationIcon(packageName)
    } catch (_: Exception) {
      null
    }
  }

  suspend fun scanAndSyncLocalInventory(
    context: Context,
    settingsStore: SettingsStore,
    inventoryDao: AppInventoryDao,
    activityDao: AppActivityDao,
  ): List<AppInventorySnapshotEntity> =
    withContext(Dispatchers.IO) {
      val localDeviceId = settingsStore.getLocalDeviceId()
      val recording = settingsStore.getRecordingSettingsOnce()
      val monitoringActive = recording.masterEnabled && recording.appTrackingEnabled

      val pm = context.packageManager
      val installedPackages = getInstalledPackagesCompat(pm)
      val existing = inventoryDao.getInventoryForDeviceOnce(localDeviceId).associateBy { it.packageName }

      val scannedItems =
        installedPackages.mapNotNull { pkgInfo ->
          val appInfo = pkgInfo.applicationInfo ?: return@mapNotNull null
          val pkgName = pkgInfo.packageName ?: return@mapNotNull null
          val appName =
            try {
              pm.getApplicationLabel(appInfo).toString().ifBlank { pkgName }
            } catch (_: Exception) {
              pkgName
            }
          val versionName = pkgInfo.versionName ?: "Unavailable"
          val versionCode =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
              pkgInfo.longVersionCode
            } else {
              @Suppress("DEPRECATION")
              pkgInfo.versionCode.toLong()
            }
          val firstInstall = pkgInfo.firstInstallTime.takeIf { it > 0L }
          val lastUpdate = pkgInfo.lastUpdateTime.takeIf { it > 0L }
          val installerPkg = getInstallerPackage(pm, pkgName)
          val installerLabel = resolveInstallerLabel(installerPkg)
          val installDate = firstInstall?.let { DateTimeUtils.formatDate(it) } ?: "Unavailable"
          val installTime = firstInstall?.let { DateTimeUtils.formatTime(it) } ?: "Unavailable"

          AppInventorySnapshotEntity(
            id = "$localDeviceId:$pkgName",
            deviceId = localDeviceId,
            appName = appName,
            packageName = pkgName,
            versionName = versionName,
            versionCode = versionCode,
            isInstalled = true,
            firstInstallTimeMillis = firstInstall,
            lastUpdateTimeMillis = lastUpdate,
            installDate = installDate,
            installTime = installTime,
            installerPackage = installerPkg,
            installerLabel = installerLabel,
            isImportedSnapshot = false,
            importedAtMillis = null,
          )
        }

      if (monitoringActive) {
        val scannedPkgSet = scannedItems.map { it.packageName }.toSet()
        val now = System.currentTimeMillis()
        val newEvents = mutableListOf<AppActivityEventEntity>()

        // Detect packages that were previously recorded as installed while monitoring was on,
        // and are now absent (e.g., removed while app process was not alive)
        val removedUpdates = mutableListOf<AppInventorySnapshotEntity>()
        if (existing.isNotEmpty()) {
          for (prev in existing.values) {
            if (prev.isInstalled && prev.packageName !in scannedPkgSet) {
              removedUpdates.add(prev.copy(isInstalled = false))
              newEvents.add(
                AppActivityEventEntity(
                  deviceId = localDeviceId,
                  appName = prev.appName,
                  packageName = prev.packageName,
                  versionName = prev.versionName,
                  eventType = "UNINSTALLED",
                  installerPackage = prev.installerPackage,
                  installerLabel = prev.installerLabel,
                  eventDate = DateTimeUtils.formatDate(now),
                  eventTime = DateTimeUtils.formatTime(now),
                  timestampMillis = now,
                  isImportedSnapshot = false,
                )
              )
            }
          }
          // Detect genuinely newly added packages since last scan
          for (item in scannedItems) {
            val prev = existing[item.packageName]
            if (prev == null || !prev.isInstalled) {
              val eventTs = item.firstInstallTimeMillis ?: now
              newEvents.add(
                AppActivityEventEntity(
                  deviceId = localDeviceId,
                  appName = item.appName,
                  packageName = item.packageName,
                  versionName = item.versionName,
                  eventType = "INSTALLED",
                  installerPackage = item.installerPackage,
                  installerLabel = item.installerLabel,
                  eventDate = DateTimeUtils.formatDate(eventTs),
                  eventTime = DateTimeUtils.formatTime(eventTs),
                  timestampMillis = eventTs,
                  isImportedSnapshot = false,
                )
              )
            } else if (
              item.versionCode != prev.versionCode || item.versionName != prev.versionName
            ) {
              val eventTs = item.lastUpdateTimeMillis ?: now
              newEvents.add(
                AppActivityEventEntity(
                  deviceId = localDeviceId,
                  appName = item.appName,
                  packageName = item.packageName,
                  versionName = item.versionName,
                  eventType = "UPDATED",
                  installerPackage = item.installerPackage,
                  installerLabel = item.installerLabel,
                  eventDate = DateTimeUtils.formatDate(eventTs),
                  eventTime = DateTimeUtils.formatTime(eventTs),
                  timestampMillis = eventTs,
                  isImportedSnapshot = false,
                )
              )
            }
          }
        }

        inventoryDao.upsertInventory(scannedItems + removedUpdates)
        if (newEvents.isNotEmpty()) {
          activityDao.insertEvents(newEvents)
        }
      }

      scannedItems
    }
}
