package com.example.monitoring

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import com.example.data.local.AppActivityDao
import com.example.data.local.AppActivityEventEntity
import com.example.data.local.AppInventoryDao
import com.example.data.local.AppInventorySnapshotEntity
import com.example.data.preferences.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object PackageInventoryScanner {

  private val scanMutex = Mutex()

  private val knownStorePackages =
    mapOf(
      "com.android.vending" to "Google Play Store",
      "com.sec.android.app.samsungapps" to "Samsung Galaxy Store",
      "com.amazon.venezia" to "Amazon Appstore",
      "com.xiaomi.market" to "Xiaomi GetApps",
      "com.xiaomi.mipicks" to "Xiaomi GetApps",
      "com.huawei.appmarket" to "Huawei AppGallery",
      "com.oppo.market" to "OPPO App Market",
      "com.heytap.market" to "App Market",
      "com.vivo.appstore" to "Vivo V-Appstore",
      "org.fdroid.fdroid" to "F-Droid Store",
    )

  fun resolveInstallerLabel(
    installerPackage: String?,
    isSystemApp: Boolean = false,
    packageSourceCode: Int? = null,
  ): String {
    if (!installerPackage.isNullOrBlank()) {
      knownStorePackages[installerPackage]?.let { return it }
      return when (installerPackage) {
        "com.google.android.packageinstaller",
        "com.android.packageinstaller" -> "Other Source (Package Installer)"
        "com.android.shell" -> "Other Source (ADB / USB Installer)"
        "com.android.chrome",
        "org.mozilla.firefox",
        "com.brave.browser",
        "com.opera.browser",
        "com.microsoft.emmx" -> "Other Source (Browser APK)"
        "com.whatsapp",
        "org.telegram.messenger",
        "com.google.android.apps.nbu.files" -> "Other Source (Shared APK)"
        else -> "Other Source ($installerPackage)"
      }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && packageSourceCode != null) {
      when (packageSourceCode) {
        PackageInstaller.PACKAGE_SOURCE_STORE -> return "App Store"
        PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE -> return "Other Source (Local APK File)"
        PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE -> return "Other Source (Downloaded APK)"
      }
    }
    return if (isSystemApp) "System / Pre-installed" else "Unknown"
  }

  @Suppress("DEPRECATION")
  fun getInstallerDetails(
    pm: PackageManager,
    packageName: String,
    appInfo: ApplicationInfo? = null,
  ): Pair<String?, String> {
    val isSystemOnly =
      appInfo != null &&
        (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
        (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0

    return try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val info = pm.getInstallSourceInfo(packageName)
        val candidates =
          listOfNotNull(
            info.installingPackageName?.takeIf { it.isNotBlank() },
            info.initiatingPackageName?.takeIf { it.isNotBlank() },
            info.originatingPackageName?.takeIf { it.isNotBlank() },
          )
        val prioritized =
          candidates.firstOrNull { it in knownStorePackages }
            ?: info.originatingPackageName?.takeIf { it.isNotBlank() }
            ?: info.installingPackageName?.takeIf { it.isNotBlank() }
            ?: info.initiatingPackageName?.takeIf { it.isNotBlank() }

        val sourceCode =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.packageSource
          } else {
            null
          }
        prioritized to resolveInstallerLabel(prioritized, isSystemOnly, sourceCode)
      } else {
        val pkg = pm.getInstallerPackageName(packageName)?.takeIf { it.isNotBlank() }
        pkg to resolveInstallerLabel(pkg, isSystemOnly, null)
      }
    } catch (_: Exception) {
      null to resolveInstallerLabel(null, isSystemOnly, null)
    }
  }

  @Suppress("DEPRECATION")
  fun getInstallerPackage(pm: PackageManager, packageName: String): String? =
    getInstallerDetails(pm, packageName, null).first

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
      scanMutex.withLock {
        val localDeviceId = settingsStore.getLocalDeviceId()
        val recording = settingsStore.getRecordingSettingsOnce()
        val monitoringActive = recording.masterEnabled && recording.appTrackingEnabled

      val pm = context.packageManager
      val installedPackages = getInstalledPackagesCompat(pm)
      val existing = inventoryDao.getInventoryForDeviceOnce(localDeviceId).associateBy { it.packageName }
      val currentBattery = BatteryUtils.getBatteryStatus(context).percent

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
          val (installerPkg, installerLabel) = getInstallerDetails(pm, pkgName, appInfo)
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
        val existingEvents = activityDao.getEventsForDeviceOnce(localDeviceId)

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
                  batteryPercent = currentBattery,
                )
              )
            }
          }
          for (item in scannedItems) {
            val prev = existing[item.packageName]
            if (prev == null || !prev.isInstalled) {
              val eventTs = item.firstInstallTimeMillis ?: now
              val duplicateRecent =
                existingEvents.any {
                  it.packageName == item.packageName &&
                    it.eventType == "INSTALLED" &&
                    kotlin.math.abs(it.timestampMillis - eventTs) < 10_000L
                }
              if (!duplicateRecent) {
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
                    batteryPercent =
                      if ((now - eventTs) in -10_000L..300_000L) currentBattery else null,
                  )
                )
              }
            } else if (
              item.versionCode != prev.versionCode || item.versionName != prev.versionName
            ) {
              val eventTs = item.lastUpdateTimeMillis ?: now
              val duplicateRecent =
                existingEvents.any {
                  it.packageName == item.packageName &&
                    it.eventType == "UPDATED" &&
                    it.versionName == item.versionName &&
                    kotlin.math.abs(it.timestampMillis - eventTs) < 10_000L
                }
              if (!duplicateRecent) {
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
                    batteryPercent =
                      if ((now - eventTs) in -10_000L..300_000L) currentBattery else null,
                  )
                )
              }
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
}
