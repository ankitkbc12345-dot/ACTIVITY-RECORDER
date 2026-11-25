package com.example.export

import android.content.Context
import com.example.data.local.AppActivityEventEntity
import com.example.data.local.AppDatabase
import com.example.data.local.AppInventorySnapshotEntity
import com.example.data.local.CallLogEntity
import com.example.data.local.PairedDeviceEntity
import com.example.security.CryptoManager
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class BackupRestoreSummary(
  val eventsCount: Int,
  val inventoryCount: Int,
  val callLogsCount: Int,
  val pairedDevicesCount: Int,
)

object EncryptedBackupManager {
  private const val MAGIC_HEADER = "PDRBAK_V1"

  suspend fun exportEncryptedBackup(
    context: Context,
    db: AppDatabase,
    localDeviceId: String,
    passphrase: String,
    outputStream: OutputStream,
  ): BackupRestoreSummary =
    withContext(Dispatchers.IO) {
      val events = db.appActivityDao().getAllEventsOnce()
      val inventory = db.appInventoryDao().getAllInventoryOnce()
      val callLogs = db.callLogDao().getAllCallLogsOnce()
      val pairedDevices = db.pairedDeviceDao().getPairedDevicesOnce()

      val root =
        JSONObject().apply {
          put("format", MAGIC_HEADER)
          put("createdAt", System.currentTimeMillis())
          put("sourceDeviceId", localDeviceId)
          put(
            "events",
            JSONArray().apply {
              events.forEach { ev ->
                put(
                  JSONObject().apply {
                    put("deviceId", ev.deviceId)
                    put("appName", ev.appName)
                    put("packageName", ev.packageName)
                    put("versionName", ev.versionName)
                    put("eventType", ev.eventType)
                    put("installerPackage", ev.installerPackage ?: JSONObject.NULL)
                    put("installerLabel", ev.installerLabel)
                    put("eventDate", ev.eventDate)
                    put("eventTime", ev.eventTime)
                    put("timestampMillis", ev.timestampMillis)
                    put("isImportedSnapshot", ev.isImportedSnapshot)
                    put("importedAtMillis", ev.importedAtMillis ?: JSONObject.NULL)
                  }
                )
              }
            },
          )
          put(
            "inventory",
            JSONArray().apply {
              inventory.forEach { item ->
                put(
                  JSONObject().apply {
                    put("id", item.id)
                    put("deviceId", item.deviceId)
                    put("appName", item.appName)
                    put("packageName", item.packageName)
                    put("versionName", item.versionName)
                    put("versionCode", item.versionCode)
                    put("isInstalled", item.isInstalled)
                    put("firstInstallTimeMillis", item.firstInstallTimeMillis ?: JSONObject.NULL)
                    put("lastUpdateTimeMillis", item.lastUpdateTimeMillis ?: JSONObject.NULL)
                    put("installDate", item.installDate)
                    put("installTime", item.installTime)
                    put("installerPackage", item.installerPackage ?: JSONObject.NULL)
                    put("installerLabel", item.installerLabel)
                    put("isImportedSnapshot", item.isImportedSnapshot)
                    put("importedAtMillis", item.importedAtMillis ?: JSONObject.NULL)
                  }
                )
              }
            },
          )
          put(
            "callLogs",
            JSONArray().apply {
              callLogs.forEach { log ->
                put(
                  JSONObject().apply {
                    put("id", log.id)
                    put("deviceId", log.deviceId)
                    put("callLogId", log.callLogId)
                    put("phoneNumber", CryptoManager.decryptField(context, log.phoneNumber))
                    put("callType", log.callType)
                    put("callDate", log.callDate)
                    put("callTime", log.callTime)
                    put("durationSeconds", log.durationSeconds)
                    put("timestampMillis", log.timestampMillis)
                    put("isImportedSnapshot", log.isImportedSnapshot)
                    put("importedAtMillis", log.importedAtMillis ?: JSONObject.NULL)
                  }
                )
              }
            },
          )
          put(
            "pairedDevices",
            JSONArray().apply {
              pairedDevices.forEach { dev ->
                put(
                  JSONObject().apply {
                    put("deviceId", dev.deviceId)
                    put("deviceName", dev.deviceName)
                    put("deviceModel", dev.deviceModel)
                    put("bluetoothAddress", dev.bluetoothAddress ?: JSONObject.NULL)
                    put("wifiDirectAddress", dev.wifiDirectAddress ?: JSONObject.NULL)
                    put("internetEndpointId", dev.internetEndpointId ?: JSONObject.NULL)
                    put("pairingStatus", dev.pairingStatus)
                    put("availableMethods", dev.availableMethods)
                    put("lastTransferTimestampMillis", dev.lastTransferTimestampMillis ?: JSONObject.NULL)
                    put(
                      "lastImportedRecordTimestampMillis",
                      dev.lastImportedRecordTimestampMillis ?: JSONObject.NULL,
                    )
                    put("remoteUninstallMode", dev.remoteUninstallMode)
                    put("createdAtMillis", dev.createdAtMillis)
                  }
                )
              }
            },
          )
        }

      val rawBytes = root.toString().toByteArray(Charsets.UTF_8)
      val checksum = CryptoManager.sha256Hex(rawBytes)
      val envelope =
        JSONObject().apply {
          put("magic", MAGIC_HEADER)
          put("sha256", checksum)
          put(
            "cipherBase64",
            android.util.Base64.encodeToString(
              CryptoManager.encryptWithPassphrase(rawBytes, passphrase),
              android.util.Base64.NO_WRAP,
            ),
          )
        }

      outputStream.use { it.write(envelope.toString().toByteArray(Charsets.UTF_8)) }

      BackupRestoreSummary(
        eventsCount = events.size,
        inventoryCount = inventory.size,
        callLogsCount = callLogs.size,
        pairedDevicesCount = pairedDevices.size,
      )
    }

