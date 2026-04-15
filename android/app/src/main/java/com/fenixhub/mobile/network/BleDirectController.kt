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
import java.security.SecureRandom
import java.util.UUID

/**
 * Dispositivo descubierto en modo directo por BLE.
 * 
 * Formatos de payload:
 * - Basic: fh2|{ephId}|{role}|{name}
 * - Sender con grupo: fh2|{ephId}|SENDER|{name}|{p2pIp}|{p2pPort}|{contentId}
 */
data class DirectBlePeer(
    val deviceId: String,
    val deviceName: String,
    val ephemeralGroupId: String,
    val rssi: Int,
    val lastSeenElapsedMs: Long,
    val senderIp: String? = null,
    val senderPort: Int? = null,
    val contentId: String? = null,
    val isInviter: Boolean = false,
)

/**
 * Estado del descubrimiento BLE en modo directo.
 */
data class DirectBleStatus(
    val discovering: Boolean,
    val advertising: Boolean,
    val lastError: String?,
)

/**
 * Listener para eventos de descubrimiento directo.
 */
interface DirectBleListener {
    fun onPeerDiscovered(peer: DirectBlePeer)
    fun onPeerLost(peerId: String)
    fun onIncomingInvite(peer: DirectBlePeer)
    fun onError(reason: String)
}

/**
 * Controlador BLE para el modo directo (AirDrop-like).
 * 
 * En modo directo NO se usa group_id. Ambos dispositivos (sender y receiver)
 * se abren en modo directo y se descubren por BLE con un ephemeral_group_id
 * generado aleatoriamente para esa sesión.
 * 
 * Formato del payload BLE: fh2|{ephemeralGroupId}|{role}|{deviceName}
 * - fh2: protocolo directo (sin group_id)
 * - ephemeralGroupId: 6 chars alfanuméricos (ej: "A3X7K9")
 * - role: "S" (sender) o "R" (receiver)
 * - deviceName: nombre del dispositivo
 */
class BleDirectController(private val context: Context) {

    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val scanner: BluetoothLeScanner? get() = adapter?.bluetoothLeScanner
    private val advertiser: BluetoothLeAdvertiser? get() = adapter?.bluetoothLeAdvertiser

    private val serviceUuid = ParcelUuid(UUID.fromString(DIRECT_SERVICE_UUID))

    private val discoveredPeers = LinkedHashMap<String, DirectBlePeer>()
    private val lock = Any()

    @Volatile
    private var ephemeralGroupId: String = ""

    @Volatile
    private var localRole: Role = Role.RECEIVER

    @Volatile
    private var localDeviceName: String = ""

    @Volatile
    private var discovering = false

    @Volatile
    private var advertising = false

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var listener: DirectBleListener? = null

    enum class Role {
        SENDER,
        RECEIVER
    }

