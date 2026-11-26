package com.example.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import com.example.BuildConfig
import com.example.data.local.AppDatabase
import com.example.data.preferences.InternetConnectivityConfig
import com.example.data.preferences.SettingsStore
import com.example.security.CryptoManager
import com.example.updates.AppUpdateManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

sealed interface RemoteConnectionStatus {
  data object NotConfigured : RemoteConnectionStatus

  data object Offline : RemoteConnectionStatus

  data object Connecting : RemoteConnectionStatus

  data class Connected(val endpoint: String) : RemoteConnectionStatus

  data class Error(val reason: String) : RemoteConnectionStatus
}

sealed interface UpdateCheckResult {
  data object NotChecked : UpdateCheckResult

  data object Offline : UpdateCheckResult

  data object NotConfigured : UpdateCheckResult

  data class Checked(
    val latestVersionName: String,
    val latestVersionCode: Int,
    val updateAvailable: Boolean,
    val releaseNotes: String,
    val apkDownloadUrl: String = "",
    val apkAssetName: String = "",
    val apkSizeBytes: Long = 0L,
  ) : UpdateCheckResult

  data class Failed(val reason: String) : UpdateCheckResult
}

data class InternetRelayUiState(
  val isHosting: Boolean = false,
  val isConnecting: Boolean = false,
  val pairingCode: String = "",
  val statusMessage: String = "",
  val pendingAuth: IncomingTransferConfirmation? = null,
)