  suspend fun restoreEncryptedBackup(
    context: Context,
    db: AppDatabase,
    passphrase: String,
    inputStream: InputStream,
  ): BackupRestoreSummary =
    withContext(Dispatchers.IO) {
      val content = inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
      val envelope = JSONObject(content)
      require(envelope.optString("magic") == MAGIC_HEADER) { "Unsupported backup file format." }
      val expectedSha = envelope.getString("sha256")
      val cipherBytes = android.util.Base64.decode(envelope.getString("cipherBase64"), android.util.Base64.NO_WRAP)
      val decryptedBytes = CryptoManager.decryptWithPassphrase(cipherBytes, passphrase)
      require(CryptoManager.sha256Hex(decryptedBytes) == expectedSha) {
        "Backup integrity verification failed."
      }

      val root = JSONObject(decryptedBytes.toString(Charsets.UTF_8))
      val eventsArr = root.getJSONArray("events")
      val events =
        (0 until eventsArr.length()).map { i ->
          val o = eventsArr.getJSONObject(i)
          AppActivityEventEntity(
            deviceId = o.getString("deviceId"),
            appName = o.getString("appName"),
            packageName = o.getString("packageName"),
            versionName = o.getString("versionName"),
            eventType = o.getString("eventType"),
            installerPackage = o.optString("installerPackage").takeIf { it.isNotEmpty() && it != "null" },
            installerLabel = o.getString("installerLabel"),
            eventDate = o.getString("eventDate"),
            eventTime = o.getString("eventTime"),
            timestampMillis = o.getLong("timestampMillis"),
            isImportedSnapshot = o.optBoolean("isImportedSnapshot", false),
            importedAtMillis = if (o.isNull("importedAtMillis")) null else o.getLong("importedAtMillis"),
          )
        }

      val invArr = root.getJSONArray("inventory")
      val inventory =
        (0 until invArr.length()).map { i ->
          val o = invArr.getJSONObject(i)
          AppInventorySnapshotEntity(
            id = o.getString("id"),
            deviceId = o.getString("deviceId"),
            appName = o.getString("appName"),
            packageName = o.getString("packageName"),
            versionName = o.getString("versionName"),
            versionCode = o.getLong("versionCode"),
            isInstalled = o.getBoolean("isInstalled"),
            firstInstallTimeMillis =
              if (o.isNull("firstInstallTimeMillis")) null else o.getLong("firstInstallTimeMillis"),
            lastUpdateTimeMillis =
              if (o.isNull("lastUpdateTimeMillis")) null else o.getLong("lastUpdateTimeMillis"),
            installDate = o.getString("installDate"),
            installTime = o.getString("installTime"),
            installerPackage = o.optString("installerPackage").takeIf { it.isNotEmpty() && it != "null" },
            installerLabel = o.getString("installerLabel"),
            isImportedSnapshot = o.optBoolean("isImportedSnapshot", false),
            importedAtMillis = if (o.isNull("importedAtMillis")) null else o.getLong("importedAtMillis"),
          )
        }

      val callArr = root.getJSONArray("callLogs")
      val callLogs =
        (0 until callArr.length()).map { i ->
          val o = callArr.getJSONObject(i)
          CallLogEntity(
            id = o.getString("id"),
            deviceId = o.getString("deviceId"),
            callLogId = o.getLong("callLogId"),
            phoneNumber = CryptoManager.encryptField(context, o.getString("phoneNumber")),
            callType = o.getString("callType"),
            callDate = o.getString("callDate"),
            callTime = o.getString("callTime"),
            durationSeconds = o.getLong("durationSeconds"),
            timestampMillis = o.getLong("timestampMillis"),
            isImportedSnapshot = o.optBoolean("isImportedSnapshot", false),
            importedAtMillis = if (o.isNull("importedAtMillis")) null else o.getLong("importedAtMillis"),
          )
        }

      val devArr = root.getJSONArray("pairedDevices")
      val pairedDevices =
        (0 until devArr.length()).map { i ->
          val o = devArr.getJSONObject(i)
          PairedDeviceEntity(
            deviceId = o.getString("deviceId"),
            deviceName = o.getString("deviceName"),
            deviceModel = o.getString("deviceModel"),
            bluetoothAddress = o.optString("bluetoothAddress").takeIf { it.isNotEmpty() && it != "null" },
            wifiDirectAddress = o.optString("wifiDirectAddress").takeIf { it.isNotEmpty() && it != "null" },
            internetEndpointId = o.optString("internetEndpointId").takeIf { it.isNotEmpty() && it != "null" },
            pairingStatus = o.optString("pairingStatus", "PAIRED"),
            availableMethods = o.optString("availableMethods", ""),
            lastTransferTimestampMillis =
              if (o.isNull("lastTransferTimestampMillis")) null else o.getLong("lastTransferTimestampMillis"),
            lastImportedRecordTimestampMillis =
              if (o.isNull("lastImportedRecordTimestampMillis")) null
              else o.getLong("lastImportedRecordTimestampMillis"),
            remoteUninstallMode = o.optString("remoteUninstallMode", "MODE_B_INTERACTIVE"),
            createdAtMillis = o.optLong("createdAtMillis", System.currentTimeMillis()),
          )
        }

      if (events.isNotEmpty()) db.appActivityDao().insertEvents(events)
      if (inventory.isNotEmpty()) db.appInventoryDao().upsertInventory(inventory)
      if (callLogs.isNotEmpty()) db.callLogDao().upsertCallLogs(callLogs)
      if (pairedDevices.isNotEmpty()) db.pairedDeviceDao().upsertDevices(pairedDevices)

      BackupRestoreSummary(
        eventsCount = events.size,
        inventoryCount = inventory.size,
        callLogsCount = callLogs.size,
        pairedDevicesCount = pairedDevices.size,
      )
    }
}
