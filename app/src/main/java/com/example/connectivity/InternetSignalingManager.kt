package com.example.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.BuildConfig
import com.example.data.local.AppDatabase
import com.example.data.preferences.InternetConnectivityConfig
import com.example.data.preferences.SettingsStore
import com.example.security.CryptoManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
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
  ) : UpdateCheckResult

  data class Failed(val reason: String) : UpdateCheckResult
}

class InternetSignalingManager(
  private val context: Context,
  private val db: AppDatabase,
  private val settingsStore: SettingsStore,
) {
  private val httpClient =
    OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(15, TimeUnit.SECONDS)
      .build()

  private val _status = MutableStateFlow<RemoteConnectionStatus>(RemoteConnectionStatus.NotConfigured)
  val status: StateFlow<RemoteConnectionStatus> = _status.asStateFlow()

  private var activeWebSocket: WebSocket? = null

  fun isInternetAvailable(): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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
      val wsUrl =
        config.signalingUrl
          .replaceFirst("https://", "wss://")
          .replaceFirst("http://", "ws://")

      try {
        val requestBuilder = Request.Builder().url(wsUrl)
        if (config.connectionToken.isNotBlank()) {
          requestBuilder.addHeader("Authorization", "Bearer ${config.connectionToken}")
        }
        val localId = settingsStore.getLocalDeviceId()
        requestBuilder.addHeader("X-Device-Id", localId)

        val ws =
          httpClient.newWebSocket(
            requestBuilder.build(),
            object : WebSocketListener() {
              override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                  JSONObject()
                    .put("type", "HELLO")
                    .put("deviceId", localId)
                    .put("stunConfigured", config.stunUri.isNotBlank())
                    .put("turnConfigured", config.turnUri.isNotBlank())
                    .toString()
                )
                resultDeferred.complete(RemoteConnectionStatus.Connected(wsUrl))
                webSocket.close(1000, "Handshake verified")
              }

              override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                resultDeferred.complete(
                  RemoteConnectionStatus.Error(t.message ?: "Signaling handshake failed")
                )
              }
            },
          )

        val finalStatus =
          withTimeoutOrNull(10_000L) { resultDeferred.await() }
            ?: run {
              ws.cancel()
              RemoteConnectionStatus.Error("Connection timed out after 10s")
            }
        _status.value = finalStatus
        finalStatus
      } catch (e: Exception) {
        val err = RemoteConnectionStatus.Error(e.message ?: "Invalid signaling URL")
        _status.value = err
        err
      }
    }

  suspend fun requestRemoteDeviceSync(
    config: InternetConnectivityConfig,
    targetDeviceId: String,
    sharedSecret: String,
  ): Result<String> =
    withContext(Dispatchers.IO) {
      if (!config.isConfigured) {
        return@withContext Result.failure(IllegalStateException("Internet Connectivity: Not Configured"))
      }
      if (!isInternetAvailable()) {
        return@withContext Result.failure(IllegalStateException("Device is offline"))
      }

      val resultDeferred = CompletableDeferred<Result<String>>()
      val wsUrl =
        config.signalingUrl
          .replaceFirst("https://", "wss://")
          .replaceFirst("http://", "ws://")

      try {
        val localId = settingsStore.getLocalDeviceId()
        val req =
          Request.Builder()
            .url(wsUrl)
            .apply {
              if (config.connectionToken.isNotBlank()) {
                addHeader("Authorization", "Bearer ${config.connectionToken}")
              }
            }
            .build()

        activeWebSocket =
          httpClient.newWebSocket(
            req,
            object : WebSocketListener() {
              override fun onOpen(webSocket: WebSocket, response: Response) {
                _status.value = RemoteConnectionStatus.Connected(wsUrl)
                webSocket.send(
                  JSONObject()
                    .put("type", "SYNC_REQUEST")
                    .put("fromDeviceId", localId)
                    .put("targetDeviceId", targetDeviceId)
                    .toString()
                )
              }

              override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                  val msg = JSONObject(text)
                  if (msg.optString("type") == "SYNC_ENVELOPE") {
                    val envelope = msg.getString("envelope")
                    resultDeferred.complete(Result.success(envelope))
                    webSocket.close(1000, "Sync complete")
                  } else if (msg.optString("type") == "ERROR") {
                    resultDeferred.complete(
                      Result.failure(IllegalStateException(msg.optString("message", "Remote device unreachable")))
                    )
                    webSocket.close(1000, "Error")
                  }
                } catch (e: Exception) {
                  resultDeferred.complete(Result.failure(e))
                }
              }

              override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                resultDeferred.complete(Result.failure(t))
              }
            },
          )

        val envelopeResult =
          withTimeoutOrNull(12_000L) { resultDeferred.await() }
            ?: Result.failure(IllegalStateException("Remote device offline or did not respond via signaling server"))

        envelopeResult.mapCatching { envelope ->
          val (inv, ev, calls) =
            TransferProtocol.verifyAndImportSnapshot(
              context = context,
              db = db,
              envelopeJson = envelope,
              sharedSecret = sharedSecret,
              transportMethod = TransferProtocol.METHOD_INTERNET,
              internetEndpointId = targetDeviceId,
            )
          "Verified & imported $inv apps, $ev events, $calls calls via encrypted Internet session."
        }
      } catch (e: Exception) {
        Result.failure(e)
      }
    }

  suspend fun checkForUpdates(manifestUrl: String): UpdateCheckResult =
    withContext(Dispatchers.IO) {
      if (manifestUrl.isBlank()) {
        return@withContext UpdateCheckResult.NotConfigured
      }
      if (!isInternetAvailable()) {
        return@withContext UpdateCheckResult.Offline
      }

      try {
        val request = Request.Builder().url(manifestUrl).get().build()
        httpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) {
            return@withContext UpdateCheckResult.Failed("HTTP ${response.code}: ${response.message}")
          }
          val body = response.body?.string().orEmpty()
          val json = JSONObject(body)
          val latestCode = json.optInt("versionCode", BuildConfig.VERSION_CODE)
          val latestName = json.optString("versionName", BuildConfig.VERSION_NAME)
          val notes = json.optString("releaseNotes", "")
          UpdateCheckResult.Checked(
            latestVersionName = latestName,
            latestVersionCode = latestCode,
            updateAvailable = latestCode > BuildConfig.VERSION_CODE,
            releaseNotes = notes,
          )
        }
      } catch (e: Exception) {
        UpdateCheckResult.Failed(e.message ?: "Update manifest request failed")
      }
    }

  fun disconnect() {
    activeWebSocket?.close(1000, "User disconnected")
    activeWebSocket = null
  }
}
