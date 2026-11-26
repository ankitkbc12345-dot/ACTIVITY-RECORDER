package com.example.data.remote

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class CloudSyncStatus {
  SYNCED,
  PAUSED;

  companion object {
    fun fromRaw(raw: String?): CloudSyncStatus =
      entries.firstOrNull { it.name == raw } ?: SYNCED
  }
}

enum class CloudRecordCategory {
  APP_EVENT,
  APP_INVENTORY,
  CALL_LOG,
  UNLOCK_LOG;

  companion object {
    fun fromRaw(raw: String?): CloudRecordCategory =
      entries.firstOrNull { it.name == raw } ?: APP_EVENT
  }
}

@Keep
@IgnoreExtraProperties
data class SyncProfile(
  @DocumentId val id: String = "",
  val userId: String = "",
  val displayName: String = "",
  val email: String? = null,
  val activeDeviceId: String = "",
  val totalSnapshots: Long = 0L,
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
) {
  fun toCreateMap(): Map<String, Any> =
    buildMap<String, Any?> {
      put("userId", userId)
      put("displayName", displayName.trim().take(80).ifEmpty { "Device Admin" })
      put("email", email?.trim()?.take(160)?.takeIf { it.isNotEmpty() })
      put("activeDeviceId", sanitizeFirestoreId(activeDeviceId, maxLen = 64))
      put("totalSnapshots", totalSnapshots.coerceAtLeast(0L))
      put("createdAt", FieldValue.serverTimestamp())
      put("updatedAt", FieldValue.serverTimestamp())
    }.filterValues { it != null }.mapValues { it.value!! }

  fun toUpdateMap(): Map<String, Any> =
    buildMap<String, Any?> {
      put("displayName", displayName.trim().take(80).ifEmpty { "Device Admin" })
      put("email", email?.trim()?.take(160)?.takeIf { it.isNotEmpty() })
      put("activeDeviceId", sanitizeFirestoreId(activeDeviceId, maxLen = 64))
      put("totalSnapshots", totalSnapshots.coerceAtLeast(0L))
      put("updatedAt", FieldValue.serverTimestamp())
    }.filterValues { it != null }.mapValues { it.value!! }
}

@Keep
@IgnoreExtraProperties
data class CloudDeviceSnapshot(
  @DocumentId val id: String = "",
  val userId: String = "",
  val deviceId: String = "",
  val deviceName: String = "",
  val deviceModel: String = "",
  val appCount: Long = 0L,
  val activityCount: Long = 0L,
  val callLogCount: Long = 0L,
  val unlockCount: Long = 0L,
  val summaryNote: String? = null,
  val syncStatus: String = CloudSyncStatus.SYNCED.name,
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
) {
  fun resolvedStatus(): CloudSyncStatus = CloudSyncStatus.fromRaw(syncStatus)

  fun toCreateMap(): Map<String, Any> =
    buildMap<String, Any?> {
      put("userId", userId)
      put("deviceId", sanitizeFirestoreId(deviceId, maxLen = 64))
      put("deviceName", deviceName.trim().take(80).ifEmpty { "This Device" })
      put("deviceModel", deviceModel.trim().take(80).ifEmpty { "Android Device" })
      put("appCount", appCount.coerceAtLeast(0L))
      put("activityCount", activityCount.coerceAtLeast(0L))
      put("callLogCount", callLogCount.coerceAtLeast(0L))
      put("unlockCount", unlockCount.coerceAtLeast(0L))
      put("summaryNote", summaryNote?.trim()?.take(500)?.takeIf { it.isNotEmpty() })
      put("syncStatus", resolvedStatus().name)
      put("createdAt", FieldValue.serverTimestamp())
      put("updatedAt", FieldValue.serverTimestamp())
    }.filterValues { it != null }.mapValues { it.value!! }

  fun toUpdateMap(): Map<String, Any> =
    buildMap<String, Any?> {
      put("deviceName", deviceName.trim().take(80).ifEmpty { "This Device" })
      put("deviceModel", deviceModel.trim().take(80).ifEmpty { "Android Device" })
      put("appCount", appCount.coerceAtLeast(0L))
      put("activityCount", activityCount.coerceAtLeast(0L))
      put("callLogCount", callLogCount.coerceAtLeast(0L))
      put("unlockCount", unlockCount.coerceAtLeast(0L))
      put("summaryNote", summaryNote?.trim()?.take(500)?.takeIf { it.isNotEmpty() })
      put("syncStatus", resolvedStatus().name)
      put("updatedAt", FieldValue.serverTimestamp())
    }.filterValues { it != null }.mapValues { it.value!! }
}

@Keep
@IgnoreExtraProperties
data class CloudActivityRecord(
  @DocumentId val id: String = "",
  val userId: String = "",
  val deviceId: String = "",
  val recordCategory: String = CloudRecordCategory.APP_EVENT.name,
  val title: String = "",
  val subtitle: String = "",
  val detail: String? = null,
  val eventDate: String = "",
  val eventTime: String = "",
  val recordedAtMillis: Long = 0L,
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
) {
  fun resolvedCategory(): CloudRecordCategory = CloudRecordCategory.fromRaw(recordCategory)

  fun toCreateMap(): Map<String, Any> =
    buildMap<String, Any?> {
      put("userId", userId)
      put("deviceId", sanitizeFirestoreId(deviceId, maxLen = 64))
      put("recordCategory", resolvedCategory().name)
      put("title", title.trim().take(120).ifEmpty { "Activity Record" })
      put("subtitle", subtitle.trim().take(160).ifEmpty { "Unknown" })
      put("detail", detail?.trim()?.take(240)?.takeIf { it.isNotEmpty() })
      put("eventDate", eventDate.trim().take(20).ifEmpty { "1970-01-01" })
      put("eventTime", eventTime.trim().take(20).ifEmpty { "00:00:00" })
      put("recordedAtMillis", recordedAtMillis.coerceAtLeast(0L))
      put("createdAt", FieldValue.serverTimestamp())
      put("updatedAt", FieldValue.serverTimestamp())
    }.filterValues { it != null }.mapValues { it.value!! }

  fun toUpdateMap(): Map<String, Any> =
    buildMap<String, Any?> {
      put("recordCategory", resolvedCategory().name)
      put("title", title.trim().take(120).ifEmpty { "Activity Record" })
      put("subtitle", subtitle.trim().take(160).ifEmpty { "Unknown" })
      put("detail", detail?.trim()?.take(240)?.takeIf { it.isNotEmpty() })
      put("eventDate", eventDate.trim().take(20).ifEmpty { "1970-01-01" })
      put("eventTime", eventTime.trim().take(20).ifEmpty { "00:00:00" })
      put("recordedAtMillis", recordedAtMillis.coerceAtLeast(0L))
      put("updatedAt", FieldValue.serverTimestamp())
    }.filterValues { it != null }.mapValues { it.value!! }
}

fun sanitizeFirestoreId(raw: String, maxLen: Int = 128): String {
  val sanitized = raw.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(maxLen)
  return sanitized.ifEmpty { "DEV_LOCAL" }
}
