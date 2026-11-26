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
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.example.data.local.AppDatabase
import com.example.data.preferences.SettingsStore
import com.example.security.CryptoManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
  val hasDeviceRecorderApp: Boolean = false,
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
    const val SERVICE_NAME = "DeviceRecorderSync"
  }

  private val bluetoothManager =
    context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
  private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

  private val _state = MutableStateFlow(BluetoothUiState())
  val state: StateFlow<BluetoothUiState> = _state.asStateFlow()

  private var serverSocket: BluetoothServerSocket? = null
  private var listenerJob: Job? = null
  private var receiverRegistered = false
  private var listenerScope: CoroutineScope? = null
  private var pendingAuthDeferred: CompletableDeferred<Boolean>? = null

  private val discoveryReceiver =
    object : BroadcastReceiver() {
      @SuppressLint("MissingPermission")
      override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
          BluetoothAdapter.ACTION_STATE_CHANGED -> {
            val stateInt = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (stateInt == BluetoothAdapter.STATE_OFF) {
              stopListening()
            }
            refreshState(listenerScope)
          }
          BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
            refreshState(listenerScope)
          }
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
                  d.name?.takeIf { it.isNotBlank() } ?: "Unnamed Bluetooth Device"
                } catch (_: SecurityException) {
                  "Bluetooth Device"
                }
              val bonded =
                try {
                  d.bondState == BluetoothDevice.BOND_BONDED
                } catch (_: SecurityException) {
                  false
                }
              val hasAppUuid =
                try {
                  d.uuids?.any { it.uuid == SERVICE_UUID } == true
                } catch (_: SecurityException) {
                  false
                }
              val current = _state.value.peers.toMutableList()
              val idx = current.indexOfFirst { it.address == addr }
              val entry =
                DiscoveredBluetoothPeer(
                  name = name,
                  address = addr,
                  isBonded = bonded,
                  hasDeviceRecorderApp = hasAppUuid,
                )
              if (idx >= 0) current[idx] = entry else current.add(entry)
              _state.value = _state.value.copy(peers = current)
            }
          }
          BluetoothDevice.ACTION_UUID -> {
            val device: BluetoothDevice? =
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
              } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
              }
            val uuids =
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID, ParcelUuid::class.java)
              } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
              }
            val addr = try { device?.address } catch (_: SecurityException) { null } ?: return
            val hasApp = uuids?.filterIsInstance<ParcelUuid>()?.any { it.uuid == SERVICE_UUID } == true
            if (hasApp) {
              val updated =
                _state.value.peers.map { p ->
                  if (p.address == addr) p.copy(hasDeviceRecorderApp = true) else p
                }
              _state.value = _state.value.copy(peers = updated)
            }
          }
          BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
            _state.value =
              _state.value.copy(
                isDiscovering = false,
                statusMessage = "Bluetooth scan completed (${_state.value.peers.size} device(s)).",
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

  private fun ensureReceiverRegistered() {
    if (receiverRegistered) return
    val filter =
      IntentFilter().apply {
        addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        addAction(BluetoothDevice.ACTION_FOUND)
        addAction(BluetoothDevice.ACTION_UUID)
        addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
      }
    try {
      ContextCompat.registerReceiver(
        context,
        discoveryReceiver,
        filter,
        ContextCompat.RECEIVER_EXPORTED,
      )
      receiverRegistered = true
    } catch (_: Exception) {}
  }

  @SuppressLint("MissingPermission")
  fun refreshState(autoListenScope: CoroutineScope? = null) {
    if (autoListenScope != null) {
      listenerScope = autoListenScope
    }
    val currentAdapter = adapter
    val hw = currentAdapter != null
    val enabled = currentAdapter?.isEnabled == true
    val perms = hasAllPermissions()
    if (hw && perms) {
      ensureReceiverRegistered()
    }

    val bonded =
      if (currentAdapter != null && enabled && perms) {
        try {
          currentAdapter.bondedDevices?.map {
            val hasAppUuid = it.uuids?.any { parcel -> parcel.uuid == SERVICE_UUID } == true
            DiscoveredBluetoothPeer(
              name = it.name?.takeIf { n -> n.isNotBlank() } ?: "Bonded Device",
              address = it.address,
              isBonded = true,
              hasDeviceRecorderApp = hasAppUuid,
            )
          } ?: emptyList()
        } catch (_: SecurityException) {
          emptyList()
        }
      } else {
        emptyList()
      }

    val merged =
      (_state.value.peers + bonded)
        .groupBy { it.address }
        .map { (_, items) ->
          items.reduce { acc, item ->
            acc.copy(
              name = if (item.name != "Unnamed Bluetooth Device") item.name else acc.name,
              isBonded = acc.isBonded || item.isBonded,
              hasDeviceRecorderApp = acc.hasDeviceRecorderApp || item.hasDeviceRecorderApp,
            )
          }
        }

    val msg =
      when {
        !hw -> "Bluetooth hardware is not available on this device."
        !perms -> "Bluetooth permissions are required before scanning or connecting."
        !enabled -> "Bluetooth is turned off. Turn on Bluetooth to connect."
        else ->
          _state.value.statusMessage.ifBlank {
            "Bluetooth ready (${bonded.size} paired device(s))."
          }
      }

    _state.value =
      _state.value.copy(
        hardwareAvailable = hw,
        adapterEnabled = enabled,
        hasPermissions = perms,
        peers = merged,
        statusMessage = msg,
      )

    if (hw && enabled && perms && listenerScope != null && !_state.value.isListening) {
      startListening(listenerScope!!)
    }
  }

  @SuppressLint("MissingPermission")
  fun startDiscovery(scope: CoroutineScope? = null) {
    refreshState(scope)
    val currentAdapter = adapter ?: return
    if (!hasAllPermissions() || !currentAdapter.isEnabled) return

    ensureReceiverRegistered()

    try {
      if (currentAdapter.isDiscovering) {
        currentAdapter.cancelDiscovery()
      }
      val started = currentAdapter.startDiscovery()
      _state.value =
        _state.value.copy(
          isDiscovering = started,
          statusMessage =
            if (started) "Scanning for nearby devices running Device Recorder..."
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
    listenerScope = scope
    val currentAdapter = adapter ?: return
    if (!hasAllPermissions() || !currentAdapter.isEnabled) return
    if (_state.value.isListening) return

    ensureReceiverRegistered()
    listenerJob =
      scope.launch(Dispatchers.IO) {
        try {
          // Listen on insecure RFCOMM so two Android devices running Device Recorder
          // can handshake without OS PIN friction; app-level protocol ID, 6-digit approval,
          // and AES-256-GCM encryption protect all transfers.
          serverSocket =
            currentAdapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
          _state.value =
            _state.value.copy(
              isListening = true,
              statusMessage = "Listening for Device Recorder peers via Bluetooth.",
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

  private suspend fun handleIncomingSocket(socket: BluetoothSocket) {
    try {
      val input = DataInputStream(socket.inputStream)
      val output = DataOutputStream(socket.outputStream)
      val helloJson = JSONObject(input.readUTF())

      // Strict Same-App Check: reject any device not running Device Recorder
      if (helloJson.optString("appProtocolId") != TransferProtocol.APP_PROTOCOL_ID) {
        output.writeUTF(
          JSONObject()
            .put("appProtocolId", TransferProtocol.APP_PROTOCOL_ID)
            .put("approved", false)
            .put("reason", "Rejected: Device Recorder app mismatch.")
            .toString()
        )
        output.flush()
        return
      }

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

      val localId = settingsStore.getLocalDeviceId()
      val localName = settingsStore.getLocalDeviceName()
      val localModel = CryptoManager.getLocalDeviceModel()

      output.writeUTF(
        JSONObject()
          .put("appProtocolId", TransferProtocol.APP_PROTOCOL_ID)
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
  private suspend fun openConnectedSocket(device: BluetoothDevice): BluetoothSocket {
    adapter?.let {
      if (it.isDiscovering) {
        it.cancelDiscovery()
        delay(250L)
      }
    }
    return try {
      val insecure = device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
      insecure.connect()
      insecure
    } catch (firstErr: IOException) {
      val secure = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
      secure.connect()
      secure
    }
  }

  @SuppressLint("MissingPermission")
  suspend fun connectAndSync(peerAddress: String): Result<ImportedSnapshotSummary> =
    withContext(Dispatchers.IO) {
      val currentAdapter =
        adapter ?: return@withContext Result.failure(IllegalStateException("Bluetooth unavailable"))
      if (!hasAllPermissions() || !currentAdapter.isEnabled) {
        return@withContext Result.failure(IllegalStateException("Turn on Bluetooth and grant permissions first"))
      }

      try {
        val device = currentAdapter.getRemoteDevice(peerAddress)
        val socket = openConnectedSocket(device)

        val localId = settingsStore.getLocalDeviceId()
        val localName = settingsStore.getLocalDeviceName()
        val localModel = CryptoManager.getLocalDeviceModel()
        val secret = CryptoManager.generatePairingSecret()
        val code = CryptoManager.generateSixDigitCode()

        val output = DataOutputStream(socket.outputStream)
        val input = DataInputStream(socket.inputStream)

        output.writeUTF(
          JSONObject()
            .put("appProtocolId", TransferProtocol.APP_PROTOCOL_ID)
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
        if (response.optString("appProtocolId") != TransferProtocol.APP_PROTOCOL_ID) {
          socket.close()
          return@withContext Result.failure(
            IllegalStateException("Connected Bluetooth device is not running Device Recorder.")
          )
        }
        if (!response.optBoolean("approved", false)) {
          socket.close()
          return@withContext Result.failure(
            IllegalStateException(response.optString("reason", "Authorization denied by remote device"))
          )
        }

        val activeSecret = response.optString("pairingSecret", secret)
        val envelopeJson = input.readUTF()
        socket.close()

        val summary =
          TransferProtocol.verifyAndImportSnapshot(
            context = context,
            db = db,
            envelopeJson = envelopeJson,
            sharedSecret = activeSecret,
            transportMethod = TransferProtocol.METHOD_BLUETOOTH,
            bluetoothAddress = peerAddress,
          )
        val msg =
          "Connected to ${summary.deviceName}: ${summary.inventoryCount} apps, ${summary.eventsCount} events, ${summary.callLogsCount} calls."
        _state.value = _state.value.copy(statusMessage = msg)
        Result.success(summary)
      } catch (e: Exception) {
        val msg =
          "Could not connect to Device Recorder on this Bluetooth device. Ensure Device Recorder is open on the other device (${e.message ?: "Unreachable"})."
        _state.value = _state.value.copy(statusMessage = msg)
        Result.failure(IllegalStateException(msg, e))
      }
    }

  @SuppressLint("MissingPermission")
  suspend fun sendRemoteUninstall(peerAddress: String, packageName: String): Result<String> =
    withContext(Dispatchers.IO) {
      val currentAdapter =
        adapter ?: return@withContext Result.failure(IllegalStateException("Bluetooth unavailable"))
      if (!hasAllPermissions() || !currentAdapter.isEnabled) {
        return@withContext Result.failure(IllegalStateException("Bluetooth disabled or permissions missing"))
      }

      try {
        val device = currentAdapter.getRemoteDevice(peerAddress)
        val socket = openConnectedSocket(device)

        val output = DataOutputStream(socket.outputStream)
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
          return@withContext Result.failure(IllegalStateException("Remote peer does not have Device Recorder active."))
        }
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
