package com.fenixhub.mobile.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.fenixhub.mobile.model.MeshDevice
import com.fenixhub.mobile.model.MeshDeviceStatus
import com.fenixhub.mobile.model.MeshEvent
import com.fenixhub.mobile.model.MeshRole
import com.fenixhub.mobile.model.MeshState
import com.fenixhub.mobile.model.MeshStatus
import com.fenixhub.mobile.data.SettingsStore
import com.fenixhub.mobile.network.BleDirectController
import com.fenixhub.mobile.network.FenixHttpClient
import com.fenixhub.mobile.network.MeshGattService
import com.fenixhub.mobile.network.WifiDirectTransferController
import com.fenixhub.mobile.util.CryptoUtils
import com.fenixhub.mobile.util.MeshGattCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MeshManager(
    private val context: Context,
    private val bleController: BleDirectController,
    private val wfdController: WifiDirectTransferController,
    private var bleBridge: MeshBleBridge? = null,
    private var bleExchange: MeshBleExchange? = null,
    private val gattService: MeshGattService? = null,
    private val httpClient: FenixHttpClient? = null,
    private val settingsStore: SettingsStore? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val secureRandom = SecureRandom()

    private val _state = MutableStateFlow(MeshState())
    val state: StateFlow<MeshState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<MeshEvent>()
    val events: SharedFlow<MeshEvent> = _events.asSharedFlow()

    private var bleDiscoveryJob: Job? = null
    private var modalCloseGuardJob: Job? = null
    private var meshNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var meshWifiTimeoutJob: Job? = null
    private var gattEventsJob: Job? = null
    private var gattScanEventsJob: Job? = null
    private var gattHostEventsJob: Job? = null
    private var heartbeatJob: Job? = null
    private var devicePingJob: Job? = null

    private val deviceLastSeenMs = ConcurrentHashMap<String, Long>()  // remoteIp → lastPingMs

    @Volatile var p2pNetwork: android.net.Network? = null
        private set

    private val MESH_SERVICE_UUID = "6f8d3a52-7a6b-4b62-b2c0-5c0d49f45713"
    private val MESH_TIMEOUT_MS = 5 * 60 * 1000L

    sealed class MeshCommand {
        data class StartAsHost(val contentPool: List<String>) : MeshCommand()
        data object StartAsDevice : MeshCommand()
        data class AcceptDevice(val deviceId: String) : MeshCommand()
        data class RejectDevice(val deviceId: String) : MeshCommand()
        data class ExpelDevice(val deviceId: String) : MeshCommand()
        data class RequestJoin(val hostMeshId: String, val hostName: String) : MeshCommand()
        data object ModalOpened : MeshCommand()
        data object ModalClosed : MeshCommand()
        data object CancelDiscovery : MeshCommand()
        data object CloseModal : MeshCommand()
        data object LeaveMesh : MeshCommand()
        data object FinalizeTransfer : MeshCommand()
        data object ConfirmAllReceived : MeshCommand()
        data object CreateMesh : MeshCommand()
        data object GenerateQrInvite : MeshCommand()
        data class ValidateQrToken(val token: String) : MeshCommand()
    }

    fun dispatch(command: MeshCommand) {
        scope.launch {
            try {
                when (command) {
                    is MeshCommand.StartAsHost -> startAsHost(command.contentPool)
                    is MeshCommand.StartAsDevice -> startAsDevice()
                    is MeshCommand.AcceptDevice -> acceptDevice(command.deviceId)
                    is MeshCommand.RejectDevice -> rejectDevice(command.deviceId)
                    is MeshCommand.ExpelDevice -> expelDevice(command.deviceId)
                    is MeshCommand.RequestJoin -> requestJoin(command.hostMeshId, command.hostName)
                    is MeshCommand.ModalOpened -> onModalOpened()
                    is MeshCommand.ModalClosed -> onModalClosed()
                    is MeshCommand.CancelDiscovery -> cancelDiscovery()
                    is MeshCommand.CloseModal -> closeModal()
                    is MeshCommand.LeaveMesh -> leaveMesh()
                    is MeshCommand.FinalizeTransfer -> finalizeTransfer()
                    is MeshCommand.ConfirmAllReceived -> confirmAllReceived()
                    is MeshCommand.CreateMesh -> createMesh()
                    is MeshCommand.GenerateQrInvite -> generateQrInvite()
                    is MeshCommand.ValidateQrToken -> validateQrToken(command.token)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Mesh command failed", e)
                _events.emit(MeshEvent.Error(e.message ?: "Mesh error"))
            }
        }
    }

    private suspend fun startAsHost(contentPool: List<String>) {
        Log.d(TAG, "startAsHost called, status=${_state.value.status}")
        if (_state.value.status != MeshStatus.IDLE) return

        val meshId = generateMeshId()
        _state.value = MeshState(
            role = MeshRole.HOST,
            status = MeshStatus.DISCOVERING,
            meshId = meshId,
            localContentPool = contentPool,
            pendingDevices = emptyList(),
            activeDevices = emptyList(),
            createdAt = System.currentTimeMillis(),
        )
        _events.emit(MeshEvent.DiscoveryStarted)
        startBleMeshDiscovery()
    }

    private suspend fun startAsDevice() {
        if (_state.value.status != MeshStatus.IDLE) return
        _state.value = MeshState(
            role = MeshRole.DEVICE,
            status = MeshStatus.DISCOVERING,
            meshId = null,
            pendingDevices = emptyList(),
            activeDevices = emptyList(),
            createdAt = System.currentTimeMillis(),
        )
        _events.emit(MeshEvent.DiscoveryStarted)
        startBleMeshDiscovery()
    }

    private suspend fun startBleMeshDiscovery(timeoutMs: Long = MESH_TIMEOUT_MS) {
        if (gattService != null) {
            val current = _state.value
            if (current.role == MeshRole.HOST) {
                gattService.startGattServer(localDeviceName())
                gattService.startAdvertising(localDeviceName())
                collectGattEvents()
            } else {
                gattService.startScanning()
                collectGattScanEvents()
            }
        } else {
            bleBridge = MeshBleBridge(context, this)
            bleBridge?.startMeshDiscovery(
                meshId = _state.value.meshId,
                asHost = _state.value.role == MeshRole.HOST,
            )
        }

        bleDiscoveryJob?.cancel()
        if (timeoutMs <= 0L) {
            bleDiscoveryJob = null
            return
        }
        bleDiscoveryJob = scope.launch {
            var remaining = timeoutMs
            while (remaining > 0 && _state.value.status == MeshStatus.DISCOVERING) {
                delay(1000)
                remaining -= 1000
            }
            if (_state.value.status == MeshStatus.DISCOVERING) cancelDiscovery()
        }
    }

    private fun collectGattEvents() {
        gattHostEventsJob?.cancel()
        gattHostEventsJob = scope.launch {
            gattService!!.events.collect { event ->
                when (event) {
                    is MeshGattService.GattEvent.JoinRequested -> {
                        val current = _state.value
                        if (current.role != MeshRole.HOST) return@collect
                        if (current.status != MeshStatus.DISCOVERING) return@collect
                        val exists = current.pendingDevices.any { it.id == event.deviceBleId } ||
                                current.activeDevices.any { it.id == event.deviceBleId }
                        if (!exists && current.pendingDevices.size + current.activeDevices.size < current.maxDevices) {
                            val device = MeshDevice(
                                id = event.deviceBleId,
                                name = event.deviceName,
                                rssi = 0,
                                status = MeshDeviceStatus.PENDING,
                                meshId = current.meshId,
                                gattDevice = event.gattDevice,
                                pubKeyBytes = event.devicePubKeyBytes,
                            )
                            _state.value = current.copy(pendingDevices = current.pendingDevices + device)
                            _events.emit(MeshEvent.DeviceDiscovered(device))
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun collectGattScanEvents() {
        gattScanEventsJob?.cancel()
        gattScanEventsJob = scope.launch {
            gattService!!.scanEvents.collect { event ->
                when (event) {
                    is MeshGattService.ScanEvent.HostFound -> {
                        val current = _state.value
                        if (current.role != MeshRole.DEVICE) return@collect
                        if (current.status != MeshStatus.DISCOVERING) return@collect
                        val exists = current.pendingDevices.any {
                            it.id == event.device.address
                        }
                        if (!exists) {
                            val host = MeshDevice(
                                id = event.device.address,
                                name = event.hostName,
                                rssi = 0,
                                status = MeshDeviceStatus.PENDING,
                                meshId = null,
                                gattDevice = event.device,
                                pubKeyBytes = null,
                            )
                            _state.value = current.copy(pendingDevices = current.pendingDevices + host)
                            _events.emit(MeshEvent.DeviceDiscovered(host))
                        }
                    }
                }
            }
        }
    }

    private fun collectDeviceGattEvents() {
        gattEventsJob?.cancel()
        gattEventsJob = scope.launch {
            gattService!!.events.collect { event ->
                when (event) {
                    is MeshGattService.GattEvent.LobbyAcked -> {
                        _state.value = _state.value.copy(status = MeshStatus.PENDING)
                        _events.emit(MeshEvent.DeviceJoined(
                            MeshDevice(id = "host", name = event.hostName, rssi = 0,
                                status = MeshDeviceStatus.CONNECTED)
                        ))
                    }
                    is MeshGattService.GattEvent.LobbyKicked -> {
                        _state.value = MeshState()
                        _events.emit(MeshEvent.Error("removed_from_lobby"))
                    }
                    is MeshGattService.GattEvent.CredentialsReceived -> {
                        handleCredentialsReceived(event.encryptedBytes)
                    }
                    is MeshGattService.GattEvent.RekeyReceived -> {
                        handleCredentialsReceived(event.encryptedBytes)
                    }
                    is MeshGattService.GattEvent.MeshTerminated -> {
                        _state.value = MeshState()
                        _events.emit(MeshEvent.MeshDestroyed)
                    }
                    is MeshGattService.GattEvent.HostDisconnected -> {
                        val s = _state.value.status
                        if (s == MeshStatus.PENDING) {
                            // Lost GATT before credentials arrived — abort
                            Log.w(TAG, "Host GATT disconnected while PENDING — resetting to IDLE")
                            gattService?.stopScanning()
                            _state.value = MeshState()
                            _events.emit(MeshEvent.Error("host_disconnected"))
                        }
                        // FORMING/ACTIVE: credentials already received, WiFi Direct taking over — ignore
                    }
                    else -> {}
                }
            }
        }
    }

    private suspend fun onModalOpened() {
        val current = _state.value
        if (current.status == MeshStatus.ACTIVE_GHOST ||
            (current.role == MeshRole.HOST && current.status == MeshStatus.TRANSFERRING)
        ) {
            if (current.role == MeshRole.HOST) {
                gattService?.startAdvertising(localDeviceName())
            }
            _state.value = current.copy(status = MeshStatus.ACTIVE)
            _events.emit(MeshEvent.MeshGhostModeOff)
        }
    }

    private suspend fun onModalClosed() {
        val current = _state.value
        bleDiscoveryJob?.cancel()
        bleDiscoveryJob = null

        when {
            current.status == MeshStatus.ACTIVE || current.status == MeshStatus.TRANSFERRING -> {
                gattService?.stopAdvertising()
                bleBridge?.stopMeshDiscovery()
                bleBridge = null
                _state.value = current.copy(status = MeshStatus.ACTIVE_GHOST)
                _events.emit(MeshEvent.MeshGhostModeOn)
            }
            current.status == MeshStatus.DISCOVERING || current.status == MeshStatus.PENDING
                    || current.status == MeshStatus.FORMING -> {
                cancelDiscovery()
            }
            else -> {}
        }
    }

    private suspend fun acceptDevice(deviceId: String) {
        val current = _state.value
        if (current.role != MeshRole.HOST) return

        val pending = current.pendingDevices.toMutableList()
        val index = pending.indexOfFirst { it.id == deviceId }
        if (index == -1) return

        val device = pending[index]
        val accepted = device.copy(status = MeshDeviceStatus.CONNECTED, joinedAt = System.currentTimeMillis())
        pending[index] = accepted
        _state.value = current.copy(pendingDevices = pending)
        _events.emit(MeshEvent.DeviceAccepted(deviceId))

        device.gattDevice?.let { bt ->
            gattService?.indicateTo(bt, mapOf(
                "type" to MeshGattService.MSG_LOBBY_ACK,
                "host_name" to localDeviceName(),
            ))
        }
    }

    private suspend fun rejectDevice(deviceId: String) {
        val current = _state.value
        if (current.role != MeshRole.HOST) return

        val device = current.pendingDevices.firstOrNull { it.id == deviceId }
        device?.gattDevice?.let { bt ->
            gattService?.indicateTo(bt, mapOf("type" to MeshGattService.MSG_LOBBY_KICKED))
        }

        val pending = current.pendingDevices.filterNot { it.id == deviceId }
        _state.value = current.copy(pendingDevices = pending)
        _events.emit(MeshEvent.DeviceRejected(deviceId))
    }

    private suspend fun expelDevice(deviceId: String) {
        val current = _state.value
        if (current.role != MeshRole.HOST) return
        if (!current.isActive) return

        val expelled = current.activeDevices.firstOrNull { it.id == deviceId }
            ?: current.pendingDevices.firstOrNull { it.id == deviceId }
        expelled?.gattDevice?.let { bt ->
            gattService?.indicateTo(bt, mapOf("type" to MeshGattService.MSG_LOBBY_KICKED))
        }

        val nextActive = current.activeDevices.filterNot { it.id == deviceId }
        _state.value = current.copy(activeDevices = nextActive)
        _events.emit(MeshEvent.DeviceExpelled(deviceId))

        if (nextActive.isEmpty()) return

        val newGroupKey = ByteArray(32).also { secureRandom.nextBytes(it) }
        val newGroupKeyHex = newGroupKey.joinToString("") { "%02x".format(it) }
        val newGroupId = UUID.randomUUID().toString()
        val newP2pPass = generatePassphrase()
        val newSsid = "DIRECT-FX-${newGroupId.take(6).uppercase()}"
        val hostKeyPair = MeshGattCrypto.generateEcKeyPair()

        wfdController.cleanup()
        val config = android.net.wifi.p2p.WifiP2pConfig.Builder()
            .setNetworkName(newSsid)
            .setPassphrase(newP2pPass)
            .build()
        wfdController.createGroupWithConfig(config) { groupInfo ->
            scope.launch {
                _state.value = _state.value.copy(
                    meshId = newGroupId,
                    groupKey = newGroupKey,
                )
                nextActive.forEach { member ->
                    val pubKeyBytes = member.pubKeyBytes ?: return@forEach
                    val encBlob = MeshGattCrypto.encryptCredentials(
                        payload = MeshGattCrypto.MeshCredentialPayload(
                            groupId = newGroupId,
                            groupKeyHex = newGroupKeyHex,
                            ssid = newSsid,
                            p2pPass = newP2pPass,
                            hostIp = groupInfo.groupOwnerAddress,
                            port = 8765,
                        ),
                        devicePubKeyBytes = pubKeyBytes,
                        hostPrivKey = hostKeyPair.privateKey,
                        hostPubKeyBytes = hostKeyPair.publicKeyBytes,
                    )
                    member.gattDevice?.let { bt ->
                        gattService?.indicateTo(bt, mapOf(
                            "type" to MeshGattService.MSG_MESH_REKEYED,
                            "enc_blob_b64" to android.util.Base64.encodeToString(encBlob, android.util.Base64.NO_WRAP),
                        ))
                    }
                }
                _events.emit(MeshEvent.GroupFormed(
                    meshId = newGroupId,
                    groupKeyHex = newGroupKeyHex,
                    hostIp = groupInfo.groupOwnerAddress,
                    port = 8765,
                ))
            }
        }
    }

    private suspend fun requestJoin(hostMeshId: String, hostName: String) {
        val current = _state.value
        if (current.role != MeshRole.DEVICE) return
        if (current.status != MeshStatus.DISCOVERING) return
        if (hostMeshId.isBlank()) return

        val host = current.pendingDevices.firstOrNull { it.id == hostMeshId } ?: return
        val gattDevice = host.gattDevice ?: return

        bleDiscoveryJob?.cancel()
        bleDiscoveryJob = null

        _state.value = current.copy(
            status = MeshStatus.PENDING,
            meshId = hostMeshId,
            pendingDevices = listOf(host),
        )

        gattService?.connectToHost(gattDevice, localDeviceName())
        collectDeviceGattEvents()
    }

    private suspend fun createMesh() {
        val current = _state.value
        Log.d(TAG, "createMesh called, role=${current.role} status=${current.status} pending=${current.pendingDevices.size}")
        if (current.role != MeshRole.HOST) return
        if (current.status != MeshStatus.DISCOVERING) return

        val accepted = current.pendingDevices.filter { it.status == MeshDeviceStatus.CONNECTED }
        Log.d(TAG, "createMesh: accepted=${accepted.size} (CONNECTED status)")
        if (accepted.isEmpty()) {
            _events.emit(MeshEvent.Error("No hay dispositivos en el lobby"))
            return
        }

        val groupKey = ByteArray(32).also { secureRandom.nextBytes(it) }
        val groupKeyHex = groupKey.joinToString("") { "%02x".format(it) }
        val groupId = UUID.randomUUID().toString()

        val hostKeyPair = MeshGattCrypto.generateEcKeyPair()

        _state.value = current.copy(
            status = MeshStatus.FORMING,
            groupKey = groupKey,
            activeDevices = accepted,
            pendingDevices = emptyList(),
        )

        val p2pPass = generatePassphrase()
        val meshSsid = "DIRECT-FX-${current.meshId ?: groupId.take(6).uppercase()}"

        val config = android.net.wifi.p2p.WifiP2pConfig.Builder()
            .setNetworkName(meshSsid)
            .setPassphrase(p2pPass)
            .build()
        wfdController.createGroupWithConfig(config) { groupInfo ->
            scope.launch {
                val hostIp = groupInfo.groupOwnerAddress
                // Wait for the P2P beacon to propagate before sending credentials.
                // Without this, the DEVICE calls requestNetwork() before the first
                // scan cycle catches the group, showing "No hay dispositivos".
                delay(1800)
                burstCredentials(
                    accepted = accepted,
                    groupId = groupId,
                    groupKeyHex = groupKeyHex,
                    ssid = meshSsid,
                    p2pPass = p2pPass,
                    hostIp = hostIp,
                    port = 8765,
                    hostKeyPair = hostKeyPair,
                )
                _state.value = _state.value.copy(status = MeshStatus.ACTIVE)
                _events.emit(MeshEvent.GroupFormed(groupId, groupKeyHex, hostIp, 8765))
                startHostHeartbeat()
            }
        }
    }

    private fun burstCredentials(
        accepted: List<MeshDevice>,
        groupId: String,
        groupKeyHex: String,
        ssid: String,
        p2pPass: String,
        hostIp: String,
        port: Int,
        hostKeyPair: MeshGattCrypto.EcKeyPair,
    ) {
        Log.d(TAG, "burstCredentials: members=${accepted.size} ssid=$ssid hostIp=$hostIp")
        val payload = MeshGattCrypto.MeshCredentialPayload(
            groupId = groupId,
            groupKeyHex = groupKeyHex,
            ssid = ssid,
            p2pPass = p2pPass,
            hostIp = hostIp,
            port = port,
        )
        accepted.forEach { member ->
            val pubKeyBytes = member.pubKeyBytes ?: run {
                Log.w(TAG, "burstCredentials: ${member.id} has no pubKeyBytes, skipping")
                return@forEach
            }
            val gatt = member.gattDevice ?: run {
                Log.w(TAG, "burstCredentials: ${member.id} has no gattDevice, skipping")
                return@forEach
            }
            val encBlob = MeshGattCrypto.encryptCredentials(
                payload           = payload,
                devicePubKeyBytes = pubKeyBytes,
                hostPrivKey       = hostKeyPair.privateKey,
                hostPubKeyBytes   = hostKeyPair.publicKeyBytes,
            )
            Log.d(TAG, "burstCredentials: indicating ${gatt.address} encBlob=${encBlob.size}B")
            gattService?.indicateTo(gatt, mapOf(
                "type"         to MeshGattService.MSG_MESH_CREDENTIALS,
                "enc_blob_b64" to android.util.Base64.encodeToString(encBlob, android.util.Base64.NO_WRAP),
            ))
        }
    }

    private suspend fun handleCredentialsReceived(encryptedBytes: ByteArray) {
        Log.d(TAG, "handleCredentialsReceived: ${encryptedBytes.size}B")
        val creds = gattService?.decryptCredentials(encryptedBytes) ?: run {
            Log.e(TAG, "handleCredentialsReceived: decryptCredentials returned null")
            return
        }
        Log.i(TAG, "handleCredentialsReceived: ssid=${creds.ssid} hostIp=${creds.hostIp} port=${creds.port}")

        gattService?.stopScanning()

        _state.value = _state.value.copy(
            status = MeshStatus.FORMING,
            meshId = creds.groupId,
            groupKey = hexToBytes(creds.groupKeyHex),
        )

        connectToMeshWifi(creds)
    }

    private fun connectToMeshWifi(creds: MeshGattCrypto.MeshCredentialPayload) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return

        val specifier = android.net.wifi.WifiNetworkSpecifier.Builder()
            .setSsid(creds.ssid)
            .setWpa2Passphrase(creds.p2pPass)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        Log.i(TAG, "Requesting mesh WiFi via specifier: ssid=${creds.ssid}")

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Mesh WiFi connected via specifier")
                meshWifiTimeoutJob?.cancel()
                meshWifiTimeoutJob = null
                p2pNetwork = network
                scope.launch {
                    _state.value = _state.value.copy(status = MeshStatus.ACTIVE)
                    _events.emit(MeshEvent.GroupFormed(
                        meshId = creds.groupId,
                        groupKeyHex = creds.groupKeyHex,
                        hostIp = creds.hostIp ?: "",
                        port = creds.port,
                    ))
                    val hostIp = creds.hostIp?.takeIf { it.isNotBlank() } ?: "192.168.49.1"
                    startDevicePingLoop(hostIp, creds.port, localDeviceName())
                }
            }

            override fun onUnavailable() {
                Log.w(TAG, "Mesh WiFi unavailable via specifier")
                if (meshNetworkCallback === this) meshNetworkCallback = null
                scope.launch {
                    if (_state.value.status == MeshStatus.FORMING) {
                        _state.value = MeshState()
                        _events.emit(MeshEvent.Error("mesh_wifi_connect_timeout"))
                    }
                }
            }
        }

        meshNetworkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        meshNetworkCallback = callback
        try {
            // No explicit timeout — the OS dialog handles its own lifecycle.
            // onUnavailable() fires when the user dismisses or the network disappears.
            // The UI "Cancelar" button in forming state gives manual control.
            cm.requestNetwork(request, callback)
        } catch (e: SecurityException) {
            Log.e(TAG, "requestNetwork denied — CHANGE_NETWORK_STATE missing?", e)
            meshNetworkCallback = null
            scope.launch {
                _state.value = MeshState()
                _events.emit(MeshEvent.Error("mesh_wifi_permission_denied"))
            }
            return
        }

        meshWifiTimeoutJob?.cancel()
        meshWifiTimeoutJob = null
    }

    private fun cleanupMeshWifi() {
        meshWifiTimeoutJob?.cancel()
        meshWifiTimeoutJob = null
        val cm = context.getSystemService(ConnectivityManager::class.java)
        meshNetworkCallback?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
        meshNetworkCallback = null
        p2pNetwork = null
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private suspend fun cancelDiscovery() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        devicePingJob?.cancel()
        devicePingJob = null
        deviceLastSeenMs.clear()
        bleDiscoveryJob?.cancel()
        bleDiscoveryJob = null
        gattEventsJob?.cancel()
        gattEventsJob = null
        gattScanEventsJob?.cancel()
        gattScanEventsJob = null
        gattHostEventsJob?.cancel()
        gattHostEventsJob = null
        bleBridge?.stopMeshDiscovery()
        bleBridge = null
        bleExchange?.stop()
        bleExchange = null
        gattService?.stopGattServer()
        gattService?.stopAdvertising()
        gattService?.stopScanning()
        cleanupMeshWifi()
        _state.value = MeshState()
    }

    private suspend fun closeModal() {
        val current = _state.value
        bleDiscoveryJob?.cancel()
        bleDiscoveryJob = null
        bleBridge?.stopMeshDiscovery()
        bleBridge = null

        if (current.role == MeshRole.HOST) {
            if (current.status != MeshStatus.DISCOVERING) {
                return
            }

            val accepted = current.pendingDevices.filter { it.status == MeshDeviceStatus.CONNECTED }
            if (accepted.isEmpty()) {
                _state.value = MeshState()
                return
            }

            val passphrase = generatePassphrase()
            _state.value = current.copy(
                status = MeshStatus.FORMING,
                passphrase = passphrase,
                activeDevices = accepted,
                pendingDevices = emptyList(),
            )

            startPassphraseExchange(passphrase)
            formWifiDirectGroup()
        } else if (current.role == MeshRole.DEVICE) {
            cancelDiscovery()
        }
    }

    private fun startPassphraseExchange(passphrase: String) {
        val current = _state.value
        val meshId = current.meshId ?: return

        bleExchange?.stop()
        bleExchange = MeshBleExchange(
            context = context,
            meshId = meshId,
            passphrase = passphrase,
            deviceName = localDeviceName(),
            isHost = true,
            listener = object : MeshPassphraseListener {
                override fun onPassphraseReceived(exchange: MeshPassphraseExchange) {
                }
            },
        )
        bleExchange?.start()
    }

    private fun renegotiatePassphraseForActiveGroup() {
        val current = _state.value
        if (current.role != MeshRole.HOST) return
        if (current.status != MeshStatus.ACTIVE && current.status != MeshStatus.TRANSFERRING) return

        val passphrase = generatePassphrase()
        _state.value = current.copy(passphrase = passphrase)
        startPassphraseExchange(passphrase)
        Log.i(TAG, "Mesh passphrase renegotiated for active group")
    }

    private suspend fun formWifiDirectGroup() {
        val current = _state.value
        if (current.role != MeshRole.HOST) return
        if (current.passphrase == null) return

        wfdController.createGroup(current.passphrase) { groupInfo ->
            scope.launch {
                _state.value = _state.value.copy(
                    status = MeshStatus.ACTIVE,
                    groupCreatedAt = System.currentTimeMillis(),
                )
                _events.emit(MeshEvent.GroupFormed(
                    meshId = current.meshId ?: "unknown",
                    groupKeyHex = CryptoUtils.toHex(current.groupKey ?: ByteArray(32)),
                    hostIp = groupInfo.groupOwnerAddress,
                    port = 8765,
                ))
            }
        }
    }

    private suspend fun leaveMesh() {
        val current = _state.value
        if (!current.canLeave && current.status == MeshStatus.IDLE) {
            cancelDiscovery()
            return
        }

        heartbeatJob?.cancel()
        heartbeatJob = null
        devicePingJob?.cancel()
        devicePingJob = null
        deviceLastSeenMs.clear()
        bleDiscoveryJob?.cancel()
        bleBridge?.stopMeshDiscovery()
        bleBridge = null
        bleExchange?.stop()
        bleExchange = null
        gattService?.disconnectFromHost()
        wfdController.cleanup()
        cleanupMeshWifi()
        _state.value = MeshState()
        _events.emit(MeshEvent.MeshDestroyed)
    }

    private suspend fun finalizeTransfer() {
        val current = _state.value
        if (current.role != MeshRole.HOST) return
        if (current.status != MeshStatus.ACTIVE && current.status != MeshStatus.TRANSFERRING) return

        bleExchange?.stop()
        bleExchange = null
        destroyMesh()
    }

    private suspend fun confirmAllReceived() {
        val current = _state.value
        if (current.role != MeshRole.DEVICE) return

        _state.value = current.copy(status = MeshStatus.DESTROYING)
        delay(500)
        _state.value = MeshState()
        _events.emit(MeshEvent.MeshDestroyed)
    }

    private suspend fun destroyMesh() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        devicePingJob?.cancel()
        devicePingJob = null
        deviceLastSeenMs.clear()
        bleDiscoveryJob?.cancel()
        gattService?.stopGattServer()
        gattService?.stopAdvertising()
        wfdController.cleanup()
        cleanupMeshWifi()
        _state.value = MeshState()
        _events.emit(MeshEvent.MeshDestroyed)
    }

    fun onBleDeviceFound(deviceId: String, deviceName: String, rssi: Int, meshId: String?, role: MeshBleRole) {
        val current = _state.value

        val newDevice = MeshDevice(
            id = deviceId,
            name = deviceName,
            rssi = rssi,
            status = MeshDeviceStatus.PENDING,
            meshId = meshId,
        )

        when (current.role) {
            MeshRole.HOST -> {
                if (current.status != MeshStatus.DISCOVERING && current.status != MeshStatus.ACTIVE && current.status != MeshStatus.TRANSFERRING) return
                if (role != MeshBleRole.DEVICE) return

                val exists = current.pendingDevices.any { it.id == deviceId } ||
                    current.activeDevices.any { it.id == deviceId }
                if (!exists && current.pendingDevices.size + current.activeDevices.size < current.maxDevices) {
                    _state.value = current.copy(
                        pendingDevices = current.pendingDevices + newDevice,
                    )
                    scope.launch { _events.emit(MeshEvent.DeviceDiscovered(newDevice)) }
                }
            }
            MeshRole.DEVICE -> {
                if (current.status != MeshStatus.DISCOVERING) return
                if (role != MeshBleRole.HOST) return
                if (current.meshId == null && meshId != null) {
                    val exists = current.pendingDevices.any { it.id == deviceId }
                    if (!exists) {
                        _state.value = current.copy(
                            pendingDevices = current.pendingDevices + newDevice.copy(
                                name = deviceName,
                            ),
                        )
                        scope.launch { _events.emit(MeshEvent.DeviceDiscovered(newDevice)) }
                    }
                }
            }
            else -> {}
        }
    }

    fun onBleDeviceLost(deviceId: String) {
        val current = _state.value
        if (current.status != MeshStatus.DISCOVERING && current.status != MeshStatus.ACTIVE && current.status != MeshStatus.TRANSFERRING) return

        if (current.role == MeshRole.HOST) {
            val pending = current.pendingDevices.filterNot { it.id == deviceId }
            val active = current.activeDevices.filterNot { it.id == deviceId }
            _state.value = current.copy(pendingDevices = pending, activeDevices = active)
            if (active.size != current.activeDevices.size) {
                renegotiatePassphraseForActiveGroup()
            }
        }
    }

    fun onDeviceHello(deviceName: String, remoteIp: String) {
        deviceLastSeenMs[remoteIp] = System.currentTimeMillis()
        scope.launch {
            val current = _state.value
            if (!current.isActive || current.role != MeshRole.HOST) return@launch
            val device = current.activeDevices.firstOrNull { it.name == deviceName && it.p2pIp == null }
                ?: current.activeDevices.firstOrNull { it.p2pIp == null }
            if (device != null) {
                _state.value = current.copy(
                    activeDevices = current.activeDevices.map {
                        if (it.id == device.id) it.copy(p2pIp = remoteIp) else it
                    }
                )
                Log.i(TAG, "Device hello: ${device.name} confirmed at $remoteIp")
            }
        }
    }

    fun onDevicePing(remoteIp: String) {
        deviceLastSeenMs[remoteIp] = System.currentTimeMillis()
    }

    private fun startHostHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(2_000)
                val current = _state.value
                if (!current.isActive || current.role != MeshRole.HOST) break

                val nowMs = System.currentTimeMillis()
                val toRemove = current.activeDevices.filter { device ->
                    val ip = device.p2pIp ?: return@filter false
                    val lastSeen = deviceLastSeenMs[ip] ?: return@filter false
                    nowMs - lastSeen > 4_000
                }
                for (device in toRemove) {
                    Log.w(TAG, "Heartbeat timeout: ${device.name} (${device.p2pIp}) — DeviceLeft")
                    device.p2pIp?.let { deviceLastSeenMs.remove(it) }
                    _state.value = _state.value.copy(
                        activeDevices = _state.value.activeDevices.filterNot { it.id == device.id }
                    )
                    _events.emit(MeshEvent.DeviceLeft(device.id))
                }
            }
        }
    }

    private fun startDevicePingLoop(hostIp: String, port: Int, deviceName: String) {
        devicePingJob?.cancel()
        devicePingJob = scope.launch {
            val socketFactory = p2pNetwork?.socketFactory
            runCatching { httpClient?.meshHello(hostIp, port, deviceName, socketFactory) }
            while (isActive && _state.value.isActive) {
                delay(2_000)
                if (!_state.value.isActive) break
                runCatching { httpClient?.meshPing(hostIp, port, socketFactory) }
            }
            Log.d(TAG, "Device ping loop ended")
        }
    }

    private val validQrTokens = mutableMapOf<String, Long>()

    private fun generateQrInvite() {
        val current = _state.value
        if (current.role != MeshRole.HOST) return
        if (!current.isActive && current.status != MeshStatus.DISCOVERING) return

        val bleMac = context.getSystemService(android.bluetooth.BluetoothManager::class.java)
            ?.adapter?.address ?: "00:00:00:00:00:00"
        val invite = com.fenixhub.mobile.util.MeshQrUtils.generateInvite(bleMac)
        validQrTokens[invite.token] = invite.expiresAt
        scope.launch { _events.emit(MeshEvent.QrInviteGenerated(invite.uri, invite.token)) }
    }

    private fun validateQrToken(token: String) {
        val expiresAt = validQrTokens[token] ?: return
        if (!com.fenixhub.mobile.util.MeshQrUtils.isTokenValid(token, expiresAt)) {
            validQrTokens.remove(token)
            return
        }
        validQrTokens.remove(token)
    }

    private fun generateMeshId(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars[secureRandom.nextInt(chars.length)] }.joinToString("")
    }

    private fun generatePassphrase(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
        return (1..16).map { chars[secureRandom.nextInt(chars.length)] }.joinToString("")
    }

    fun encryptPassphrase(passphrase: String, meshId: String): String {
        val key = CryptoUtils.hkdfExpand(
            CryptoUtils.hkdfExtract(meshId.toByteArray(Charsets.UTF_8)),
            "fenixhub-mesh-key".toByteArray(Charsets.UTF_8),
            32,
        )
        val encrypted = CryptoUtils.encryptAesGcm(key, passphrase.toByteArray(Charsets.UTF_8))
        return CryptoUtils.toHex(encrypted)
    }

    fun decryptPassphrase(encryptedHex: String, meshId: String): String? {
        return runCatching {
            val key = CryptoUtils.hkdfExpand(
                CryptoUtils.hkdfExtract(meshId.toByteArray(Charsets.UTF_8)),
                "fenixhub-mesh-key".toByteArray(Charsets.UTF_8),
                32,
            )
            val encrypted = CryptoUtils.hexToBytes(encryptedHex)
            val decrypted = CryptoUtils.decryptAesGcm(key, encrypted)
            String(decrypted, Charsets.UTF_8)
        }.getOrNull()
    }

    private fun localDeviceName(): String {
        settingsStore?.current()?.deviceName?.takeIf { it.isNotBlank() }?.let { return it }
        val btName = runCatching {
            context.getSystemService(android.bluetooth.BluetoothManager::class.java)
                ?.adapter?.name
        }.getOrNull()
        return btName?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
    }

    private companion object {
        const val TAG = "MeshManager"
    }
}
