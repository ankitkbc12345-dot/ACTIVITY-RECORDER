package com.example.monitoring

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CallLog
import androidx.core.content.ContextCompat
import com.example.data.local.CallLogDao
import com.example.data.local.CallLogEntity
import com.example.data.preferences.SettingsStore
import com.example.security.CryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface CallLogSyncResult {
  data class Success(val syncedCount: Int) : CallLogSyncResult

  data object PermissionDenied : CallLogSyncResult

  data object RecordingDisabled : CallLogSyncResult

  data class Error(val message: String) : CallLogSyncResult
}

object CallLogReader {

  fun hasCallLogPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
      PackageManager.PERMISSION_GRANTED
  }

  suspend fun syncLocalCallLogs(
    context: Context,
    settingsStore: SettingsStore,
    callLogDao: CallLogDao,
  ): CallLogSyncResult =
    withContext(Dispatchers.IO) {
      if (!hasCallLogPermission(context)) {
        return@withContext CallLogSyncResult.PermissionDenied
      }
      val recording = settingsStore.getRecordingSettingsOnce()
      if (!recording.masterEnabled || !recording.callLogEnabled) {
        return@withContext CallLogSyncResult.RecordingDisabled
      }

      val localDeviceId = settingsStore.getLocalDeviceId()
      val projection =
        arrayOf(
          CallLog.Calls._ID,
          CallLog.Calls.NUMBER,
          CallLog.Calls.TYPE,
          CallLog.Calls.DATE,
          CallLog.Calls.DURATION,
        )

      try {
        val entries = mutableListOf<CallLogEntity>()
        context.contentResolver
          .query(
            CallLog.Calls.CONTENT_URI,
            projection,
            null,
            null,
            "${CallLog.Calls.DATE} DESC",
          )
          ?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
            val numIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val durIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)

            while (cursor.moveToNext()) {
              val rowId = cursor.getLong(idIdx)
              val number = cursor.getString(numIdx)?.ifBlank { "Private / Unknown" } ?: "Private / Unknown"
              val typeInt = cursor.getInt(typeIdx)
              val dateMillis = cursor.getLong(dateIdx)
              val durationSec = cursor.getLong(durIdx).coerceAtLeast(0L)

              val callType =
                when (typeInt) {
                  CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                  CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                  CallLog.Calls.MISSED_TYPE -> "MISSED"
                  CallLog.Calls.VOICEMAIL_TYPE -> "VOICEMAIL"
                  CallLog.Calls.REJECTED_TYPE -> "REJECTED"
                  else -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                      typeInt == CallLog.Calls.BLOCKED_TYPE
                    ) {
                      "BLOCKED"
                    } else {
                      "OTHER"
                    }
                  }
                }

              entries.add(
                CallLogEntity(
                  id = "$localDeviceId:$rowId:$dateMillis",
                  deviceId = localDeviceId,
                  callLogId = rowId,
                  phoneNumber = CryptoManager.encryptField(context, number),
                  callType = callType,
                  callDate = DateTimeUtils.formatDate(dateMillis),
                  callTime = DateTimeUtils.formatTime(dateMillis),
                  durationSeconds = durationSec,
                  timestampMillis = dateMillis,
                  isImportedSnapshot = false,
                  importedAtMillis = null,
                )
              )
            }
          }

        if (entries.isNotEmpty()) {
          callLogDao.upsertCallLogs(entries)
        }
        CallLogSyncResult.Success(entries.size)
      } catch (se: SecurityException) {
        CallLogSyncResult.PermissionDenied
      } catch (e: Exception) {
        CallLogSyncResult.Error(e.message ?: "Unable to read call log")
      }
    }
}
