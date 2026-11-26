package com.example.monitoring

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.provider.CallLog
import com.example.data.local.AppDatabase
import com.example.data.preferences.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CallLogSyncJobService : JobService() {

  override fun onStartJob(params: JobParameters): Boolean {
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val appCtx = applicationContext
        if (CallLogReader.hasCallLogPermission(appCtx)) {
          CallLogReader.syncLocalCallLogs(
            context = appCtx,
            settingsStore = SettingsStore(appCtx),
            callLogDao = AppDatabase.getInstance(appCtx).callLogDao(),
          )
        }
      } finally {
        scheduleContentTrigger(applicationContext)
        jobFinished(params, false)
      }
    }
    return true
  }

  override fun onStopJob(params: JobParameters): Boolean = true

  companion object {
    private const val JOB_ID = 4102

    fun scheduleContentTrigger(context: Context) {
      val appCtx = context.applicationContext
      if (!CallLogReader.hasCallLogPermission(appCtx)) return
      val scheduler = appCtx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
      try {
        val jobInfo =
          JobInfo.Builder(JOB_ID, ComponentName(appCtx, CallLogSyncJobService::class.java))
            .addTriggerContentUri(
              JobInfo.TriggerContentUri(
                CallLog.Calls.CONTENT_URI,
                JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
              )
            )
            .setTriggerContentUpdateDelay(1_000L)
            .setTriggerContentMaxDelay(5_000L)
            .build()
        scheduler.schedule(jobInfo)
      } catch (_: Exception) {
        // Ignore in test environments or restricted OEM states
      }
    }
  }
}
