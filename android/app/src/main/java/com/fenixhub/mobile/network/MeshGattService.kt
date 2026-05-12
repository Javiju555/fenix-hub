package com.fenixhub.mobile.network

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Base64
import android.util.Log
import com.fenixhub.mobile.util.MeshGattCrypto
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject
import java.security.PrivateKey
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

@SuppressLint("MissingPermission")
class MeshGattService(private val context: Context) {

    companion object {
        val MESH_SERVICE_UUID      = UUID.fromString("a3c8f100-1234-5678-abcd-000000000001")
        val JOIN_REQUEST_CHAR_UUID = UUID.fromString("a3c8f100-1234-5678-abcd-000000000002")
        val CONTROL_CHAR_UUID      = UUID.fromString("a3c8f100-1234-5678-abcd-000000000003")
        val CCCD_UUID              = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val MSG_JOIN_REQUEST     = "JOIN_REQUEST"
        const val MSG_LOBBY_ACK        = "LOBBY_ACK"
        const val MSG_LOBBY_KICKED     = "LOBBY_KICKED"
        const val MSG_MESH_CREDENTIALS = "MESH_CREDENTIALS"
        const val MSG_MESH_REKEYED     = "MESH_REKEYED"
        const val MSG_MESH_TERMINATED  = "MESH_TERMINATED"

        private const val TAG = "MeshGattService"
        private const val MTU_REQUEST = 512
        private const val FENIX_COMPANY_ID = 0xFE4E
    }

    sealed class GattEvent {
        data class JoinRequested(
            val deviceAddress: String,
            val deviceName: String,
            val deviceBleId: String,
            val devicePubKeyBytes: ByteArray,
            val gattDevice: BluetoothDevice,
        ) : GattEvent()

        data class LobbyAcked(val hostName: String) : GattEvent()
        data object LobbyKicked : GattEvent()
        data class CredentialsReceived(val encryptedBytes: ByteArray) : GattEvent()
        data class RekeyReceived(val encryptedBytes: ByteArray) : GattEvent()
        data object MeshTerminated : GattEvent()
        data object HostDisconnected : GattEvent()
    }

    private val _events = MutableSharedFlow<GattEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<GattEvent> = _events.asSharedFlow()

    private var gattServer: BluetoothGattServer? = null
    // Accessed from both BluetoothGattServerCallback (Binder thread) and coroutine scope
    private val connectedClients = ConcurrentHashMap<String, BluetoothDevice>()
    private val subscribedClients = CopyOnWriteArraySet<String>()
    private val deviceMtu = ConcurrentHashMap<String, Int>()

