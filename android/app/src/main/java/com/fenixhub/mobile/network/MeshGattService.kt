package com.fenixhub.mobile.network

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.fenixhub.mobile.util.MeshGattCrypto
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject
import java.security.PrivateKey
import java.util.UUID

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
    }

    private val _events = MutableSharedFlow<GattEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<GattEvent> = _events.asSharedFlow()

    private var gattServer: BluetoothGattServer? = null
    private val connectedClients = mutableMapOf<String, BluetoothDevice>()
    private val subscribedClients = mutableSetOf<String>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun startGattServer(hostName: String) {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return

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

        gattServer = manager.openGattServer(context, gattServerCallback)
        gattServer?.addService(service)
        Log.i(TAG, "GATT server started: hostName=$hostName")
    }

    fun stopGattServer() {
        gattServer?.close()
        gattServer = null
        connectedClients.clear()
        subscribedClients.clear()
        Log.i(TAG, "GATT server stopped")
    }

    fun indicateAll(message: Map<String, Any>): Int {
        val server = gattServer ?: return 0
        val char = server.getService(MESH_SERVICE_UUID)
            ?.getCharacteristic(CONTROL_CHAR_UUID) ?: return 0
        val payload = JSONObject(message).toString().toByteArray(Charsets.UTF_8)
        var count = 0
        subscribedClients.toList().forEach { addr ->
            val device = connectedClients[addr] ?: return@forEach
            char.value = payload
            server.notifyCharacteristicChanged(device, char, true)
            count++
        }
        return count
    }

    fun indicateTo(device: BluetoothDevice, message: Map<String, Any>) {
        val server = gattServer ?: return
        val char = server.getService(MESH_SERVICE_UUID)
            ?.getCharacteristic(CONTROL_CHAR_UUID) ?: return
        val payload = JSONObject(message).toString().toByteArray(Charsets.UTF_8)
        char.value = payload
        server.notifyCharacteristicChanged(device, char, true)
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
                    Log.d(TAG, "Server: device disconnected ${device.address}")
                }
            }
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

    fun connectToHost(
        device: BluetoothDevice,
        deviceName: String,
    ) {
        val keypair = MeshGattCrypto.generateEcKeyPair()
        devicePrivKey    = keypair.privateKey
        devicePubKeyBytes = keypair.publicKeyBytes

        gatt = device.connectGatt(context, false, gattClientCallback)
        Log.i(TAG, "Client: connecting to host ${device.address}")

        pendingDeviceName     = deviceName
        pendingDevicePubBytes = keypair.publicKeyBytes
    }

    fun disconnectFromHost() {
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
                Log.d(TAG, "Client: disconnected from host")
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
            cccd.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            gatt.writeDescriptor(cccd)
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

            joinChar.value = payload
            joinChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt.writeCharacteristic(joinChar)
            Log.d(TAG, "Client: JoinRequest written")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid != CONTROL_CHAR_UUID) return
            val json = runCatching {
                JSONObject(String(characteristic.value, Charsets.UTF_8))
            }.getOrNull() ?: return

            when (json.optString("type")) {
                MSG_LOBBY_ACK  -> _events.tryEmit(GattEvent.LobbyAcked(json.optString("host_name")))
                MSG_LOBBY_KICKED -> _events.tryEmit(GattEvent.LobbyKicked)
                MSG_MESH_CREDENTIALS -> {
                    val enc = hexToBytes(json.getString("enc_blob_hex"))
                    _events.tryEmit(GattEvent.CredentialsReceived(enc))
                }
                MSG_MESH_REKEYED -> {
                    val enc = hexToBytes(json.getString("enc_blob_hex"))
                    _events.tryEmit(GattEvent.RekeyReceived(enc))
                }
                MSG_MESH_TERMINATED -> _events.tryEmit(GattEvent.MeshTerminated)
            }
        }
    }

    fun decryptCredentials(encryptedBytes: ByteArray): MeshGattCrypto.MeshCredentialPayload? {
        val priv = devicePrivKey ?: return null
        return MeshGattCrypto.decryptCredentials(encryptedBytes, priv)
    }

    private var advertiser: BluetoothLeAdvertiser? = null

    fun startAdvertising(hostName: String) {
        val adapter = context.getSystemService(BluetoothManager::class.java)
            ?.adapter ?: return
        advertiser = adapter.bluetoothLeAdvertiser ?: return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
            .setIncludeDeviceName(true)
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
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

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(listOf(filter), settings, scanCallback)
        Log.i(TAG, "BLE scanning started")
    }

    fun stopScanning() {
        scanner?.stopScan(scanCallback)
        scanner = null
        Log.i(TAG, "BLE scanning stopped")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName ?: "Unknown"
            _scanEvents.tryEmit(ScanEvent.HostFound(result.device, name))
        }
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
