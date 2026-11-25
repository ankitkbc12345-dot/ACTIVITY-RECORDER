package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "app_activity_events",
  indices = [
    Index("deviceId"),
    Index("packageName"),
    Index("timestampMillis"),
    Index("eventDate"),
  ],
)
data class AppActivityEventEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val deviceId: String,
  val appName: String,
  val packageName: String,
  val versionName: String,
  val eventType: String, // INSTALLED, UNINSTALLED, UPDATED
  val installerPackage: String?,
  val installerLabel: String, // "Google Play Store", known installer, or "Unknown"
  val eventDate: String, // yyyy-MM-dd
  val eventTime: String, // HH:mm:ss
  val timestampMillis: Long,
  val isImportedSnapshot: Boolean = false,
  val importedAtMillis: Long? = null,
)

@Entity(
  tableName = "app_inventory_snapshots",
  indices = [
    Index("deviceId"),
    Index("packageName"),
    Index("isInstalled"),
  ],
)
data class AppInventorySnapshotEntity(
  @PrimaryKey val id: String, // "$deviceId:$packageName"
  val deviceId: String,
  val appName: String,
  val packageName: String,
  val versionName: String,
  val versionCode: Long,
  val isInstalled: Boolean,
  val firstInstallTimeMillis: Long?,
  val lastUpdateTimeMillis: Long?,
  val installDate: String,
  val installTime: String,
  val installerPackage: String?,
  val installerLabel: String,
  val isImportedSnapshot: Boolean = false,
  val importedAtMillis: Long? = null,
)

@Entity(
  tableName = "call_logs",
  indices = [
    Index("deviceId"),
    Index("timestampMillis"),
    Index("callDate"),
    Index("phoneNumber"),
  ],
)
data class CallLogEntity(
  @PrimaryKey val id: String, // "$deviceId:$callLogId:$timestampMillis"
  val deviceId: String,
  val callLogId: Long,
  val phoneNumber: String,
  val callType: String, // INCOMING, OUTGOING, MISSED, REJECTED, BLOCKED, VOICEMAIL, OTHER
  val callDate: String, // yyyy-MM-dd
  val callTime: String, // HH:mm:ss
  val durationSeconds: Long,
  val timestampMillis: Long,
  val isImportedSnapshot: Boolean = false,
  val importedAtMillis: Long? = null,
)

@Entity(tableName = "paired_devices")
data class PairedDeviceEntity(
  @PrimaryKey val deviceId: String,
  val deviceName: String,
  val deviceModel: String,
  val bluetoothAddress: String? = null,
  val wifiDirectAddress: String? = null,
  val internetEndpointId: String? = null,
  val pairingStatus: String = "PAIRED", // PAIRED, PENDING_AUTH, REVOKED
  val availableMethods: String = "", // comma-separated BLUETOOTH,WIFI_DIRECT,INTERNET
  val sharedSecretEncrypted: String = "",
  val lastTransferTimestampMillis: Long? = null,
  val lastImportedRecordTimestampMillis: Long? = null,
  val remoteUninstallMode: String = "MODE_B_INTERACTIVE",
  val createdAtMillis: Long = System.currentTimeMillis(),
)

@Entity(
  tableName = "remote_uninstall_requests",
  indices = [Index("targetDeviceId"), Index("timestampMillis")],
)
data class RemoteUninstallRequestEntity(
  @PrimaryKey val requestId: String,
  val targetDeviceId: String,
  val packageName: String,
  val appName: String,
  val mode: String, // MODE_A_MANAGED, MODE_B_INTERACTIVE
  val status: String, // PENDING, SENT, COMPLETED, DENIED, FAILED, UNSUPPORTED
  val resultMessage: String,
  val timestampMillis: Long,
)
