package com.example.monitoring

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.os.Build
import android.os.Process
import com.example.data.local.DeviceUnlockEventEntity
import com.example.data.local.UnlockEventDao
import com.example.data.preferences.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object UnlockTracker {

  private val syncMutex = Mutex()
  private const val KEYGUARD_HIDDEN_EVENT_TYPE = 18 // UsageEvents.Event.KEYGUARD_HIDDEN (API 28+)

  fun detectUnlockMethod(context: Context): Pair<String, String> {
    val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    val isSecure = km?.isDeviceSecure == true
    if (!isSecure) {
      return "SWIPE_OR_NONE" to "Swipe / No Screen Lock"
    }

    val pm = context.packageManager
    val hasFingerprint = pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
    val hasFace =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        pm.hasSystemFeature(PackageManager.FEATURE_FACE)
      } else {
        false
      }

    val biometricEnrolled =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bm = context.getSystemService(Context.BIOMETRIC_SERVICE) as? BiometricManager
        val res =
          bm?.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
              BiometricManager.Authenticators.BIOMETRIC_WEAK
          )
        res == BiometricManager.BIOMETRIC_SUCCESS
      } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        @Suppress("DEPRECATION")
        val bm = context.getSystemService(Context.BIOMETRIC_SERVICE) as? BiometricManager
        @Suppress("DEPRECATION")
        bm?.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
      } else {
        false
      }

    return if (biometricEnrolled) {
      val label =
        when {
          hasFingerprint && hasFace -> "Secured (Fingerprint / Face or PIN)"
          hasFingerprint -> "Secured (Fingerprint or PIN/Pattern)"
          hasFace -> "Secured (Face Unlock or PIN/Pattern)"
          else -> "Secured (Biometric or PIN/Pattern)"
        }
      "BIOMETRIC_ENROLLED" to label
    } else {
      "PIN_PATTERN_PASSWORD" to "Secured (PIN / Pattern / Password)"
    }
  }

  @Suppress("DEPRECATION")
  fun hasUsageStatsPermission(context: Context): Boolean {
    return try {
      val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
      val mode =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
          )
        } else {
          appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
          )
        }
      mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
      false
    }
  }

  suspend fun recordLiveUnlock(
    context: Context,
    settingsStore: SettingsStore,
    unlockEventDao: UnlockEventDao,
    timestampMillis: Long = System.currentTimeMillis(),
  ): Boolean =
    withContext(Dispatchers.IO) {
      val recording = settingsStore.getRecordingSettingsOnce()
      if (!recording.masterEnabled || !recording.unlockTrackingEnabled) {
        return@withContext false
      }
      syncMutex.withLock {
        val localDeviceId = settingsStore.getLocalDeviceId()
        val existing = unlockEventDao.getUnlockEventsForDeviceOnce(localDeviceId)
        val duplicate = existing.any { kotlin.math.abs(it.timestampMillis - timestampMillis) < 2_500L }
        if (duplicate) return@withLock false

        val (category, methodLabel) = detectUnlockMethod(context)
        val batteryPercent = BatteryUtils.getBatteryStatus(context).percent
        val event =
          DeviceUnlockEventEntity(
            id = "$localDeviceId:$timestampMillis:LIVE",
            deviceId = localDeviceId,
            unlockMethod = methodLabel,
            unlockCategory = category,
            batteryPercent = batteryPercent,
            unlockDate = DateTimeUtils.formatDate(timestampMillis),
            unlockTime = DateTimeUtils.formatTime(timestampMillis),
            timestampMillis = timestampMillis,
            source = "LIVE_RECEIVER",
            isImportedSnapshot = false,
            importedAtMillis = null,
          )
        unlockEventDao.upsertUnlockEvent(event)
        true
      }
    }

  suspend fun syncUsageStatsUnlocks(
    context: Context,
    settingsStore: SettingsStore,
    unlockEventDao: UnlockEventDao,
  ): Int =
    withContext(Dispatchers.IO) {
      if (!hasUsageStatsPermission(context)) return@withContext 0
      val recording = settingsStore.getRecordingSettingsOnce()
      if (!recording.masterEnabled || !recording.unlockTrackingEnabled) return@withContext 0

      syncMutex.withLock {
        val usm =
          context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return@withLock 0
        val localDeviceId = settingsStore.getLocalDeviceId()
        val now = System.currentTimeMillis()
        val existing = unlockEventDao.getUnlockEventsForDeviceOnce(localDeviceId)
        val latestStoredTs = existing.maxOfOrNull { it.timestampMillis } ?: 0L
        val sevenDaysAgo = now - (7L * 24 * 60 * 60 * 1000L)
        val startTime = maxOf(sevenDaysAgo, if (latestStoredTs > 0L) latestStoredTs - 60_000L else sevenDaysAgo)

        val (category, methodLabel) = detectUnlockMethod(context)
        val currentBattery = BatteryUtils.getBatteryStatus(context).percent
        val toInsert = mutableListOf<DeviceUnlockEventEntity>()

        try {
          val usageEvents = usm.queryEvents(startTime, now)
          val event = UsageEvents.Event()
          while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            if (event.eventType == KEYGUARD_HIDDEN_EVENT_TYPE && event.timeStamp > 0L) {
              val ts = event.timeStamp
              val alreadyExists =
                existing.any { kotlin.math.abs(it.timestampMillis - ts) < 3_000L } ||
                  toInsert.any { kotlin.math.abs(it.timestampMillis - ts) < 3_000L }
              if (!alreadyExists) {
                toInsert.add(
                  DeviceUnlockEventEntity(
                    id = "$localDeviceId:$ts:USAGE",
                    deviceId = localDeviceId,
                    unlockMethod = methodLabel,
                    unlockCategory = category,
                    batteryPercent =
                      if ((now - ts) in -10_000L..300_000L) currentBattery else null,
                    unlockDate = DateTimeUtils.formatDate(ts),
                    unlockTime = DateTimeUtils.formatTime(ts),
                    timestampMillis = ts,
                    source = "USAGE_STATS",
                    isImportedSnapshot = false,
                    importedAtMillis = null,
                  )
                )
              }
            }
          }
          if (toInsert.isNotEmpty()) {
            unlockEventDao.upsertUnlockEvents(toInsert)
          }
          toInsert.size
        } catch (_: Exception) {
          0
        }
      }
    }
}
