package com.example.connectivity

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.example.data.local.AppActivityEventEntity
import com.example.data.local.AppDatabase
import com.example.data.local.AppInventorySnapshotEntity
import com.example.data.local.CallLogEntity
import com.example.data.local.PairedDeviceEntity
import com.example.data.preferences.SettingsStore
import com.example.security.CryptoManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class IncomingTransferConfirmation(
  val remoteDeviceId: String,
  val remoteDeviceName: String,
  val remoteDeviceModel: String,
  val verificationCode: String,
  val transportMethod: String,
  val actionType: String, // "PAIR_AND_SYNC", "SYNC_PULL", "REMOTE_UNINSTALL"
  val packageToUninstall: String? = null,
)

data class ImportedSnapshotSummary(
  val deviceId: String,
  val deviceName: String,
  val inventoryCount: Int,
  val eventsCount: Int,
  val callLogsCount: Int,
)

object TransferProtocol {
  const val APP_PROTOCOL_ID = "com.aistudio.devicerecorder.protocol.v1"
  const val PROTOCOL_VERSION = 1
  const val METHOD_BLUETOOTH = "BLUETOOTH"
  const val METHOD_WIFI_DIRECT = "WIFI_DIRECT"
  const val METHOD_INTERNET = "INTERNET"

  fun compressTextBase64(text: String): String {
    val bos = ByteArrayOutputStream()
    GZIPOutputStream(bos).use { gzip ->
      gzip.write(text.toByteArray(Charsets.UTF_8))
    }
    return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
  }

  fun decompressTextBase64(compressedBase64: String): String {
    val bytes = Base64.decode(compressedBase64, Base64.NO_WRAP)
    GZIPInputStream(ByteArrayInputStream(bytes)).use { gzip ->
      return gzip.readBytes().toString(Charsets.UTF_8)
    }
  }

  suspend fun buildEncryptedRecordSnapshot(
    context: Context,
    db: AppDatabase,
    settingsStore: SettingsStore,
    sharedSecret: String,
  ): String =
    withContext(Dispatchers.IO) {
      val localId = settingsStore.getLocalDeviceId()
      val localName = settingsStore.getLocalDeviceName()
      val localModel = CryptoManager.getLocalDeviceModel()
      val events = db.appActivityDao().getEventsForDeviceOnce(localId)
      val inventory = db.appInventoryDao().getInventoryForDeviceOnce(localId)
      val callLogs = db.callLogDao().getCallLogsForDeviceOnce(localId)

      val payload =
        JSONObject().apply {
          put("type", "SYNC_RESPONSE")
          put("appProtocolId", APP_PROTOCOL_ID)
          put("protocolVersion", PROTOCOL_VERSION)
          put("deviceId", localId)
          put("deviceName", localName)
          put("deviceModel", localModel)
          put("exportedAt", System.currentTimeMillis())
          put(
            "events",
            JSONArray().apply {
              events.forEach { ev ->
                put(
                  JSONObject().apply {
                    put("appName", ev.appName)
                    put("packageName", ev.packageName)
                    put("versionName", ev.versionName)
                    put("eventType", ev.eventType)
                    put("installerPackage", ev.installerPackage ?: JSONObject.NULL)
                    put("installerLabel", ev.installerLabel)
                    put("eventDate", ev.eventDate)
                    put("eventTime", ev.eventTime)
                    put("timestampMillis", ev.timestampMillis)
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
                    put("callLogId", log.callLogId)
                    put("phoneNumber", CryptoManager.decryptField(context, log.phoneNumber))
                    put("callType", log.callType)
                    put("callDate", log.callDate)
                    put("callTime", log.callTime)
                    put("durationSeconds", log.durationSeconds)
                    put("timestampMillis", log.timestampMillis)
                  }
                )
              }
            },
          )
        }

      val plainBytes = payload.toString().toByteArray(Charsets.UTF_8)
      val sha256 = CryptoManager.sha256Hex(plainBytes)
      val cipherBytes = CryptoManager.encryptWithSharedSecret(plainBytes, sharedSecret)
      JSONObject()
        .apply {
          put("appProtocolId", APP_PROTOCOL_ID)
          put("senderDeviceId", localId)
          put("sha256", sha256)
          put("cipherBase64", Base64.encodeToString(cipherBytes, Base64.NO_WRAP))
        }
        .toString()
    }

