package com.example.connectivity

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.data.local.AppDatabase
import com.example.data.preferences.SettingsStore
import com.example.security.CryptoManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class WifiDirectPeer(
  val deviceName: String,
  val deviceAddress: String,
  val status: Int,
  val hasDeviceRecorderApp: Boolean = false,
)

data class WifiDirectUiState(
  val hardwareAvailable: Boolean = false,
  val hasPermissions: Boolean = false,
  val isWifiP2pEnabled: Boolean = false,
  val isWifiHardwareEnabled: Boolean = true,
  val isDiscovering: Boolean = false,
  val isListening: Boolean = false,
  val isConnected: Boolean = false,
  val groupOwnerHost: String? = null,
  val isGroupOwner: Boolean = false,
  val peers: List<WifiDirectPeer> = emptyList(),
  val statusMessage: String = "",
  val pendingAuth: IncomingTransferConfirmation? = null,
)

class WifiDirectTransportManager(
  private val context: Context,
  private val db: AppDatabase,
  private val settingsStore: SettingsStore,
) {
  companion object {
    const val P2P_PORT = 8988
    const val SERVICE_INSTANCE = "DeviceRecorderSync"
    const val SERVICE_TYPE = "_devicerecorder._tcp"
  }

  private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
  private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
  private var channel: WifiP2pManager.Channel? = null

  private val _state = MutableStateFlow(WifiDirectUiState())
  val state: StateFlow<WifiDirectUiState> = _state.asStateFlow()

  private var serverSocket: ServerSocket? = null
  private var listenerJob: Job? = null
  private var serviceScope: CoroutineScope? = null
  private var receiverRegistered = false
  private var localServiceAdded = false
  private var pendingAuthDeferred: CompletableDeferred<Boolean>? = null
  private val appVerifiedAddresses = mutableSetOf<String>()

  private val p2pReceiver =
    object : BroadcastReceiver() {
      @SuppressLint("MissingPermission")
      override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
          WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
            val stateInt = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
            val enabled = stateInt == WifiP2pManager.WIFI_P2P_STATE_ENABLED
            val wifiOn = try { wifiManager?.isWifiEnabled != false } catch (_: Exception) { enabled }
            _state.value =
              _state.value.copy(
                isWifiP2pEnabled = enabled,
                isWifiHardwareEnabled = wifiOn,
                statusMessage =
                  if (!enabled) "Wi-Fi is turned off. Enable Wi-Fi to use Wi-Fi Direct."
                  else _state.value.statusMessage,
              )
          }
          WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
            val ch = channel ?: return
            if (hasAllPermissions()) {
              try {
                manager?.requestPeers(ch) { peerList ->
                  val list =
                    peerList.deviceList.mapNotNull { d: WifiP2pDevice ->
                      val addr = d.deviceAddress ?: return@mapNotNull null
                      WifiDirectPeer(
                        deviceName = d.deviceName?.ifBlank { "Wi-Fi Direct Peer" } ?: "Wi-Fi Direct Peer",
                        deviceAddress = addr,
                        status = d.status,
                        hasDeviceRecorderApp = appVerifiedAddresses.contains(addr),
                      )
                    }
                  _state.value =
                    _state.value.copy(
                      peers = list,
                      isDiscovering = false,
                      statusMessage =
                        if (list.isEmpty()) "No nearby Wi-Fi Direct peers found yet."
                        else "Found ${list.size} nearby Wi-Fi Direct peer(s).",
                    )
                }
              } catch (_: Exception) {}
            }
          }
          WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
            val ch = channel ?: return
            try {
              manager?.requestConnectionInfo(ch) { info ->
                if (info != null && info.groupFormed) {
                  val hostIp = info.groupOwnerAddress?.hostAddress
                  _state.value =
                    _state.value.copy(
                      isConnected = true,
                      groupOwnerHost = hostIp,
                      isGroupOwner = info.isGroupOwner,
                      statusMessage =
                        if (info.isGroupOwner)
                          "Wi-Fi Direct Group Owner active — ready for Device Recorder peer."
                        else "Connected to Wi-Fi Direct Group Owner ($hostIp).",
                    )
                  if (info.isGroupOwner && serviceScope != null && !_state.value.isListening) {
                    startSocketListener(serviceScope!!)
                  }
                } else {
                  _state.value =
                    _state.value.copy(
                      isConnected = false,
                      groupOwnerHost = null,
                      isGroupOwner = false,
                    )
                }
              }
            } catch (_: Exception) {}
          }
        }
      }
    }

  fun requiredPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
      arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
  }

  fun hasAllPermissions(): Boolean {
    return requiredPermissions().all {
      ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
  }

  @SuppressLint("MissingPermission")
  fun initialize(scope: CoroutineScope? = null) {
    if (scope != null) {
      serviceScope = scope
    }
    val hasFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
    val mgr = manager
    val hw = hasFeature && mgr != null
    val perms = hasAllPermissions()
    val wifiOn = try { wifiManager?.isWifiEnabled != false } catch (_: Exception) { true }

    if (mgr != null && hw && channel == null) {
      channel =
        try {
          mgr.initialize(context, Looper.getMainLooper(), null)
        } catch (_: Exception) {
          null
        }
    }
    if (hw && channel != null && !receiverRegistered) {
      val filter =
        IntentFilter().apply {
          addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
          addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
          addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
      try {
        ContextCompat.registerReceiver(context, p2pReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
      } catch (_: Exception) {}
    }

    if (hw && channel != null && perms && wifiOn && !localServiceAdded) {
      registerLocalAppService()
    }

    val msg =
      when {
        !hw -> "Wi-Fi Direct (P2P) hardware is unavailable on this device."
        !perms -> "Nearby Wi-Fi / Location permission is required for Wi-Fi Direct discovery."
        !wifiOn -> "Wi-Fi is turned off. Turn on Wi-Fi to connect via Wi-Fi Direct."
        else -> _state.value.statusMessage.ifBlank { "Wi-Fi Direct ready (offline local P2P)." }
      }
    _state.value =
      _state.value.copy(
        hardwareAvailable = hw,
        hasPermissions = perms,
        isWifiHardwareEnabled = wifiOn,
        isWifiP2pEnabled = _state.value.isWifiP2pEnabled || wifiOn,
        statusMessage = msg,
      )
  }

  @SuppressLint("MissingPermission")
  private fun registerLocalAppService() {
    val mgr = manager ?: return
    val ch = channel ?: return
    try {
      val txtMap = mapOf("app" to TransferProtocol.APP_PROTOCOL_ID)
      val serviceInfo =
        WifiP2pDnsSdServiceInfo.newInstance(SERVICE_INSTANCE, SERVICE_TYPE, txtMap)
      mgr.addLocalService(
        ch,
        serviceInfo,
        object : WifiP2pManager.ActionListener {
          override fun onSuccess() {
            localServiceAdded = true
          }
          override fun onFailure(reason: Int) {}
        },
      )
    } catch (_: Exception) {}
  }

  @SuppressLint("MissingPermission")
  fun discoverPeers(scope: CoroutineScope? = null) {
    initialize(scope)
    val mgr = manager ?: return
    val ch = channel ?: return
    if (!_state.value.hardwareAvailable || !hasAllPermissions()) return

    _state.value = _state.value.copy(isDiscovering = true, statusMessage = "Searching Wi-Fi Direct devices running Device Recorder...")
    try {
      mgr.setDnsSdResponseListeners(
        ch,
        { _, _, srcDevice ->
          srcDevice.deviceAddress?.let { addr ->
            appVerifiedAddresses.add(addr)
            val updated =
              _state.value.peers.map {
                if (it.deviceAddress == addr) it.copy(hasDeviceRecorderApp = true) else it
              }
            _state.value = _state.value.copy(peers = updated)
          }
        },
        { _, txtMap, srcDevice ->
          if (txtMap["app"] == TransferProtocol.APP_PROTOCOL_ID) {
            srcDevice.deviceAddress?.let { addr ->
              appVerifiedAddresses.add(addr)
              val updated =
                _state.value.peers.map {
                  if (it.deviceAddress == addr) it.copy(hasDeviceRecorderApp = true) else it
                }
              _state.value = _state.value.copy(peers = updated)
            }
          }
        },
      )
      val req = WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE)
      mgr.clearServiceRequests(
        ch,
        object : WifiP2pManager.ActionListener {
          override fun onSuccess() {
            mgr.addServiceRequest(
              ch,
              req,
              object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                  mgr.discoverServices(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {}
                    override fun onFailure(reason: Int) {}
                  })
                }
                override fun onFailure(reason: Int) {}
              },
            )
          }
          override fun onFailure(reason: Int) {}
        },
      )
    } catch (_: Exception) {}

    try {
      mgr.discoverPeers(
        ch,
        object : WifiP2pManager.ActionListener {
          override fun onSuccess() {
            _state.value = _state.value.copy(statusMessage = "Wi-Fi Direct scan active. Keep the other phone open on Device Connections.")
          }

          override fun onFailure(reason: Int) {
            val detail =
              when (reason) {
                WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct unsupported"
                WifiP2pManager.BUSY -> "Wi-Fi Direct framework busy — wait a moment"
                WifiP2pManager.ERROR -> "Check Wi-Fi and Location are enabled"
                else -> "code $reason"
              }
            _state.value =
              _state.value.copy(
                isDiscovering = false,
                statusMessage = "Wi-Fi Direct discovery failed ($detail).",
              )
          }
        },
      )
    } catch (e: Exception) {
      _state.value =
        _state.value.copy(
          isDiscovering = false,
          statusMessage = "Wi-Fi Direct discovery error: ${e.message ?: "Unavailable"}",
        )
    }
  }

  @SuppressLint("MissingPermission")
  fun connectToPeer(deviceAddress: String) {
    val mgr = manager ?: return
    val ch = channel ?: return
    if (!_state.value.hardwareAvailable || !hasAllPermissions()) return

    val config = WifiP2pConfig().apply { this.deviceAddress = deviceAddress }
    try {
      mgr.connect(
        ch,
        config,
        object : WifiP2pManager.ActionListener {
          override fun onSuccess() {
            _state.value = _state.value.copy(statusMessage = "Connecting to Wi-Fi Direct peer $deviceAddress...")
          }

          override fun onFailure(reason: Int) {
            _state.value = _state.value.copy(statusMessage = "Wi-Fi Direct connect failed (code $reason).")
          }
        },
      )
    } catch (e: Exception) {
      _state.value = _state.value.copy(statusMessage = "Wi-Fi Direct connect error: ${e.message}")
    }
  }

  @SuppressLint("MissingPermission")
  fun disconnectGroup() {
    val mgr = manager ?: return
    val ch = channel ?: return
    stopSocketListener()
    try {
      mgr.removeGroup(
        ch,
        object : WifiP2pManager.ActionListener {
          override fun onSuccess() {
            _state.value =
              _state.value.copy(
                isConnected = false,
                isGroupOwner = false,
                groupOwnerHost = null,
                statusMessage = "Wi-Fi Direct group closed.",
              )
          }
          override fun onFailure(reason: Int) {
            _state.value = _state.value.copy(statusMessage = "Failed to close group (code $reason).")
          }
        },
      )
    } catch (_: Exception) {}
  }

  fun startSocketListener(scope: CoroutineScope) {
    serviceScope = scope
    if (_state.value.isListening) return
    listenerJob =
      scope.launch(Dispatchers.IO) {
        try {
          val sock = ServerSocket()
          sock.reuseAddress = true
          sock.bind(InetSocketAddress(P2P_PORT))
          serverSocket = sock
          _state.value =
            _state.value.copy(
              isListening = true,
              statusMessage = "Wi-Fi Direct listener active on port $P2P_PORT.",
            )
          while (_state.value.isListening) {
            val client = serverSocket?.accept() ?: break
            handleClient(client)
          }
        } catch (e: Exception) {
          if (_state.value.isListening) {
            _state.value =
              _state.value.copy(
                isListening = false,
                statusMessage = "Wi-Fi Direct listener stopped: ${e.message ?: "Error"}",
              )
          }
        }
      }
  }

  fun stopSocketListener() {
    _state.value = _state.value.copy(isListening = false)
    try {
      serverSocket?.close()
    } catch (_: Exception) {}
    serverSocket = null
    listenerJob?.cancel()
    listenerJob = null
  }

  fun resolvePendingAuthorization(approved: Boolean) {
    pendingAuthDeferred?.complete(approved)
    _state.value = _state.value.copy(pendingAuth = null)
  }

  private suspend fun handleClient(socket: Socket) {
    try {
      val input = DataInputStream(socket.getInputStream())
      val output = DataOutputStream(socket.getOutputStream())
      val helloJson = JSONObject(input.readUTF())

      if (helloJson.optString("appProtocolId") != TransferProtocol.APP_PROTOCOL_ID) {
        output.writeUTF(
          JSONObject()
            .put("appProtocolId", TransferProtocol.APP_PROTOCOL_ID)
            .put("approved", false)
            .put("reason", "Rejected: Peer is not running Device Recorder.")
            .toString()
        )
        output.flush()
        return
      }

      val remoteDeviceId = helloJson.getString("deviceId")
      val remoteName = helloJson.optString("deviceName", remoteDeviceId)
      val remoteModel = helloJson.optString("deviceModel", "Wi-Fi Direct Peer")
      val actionType = helloJson.optString("action", "PAIR_AND_SYNC")
      val pkgToUninstall = helloJson.optString("packageName").takeIf { it.isNotBlank() }
      val code = helloJson.optString("verificationCode", CryptoManager.generateSixDigitCode())

      val deferred = CompletableDeferred<Boolean>()
      pendingAuthDeferred = deferred
      _state.value =
        _state.value.copy(
          pendingAuth =
            IncomingTransferConfirmation(
              remoteDeviceId = remoteDeviceId,
              remoteDeviceName = remoteName,
              remoteDeviceModel = remoteModel,
              verificationCode = code,
              transportMethod = TransferProtocol.METHOD_WIFI_DIRECT,
              actionType = actionType,
              packageToUninstall = pkgToUninstall,
            )
        )

      val approved = deferred.await()
      if (!approved) {
        output.writeUTF(
          JSONObject()
            .put("appProtocolId", TransferProtocol.APP_PROTOCOL_ID)
            .put("approved", false)
            .put("reason", "Rejected by device owner")
            .toString()
        )
        output.flush()
        return
      }

      val existing = db.pairedDeviceDao().getDeviceById(remoteDeviceId)
      val sharedSecret =
        existing?.sharedSecretEncrypted?.let { CryptoManager.decryptLocalString(context, it) }
          ?.takeIf { it.isNotBlank() }
          ?: helloJson.optString("pairingSecret").takeIf { it.isNotBlank() }
          ?: CryptoManager.generatePairingSecret()

      output.writeUTF(
        JSONObject()
          .put("appProtocolId", TransferProtocol.APP_PROTOCOL_ID)
          .put("approved", true)
          .put("deviceId", settingsStore.getLocalDeviceId())
          .put("deviceName", settingsStore.getLocalDeviceName())
          .put("deviceModel", CryptoManager.getLocalDeviceModel())
          .put("pairingSecret", sharedSecret)
          .toString()
      )
      output.flush()

      if (actionType == "REMOTE_UNINSTALL" && pkgToUninstall != null) {
        val launched = TransferProtocol.launchInteractiveUninstall(context, pkgToUninstall)
        output.writeUTF(
          JSONObject()
            .put("appProtocolId", TransferProtocol.APP_PROTOCOL_ID)
            .put("status", if (launched) "PROMPTED_ON_SOURCE" else "FAILED")
            .put(
              "message",
              if (launched) "System uninstall prompt displayed on source device for $pkgToUninstall."
              else "Failed to launch uninstaller for $pkgToUninstall.",
            )
            .toString()
        )
        output.flush()
      } else {
        val encrypted =
          TransferProtocol.buildEncryptedRecordSnapshot(context, db, settingsStore, sharedSecret)
        output.writeUTF(encrypted)
        output.flush()
        _state.value = _state.value.copy(statusMessage = "Encrypted snapshot sent to $remoteName.")
      }
    } catch (e: Exception) {
      _state.value = _state.value.copy(statusMessage = "Wi-Fi Direct transfer error: ${e.message}")
    } finally {
      try {
        socket.close()
      } catch (_: Exception) {}
    }
  }

  suspend fun syncWithHost(hostIp: String): Result<ImportedSnapshotSummary> =
    withContext(Dispatchers.IO) {
      try {
        val socket = Socket()
        socket.connect(InetSocketAddress(hostIp, P2P_PORT), 8000)

        val secret = CryptoManager.generatePairingSecret()
        val output = DataOutputStream(socket.getOutputStream())
        val input = DataInputStream(socket.inputStream)

        output.writeUTF(
          JSONObject()
            .put("appProtocolId", TransferProtocol.APP_PROTOCOL_ID)
            .put("action", "PAIR_AND_SYNC")
            .put("deviceId", settingsStore.getLocalDeviceId())
            .put("deviceName", settingsStore.getLocalDeviceName())
            .put("deviceModel", CryptoManager.getLocalDeviceModel())
            .put("pairingSecret", secret)
            .put("verificationCode", CryptoManager.generateSixDigitCode())
            .toString()
        )
        output.flush()

        val ack = JSONObject(input.readUTF())
        if (ack.optString("appProtocolId") != TransferProtocol.APP_PROTOCOL_ID) {
          socket.close()
          return@withContext Result.failure(
            IllegalStateException("Connected Wi-Fi Direct peer is not running Device Recorder.")
          )
        }
        if (!ack.optBoolean("approved", false)) {
          socket.close()
          return@withContext Result.failure(
            IllegalStateException(ack.optString("reason", "Denied by remote device"))
          )
        }
        val activeSecret = ack.optString("pairingSecret", secret)
        val envelope = input.readUTF()
        socket.close()

        val summary =
          TransferProtocol.verifyAndImportSnapshot(
            context = context,
            db = db,
            envelopeJson = envelope,
            sharedSecret = activeSecret,
            transportMethod = TransferProtocol.METHOD_WIFI_DIRECT,
            wifiDirectAddress = hostIp,
          )
        val msg =
          "Connected to ${summary.deviceName}: ${summary.inventoryCount} apps, ${summary.eventsCount} events, ${summary.callLogsCount} calls over Wi-Fi Direct."
        _state.value = _state.value.copy(statusMessage = msg)
        Result.success(summary)
      } catch (e: Exception) {
        val msg =
          "Could not reach Device Recorder on Group Owner ($hostIp). Make sure Device Recorder is open on the other device."
        _state.value = _state.value.copy(statusMessage = msg)
        Result.failure(IllegalStateException(msg, e))
      }
    }

  suspend fun sendRemoteUninstall(hostIp: String, packageName: String): Result<String> =
    withContext(Dispatchers.IO) {
      try {
        val socket = Socket()
        socket.connect(InetSocketAddress(hostIp, P2P_PORT), 8000)
        val output = DataOutputStream(socket.getOutputStream())
        val input = DataInputStream(socket.inputStream)

        output.writeUTF(
          JSONObject()
            .put("appProtocolId", TransferProtocol.APP_PROTOCOL_ID)
            .put("action", "REMOTE_UNINSTALL")
            .put("deviceId", settingsStore.getLocalDeviceId())
            .put("deviceName", settingsStore.getLocalDeviceName())
            .put("deviceModel", CryptoManager.getLocalDeviceModel())
            .put("packageName", packageName)
            .put("verificationCode", CryptoManager.generateSixDigitCode())
            .toString()
        )
        output.flush()

        val ack = JSONObject(input.readUTF())
        if (ack.optString("appProtocolId") != TransferProtocol.APP_PROTOCOL_ID) {
          socket.close()
          return@withContext Result.failure(IllegalStateException("Remote peer is not running Device Recorder."))
        }
        if (!ack.optBoolean("approved", false)) {
          socket.close()
          return@withContext Result.failure(IllegalStateException(ack.optString("reason", "Denied")))
        }
        val res = JSONObject(input.readUTF())
        socket.close()
        Result.success(res.optString("message", "Uninstall request delivered."))
      } catch (e: Exception) {
        Result.failure(e)
      }
    }

  fun cleanup() {
    stopSocketListener()
    if (receiverRegistered) {
      try {
        context.unregisterReceiver(p2pReceiver)
      } catch (_: Exception) {}
      receiverRegistered = false
    }
  }
}
