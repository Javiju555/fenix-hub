package com.fenixhub.mobile.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import com.fenixhub.mobile.util.CryptoUtils
import java.nio.charset.StandardCharsets
import java.util.UUID

data class MeshPassphraseExchange(
    val meshId: String,
    val encryptedPassphrase: String,
    val hostDeviceId: String,
    val hostName: String,
    val rssi: Int,
)

interface MeshPassphraseListener {
    fun onPassphraseReceived(exchange: MeshPassphraseExchange)
}

@SuppressLint("MissingPermission")
class MeshBleExchange(
    private val context: Context,
    private val meshId: String,
    private val passphrase: String,
    private val deviceName: String,
    private val isHost: Boolean,
    private val listener: MeshPassphraseListener,
    private val discoveryMode: Boolean = false,
) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val scanner: BluetoothLeScanner? get() = adapter?.bluetoothLeScanner
    private val advertiser: BluetoothLeAdvertiser? get() = adapter?.bluetoothLeAdvertiser

    private val serviceUuid = ParcelUuid(UUID.fromString(MESH_EXCHANGE_SERVICE_UUID))

    private var discovering = false
    private var advertising = false
    private var lastError: String? = null

    fun start() {
        if (discoveryMode) {
            startScanning()
        } else if (isHost) {
            startAdvertising()
        } else {
            startScanning()
        }
    }

    fun stop() {
        runCatching { scanner?.stopScan(scanCallback) }
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        discovering = false
        advertising = false
    }

    private fun startAdvertising() {
        if (advertiser == null) return

        val encrypted = encryptPassphrase()
        val payload = "fh3|$meshId|$encrypted".toByteArray(StandardCharsets.UTF_8)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(false)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(serviceUuid)
            .addServiceData(serviceUuid, payload)
            .build()

        runCatching {
            advertiser?.startAdvertising(settings, data, advertiseCallback)
            advertising = true
            Log.d(TAG, "Mesh exchange advertising started: meshId=$meshId")
        }.onFailure { e ->
            advertising = false
            lastError = e.message
            Log.w(TAG, "Mesh exchange advertise failed", e)
        }
    }

    private fun startScanning() {
        if (scanner == null) return

        val filter = ScanFilter.Builder()
            .setServiceUuid(serviceUuid)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        runCatching {
            scanner?.startScan(listOf(filter), settings, scanCallback)
            discovering = true
            Log.d(TAG, "Mesh exchange scanning started: meshId=$meshId")
        }.onFailure { e ->
            discovering = false
            lastError = e.message
            Log.w(TAG, "Mesh exchange scan failed", e)
        }
    }

    private fun encryptPassphrase(): String {
        val key = CryptoUtils.hkdfExpand(
            CryptoUtils.hkdfExtract(meshId.toByteArray(Charsets.UTF_8)),
            "fenixhub-mesh-key".toByteArray(Charsets.UTF_8),
            32,
        )
        val encrypted = CryptoUtils.encryptAesGcm(key, passphrase.toByteArray(Charsets.UTF_8))
        return CryptoUtils.toHex(encrypted)
    }

    private fun parseExchangePayload(result: ScanResult): MeshPassphraseExchange? {
        val record = result.scanRecord ?: return null
        val raw = record.getServiceData(serviceUuid) ?: return null
        val decoded = raw.toString(StandardCharsets.UTF_8)

        val parts = decoded.split('|')
        if (parts.size < 2) return null

        when (parts[0]) {
            "fh3" -> {
                if (parts.size < 3) return null
                val detectedMeshId = parts[1]
                if (detectedMeshId != meshId && meshId != "MESH") return null
                val encryptedPassphrase = parts[2]
                val hostName = parts.getOrNull(3) ?: "Host"
                val address = runCatching { result.device.address }.getOrNull() ?: "unknown"
                return MeshPassphraseExchange(
                    meshId = detectedMeshId,
                    encryptedPassphrase = encryptedPassphrase,
                    hostDeviceId = address,
                    hostName = hostName,
                    rssi = result.rssi,
                )
            }
            "fh2" -> {
                if (parts.size < 4) return null
                val detectedMeshId = parts[1]
                val peerName = parts.getOrNull(3) ?: "Peer"
                val address = runCatching { result.device.address }.getOrNull() ?: "unknown"
                return MeshPassphraseExchange(
                    meshId = detectedMeshId,
                    encryptedPassphrase = "",
                    hostDeviceId = address,
                    hostName = peerName,
                    rssi = result.rssi,
                )
            }
            else -> return null
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val exchange = parseExchangePayload(result) ?: return
            Log.d(TAG, "Mesh passphrase received from ${exchange.hostName}")
            listener.onPassphraseReceived(exchange)
            stop()
        }

        override fun onScanFailed(errorCode: Int) {
            discovering = false
            lastError = "scan_failed_$errorCode"
            Log.w(TAG, "Mesh exchange scan failed: $errorCode")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertising = true
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            lastError = "advertise_failed_$errorCode"
            Log.w(TAG, "Mesh exchange advertise failed: $errorCode")
        }
    }

    private companion object {
        const val TAG = "MeshBleExchange"
        const val MESH_EXCHANGE_SERVICE_UUID = "6f8d3a52-7a6b-4b62-b2c0-5c0d49f45714"
    }
}