    fun startGattServer(hostName: String) {
        Log.d(TAG, "startGattServer called: hostName=$hostName")
        val manager = context.getSystemService(BluetoothManager::class.java)
        if (manager == null) { Log.e(TAG, "BluetoothManager null"); return }

        val joinChar = BluetoothGattCharacteristic(
            JOIN_REQUEST_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        val cccd = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        val controlChar = BluetoothGattCharacteristic(
            CONTROL_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).also { it.addDescriptor(cccd) }

        val service = BluetoothGattService(
            MESH_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        ).also {
            it.addCharacteristic(joinChar)
            it.addCharacteristic(controlChar)
        }

        gattServer = runCatching { manager.openGattServer(context, gattServerCallback) }
            .onFailure { Log.e(TAG, "openGattServer failed: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()
        if (gattServer == null) { Log.e(TAG, "openGattServer returned null"); return }
        gattServer?.addService(service)
        Log.i(TAG, "GATT server started: hostName=$hostName")
    }

    fun stopGattServer() {
        gattServer?.close()
        gattServer = null
        connectedClients.clear()
        subscribedClients.clear()
        deviceMtu.clear()
        Log.i(TAG, "GATT server stopped")
    }

    fun indicateAll(message: Map<String, Any>): Int {
        val server = gattServer ?: return 0
        val char = server.getService(MESH_SERVICE_UUID)
            ?.getCharacteristic(CONTROL_CHAR_UUID) ?: return 0
        val payload = JSONObject(message).toString().toByteArray(Charsets.UTF_8)
        var count = 0
        subscribedClients.forEach { addr ->
            val device = connectedClients[addr] ?: return@forEach
            notifyDevice(server, char, device, payload)
            count++
        }
        return count
    }

    fun indicateTo(device: BluetoothDevice, message: Map<String, Any>) {
        val server = gattServer ?: run { Log.e(TAG, "indicateTo: gattServer null"); return }
        val char = server.getService(MESH_SERVICE_UUID)
            ?.getCharacteristic(CONTROL_CHAR_UUID) ?: run { Log.e(TAG, "indicateTo: char null"); return }
        val payload = JSONObject(message).toString().toByteArray(Charsets.UTF_8)
        val isSubscribed = subscribedClients.contains(device.address)
        Log.d(TAG, "indicateTo ${device.address} type=${message["type"]} subscribed=$isSubscribed bytes=${payload.size}")
        notifyDevice(server, char, device, payload)
    }

    @Suppress("DEPRECATION")
    private fun notifyDevice(
        server: BluetoothGattServer,
        char: BluetoothGattCharacteristic,
        device: BluetoothDevice,
        payload: ByteArray,
    ) {
        val mtu = deviceMtu[device.address] ?: 23
        val maxPayload = mtu - 3
        if (payload.size > maxPayload) {
            Log.e(TAG, "notifyDevice: payload ${payload.size}B exceeds ATT limit ${maxPayload}B (MTU=$mtu) for ${device.address} — indication will be dropped or truncated!")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val rc = server.notifyCharacteristicChanged(device, char, true, payload)
            if (rc != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "notifyCharacteristicChanged returned $rc for ${device.address}")
            }
        } else {
            char.value = payload
            server.notifyCharacteristicChanged(device, char, true)
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedClients[device.address] = device
                    Log.d(TAG, "Server: device connected ${device.address}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedClients.remove(device.address)
                    subscribedClients.remove(device.address)
                    deviceMtu.remove(device.address)
                    Log.d(TAG, "Server: device disconnected ${device.address}")
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            deviceMtu[device.address] = mtu
            Log.d(TAG, "Server: MTU changed for ${device.address} → $mtu (max payload=${mtu - 3}B)")
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (characteristic.uuid != JOIN_REQUEST_CHAR_UUID) return
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            runCatching {
                val json = JSONObject(String(value, Charsets.UTF_8))
                require(json.getString("type") == MSG_JOIN_REQUEST)
                val event = GattEvent.JoinRequested(
                    deviceAddress   = device.address,
                    deviceName      = json.getString("dev_name"),
                    deviceBleId     = json.getString("dev_ble_id"),
                    devicePubKeyBytes = hexToBytes(json.getString("dev_pubkey_hex")),
                    gattDevice      = device,
                )
                _events.tryEmit(event)
            }.onFailure { Log.w(TAG, "Invalid JoinRequest: ${it.message}") }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (descriptor.uuid != CCCD_UUID) return
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)) {
                subscribedClients.add(device.address)
                Log.d(TAG, "Server: ${device.address} subscribed to indications")
            } else {
                subscribedClients.remove(device.address)
            }
        }
    }

    private var gatt: BluetoothGatt? = null
    private var devicePrivKey: PrivateKey? = null
    private var devicePubKeyBytes: ByteArray? = null
    // Set before calling gatt.disconnect() so the async STATE_DISCONNECTED callback
    // can tell whether the disconnect was intentional (cleanup) or unexpected (error).
    @Volatile private var intentionalDisconnect = false

    fun connectToHost(
        device: BluetoothDevice,
        deviceName: String,
    ) {
        val keypair = MeshGattCrypto.generateEcKeyPair()
        devicePrivKey    = keypair.privateKey
        devicePubKeyBytes = keypair.publicKeyBytes

        intentionalDisconnect = false
        gatt = device.connectGatt(context, false, gattClientCallback)
        Log.i(TAG, "Client: connecting to host ${device.address}")

        pendingDeviceName     = deviceName
        pendingDevicePubBytes = keypair.publicKeyBytes
    }

    fun disconnectFromHost() {
        intentionalDisconnect = true
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        devicePrivKey     = null
        devicePubKeyBytes = null
        Log.i(TAG, "Client: disconnected from host")
    }

    private var pendingDeviceName: String = ""
    private var pendingDevicePubBytes: ByteArray = ByteArray(0)

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Client: connected to host, requesting MTU=$MTU_REQUEST")
                gatt.requestMtu(MTU_REQUEST)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasIntentional = intentionalDisconnect
                intentionalDisconnect = false
                Log.d(TAG, "Client: disconnected from host (status=$status intentional=$wasIntentional)")
                if (!wasIntentional) {
                    _events.tryEmit(GattEvent.HostDisconnected)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "Client: MTU changed to $mtu")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val controlChar = gatt.getService(MESH_SERVICE_UUID)
                ?.getCharacteristic(CONTROL_CHAR_UUID) ?: return

            gatt.setCharacteristicNotification(controlChar, true)
            val cccd = controlChar.getDescriptor(CCCD_UUID) ?: return
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
            } else {
                cccd.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                gatt.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val joinChar = gatt.getService(MESH_SERVICE_UUID)
                ?.getCharacteristic(JOIN_REQUEST_CHAR_UUID) ?: return

            val localAddress = gatt.device.address
            val payload = JSONObject().apply {
                put("type",           MSG_JOIN_REQUEST)
                put("dev_name",       pendingDeviceName)
                put("dev_ble_id",     localAddress)
                put("dev_pubkey_hex", bytesToHex(pendingDevicePubBytes))
            }.toString().toByteArray(Charsets.UTF_8)

            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(joinChar, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                joinChar.value = payload
                joinChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                gatt.writeCharacteristic(joinChar)
            }
            Log.d(TAG, "Client: JoinRequest written")
        }

