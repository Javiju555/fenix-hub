package com.fenixhub.mobile.network

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
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
import com.fenixhub.mobile.util.TransportHardwareInspector
import java.nio.charset.StandardCharsets
import java.util.UUID

data class BlePeer(
    val deviceId: String,
    val deviceName: String,
    val rssi: Int,
    val groupTag: String,
    val lastSeenElapsedMs: Long,
)

data class BleRuntimeStatus(
    val scanning: Boolean,
    val advertising: Boolean,
    val lastError: String?,
)

class BleIdentityController(context: Context) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val scanner: BluetoothLeScanner? get() = adapter?.bluetoothLeScanner
    private val advertiser: BluetoothLeAdvertiser? get() = adapter?.bluetoothLeAdvertiser

    private val serviceUuid = ParcelUuid(UUID.fromString(SERVICE_UUID))
    private val peers = LinkedHashMap<String, BlePeer>()
    private val lock = Any()

    @Volatile
    private var scanning = false

    @Volatile
    private var advertising = false

    @Volatile
    private var activeGroupTag: String = ""

    @Volatile
    private var activeDeviceName: String = ""

    @Volatile
    private var lastError: String? = null

    fun ensureRunning(groupId: String, deviceName: String) {
        val hw = TransportHardwareInspector.snapshot(appContext).ble
        val groupTag = normalizedGroupTag(groupId)
        val cleanName = normalizedDeviceName(deviceName)

        if (!hw.supported || !hw.enabled || !hw.permissionsReady) {
            stop()
            return
        }

        if (groupTag.isBlank() || cleanName.isBlank()) {
            stop()
            return
        }

        val sameIdentity = groupTag == activeGroupTag && cleanName == activeDeviceName
        if (sameIdentity && scanning && advertising) {
            pruneStalePeers()
            return
        }

        stop()
        activeGroupTag = groupTag
        activeDeviceName = cleanName
        startAdvertising(groupTag, cleanName)
        startScanning()
    }

    fun stop() {
        runCatching { scanner?.stopScan(scanCallback) }
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        scanning = false
        advertising = false
        activeGroupTag = ""
        activeDeviceName = ""
        synchronized(lock) { peers.clear() }
    }

    fun snapshotPeers(): List<BlePeer> {
        pruneStalePeers()
        synchronized(lock) {
            return peers.values
                .sortedByDescending { it.lastSeenElapsedMs }
                .toList()
        }
    }

    fun status(): BleRuntimeStatus {
        return BleRuntimeStatus(
            scanning = scanning,
            advertising = advertising,
            lastError = lastError,
        )
    }

    private fun startScanning() {
        val bleScanner = scanner ?: return

        val filter = ScanFilter.Builder()
            .setServiceUuid(serviceUuid)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        runCatching {
            bleScanner.startScan(listOf(filter), settings, scanCallback)
            scanning = true
        }.onFailure { error ->
            scanning = false
            lastError = error.message
            Log.w(TAG, "BLE scan start failed", error)
        }
    }

    private fun startAdvertising(groupTag: String, deviceName: String) {
        if (!TransportHardwareInspector.hasBleAdvertisePermission(appContext)) {
            advertising = false
            return
        }

        val bleAdvertiser = advertiser ?: return
        val payload = "fh1|$groupTag|$deviceName".toByteArray(StandardCharsets.UTF_8)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(false)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(serviceUuid)
            .addServiceData(serviceUuid, payload)
            .build()

        runCatching {
            bleAdvertiser.startAdvertising(settings, data, advertiseCallback)
            advertising = true
        }.onFailure { error ->
            advertising = false
            lastError = error.message
            Log.w(TAG, "BLE advertising start failed", error)
        }
    }

    private fun parseServiceData(result: ScanResult): Triple<String, String, String>? {
        val record = result.scanRecord ?: return null
        val raw = record.getServiceData(serviceUuid) ?: return null
        val decoded = raw.toString(StandardCharsets.UTF_8)
        val parts = decoded.split('|', limit = 3)
        if (parts.size != 3 || parts[0] != "fh1") {
            return null
        }
        return Triple(parts[0], parts[1], parts[2])
    }

    private fun updatePeer(result: ScanResult, groupTag: String, announcedName: String) {
        if (groupTag != activeGroupTag) {
            return
        }

        val now = SystemClock.elapsedRealtime()
        val safeName = announcedName.ifBlank { "Peer BLE" }
        val address = runCatching { result.device.address }.getOrNull()
        val deviceId = if (!address.isNullOrBlank()) address else "ble-${safeName.lowercase()}"

        synchronized(lock) {
            peers[deviceId] = BlePeer(
                deviceId = deviceId,
                deviceName = safeName,
                rssi = result.rssi,
                groupTag = groupTag,
                lastSeenElapsedMs = now,
            )
        }
    }

    private fun pruneStalePeers() {
        val cutoff = SystemClock.elapsedRealtime() - PEER_TTL_MS
        synchronized(lock) {
            val stale = peers.filterValues { it.lastSeenElapsedMs < cutoff }.keys
            stale.forEach(peers::remove)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val (_, groupTag, deviceName) = parseServiceData(result) ?: return
            updatePeer(result, groupTag, deviceName)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            lastError = "scan_failed_$errorCode"
            Log.w(TAG, "BLE scan failed: $errorCode")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertising = true
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            lastError = "advertise_failed_$errorCode"
            Log.w(TAG, "BLE advertise failed: $errorCode")
        }
    }

    private companion object {
        const val TAG = "BleIdentityController"
        const val SERVICE_UUID = "6f8d3a52-7a6b-4b62-b2c0-5c0d49f45710"
        const val PEER_TTL_MS = 25_000L

        fun normalizedGroupTag(groupId: String): String {
            return groupId.trim().lowercase().take(12)
        }

        fun normalizedDeviceName(deviceName: String): String {
            return deviceName.trim().replace('|', '_').take(32)
        }
    }
}