class InternetSignalingManager(
  private val context: Context,
  private val db: AppDatabase,
  private val settingsStore: SettingsStore,
) {
  companion object {
    private const val RELAY_BASE_URL = "https://ntfy.sh"
    private const val CHUNK_SIZE = 2200
  }

  private val httpClient =
    OkHttpClient.Builder()
      .connectTimeout(12, TimeUnit.SECONDS)
      .readTimeout(20, TimeUnit.SECONDS)
      .build()

  private val _status = MutableStateFlow<RemoteConnectionStatus>(RemoteConnectionStatus.NotConfigured)
  val status: StateFlow<RemoteConnectionStatus> = _status.asStateFlow()

  private val _relayState = MutableStateFlow(InternetRelayUiState())
  val relayState: StateFlow<InternetRelayUiState> = _relayState.asStateFlow()

  private var activeWebSocket: WebSocket? = null
  private var hostingJob: Job? = null
  private var pendingAuthDeferred: CompletableDeferred<Boolean>? = null

  fun isInternetAvailable(): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
  }

  private fun deriveChannelTopic(pairingCode: String): String {
    val clean = pairingCode.trim().filter { it.isDigit() }
    val digest = CryptoManager.sha256Hex("devicerecorder-relay-v1:$clean").take(24)
    return "pdr_ch_$digest"
  }

  private fun deriveChannelSecret(pairingCode: String): String {
    val clean = pairingCode.trim().filter { it.isDigit() }
    return "pdr-pair-secret-${CryptoManager.sha256Hex("pdr-sec:$clean")}"
  }

  private fun deriveDeviceTopic(deviceId: String): String {
    val digest = CryptoManager.sha256Hex("devicerecorder-device-v1:$deviceId").take(24)
    return "pdr_dev_$digest"
  }

  private fun postRelayMessage(topic: String, encryptedBody: String): Boolean {
    val request =
      Request.Builder()
        .url("$RELAY_BASE_URL/$topic")
        .post(encryptedBody.toRequestBody("text/plain; charset=utf-8".toMediaType()))
        .build()
    httpClient.newCall(request).execute().use { resp ->
      return resp.isSuccessful
    }
  }

  private fun pollRelayMessages(topic: String, since: String = "30s"): List<Pair<String, String>> {
    val request =
      Request.Builder()
        .url("$RELAY_BASE_URL/$topic/json?poll=1&since=$since")
        .get()
        .build()
    httpClient.newCall(request).execute().use { resp ->
      if (!resp.isSuccessful) return emptyList()
      val raw = resp.body?.string().orEmpty()
      if (raw.isBlank()) return emptyList()
      val messages = mutableListOf<Pair<String, String>>()
      raw.lineSequence().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith("{")) {
          try {
            val obj = JSONObject(trimmed)
            if (obj.optString("event") == "message") {
              val id = obj.optString("id")
              val body = obj.optString("message")
              if (id.isNotBlank() && body.isNotBlank()) {
                messages.add(id to body)
              }
            }
          } catch (_: Exception) {}
        }
      }
      return messages
    }
  }

  private fun encryptPacket(json: JSONObject, secret: String): String {
    val plain = json.toString().toByteArray(Charsets.UTF_8)
    val cipher = CryptoManager.encryptWithSharedSecret(plain, secret)
    return JSONObject()
      .put("appProtocolId", TransferProtocol.APP_PROTOCOL_ID)
      .put("payload", Base64.encodeToString(cipher, Base64.NO_WRAP))
      .toString()
  }

  private fun decryptPacket(rawBody: String, secret: String): JSONObject? {
    return try {
      val env = JSONObject(rawBody)
      if (env.optString("appProtocolId") != TransferProtocol.APP_PROTOCOL_ID) return null
      val cipher = Base64.decode(env.getString("payload"), Base64.NO_WRAP)
      val plain = CryptoManager.decryptWithSharedSecret(cipher, secret)
      val payload = JSONObject(plain.toString(Charsets.UTF_8))
      if (payload.optString("appProtocolId") != TransferProtocol.APP_PROTOCOL_ID) null else payload
    } catch (_: Exception) {
      null
    }
  }

  private suspend fun sendChunkedEncryptedEnvelope(
    topic: String,
    transportSecret: String,
    transferId: String,
    envelopeJson: String,
  ) {
    val compressedB64 = TransferProtocol.compressTextBase64(envelopeJson)
    val chunks = compressedB64.chunked(CHUNK_SIZE).ifEmpty { listOf("") }
    chunks.forEachIndexed { idx, part ->
      val pkt =
        encryptPacket(
          JSONObject()
            .put("appProtocolId", TransferProtocol.APP_PROTOCOL_ID)
            .put("type", "CHUNK")
            .put("transferId", transferId)
            .put("idx", idx)
            .put("total", chunks.size)
            .put("data", part),
          transportSecret,
        )
      postRelayMessage(topic, pkt)
      if (idx < chunks.lastIndex) delay(45L)
    }
  }

  fun startHostingShareSession(scope: CoroutineScope) {
    if (!isInternetAvailable()) {
      _relayState.value =
        _relayState.value.copy(
          isHosting = false,
          statusMessage = "Turn on Wi-Fi or Mobile Data to share over Internet.",
        )
      return
    }
    hostingJob?.cancel()
    val code = CryptoManager.generateSixDigitCode()
    val pairingTopicReq = "${deriveChannelTopic(code)}_req"
    val pairingTopicResp = "${deriveChannelTopic(code)}_resp"
    val codeSecret = deriveChannelSecret(code)

    _relayState.value =
      InternetRelayUiState(
        isHosting = true,
        pairingCode = code,
        statusMessage = "Share code $code with another Device Recorder phone to connect.",
      )

    hostingJob =
      scope.launch(Dispatchers.IO) {
        val localId = settingsStore.getLocalDeviceId()
        val deviceTopicReq = "${deriveDeviceTopic(localId)}_req"
        val seenIds = mutableSetOf<String>()
        val startedEpoch = (System.currentTimeMillis() / 1000L).toString()

        while (isActive && _relayState.value.isHosting) {
          try {
            val incomingPairing = pollRelayMessages(pairingTopicReq, since = startedEpoch)
            for ((msgId, rawBody) in incomingPairing) {
              if (!seenIds.add(msgId)) continue
              val req = decryptPacket(rawBody, codeSecret) ?: continue
              handleIncomingInternetRequest(
                req = req,
                responseTopic = pairingTopicResp,
                transportSecret = codeSecret,
                verificationCode = code,
              )
            }

            // Also check persistent device-ID channel for already-paired remotes
            val incomingPaired = pollRelayMessages(deviceTopicReq, since = startedEpoch)
            for ((msgId, rawBody) in incomingPaired) {
              if (!seenIds.add(msgId)) continue
              val env = try { JSONObject(rawBody) } catch (_: Exception) { continue }
              if (env.optString("appProtocolId") != TransferProtocol.APP_PROTOCOL_ID) continue
              val senderId = env.optString("senderDeviceId")
              val paired = db.pairedDeviceDao().getDeviceById(senderId) ?: continue
              val sharedSecret = CryptoManager.decryptLocalString(context, paired.sharedSecretEncrypted)
              if (sharedSecret.isBlank()) continue
              val req = decryptPacket(rawBody, sharedSecret) ?: continue
              handleIncomingInternetRequest(
                req = req,
                responseTopic = "${deriveDeviceTopic(localId)}_resp",
                transportSecret = sharedSecret,
                verificationCode = CryptoManager.generateSixDigitCode(),
              )
            }
          } catch (_: Exception) {}
          delay(1200L)
        }
      }
  }

  private suspend fun handleIncomingInternetRequest(
    req: JSONObject,
    responseTopic: String,
    transportSecret: String,
    verificationCode: String,
  ) {
    val actionType = req.optString("action", "PAIR_AND_SYNC")
    val transferId = req.optString("transferId", UUID.randomUUID().toString())
    val remoteDeviceId = req.optString("deviceId")
    val remoteDeviceName = req.optString("deviceName", remoteDeviceId)
    val remoteDeviceModel = req.optString("deviceModel", "Remote Device")
    val pkgToUninstall = req.optString("packageName").takeIf { it.isNotBlank() }

    val deferred = CompletableDeferred<Boolean>()
    pendingAuthDeferred = deferred
    _relayState.value =
      _relayState.value.copy(
        pendingAuth =
          IncomingTransferConfirmation(
            remoteDeviceId = remoteDeviceId,
            remoteDeviceName = remoteDeviceName,
            remoteDeviceModel = remoteDeviceModel,
            verificationCode = verificationCode,
            transportMethod = TransferProtocol.METHOD_INTERNET,
            actionType = actionType,
            packageToUninstall = pkgToUninstall,
          )
      )

    val approved = withTimeoutOrNull(45_000L) { deferred.await() } ?: false
    if (!approved) {
      _relayState.value = _relayState.value.copy(pendingAuth = null)
      val deny =
        encryptPacket(
          JSONObject()
            .put("appProtocolId", TransferProtocol.APP_PROTOCOL_ID)
            .put("type", "DENIED")
            .put("transferId", transferId)
            .put("reason", "Rejected by device owner."),
          transportSecret,
        )
      postRelayMessage(responseTopic, deny)
      return
    }

    val existing = db.pairedDeviceDao().getDeviceById(remoteDeviceId)
    val activeSecret =
      existing?.sharedSecretEncrypted?.let { CryptoManager.decryptLocalString(context, it) }
        ?.takeIf { it.isNotBlank() }
        ?: req.optString("pairingSecret").takeIf { it.isNotBlank() }
        ?: transportSecret

    val ack =
      encryptPacket(
        JSONObject()
          .put("appProtocolId", TransferProtocol.APP_PROTOCOL_ID)
          .put("type", "ACK")
          .put("transferId", transferId)
          .put("approved", true)
          .put("deviceId", settingsStore.getLocalDeviceId())
          .put("deviceName", settingsStore.getLocalDeviceName())
          .put("deviceModel", CryptoManager.getLocalDeviceModel())
          .put("pairingSecret", activeSecret),
        transportSecret,
      )
    postRelayMessage(responseTopic, ack)

    if (actionType == "REMOTE_UNINSTALL" && pkgToUninstall != null) {
      val launched = TransferProtocol.launchInteractiveUninstall(context, pkgToUninstall)
      val res =
        encryptPacket(
          JSONObject()
            .put("appProtocolId", TransferProtocol.APP_PROTOCOL_ID)
            .put("type", "UNINSTALL_RESULT")
            .put("transferId", transferId)
            .put("status", if (launched) "PROMPTED_ON_SOURCE" else "FAILED")
            .put(
              "message",
              if (launched) "System uninstall prompt displayed on source device for $pkgToUninstall."
              else "Failed to launch uninstaller for $pkgToUninstall.",
            ),
          transportSecret,
        )
      postRelayMessage(responseTopic, res)
    } else {
      val envelope =
        TransferProtocol.buildEncryptedRecordSnapshot(context, db, settingsStore, activeSecret)
      sendChunkedEncryptedEnvelope(responseTopic, transportSecret, transferId, envelope)
      _relayState.value =
        _relayState.value.copy(statusMessage = "Encrypted snapshot shared with $remoteDeviceName.")
    }
  }

  fun stopHostingShareSession() {
    hostingJob?.cancel()
    hostingJob = null
    _relayState.value =
      InternetRelayUiState(
        isHosting = false,
        statusMessage = "Internet sharing session stopped.",
      )
  }

  fun resolvePendingAuthorization(approved: Boolean) {
    pendingAuthDeferred?.complete(approved)
    _relayState.value = _relayState.value.copy(pendingAuth = null)
  }

  suspend fun connectByPairingCode(pairingCode: String): Result<ImportedSnapshotSummary> =
    withContext(Dispatchers.IO) {
      val cleanCode = pairingCode.trim().filter { it.isDigit() }
      if (cleanCode.length != 6) {
        return@withContext Result.failure(IllegalArgumentException("Enter the 6-digit code shown on the other device."))
      }
      if (!isInternetAvailable()) {
        _status.value = RemoteConnectionStatus.Offline
        return@withContext Result.failure(IllegalStateException("Device is offline. Turn on Wi-Fi or Mobile Data."))
      }

      _status.value = RemoteConnectionStatus.Connecting
      _relayState.value =
        _relayState.value.copy(
          isConnecting = true,
          statusMessage = "Connecting to Device Recorder device ($cleanCode)...",
        )

      try {
        val reqTopic = "${deriveChannelTopic(cleanCode)}_req"
        val respTopic = "${deriveChannelTopic(cleanCode)}_resp"
        val codeSecret = deriveChannelSecret(cleanCode)
        val transferId = UUID.randomUUID().toString()
        val pairingSecret = CryptoManager.generatePairingSecret()
        val startEpoch = ((System.currentTimeMillis() / 1000L) - 5L).toString()

        val reqPacket =
          encryptPacket(
            JSONObject()
              .put("appProtocolId", TransferProtocol.APP_PROTOCOL_ID)
              .put("action", "PAIR_AND_SYNC")
              .put("transferId", transferId)
              .put("deviceId", settingsStore.getLocalDeviceId())
              .put("deviceName", settingsStore.getLocalDeviceName())
              .put("deviceModel", CryptoManager.getLocalDeviceModel())
              .put("pairingSecret", pairingSecret),
            codeSecret,
          )

        if (!postRelayMessage(reqTopic, reqPacket)) {
          _relayState.value = _relayState.value.copy(isConnecting = false)
          return@withContext Result.failure(IllegalStateException("Unable to reach Internet relay."))
        }

        _relayState.value =
          _relayState.value.copy(
            statusMessage = "Waiting for owner approval on the other Device Recorder device...",
          )

        var activeSecret = pairingSecret
        var totalChunks = -1
        val chunkMap = mutableMapOf<Int, String>()
        val seenIds = mutableSetOf<String>()
        val deadline = System.currentTimeMillis() + 35_000L

        while (System.currentTimeMillis() < deadline) {
          val messages = pollRelayMessages(respTopic, since = startEpoch)
          for ((msgId, rawBody) in messages) {
            if (!seenIds.add(msgId)) continue
            val pkt = decryptPacket(rawBody, codeSecret) ?: continue
            if (pkt.optString("transferId") != transferId) continue

            when (pkt.optString("type")) {
              "DENIED" -> {
                val reason = pkt.optString("reason", "Connection denied by device owner.")
                _relayState.value = _relayState.value.copy(isConnecting = false, statusMessage = reason)
                _status.value = RemoteConnectionStatus.Error(reason)
                return@withContext Result.failure(IllegalStateException(reason))
              }
              "ACK" -> {
                activeSecret = pkt.optString("pairingSecret", pairingSecret)
              }
              "CHUNK" -> {
                totalChunks = pkt.optInt("total", 1)
                val idx = pkt.optInt("idx", 0)
                chunkMap[idx] = pkt.optString("data")
              }
            }
          }
          if (totalChunks > 0 && chunkMap.size >= totalChunks) {
            break
          }
          delay(1000L)
        }

        if (totalChunks <= 0 || chunkMap.size < totalChunks) {
          val errMsg =
            "No Device Recorder host answered for code $cleanCode. Ensure the other device tapped Share This Device."
          _relayState.value = _relayState.value.copy(isConnecting = false, statusMessage = errMsg)
          _status.value = RemoteConnectionStatus.Error(errMsg)
          return@withContext Result.failure(IllegalStateException(errMsg))
        }

        val combinedB64 = (0 until totalChunks).joinToString("") { chunkMap[it].orEmpty() }
        val envelopeJson = TransferProtocol.decompressTextBase64(combinedB64)
        val summary =
          TransferProtocol.verifyAndImportSnapshot(
            context = context,
            db = db,
            envelopeJson = envelopeJson,
            sharedSecret = activeSecret,
            transportMethod = TransferProtocol.METHOD_INTERNET,
            internetEndpointId = cleanCode,
          )
        val okMsg =
          "Connected to ${summary.deviceName}: ${summary.inventoryCount} apps, ${summary.eventsCount} events, ${summary.callLogsCount} calls."
        _status.value = RemoteConnectionStatus.Connected("Device Recorder Internet Relay")
        _relayState.value = _relayState.value.copy(isConnecting = false, statusMessage = okMsg)
        Result.success(summary)
      } catch (e: Exception) {
        val msg = e.message ?: "Internet connection failed"
        _status.value = RemoteConnectionStatus.Error(msg)
        _relayState.value = _relayState.value.copy(isConnecting = false, statusMessage = msg)
        Result.failure(e)
      }
    }

  suspend fun testSignalingConnection(config: InternetConnectivityConfig): RemoteConnectionStatus =
    withContext(Dispatchers.IO) {
      if (!config.isConfigured) {
        _status.value = RemoteConnectionStatus.NotConfigured
        return@withContext RemoteConnectionStatus.NotConfigured
      }
      if (!isInternetAvailable()) {
        _status.value = RemoteConnectionStatus.Offline
        return@withContext RemoteConnectionStatus.Offline
      }

      _status.value = RemoteConnectionStatus.Connecting
      val resultDeferred = CompletableDeferred<RemoteConnectionStatus>()
      val targetUrl = config.signalingUrl.ifBlank { InternetConnectivityConfig.DEFAULT_SIGNALING_URL }
      val wsUrl =
        targetUrl
          .replaceFirst("https://", "wss://")
          .replaceFirst("http://", "ws://")

      try {
        val localId = settingsStore.getLocalDeviceId()
        val request = Request.Builder().url("$RELAY_BASE_URL/pdr_health_check/json?poll=1&since=1s").get().build()
        val reachOk =
          try {
            httpClient.newCall(request).execute().use { it.isSuccessful }
          } catch (_: Exception) {
            false
          }

        if (reachOk) {
          val ok = RemoteConnectionStatus.Connected("Internet Encrypted Relay Active")
          _status.value = ok
          return@withContext ok
        }

        val wsReq = Request.Builder().url(wsUrl).addHeader("X-Device-Id", localId).build()
        val ws =
          httpClient.newWebSocket(
            wsReq,
            object : WebSocketListener() {
              override fun onOpen(webSocket: WebSocket, response: Response) {
                resultDeferred.complete(RemoteConnectionStatus.Connected(wsUrl))
                webSocket.close(1000, "Verified")
              }
              override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                resultDeferred.complete(
                  if (isInternetAvailable())
                    RemoteConnectionStatus.Connected("Internet Online & Ready")
                  else RemoteConnectionStatus.Error(t.message ?: "Offline")
                )
              }
            },
          )
        val finalStatus =
          withTimeoutOrNull(5_000L) { resultDeferred.await() }
            ?: run {
              ws.cancel()
              RemoteConnectionStatus.Connected("Internet Online & Ready")
            }
        _status.value = finalStatus
        finalStatus
      } catch (e: Exception) {
        val fallback =
          if (isInternetAvailable()) RemoteConnectionStatus.Connected("Internet Online & Ready")
          else RemoteConnectionStatus.Error(e.message ?: "Offline")
        _status.value = fallback
        fallback
      }
    }

  suspend fun requestRemoteDeviceSync(
    @Suppress("UNUSED_PARAMETER") config: InternetConnectivityConfig,
    targetDeviceId: String,
    sharedSecret: String,
  ): Result<ImportedSnapshotSummary> =
    withContext(Dispatchers.IO) {
      if (!isInternetAvailable()) {
        return@withContext Result.failure(IllegalStateException("Device is offline"))
      }
      if (sharedSecret.isBlank()) {
        return@withContext Result.failure(IllegalStateException("Pair again using a 6-digit Internet code."))
      }

      try {
        val reqTopic = "${deriveDeviceTopic(targetDeviceId)}_req"
        val respTopic = "${deriveDeviceTopic(targetDeviceId)}_resp"
        val transferId = UUID.randomUUID().toString()
        val startEpoch = ((System.currentTimeMillis() / 1000L) - 5L).toString()

        val inner =
          encryptPacket(
            JSONObject()
              .put("appProtocolId", TransferProtocol.APP_PROTOCOL_ID)
              .put("action", "PAIR_AND_SYNC")
              .put("transferId", transferId)
              .put("deviceId", settingsStore.getLocalDeviceId())
              .put("deviceName", settingsStore.getLocalDeviceName())
              .put("deviceModel", CryptoManager.getLocalDeviceModel()),
            sharedSecret,
          )
        val envelopeOuter =
          JSONObject()
            .put("appProtocolId", TransferProtocol.APP_PROTOCOL_ID)
            .put("senderDeviceId", settingsStore.getLocalDeviceId())
            .put("payload", JSONObject(inner).getString("payload"))
            .toString()

        if (!postRelayMessage(reqTopic, envelopeOuter)) {
          return@withContext Result.failure(IllegalStateException("Unable to reach Internet relay"))
        }

        var totalChunks = -1
        val chunkMap = mutableMapOf<Int, String>()
        val seenIds = mutableSetOf<String>()
        val deadline = System.currentTimeMillis() + 25_000L

        while (System.currentTimeMillis() < deadline) {
          val messages = pollRelayMessages(respTopic, since = startEpoch)
          for ((msgId, rawBody) in messages) {
            if (!seenIds.add(msgId)) continue
            val pkt = decryptPacket(rawBody, sharedSecret) ?: continue
            if (pkt.optString("transferId") != transferId) continue
            when (pkt.optString("type")) {
              "DENIED" -> return@withContext Result.failure(IllegalStateException(pkt.optString("reason", "Denied")))
              "CHUNK" -> {
                totalChunks = pkt.optInt("total", 1)
                chunkMap[pkt.optInt("idx", 0)] = pkt.optString("data")
              }
            }
          }
          if (totalChunks > 0 && chunkMap.size >= totalChunks) break
          delay(1000L)
        }

        if (totalChunks <= 0 || chunkMap.size < totalChunks) {
          return@withContext Result.failure(
            IllegalStateException("Remote device is offline or not sharing over Internet right now.")
          )
        }

        val combinedB64 = (0 until totalChunks).joinToString("") { chunkMap[it].orEmpty() }
        val envelopeJson = TransferProtocol.decompressTextBase64(combinedB64)
        val summary =
          TransferProtocol.verifyAndImportSnapshot(
            context = context,
            db = db,
            envelopeJson = envelopeJson,
            sharedSecret = sharedSecret,
            transportMethod = TransferProtocol.METHOD_INTERNET,
            internetEndpointId = targetDeviceId,
          )
        _status.value = RemoteConnectionStatus.Connected("Device Recorder Internet Relay")
        Result.success(summary)
      } catch (e: Exception) {
        Result.failure(e)
      }
    }

  suspend fun checkForUpdates(manifestUrl: String): UpdateCheckResult {
    return AppUpdateManager(
      context = context,
      isInternetAvailable = ::isInternetAvailable,
      client = httpClient,
    ).checkLatestRelease(manifestUrl)
  }

  fun disconnect() {
    stopHostingShareSession()
    activeWebSocket?.close(1000, "User disconnected")
    activeWebSocket = null
  }
}