        // API 33+: system passes value directly, no longer populates characteristic.value
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid != CONTROL_CHAR_UUID) return
            parseControlMessage(value)
        }

        // API < 33: value is in characteristic.value
        @Suppress("DEPRECATION")
        @Deprecated("Use onCharacteristicChanged(BluetoothGatt, BluetoothGattCharacteristic, ByteArray)")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid != CONTROL_CHAR_UUID) return
            parseControlMessage(characteristic.value ?: return)
        }
    }

    private fun parseControlMessage(value: ByteArray) {
        val json = runCatching {
            JSONObject(String(value, Charsets.UTF_8))
        }.getOrNull() ?: run { Log.w(TAG, "parseControlMessage: invalid JSON, ${value.size} bytes"); return }

        val type = json.optString("type")
        Log.d(TAG, "parseControlMessage: type=$type")
        when (type) {
            MSG_LOBBY_ACK        -> _events.tryEmit(GattEvent.LobbyAcked(json.optString("host_name")))
            MSG_LOBBY_KICKED     -> _events.tryEmit(GattEvent.LobbyKicked)
            MSG_MESH_CREDENTIALS -> {
                val enc = Base64.decode(json.getString("enc_blob_b64"), Base64.NO_WRAP)
                _events.tryEmit(GattEvent.CredentialsReceived(enc))
            }
            MSG_MESH_REKEYED -> {
                val enc = Base64.decode(json.getString("enc_blob_b64"), Base64.NO_WRAP)
                _events.tryEmit(GattEvent.RekeyReceived(enc))
            }
            MSG_MESH_TERMINATED  -> _events.tryEmit(GattEvent.MeshTerminated)
        }
    }

    fun decryptCredentials(encryptedBytes: ByteArray): MeshGattCrypto.MeshCredentialPayload? {
        val priv = devicePrivKey ?: return null
        return MeshGattCrypto.decryptCredentials(encryptedBytes, priv)
    }

    private var advertiser: BluetoothLeAdvertiser? = null

    fun startAdvertising(hostName: String) {
        Log.d(TAG, "startAdvertising called: hostName=$hostName")
        val adapter = context.getSystemService(BluetoothManager::class.java)
            ?.adapter ?: run { Log.e(TAG, "BT adapter null"); return }
        advertiser = adapter.bluetoothLeAdvertiser ?: run { Log.e(TAG, "BLE advertiser null"); return }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        // Scan response carries the host name (up to 20 chars) as manufacturer data
        // to stay well within the 31-byte legacy BLE limit.
        val nameBytes = hostName.toByteArray(Charsets.UTF_8).take(27).toByteArray()
        val scanResponse = android.bluetooth.le.AdvertiseData.Builder()
            .addManufacturerData(FENIX_COMPANY_ID, nameBytes)
            .build()

        advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
        Log.i(TAG, "BLE advertising started: $hostName")
    }

    fun stopAdvertising() {
        advertiser?.stopAdvertising(advertiseCallback)
        advertiser = null
        Log.i(TAG, "BLE advertising stopped")
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "BLE advertise started")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "BLE advertise failed: $errorCode")
        }
    }

    private var scanner: BluetoothLeScanner? = null

    sealed class ScanEvent {
        data class HostFound(val device: BluetoothDevice, val hostName: String) : ScanEvent()
    }

    private val _scanEvents = MutableSharedFlow<ScanEvent>(extraBufferCapacity = 32)
    val scanEvents: SharedFlow<ScanEvent> = _scanEvents.asSharedFlow()

    fun startScanning() {
        val adapter = context.getSystemService(BluetoothManager::class.java)
            ?.adapter ?: return
        scanner = adapter.bluetoothLeScanner ?: return

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // No hardware UUID filter — EMUI 10 may drop 128-bit service UUID filters.
        // We filter in onScanResult instead.
        scanner?.startScan(emptyList(), settings, scanCallback)
        Log.i(TAG, "BLE scanning started")
    }

    fun stopScanning() {
        scanner?.stopScan(scanCallback)
        scanner = null
        Log.i(TAG, "BLE scanning stopped")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val uuids = result.scanRecord?.serviceUuids
            val isFenix = uuids?.any { it.uuid == MESH_SERVICE_UUID } == true
            Log.d(TAG, "BLE scan result: ${result.device.address} fenix=$isFenix uuids=$uuids")
            if (!isFenix) return
            val nameBytes = result.scanRecord?.getManufacturerSpecificData(FENIX_COMPANY_ID)
            val name = nameBytes?.let { String(it, Charsets.UTF_8) }
                ?: result.scanRecord?.deviceName
                ?: result.device.name
                ?: "Unknown"
            Log.i(TAG, "Fenix host found: ${result.device.address} name=$name")
            _scanEvents.tryEmit(ScanEvent.HostFound(result.device, name))
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: $errorCode")
        }
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