  suspend fun verifyAndImportSnapshot(
    context: Context,
    db: AppDatabase,
    envelopeJson: String,
    sharedSecret: String,
    transportMethod: String,
    bluetoothAddress: String? = null,
    wifiDirectAddress: String? = null,
    internetEndpointId: String? = null,
  ): ImportedSnapshotSummary =
    withContext(Dispatchers.IO) {
      val env = JSONObject(envelopeJson)
      require(env.optString("appProtocolId") == APP_PROTOCOL_ID) {
        "Rejected: Remote device is not running Device Recorder."
      }
      val expectedSha = env.getString("sha256")
      val cipherBytes = Base64.decode(env.getString("cipherBase64"), Base64.NO_WRAP)
      val plainBytes = CryptoManager.decryptWithSharedSecret(cipherBytes, sharedSecret)
      require(CryptoManager.sha256Hex(plainBytes) == expectedSha) {
        "Integrity check failed: SHA-256 mismatch."
      }

      val root = JSONObject(plainBytes.toString(Charsets.UTF_8))
      require(root.optString("appProtocolId") == APP_PROTOCOL_ID) {
        "Rejected: Payload did not originate from Device Recorder."
      }
      val sourceDeviceId = root.getString("deviceId")
      val sourceDeviceName = root.optString("deviceName", sourceDeviceId)
      val sourceDeviceModel = root.optString("deviceModel", "Android Device")
      val now = System.currentTimeMillis()

      val eventsArr = root.getJSONArray("events")
      val events =
        (0 until eventsArr.length()).map { i ->
          val o = eventsArr.getJSONObject(i)
          AppActivityEventEntity(
            deviceId = sourceDeviceId,
            appName = o.getString("appName"),
            packageName = o.getString("packageName"),
            versionName = o.getString("versionName"),
            eventType = o.getString("eventType"),
            installerPackage = o.optString("installerPackage").takeIf { it.isNotEmpty() && it != "null" },
            installerLabel = o.getString("installerLabel"),
            eventDate = o.getString("eventDate"),
            eventTime = o.getString("eventTime"),
            timestampMillis = o.getLong("timestampMillis"),
            isImportedSnapshot = true,
            importedAtMillis = now,
          )
        }

      val invArr = root.getJSONArray("inventory")
      val inventory =
        (0 until invArr.length()).map { i ->
          val o = invArr.getJSONObject(i)
          val pkg = o.getString("packageName")
          AppInventorySnapshotEntity(
            id = "$sourceDeviceId:$pkg",
            deviceId = sourceDeviceId,
            appName = o.getString("appName"),
            packageName = pkg,
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
            isImportedSnapshot = true,
            importedAtMillis = now,
          )
        }

      val callArr = root.getJSONArray("callLogs")
      val callLogs =
        (0 until callArr.length()).map { i ->
          val o = callArr.getJSONObject(i)
          val rowId = o.getLong("callLogId")
          val ts = o.getLong("timestampMillis")
          CallLogEntity(
            id = "$sourceDeviceId:$rowId:$ts",
            deviceId = sourceDeviceId,
            callLogId = rowId,
            phoneNumber = CryptoManager.encryptField(context, o.getString("phoneNumber")),
            callType = o.getString("callType"),
            callDate = o.getString("callDate"),
            callTime = o.getString("callTime"),
            durationSeconds = o.getLong("durationSeconds"),
            timestampMillis = ts,
            isImportedSnapshot = true,
            importedAtMillis = now,
          )
        }

      db.appActivityDao().deleteEventsForDevice(sourceDeviceId)
      if (events.isNotEmpty()) db.appActivityDao().insertEvents(events)

      db.appInventoryDao().deleteInventoryForDevice(sourceDeviceId)
      if (inventory.isNotEmpty()) db.appInventoryDao().upsertInventory(inventory)

      db.callLogDao().deleteCallLogsForDevice(sourceDeviceId)
      if (callLogs.isNotEmpty()) db.callLogDao().upsertCallLogs(callLogs)

      val latestRecordTs =
        maxOf(
          events.maxOfOrNull { it.timestampMillis } ?: 0L,
          callLogs.maxOfOrNull { it.timestampMillis } ?: 0L,
        ).takeIf { it > 0L }

      val existingDevice = db.pairedDeviceDao().getDeviceById(sourceDeviceId)
      val methods =
        ((existingDevice?.availableMethods?.split(',')?.filter { it.isNotBlank() }.orEmpty()) +
            transportMethod)
          .distinct()
          .joinToString(",")

      db.pairedDeviceDao()
        .upsertDevice(
          PairedDeviceEntity(
            deviceId = sourceDeviceId,
            deviceName = sourceDeviceName,
            deviceModel = sourceDeviceModel,
            bluetoothAddress = bluetoothAddress ?: existingDevice?.bluetoothAddress,
            wifiDirectAddress = wifiDirectAddress ?: existingDevice?.wifiDirectAddress,
            internetEndpointId = internetEndpointId ?: existingDevice?.internetEndpointId,
            pairingStatus = "PAIRED",
            availableMethods = methods,
            sharedSecretEncrypted = CryptoManager.encryptLocalString(context, sharedSecret),
            lastTransferTimestampMillis = now,
            lastImportedRecordTimestampMillis = latestRecordTs ?: existingDevice?.lastImportedRecordTimestampMillis,
            remoteUninstallMode = existingDevice?.remoteUninstallMode ?: "MODE_B_INTERACTIVE",
            createdAtMillis = existingDevice?.createdAtMillis ?: now,
          )
        )

      ImportedSnapshotSummary(
        deviceId = sourceDeviceId,
        deviceName = sourceDeviceName,
        inventoryCount = inventory.size,
        eventsCount = events.size,
        callLogsCount = callLogs.size,
      )
    }

  fun isDeviceOwnerCapable(context: Context): Boolean {
    return try {
      val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
      dpm?.isDeviceOwnerApp(context.packageName) == true
    } catch (_: Exception) {
      false
    }
  }

  fun launchInteractiveUninstall(context: Context, packageName: String): Boolean {
    return try {
      val intent =
        Intent(Intent.ACTION_DELETE).apply {
          data = Uri.parse("package:$packageName")
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
      context.startActivity(intent)
      true
    } catch (_: Exception) {
      false
    }
  }
}