    /**
     * Genera un nuevo ephemeral_group_id aleatorio.
     */
    fun generateEphemeralGroupId(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars[secureRandom.nextInt(chars.length)] }.joinToString("")
    }

    /**
     * Inicia el modo directo.
     * - advertising=true: nos anunciamos para que otros nos descubran
     * - discovering=true: escaneamos otros en modo directo
     * 
     * @param role SENDER o RECEIVER
     * @param deviceName nombre del dispositivo
     * @param listener callbacks de descubrimiento
     */
    fun startDirectMode(role: Role, deviceName: String, listener: DirectBleListener) {
        val hw = TransportHardwareInspector.snapshot(appContext).ble
        if (!hw.supported || !hw.enabled || !hw.permissionsReady) {
            listener.onError("BLE no disponible o sin permisos")
            return
        }

        this.localRole = role
        this.localDeviceName = deviceName.trim().replace('|', '_').take(32)
        this.ephemeralGroupId = generateEphemeralGroupId()
        this.listener = listener

        startAdvertising()
        startScanning()
        Log.d(TAG, "Direct mode started: role=$role, ephemeralId=$ephemeralGroupId")
    }

    /**
     * Actualiza el advertise BLE con la IP, puerto y contentId del sender.
     * Se llama después de crear el grupo WiFi Direct para notificar a los receivers.
     * Si no se estaba anunciando, no hace nada.
     */
    fun updateAdvertisedData(p2pIp: String, port: Int, contentId: String) {
        if (!advertising) {
            Log.w(TAG, "Cannot update advertise: not advertising")
            return
        }
        stopAdvertisingInternal()
        val bleAdvertiser = advertiser ?: return
        val payload = "fh2|$ephemeralGroupId|SENDER|$localDeviceName|$p2pIp|$port|$contentId"
            .toByteArray(StandardCharsets.UTF_8)

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
            bleAdvertiser.startAdvertising(settings, data, directAdvertiseCallback)
            advertising = true
            Log.d(TAG, "Advertise updated: ip=$p2pIp port=$port contentId=$contentId")
        }.onFailure { e ->
            advertising = false
            lastError = e.message
            Log.w(TAG, "Failed to update advertise", e)
        }
    }

    /**
     * Detiene el modo directo y limpia todo.
     */
    fun stopDirectMode() {
        stopScanningInternal()
        stopAdvertisingInternal()
        listener = null

        synchronized(lock) {
            discoveredPeers.clear()
        }

        Log.d(TAG, "Direct mode stopped")
    }

    /**
     * Detiene solo el scanning (mantiene advertising).
     * Para receiver cuando acepta una invitación.
     */
    fun stopScanningOnly() {
        stopScanningInternal()
    }

    /**
     * Obtiene la lista actual de peers descubiertos.
     */
    fun snapshotPeers(): List<DirectBlePeer> {
        pruneStalePeers()
        synchronized(lock) {
            return discoveredPeers.values.sortedByDescending { it.lastSeenElapsedMs }.toList()
        }
    }

    /**
     * Obtiene el ephemeral_group_id actual de esta sesión.
     */
    fun currentEphemeralGroupId(): String = ephemeralGroupId

    /**
     * Obtiene el estado actual.
     */
    fun status(): DirectBleStatus {
        return DirectBleStatus(
            discovering = discovering,
            advertising = advertising,
            lastError = lastError,
        )
    }

    // ── Advertising ──────────────────────────────────────────────────────

    private fun startAdvertising() {
        if (!TransportHardwareInspector.hasBleAdvertisePermission(appContext)) {
            lastError = "Sin permiso BLE advertise"
            listener?.onError(lastError!!)
            return
        }

        val bleAdvertiser = advertiser ?: return
        val roleChar = if (localRole == Role.SENDER) "S" else "R"
        val payload = "fh2|$ephemeralGroupId|$roleChar|$localDeviceName"
            .toByteArray(StandardCharsets.UTF_8)

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
            bleAdvertiser.startAdvertising(settings, data, directAdvertiseCallback)
            advertising = true
        }.onFailure { e ->
            advertising = false
            lastError = e.message
            Log.w(TAG, "Direct mode advertise failed", e)
            listener?.onError(e.message ?: "Advertise failed")
        }
    }

    // ── Scanning ────────────────────────────────────────────────────────

    private fun startScanning() {
        val bleScanner = scanner ?: return

        val filter = ScanFilter.Builder()
            .setServiceUuid(serviceUuid)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        runCatching {
            bleScanner.startScan(listOf(filter), settings, directScanCallback)
            discovering = true
        }.onFailure { e ->
            discovering = false
            lastError = e.message
            Log.w(TAG, "Direct mode scan failed", e)
            listener?.onError(e.message ?: "Scan failed")
        }
    }

    // ── Parse ───────────────────────────────────────────────────────────

    /**
     * Formatos de payload:
     * - Basic: fh2|{ephId}|{role}|{name}
     * - Sender inviter: fh2|{ephId}|SENDER|{name}|{ip}|{port}|{contentId}
     * 
     * El receiver detecta una invitación cuando:
     * - Ve un peer SENDER con ip+port+contentId (grupo creado, esperando receivers)
     */
    private fun parseDirectPayload(result: ScanResult): DirectBlePeer? {
        val record = result.scanRecord ?: return null
        val raw = record.getServiceData(serviceUuid) ?: return null
        val decoded = raw.toString(StandardCharsets.UTF_8)

        val parts = decoded.split('|')
        if (parts.size < 4 || parts[0] != "fh2") return null

        val peerEphemeralId = parts[1]
        val peerRole = parts[2]
        val peerName = parts.getOrNull(3) ?: "Dispositivo"

        if (peerEphemeralId == ephemeralGroupId && peerRole == if (localRole == Role.SENDER) "S" else "R") {
            return null
        }

        val isInviter = parts.size >= 7 && peerRole == "SENDER"
        val senderIp = if (parts.size >= 6) parts[4] else null
        val senderPort = if (parts.size >= 6) parts[5].toIntOrNull() else null
        val contentId = if (parts.size >= 7) parts[6] else null

        val address = runCatching { result.device.address }.getOrNull()
        val deviceId = if (!address.isNullOrBlank()) address else "direct-${peerEphemeralId}-${peerRole}"

        return DirectBlePeer(
            deviceId = deviceId,
            deviceName = peerName,
            ephemeralGroupId = peerEphemeralId,
            rssi = result.rssi,
            lastSeenElapsedMs = SystemClock.elapsedRealtime(),
            senderIp = senderIp,
            senderPort = senderPort,
            contentId = contentId,
            isInviter = isInviter,
        )
    }

    private fun pruneStalePeers() {
        val cutoff = SystemClock.elapsedRealtime() - PEER_TTL_MS
        synchronized(lock) {
            val stale = discoveredPeers.filterValues { it.lastSeenElapsedMs < cutoff }
            stale.forEach { (id, peer) ->
                discoveredPeers.remove(id)
                listener?.onPeerLost(peer.deviceId)
            }
        }
    }

    // ── Callbacks ───────────────────────────────────────────────────────

    private val directScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val peer = parseDirectPayload(result) ?: return

            val isNew: Boolean
            val wasInviter: Boolean
            synchronized(lock) {
                wasInviter = discoveredPeers[peer.deviceId]?.isInviter == true
                isNew = !discoveredPeers.containsKey(peer.deviceId)
                discoveredPeers[peer.deviceId] = peer
            }

            if (isNew) {
                Log.d(TAG, "Direct peer discovered: ${peer.deviceName} (${peer.ephemeralGroupId}) rssi=${peer.rssi}")
                listener?.onPeerDiscovered(peer)
            }

            if (peer.isInviter && !wasInviter && localRole == Role.RECEIVER) {
                Log.i(TAG, "Incoming invite from ${peer.deviceName} (${peer.senderIp}:${peer.senderPort})")
                listener?.onIncomingInvite(peer)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            discovering = false
            lastError = "scan_failed_$errorCode"
            Log.w(TAG, "Direct mode scan failed: $errorCode")
            listener?.onError("scan_failed_$errorCode")
        }
    }

    private val directAdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertising = true
            Log.d(TAG, "Direct mode advertising active")
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            lastError = "advertise_failed_$errorCode"
            Log.w(TAG, "Direct mode advertise failed: $errorCode")
            listener?.onError("advertise_failed_$errorCode")
        }
    }

    private fun stopScanningInternal() {
        runCatching { scanner?.stopScan(directScanCallback) }
        discovering = false
    }

    private fun stopAdvertisingInternal() {
        runCatching { advertiser?.stopAdvertising(directAdvertiseCallback) }
        advertising = false
    }

    private companion object {
        const val TAG = "BleDirectController"
        const val DIRECT_SERVICE_UUID = "6f8d3a52-7a6b-4b62-b2c0-5c0d49f45712"
        const val PEER_TTL_MS = 20_000L
        val secureRandom = SecureRandom()
    }
}
