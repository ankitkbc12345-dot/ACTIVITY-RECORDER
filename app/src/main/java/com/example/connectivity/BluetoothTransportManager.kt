package com.example.connectivity

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.data.local.AppDatabase
import com.example.data.preferences.SettingsStore
import com.example.security.CryptoManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
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

data class DiscoveredBluetoothPeer(
  val name: String,
  val address: String,
  val isBonded: Boolean,
)

data class BluetoothUiState(
  val hardwareAvailable: Boolean = false,
  val adapterEnabled: Boolean = false,
  val hasPermissions: Boolean = false,
  val isDiscovering: Boolean = false,
  val isListening: Boolean = false,
  val peers: List<DiscoveredBluetoothPeer> = emptyList(),
  val statusMessage: String = "",
  val pendingAuth: IncomingTransferConfirmation? = null,
)

class BluetoothTransportManager(
  private val context: Context,
  private val db: AppDatabase,
  private val settingsStore: SettingsStore,
) {
  companion object {
    val SERVICE_UUID: UUID = UUID.fromString("8c5b4f90-7b2d-4c5a-9e12-3f8a1b2c3d4e")
    const val SERVICE_NAME = "PersonalDeviceActivityRecorder"
  }

  private val bluetoothManager =
    context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
  private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

  private val _state = MutableStateFlow(BluetoothUiState())
  val state: StateFlow<BluetoothUiState> = _state.asStateFlow()

  private var serverSocket: BluetoothServerSocket? = null
  private var listenerJob: Job? = null
  private var receiverRegistered = false
  private var pendingAuthDeferred: CompletableDeferred<Boolean>? = null

  private val discoveryReceiver =
    object : BroadcastReceiver() {
      @SuppressLint("MissingPermission")
      override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
          BluetoothDevice.ACTION_FOUND -> {
            val device: BluetoothDevice? =
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
              } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
              }
            device?.let { d ->
              val addr =
                try {
                  d.address
                } catch (_: SecurityException) {
                  null
                } ?: return
              val name =
                try {
                  d.name ?: "Unnamed Bluetooth Device"
                } catch (_: SecurityException) {
                  "Bluetooth Device"
                }
              val bonded =
                try {
                  d.bondState == BluetoothDevice.BOND_BONDED
                } catch (_: SecurityException) {
                  false
                }
              val current = _state.value.peers.toMutableList()
              if (current.none { it.address == addr }) {
                current.add(
                  DiscoveredBluetoothPeer(
                    name = name,
                    address = addr,
                    isBonded = bonded,
                  )
                )
                _state.value = _state.value.copy(peers = current)
              }
            }
          }
          BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
            _state.value =
              _state.value.copy(
                isDiscovering = false,
                statusMessage = "Bluetooth scan completed.",
              )
          }
        }
      }
    }

  fun requiredPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
      )
    } else {
      arrayOf(
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.ACCESS_FINE_LOCATION,
      )
    }
  }

  fun hasAllPermissions(): Boolean {
    return requiredPermissions().all { perm ->
      ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }
  }

  @SuppressLint("MissingPermission")
  fun refreshState() {
    val hw = adapter != null
    val enabled = adapter?.isEnabled == true
    val perms = hasAllPermissions()
    val bonded =
      if (hw && enabled && perms) {
        try {
          adapter?.bondedDevices?.map {
            DiscoveredBluetoothPeer(
              name = it.name ?: "Bonded Device",
              address = it.address,
              isBonded = true,
            )
          } ?: emptyList()
        } catch (_: SecurityException) {
          emptyList()
        }
      } else {
        emptyList()
      }

    val merged = (_state.value.peers + bonded).distinctBy { it.address }
    val msg =
      when {
        !hw -> "Bluetooth hardware is not available on this device."
        !perms -> "Bluetooth permissions are required before scanning or connecting."
        !enabled -> "Bluetooth adapter is currently turned off."
        else -> _state.value.statusMessage.ifBlank { "Bluetooth ready (${bonded.size} paired system devices)." }
      }

    _state.value =
      _state.value.copy(
        hardwareAvailable = hw,
        adapterEnabled = enabled,
        hasPermissions = perms,
        peers = merged,
        statusMessage = msg,
      )
  }

  @SuppressLint("MissingPermission")
  fun startDiscovery() {
    refreshState()
    val currentAdapter = adapter ?: return
    if (!hasAllPermissions() || !currentAdapter.isEnabled) return

    if (!receiverRegistered) {
      val filter =
        IntentFilter().apply {
          addAction(BluetoothDevice.ACTION_FOUND)
          addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
      ContextCompat.registerReceiver(
        context,
        discoveryReceiver,
        filter,
        ContextCompat.RECEIVER_EXPORTED,
      )
      receiverRegistered = true
    }

    try {
      if (currentAdapter.isDiscovering) {
        currentAdapter.cancelDiscovery()
      }
      val started = currentAdapter.startDiscovery()
      _state.value =
        _state.value.copy(
          isDiscovering = started,
          statusMessage =
            if (started) "Scanning for nearby Bluetooth devices..."
            else "Unable to start Bluetooth discovery.",
        )
    } catch (e: Exception) {
      _state.value =
        _state.value.copy(
          isDiscovering = false,
          statusMessage = "Bluetooth discovery error: ${e.message ?: "Unavailable"}",
        )
    }
  }

  @SuppressLint("MissingPermission")
  fun startListening(scope: CoroutineScope) {
    refreshState()
    val currentAdapter = adapter ?: return
    if (!hasAllPermissions() || !currentAdapter.isEnabled) return
    if (_state.value.isListening) return

    listenerJob =
      scope.launch(Dispatchers.IO) {
        try {
          serverSocket =
            currentAdapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
          _state.value =
            _state.value.copy(
              isListening = true,
              statusMessage = "Listening for authorized Bluetooth connections...",
            )
          while (_state.value.isListening) {
            val socket = serverSocket?.accept() ?: break
            handleIncomingSocket(socket)
          }
        } catch (e: Exception) {
          if (_state.value.isListening) {
            _state.value =
              _state.value.copy(
                isListening = false,
                statusMessage = "Bluetooth listener stopped: ${e.message ?: "Error"}",
              )
          }
        }
      }
  }

  fun stopListening() {
    _state.value = _state.value.copy(isListening = false, statusMessage = "Bluetooth listener stopped.")
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

  private suspend fun handleIncomingSocket(socket: BluetoothSocket) {
    try {
      val input = DataInputStream(socket.inputStream)
      val output = DataOutputStream(socket.outputStream)
      val helloJson = JSONObject(input.readUTF())
      val remoteDeviceId = helloJson.getString("deviceId")
      val remoteDeviceName = helloJson.optString("deviceName", remoteDeviceId)
      val remoteDeviceModel = helloJson.optString("deviceModel", "Bluetooth Peer")
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
              remoteDeviceName = remoteDeviceName,
              remoteDeviceModel = remoteDeviceModel,
              verificationCode = code,
              transportMethod = TransferProtocol.METHOD_BLUETOOTH,
              actionType = actionType,
              packageToUninstall = pkgToUninstall,
            )
        )

      val approved = deferred.await()
      if (!approved) {
        output.writeUTF(JSONObject().put("approved", false).put("reason", "Rejected by device owner").toString())
        output.flush()
        return
      }

      val existing = db.pairedDeviceDao().getDeviceById(remoteDeviceId)
      val sharedSecret =
        existing?.sharedSecretEncrypted?.let { CryptoManager.decryptLocalString(context, it) }
          ?.takeIf { it.isNotBlank() }
          ?: helloJson.optString("pairingSecret").takeIf { it.isNotBlank() }
          ?: CryptoManager.generatePairingSecret()

      val localId = settingsStore.getLocalDeviceId()
      val localName = settingsStore.getLocalDeviceName()
      val localModel = CryptoManager.getLocalDeviceModel()

      output.writeUTF(
        JSONObject()
          .put("approved", true)
          .put("deviceId", localId)
          .put("deviceName", localName)
          .put("deviceModel", localModel)
          .put("pairingSecret", sharedSecret)
          .toString()
      )
      output.flush()

      if (actionType == "REMOTE_UNINSTALL" && pkgToUninstall != null) {
        val launched = TransferProtocol.launchInteractiveUninstall(context, pkgToUninstall)
        output.writeUTF(
          JSONObject()
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
        val encryptedPayload =
          TransferProtocol.buildEncryptedRecordSnapshot(context, db, settingsStore, sharedSecret)
        output.writeUTF(encryptedPayload)
        output.flush()
        _state.value =
          _state.value.copy(statusMessage = "Encrypted records transferred to $remoteDeviceName.")
      }
    } catch (e: Exception) {
      _state.value =
        _state.value.copy(statusMessage = "Bluetooth transfer error: ${e.message ?: "Failed"}")
    } finally {
      try {
        socket.close()
      } catch (_: Exception) {}
    }
  }

  @SuppressLint("MissingPermission")
  suspend fun connectAndSync(peerAddress: String): Result<String> =
    withContext(Dispatchers.IO) {
      val currentAdapter = adapter ?: return@withContext Result.failure(IllegalStateException("Bluetooth unavailable"))
      if (!hasAllPermissions() || !currentAdapter.isEnabled) {
        return@withContext Result.failure(IllegalStateException("Bluetooth disabled or permissions missing"))
      }

      try {
        if (currentAdapter.isDiscovering) currentAdapter.cancelDiscovery()
        val device = currentAdapter.getRemoteDevice(peerAddress)
        val socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
        socket.connect()

        val localId = settingsStore.getLocalDeviceId()
        val localName = settingsStore.getLocalDeviceName()
        val localModel = CryptoManager.getLocalDeviceModel()
        val secret = CryptoManager.generatePairingSecret()
        val code = CryptoManager.generateSixDigitCode()

        val output = DataOutputStream(socket.outputStream)
        val input = DataInputStream(socket.inputStream)

        output.writeUTF(
          JSONObject()
            .put("action", "PAIR_AND_SYNC")
            .put("deviceId", localId)
            .put("deviceName", localName)
            .put("deviceModel", localModel)
            .put("pairingSecret", secret)
            .put("verificationCode", code)
            .toString()
        )
        output.flush()

        val response = JSONObject(input.readUTF())
        if (!response.optBoolean("approved", false)) {
          socket.close()
          return@withContext Result.failure(
            IllegalStateException(response.optString("reason", "Authorization denied by remote device"))
          )
        }

        val activeSecret = response.optString("pairingSecret", secret)
        val envelopeJson = input.readUTF()
        socket.close()

        val (invCount, evCount, callCount) =
          TransferProtocol.verifyAndImportSnapshot(
            context = context,
            db = db,
            envelopeJson = envelopeJson,
            sharedSecret = activeSecret,
            transportMethod = TransferProtocol.METHOD_BLUETOOTH,
            bluetoothAddress = peerAddress,
          )
        val msg = "Verified & imported $invCount apps, $evCount events, $callCount calls via Bluetooth."
        _state.value = _state.value.copy(statusMessage = msg)
        Result.success(msg)
      } catch (e: Exception) {
        val msg = "Bluetooth connection failed: ${e.message ?: "Unreachable"}"
        _state.value = _state.value.copy(statusMessage = msg)
        Result.failure(e)
      }
    }

  @SuppressLint("MissingPermission")
  suspend fun sendRemoteUninstall(peerAddress: String, packageName: String): Result<String> =
    withContext(Dispatchers.IO) {
      val currentAdapter = adapter ?: return@withContext Result.failure(IllegalStateException("Bluetooth unavailable"))
      if (!hasAllPermissions() || !currentAdapter.isEnabled) {
        return@withContext Result.failure(IllegalStateException("Bluetooth disabled or permissions missing"))
      }

      try {
        if (currentAdapter.isDiscovering) currentAdapter.cancelDiscovery()
        val device = currentAdapter.getRemoteDevice(peerAddress)
        val socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
        socket.connect()

        val output = DataOutputStream(socket.outputStream)
        val input = DataInputStream(socket.inputStream)

        output.writeUTF(
          JSONObject()
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
        if (!ack.optBoolean("approved", false)) {
          socket.close()
          return@withContext Result.failure(IllegalStateException(ack.optString("reason", "Denied")))
        }
        val resultJson = JSONObject(input.readUTF())
        socket.close()
        Result.success(resultJson.optString("message", "Uninstall request processed."))
      } catch (e: Exception) {
        Result.failure(e)
      }
    }

  fun cleanup() {
    stopListening()
    if (receiverRegistered) {
      try {
        context.unregisterReceiver(discoveryReceiver)
      } catch (_: Exception) {}
      receiverRegistered = false
    }
  }
}
