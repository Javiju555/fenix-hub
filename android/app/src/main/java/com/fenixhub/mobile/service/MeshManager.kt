package com.fenixhub.mobile.service

import android.content.Context
import android.util.Log
import com.fenixhub.mobile.model.MeshDevice
import com.fenixhub.mobile.model.MeshDeviceStatus
import com.fenixhub.mobile.model.MeshEvent
import com.fenixhub.mobile.model.MeshRole
import com.fenixhub.mobile.model.MeshState
import com.fenixhub.mobile.model.MeshStatus
import com.fenixhub.mobile.network.BleDirectController
import com.fenixhub.mobile.network.WifiDirectTransferController
import com.fenixhub.mobile.util.CryptoUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.UUID

class MeshManager(
    private val context: Context,
    private val bleController: BleDirectController,
    private val wfdController: WifiDirectTransferController,
    private var bleBridge: MeshBleBridge? = null,
    private var bleExchange: MeshBleExchange? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val secureRandom = SecureRandom()

    private val _state = MutableStateFlow(MeshState())
    val state: StateFlow<MeshState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<MeshEvent>()
    val events: SharedFlow<MeshEvent> = _events.asSharedFlow()

    private var bleDiscoveryJob: Job? = null
    private var modalCloseGuardJob: Job? = null

    private val MESH_SERVICE_UUID = "6f8d3a52-7a6b-4b62-b2c0-5c0d49f45713"
    private val MESH_TIMEOUT_MS = 5 * 60 * 1000L

    sealed class MeshCommand {
        data class StartAsHost(val contentPool: List<String>) : MeshCommand()
        data object StartAsDevice : MeshCommand()
        data class AcceptDevice(val deviceId: String) : MeshCommand()
        data class RejectDevice(val deviceId: String) : MeshCommand()
        data class RequestJoin(val hostMeshId: String, val hostName: String) : MeshCommand()
        data object CancelDiscovery : MeshCommand()
        data object CloseModal : MeshCommand()
        data object LeaveMesh : MeshCommand()
        data object FinalizeTransfer : MeshCommand()
        data object ConfirmAllReceived : MeshCommand()
    }

    fun dispatch(command: MeshCommand) {
        scope.launch {
            try {
                when (command) {
                    is MeshCommand.StartAsHost -> startAsHost(command.contentPool)
                    is MeshCommand.StartAsDevice -> startAsDevice()
                    is MeshCommand.AcceptDevice -> acceptDevice(command.deviceId)
                    is MeshCommand.RejectDevice -> rejectDevice(command.deviceId)
                    is MeshCommand.RequestJoin -> requestJoin(command.hostMeshId, command.hostName)
                    is MeshCommand.CancelDiscovery -> cancelDiscovery()
                    is MeshCommand.CloseModal -> closeModal()
                    is MeshCommand.LeaveMesh -> leaveMesh()
                    is MeshCommand.FinalizeTransfer -> finalizeTransfer()
                    is MeshCommand.ConfirmAllReceived -> confirmAllReceived()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Mesh command failed", e)
                _events.emit(MeshEvent.Error(e.message ?: "Mesh error"))
            }
        }
    }

    private suspend fun startAsHost(contentPool: List<String>) {
        if (_state.value.status != MeshStatus.IDLE) return
        if (contentPool.isEmpty()) {
            _events.emit(MeshEvent.Error("Añade contenido al hub antes de crear mesh"))
            return
        }

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

    private suspend fun startBleMeshDiscovery() {
        bleBridge = MeshBleBridge(context, this)
        bleBridge?.startMeshDiscovery(_state.value.meshId)
        bleDiscoveryJob?.cancel()
        bleDiscoveryJob = scope.launch {
            var timeoutMs = MESH_TIMEOUT_MS
            while (timeoutMs > 0 && _state.value.status == MeshStatus.DISCOVERING) {
                delay(1000)
                timeoutMs -= 1000
                if (_state.value.status != MeshStatus.DISCOVERING) break
            }
            if (_state.value.status == MeshStatus.DISCOVERING) {
                val currentState = _state.value
                if (currentState.role == MeshRole.HOST && currentState.pendingDevices.isNotEmpty()) {
                    closeModal()
                } else if (currentState.role == MeshRole.DEVICE && currentState.pendingDevices.isNotEmpty()) {
                    closeModal()
                } else {
                    cancelDiscovery()
                }
            }
        }
    }

    private suspend fun acceptDevice(deviceId: String) {
        val current = _state.value
        if (current.role != MeshRole.HOST) return

        val pending = current.pendingDevices.toMutableList()
        val index = pending.indexOfFirst { it.id == deviceId }
        if (index == -1) return

        val device = pending[index]
        pending[index] = device.copy(status = MeshDeviceStatus.CONNECTED, joinedAt = System.currentTimeMillis())
        _state.value = current.copy(pendingDevices = pending)
        _events.emit(MeshEvent.DeviceAccepted(deviceId))
    }

    private suspend fun rejectDevice(deviceId: String) {
        val current = _state.value
        if (current.role != MeshRole.HOST) return

        val pending = current.pendingDevices.filterNot { it.id == deviceId }
        _state.value = current.copy(pendingDevices = pending)
        _events.emit(MeshEvent.DeviceRejected(deviceId))
    }

    private suspend fun requestJoin(hostMeshId: String, hostName: String) {
        val current = _state.value
        if (current.role != MeshRole.DEVICE) return

        val deviceId = UUID.randomUUID().toString().take(8)
        val device = MeshDevice(
            id = deviceId,
            name = hostName,
            status = MeshDeviceStatus.PENDING,
            joinedAt = System.currentTimeMillis(),
        )
        _state.value = current.copy(
            meshId = hostMeshId,
            pendingDevices = listOf(device),
        )
        _events.emit(MeshEvent.DeviceAccepted(deviceId))
    }

    private suspend fun cancelDiscovery() {
        bleDiscoveryJob?.cancel()
        bleDiscoveryJob = null
        bleBridge?.stopMeshDiscovery()
        bleBridge = null
        bleExchange?.stop()
        bleExchange = null
        val current = _state.value
        if (current.role == MeshRole.HOST) {
            _state.value = MeshState()
        } else {
            _state.value = MeshState()
        }
    }

    private suspend fun closeModal() {
        val current = _state.value
        bleDiscoveryJob?.cancel()
        bleDiscoveryJob = null
        bleBridge?.stopMeshDiscovery()
        bleBridge = null

        if (current.role == MeshRole.HOST) {
            if (current.pendingDevices.isEmpty() && current.activeDevices.isEmpty()) {
                _state.value = MeshState()
                return
            }

            val passphrase = generatePassphrase()
            _state.value = current.copy(
                status = MeshStatus.FORMING,
                passphrase = passphrase,
                activeDevices = current.pendingDevices.map {
                    it.copy(status = MeshDeviceStatus.CONNECTED, joinedAt = System.currentTimeMillis())
                },
                pendingDevices = emptyList(),
            )

            startPassphraseExchange(passphrase)
            formWifiDirectGroup()
        } else if (current.role == MeshRole.DEVICE) {
            if (current.pendingDevices.isEmpty()) {
                _state.value = MeshState()
                return
            }

            bleExchange?.stop()
            bleExchange = null

            _state.value = current.copy(status = MeshStatus.FORMING)
            _events.emit(MeshEvent.GroupFormed(current.meshId ?: "unknown"))
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
            deviceName = android.os.Build.MODEL,
            isHost = true,
            listener = object : MeshPassphraseListener {
                override fun onPassphraseReceived(exchange: MeshPassphraseExchange) {
                    // Host doesn't need to receive passphrase, ignore
                }
            },
        )
        bleExchange?.start()
    }

    private fun startPassphraseReceive(meshId: String) {
        bleExchange?.stop()
        bleExchange = MeshBleExchange(
            context = context,
            meshId = meshId,
            passphrase = "",
            deviceName = android.os.Build.MODEL,
            isHost = false,
            listener = object : MeshPassphraseListener {
                override fun onPassphraseReceived(exchange: MeshPassphraseExchange) {
                    scope.launch {
                        handlePassphraseReceived(exchange)
                    }
                }
            },
        )
        bleExchange?.start()
    }

    private suspend fun handlePassphraseReceived(exchange: MeshPassphraseExchange) {
        val meshId = _state.value.meshId ?: return
        val passphrase = decryptPassphrase(exchange.encryptedPassphrase, meshId) ?: return

        _state.value = _state.value.copy(
            passphrase = passphrase,
            activeDevices = _state.value.pendingDevices.map {
                it.copy(status = MeshDeviceStatus.CONNECTED, joinedAt = System.currentTimeMillis())
            },
            pendingDevices = emptyList(),
            status = MeshStatus.FORMING,
        )

        wfdController.connectToGroup(
            goDeviceAddress = exchange.hostDeviceId,
            passphrase = passphrase,
            networkName = "DIRECT-$meshId",
        ) { groupInfo ->
            scope.launch {
                _state.value = _state.value.copy(
                    status = MeshStatus.ACTIVE,
                    groupCreatedAt = System.currentTimeMillis(),
                )
                _events.emit(MeshEvent.GroupFormed(meshId))
            }
        }
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
                _events.emit(MeshEvent.GroupFormed(current.meshId ?: "unknown"))
            }
        }
    }

    private suspend fun leaveMesh() {
        val current = _state.value
        if (!current.canLeave && current.status == MeshStatus.IDLE) {
            cancelDiscovery()
            return
        }

        bleDiscoveryJob?.cancel()
        bleBridge?.stopMeshDiscovery()
        bleBridge = null
        bleExchange?.stop()
        bleExchange = null
        wfdController.cleanup()
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
        val current = _state.value
        bleDiscoveryJob?.cancel()
        wfdController.cleanup()
        _state.value = MeshState()
        _events.emit(MeshEvent.MeshDestroyed)
    }

    fun onBleDeviceFound(deviceId: String, deviceName: String, rssi: Int, meshId: String?) {
        val current = _state.value
        if (current.status != MeshStatus.DISCOVERING) return

        val newDevice = MeshDevice(
            id = deviceId,
            name = deviceName,
            rssi = rssi,
            status = MeshDeviceStatus.PENDING,
        )

        when (current.role) {
            MeshRole.HOST -> {
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
                if (current.meshId == null && meshId != null) {
                    val exists = current.pendingDevices.any { it.id == deviceId }
                    if (!exists) {
                        _state.value = current.copy(
                            pendingDevices = current.pendingDevices + newDevice.copy(
                                name = "$deviceName ($meshId)",
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
        if (current.status != MeshStatus.DISCOVERING) return

        if (current.role == MeshRole.HOST) {
            val pending = current.pendingDevices.filterNot { it.id == deviceId }
            val active = current.activeDevices.filterNot { it.id == deviceId }
            _state.value = current.copy(pendingDevices = pending, activeDevices = active)
        }
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

    private companion object {
        const val TAG = "MeshManager"
    }
}