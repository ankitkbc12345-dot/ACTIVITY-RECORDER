package com.example.data.remote

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.example.data.local.AppDatabase
import com.example.data.preferences.CloudSyncSchedule
import com.example.data.preferences.SettingsStore
import com.example.monitoring.CallLogSyncJobService
import com.example.monitoring.DateTimeUtils
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object CloudSyncScheduler {
  private const val JOB_ID_CLOUD_SYNC = 4208

  fun applySchedule(
    context: Context,
    schedule: CloudSyncSchedule,
    dailyHour: Int,
    dailyMinute: Int,
  ) {
    val appCtx = context.applicationContext
    val scheduler = appCtx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
    scheduler.cancel(JOB_ID_CLOUD_SYNC)
    val component = ComponentName(appCtx, CloudSyncJobService::class.java)

    val builder =
      JobInfo.Builder(JOB_ID_CLOUD_SYNC, component)
        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        .setPersisted(true)

    val jobInfo =
      when (schedule) {
        CloudSyncSchedule.MANUAL -> return
        CloudSyncSchedule.ALWAYS -> builder.setPeriodic(15 * 60_000L).build()
        CloudSyncSchedule.AUTO_NETWORK -> builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build()
        CloudSyncSchedule.EVERY_1_MIN ->
          builder.setMinimumLatency(60_000L).setOverrideDeadline(90_000L).build()
        CloudSyncSchedule.EVERY_1_HOUR -> builder.setPeriodic(60 * 60_000L).build()
        CloudSyncSchedule.EVERY_5_HOURS -> builder.setPeriodic(5 * 60 * 60_000L).build()
        CloudSyncSchedule.EVERY_10_HOURS -> builder.setPeriodic(10 * 60 * 60_000L).build()
        CloudSyncSchedule.DAILY -> {
          val delay = computeDelayUntilNextTimeMillis(dailyHour, dailyMinute)
          builder.setMinimumLatency(delay).setOverrideDeadline(delay + 15 * 60_000L).build()
        }
      }

    try {
      scheduler.schedule(jobInfo)
    } catch (_: Exception) {
      // Safe fallback in Robolectric or restricted environments
    }
  }

  fun computeDelayUntilNextTimeMillis(hour: Int, minute: Int, nowMillis: Long = System.currentTimeMillis()): Long {
    val cal =
      Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
        set(Calendar.MINUTE, minute.coerceIn(0, 59))
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= nowMillis + 5_000L) {
          add(Calendar.DAY_OF_YEAR, 1)
        }
      }
    return (cal.timeInMillis - nowMillis).coerceAtLeast(10_000L)
  }

  suspend fun runSyncNow(context: Context, summaryNote: String? = null): Result<CloudSyncUploadSummary> {
    val appCtx = context.applicationContext
    val user = com.example.ui.auth.safeCurrentFirebaseUser(appCtx)
    if (user == null) {
      return Result.failure(IllegalStateException("Google Sign-In required"))
    }
    val settingsStore = SettingsStore(appCtx)
    val prefs = settingsStore.getCloudSyncPreferencesOnce()
    val localId = settingsStore.getLocalDeviceId()
    val localName = settingsStore.getLocalDeviceName()
    val repo = FirestoreSyncRepository(appCtx)
    val result =
      repo.syncLocalDeviceToCloud(
        localDb = AppDatabase.getInstance(appCtx),
        localDeviceId = localId,
        localDeviceName = localName,
        summaryNote = summaryNote,
        syncCallLogs = prefs.syncCallLogs,
        syncAppActivity = prefs.syncAppActivity,
        syncUnlockLogs = prefs.syncUnlockLogs,
      )
    val now = System.currentTimeMillis()
    result.fold(
      onSuccess = { summary ->
        settingsStore.updateLastCloudSync(
          timestampMillis = now,
          statusMessage = "Synced ${summary.uploadedRecordsCount} record(s) at ${DateTimeUtils.formatDateTime(now)}",
        )
      },
      onFailure = { err ->
        settingsStore.updateLastCloudSync(
          timestampMillis = now,
          statusMessage = "Failed: ${err.message ?: "Network / Auth error"}",
        )
      },
    )
    return result
  }
}

class CloudSyncJobService : JobService() {
  override fun onStartJob(params: JobParameters): Boolean {
    CoroutineScope(Dispatchers.IO).launch {
      val appCtx = applicationContext
      val settingsStore = SettingsStore(appCtx)
      val prefs =
        try {
          CloudSyncScheduler.runSyncNow(appCtx, summaryNote = "Scheduled ${DateTimeUtils.formatDateTime(System.currentTimeMillis())}")
          settingsStore.getCloudSyncPreferencesOnce()
        } catch (_: Exception) {
          null
        }
      if (prefs != null && (prefs.schedule == CloudSyncSchedule.EVERY_1_MIN || prefs.schedule == CloudSyncSchedule.DAILY)) {
        CloudSyncScheduler.applySchedule(appCtx, prefs.schedule, prefs.dailyHour, prefs.dailyMinute)
      }
      jobFinished(params, false)
    }
    return true
  }

  override fun onStopJob(params: JobParameters): Boolean = true
}

class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val action = intent.action ?: return
    if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
    val pendingResult = runCatching { goAsync() }.getOrNull()
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val appCtx = context.applicationContext
        CallLogSyncJobService.scheduleContentTrigger(appCtx)
        val prefs = SettingsStore(appCtx).getCloudSyncPreferencesOnce()
        CloudSyncScheduler.applySchedule(appCtx, prefs.schedule, prefs.dailyHour, prefs.dailyMinute)
      } finally {
        pendingResult?.finish()
      }
    }
  }
}
