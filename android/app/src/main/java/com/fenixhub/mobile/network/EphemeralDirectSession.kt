package com.fenixhub.mobile.network

import android.content.Context
import android.util.Log
import com.fenixhub.mobile.data.ContentRepository
import com.fenixhub.mobile.data.ReceivedContentHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Estados de la sesión directa.
 */
sealed class DirectSessionState {
    data object Idle : DirectSessionState()
    data object Discovering : DirectSessionState()
    data class SelectingPeers(val peers: List<DirectBlePeer>) : DirectSessionState()
    data object CreatingGroup : DirectSessionState()
    data object WaitingForConnections : DirectSessionState()
    data class Transferring(
        val contentId: String,
        val totalBytes: Long,
        val transferredBytes: Long = 0,
    ) : DirectSessionState()
    data class Complete(val contentId: String, val received: Int = 0) : DirectSessionState()
    data class Error(val reason: String) : DirectSessionState()
}

/**
 * Sesión directa AirDrop-like.
 * 
 * Flujo SENDER:
 * 1. startAsSender() → BLE advertising + scanning
 * 2. peers aparecen en snapshot → UI los muestra
 * 3. acceptPeers(selectedPeers) → crea WiFi Direct group, arranca HTTP server
 * 4. peers BLE del receiver se conectan al grupo WiFi Direct
 * 5. Receiver hace HTTP pull → transfer
 * 6. cleanup() después de X minutos
 * 
 * Flujo RECEIVER:
 * 1. startAsReceiver() → BLE advertising + scanning
 * 2. Recibe notificación de que el sender creó grupo
 * 3. Se conecta al grupo WiFi Direct
 * 4. Hace HTTP pull
 * 5. cleanup()
 */
class EphemeralDirectSession(
    private val context: Context,
    private val bleController: BleDirectController,
    private val transferController: WifiDirectTransferController,
    private val contentRepository: ContentRepository,
    private val receivedHandler: ReceivedContentHandler,
    private val httpClient: FenixHttpClient,
    private val httpServer: FenixHttpServer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _sessionState = MutableStateFlow<DirectSessionState>(DirectSessionState.Idle)
    val sessionState: StateFlow<DirectSessionState> = _sessionState.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<DirectBlePeer>>(emptyList())
    val discoveredPeers: StateFlow<List<DirectBlePeer>> = _discoveredPeers.asStateFlow()

    private val _selectedPeers = MutableStateFlow<Set<String>>(emptySet())
    val selectedPeers: StateFlow<Set<String>> = _selectedPeers.asStateFlow()

    @Volatile
    private var ephemeralGroupId = ""

    private var autoCleanupJob: kotlinx.coroutines.Job? = null

    private val bleListener = object : DirectBleListener {
        override fun onPeerDiscovered(peer: DirectBlePeer) {
            Log.d(TAG, "Peer discovered: ${peer.deviceName} (${peer.ephemeralGroupId})")
            val current = _discoveredPeers.value.toMutableList()
            if (current.none { it.deviceId == peer.deviceId }) {
                current.add(peer)
                _discoveredPeers.value = current
            }
            updateState()
        }

        override fun onPeerLost(peerId: String) {
            Log.d(TAG, "Peer lost: $peerId")
            val current = _discoveredPeers.value.toMutableList()
            current.removeAll { it.deviceId == peerId }
            _discoveredPeers.value = current
            _selectedPeers.value = _selectedPeers.value - peerId
            updateState()
        }

        override fun onIncomingInvite(peer: DirectBlePeer) {
            Log.i(TAG, "Incoming invite from ${peer.deviceName} at ${peer.senderIp}:${peer.senderPort}")
            _sessionState.value = DirectSessionState.SelectingPeers(listOf(peer))
        }

        override fun onError(reason: String) {
            Log.w(TAG, "BLE error in direct session: $reason")
            _sessionState.value = DirectSessionState.Error(reason)
        }
    }

    // ── SENDER ──────────────────────────────────────────────────────────

    /**
     * Inicia modo directo como SENDER.
     * Comienza a descubrir receivers cercanos.
     */
    fun startAsSender(deviceName: String) {
        ephemeralGroupId = bleController.generateEphemeralGroupId()
        _sessionState.value = DirectSessionState.Discovering
        _discoveredPeers.value = emptyList()
        _selectedPeers.value = emptySet()

        bleController.startDirectMode(BleDirectController.Role.SENDER, deviceName, bleListener)
        Log.i(TAG, "Sender mode started, ephemeralId=$ephemeralGroupId")
    }

    /**
     * Alterna la selección de un peer.
     */
    fun togglePeerSelection(peerId: String) {
        val current = _selectedPeers.value.toMutableSet()
        if (current.contains(peerId)) {
            current.remove(peerId)
        } else {
            current.add(peerId)
        }
        _selectedPeers.value = current
        updateState()
    }

    /**
     * Confirma la selección de peers y crea el grupo WiFi Direct.
     * @param contentToSendId ID del contenido a enviar (debe existir en contentRepository)
     */
    fun acceptSelectedPeers(contentToSendId: String, onGroupReady: (ephemeralGroupId: String, peerCount: Int) -> Unit) {
        val selected = _selectedPeers.value
        if (selected.isEmpty()) {
            _sessionState.value = DirectSessionState.Error("Selecciona al menos un destinatario")
            return
        }

        val content = contentRepository.getLocalContent(contentToSendId)
        if (content == null) {
            _sessionState.value = DirectSessionState.Error("Contenido no encontrado")
            return
        }

        _sessionState.value = DirectSessionState.CreatingGroup
        bleController.stopDirectMode()

        transferController.createGroup { groupInfo ->
            scope.launch {
                val port = httpServer.startIfNeededEphemeral()
                val p2pIp = transferController.getP2pLocalAddress()
                    ?: resolveGoIp(groupInfo.groupOwnerAddress)

                if (p2pIp == null) {
                    _sessionState.value = DirectSessionState.Error("No se pudo obtener IP del grupo")
                    return@launch
                }

                bleController.updateAdvertisedData(p2pIp, port, contentToSendId)

                _sessionState.value = DirectSessionState.WaitingForConnections
                Log.i(TAG, "Group created: ephemeralId=$ephemeralGroupId, ip=$p2pIp:$port, contentId=$contentToSendId")

                startAutoCleanup(AUTO_CLEANUP_MS)
                onGroupReady(ephemeralGroupId, selected.size)
            }
        }
    }

    // ── RECEIVER ────────────────────────────────────────────────────────

    /**
     * Inicia modo directo como RECEIVER.
     */
    fun startAsReceiver(deviceName: String) {
        ephemeralGroupId = bleController.generateEphemeralGroupId()
        _sessionState.value = DirectSessionState.Discovering
        _discoveredPeers.value = emptyList()

        bleController.startDirectMode(BleDirectController.Role.RECEIVER, deviceName, bleListener)
        Log.i(TAG, "Receiver mode started, ephemeralId=$ephemeralGroupId")
    }

    /**
     * El receiver se conecta al grupo del sender.
     * @param senderPeer El peer sender al que conectarse (contiene senderIp, senderPort, contentId)
     * @param onConnected Callback cuando la conexión al grupo está lista
     */
    fun connectToSender(senderPeer: DirectBlePeer, onConnected: (senderIp: String, senderPort: Int, contentId: String) -> Unit) {
        val senderIp = senderPeer.senderIp
        val senderPort = senderPeer.senderPort ?: EPHEMERAL_DEFAULT_PORT
        val contentId = senderPeer.contentId ?: ""
        ephemeralGroupId = senderPeer.ephemeralGroupId

        _sessionState.value = DirectSessionState.CreatingGroup
        bleController.stopScanningOnly()

        transferController.connectToGroup(goDeviceAddress = senderPeer.deviceId) { groupInfo ->
            scope.launch {
                val resolvedIp = senderIp ?: run {
                    transferController.getP2pLocalAddress() ?: resolveGoIp(groupInfo.groupOwnerAddress)
                }
                _sessionState.value = DirectSessionState.WaitingForConnections
                Log.i(TAG, "Connected to sender's group: $resolvedIp:$senderPort")
                startAutoCleanup(AUTO_CLEANUP_MS)
                onConnected(resolvedIp ?: groupInfo.groupOwnerAddress, senderPort, contentId)
            }
        }
    }

    /**
     * Receiver hace pull del contenido del sender.
     * @param senderIp IP del sender en la red p2p
     * @param senderPort Puerto HTTP del sender
     * @param contentId ID del contenido a recibir
     */
    suspend fun pullFromSender(senderIp: String, senderPort: Int, contentId: String): Boolean {
        _sessionState.value = DirectSessionState.Transferring(
            contentId = contentId,
            totalBytes = -1,
        )

        if (!waitForConnectivity(senderIp, senderPort)) {
            _sessionState.value = DirectSessionState.Error("No se pudo conectar al sender")
            return false
        }

        val result = httpClient.pullContentEphemeral(senderIp, contentId)
        return result.fold(
            onSuccess = { pulled ->
                val peer = com.fenixhub.mobile.model.PeerContent(
                    peerIp = senderIp,
                    port = senderPort,
                    announcement = com.fenixhub.mobile.model.Announcement(
                        groupId = "ephemeral:$ephemeralGroupId",
                        contentId = contentId,
                        deviceName = "Sender Directo",
                        preview = "",
                        contentType = com.fenixhub.mobile.model.HubContentType.FILE,
                        sizeBytes = pulled.bytes.size.toLong(),
                        sendMode = com.fenixhub.mobile.model.SendMode.Broadcast,
                        createdAt = System.currentTimeMillis(),
                        port = senderPort,
                    ),
                )
                val received = receivedHandler.handle(peer, pulled)
                contentRepository.addLocalContent(received.item)
                _sessionState.value = DirectSessionState.Complete(contentId)
                Log.i(TAG, "Direct receive complete: $contentId (${pulled.bytes.size} bytes)")
                true
            },
            onFailure = { e ->
                Log.e(TAG, "Direct pull failed", e)
                _sessionState.value = DirectSessionState.Error(e.message ?: "Pull failed")
                false
            },
        )
    }

    // ── Receiver ─────────────────────────────────────────────────────────

    /**
     * Obtiene el peer que ha enviado una invitación entrante (si hay).
     */
    fun getCurrentInviter(): DirectBlePeer? {
        val state = _sessionState.value
        return if (state is DirectSessionState.SelectingPeers) {
            state.peers.firstOrNull { it.isInviter }
        } else null
    }

    /**
     * El usuario acepta la invitación entrante.
     * @param onConnected callback(ip, port, contentId) cuando la conexión está lista
     */
    fun acceptIncomingInvite(onConnected: (senderIp: String, senderPort: Int, contentId: String) -> Unit) {
        val inviter = getCurrentInviter() ?: run {
            _sessionState.value = DirectSessionState.Error("No hay invitación activa")
            return
        }
        connectToSender(inviter, onConnected)
    }

    /**
     * El usuario rechaza la invitación entrante.
     */
    fun rejectIncomingInvite() {
        val inviter = getCurrentInviter()
        inviter?.let {
            _discoveredPeers.value = _discoveredPeers.value.filter { p -> p.deviceId != it.deviceId }
        }
        _sessionState.value = DirectSessionState.Idle
        Log.d(TAG, "Incoming invite rejected")
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun resolveGoIp(groupOwnerAddress: String): String? {
        if (groupOwnerAddress.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
            return groupOwnerAddress
        }
        return runCatching {
            java.net.InetAddress.getByName(groupOwnerAddress).hostAddress
        }.getOrNull()
    }

    private fun waitForConnectivity(ip: String, port: Int, timeoutMs: Long = 15_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(ip, port), 3000)
                    return true
                }
            } catch (_: Exception) {
                Thread.sleep(500)
            }
        }
        return false
    }

    private fun startAutoCleanup(delayMs: Long) {
        autoCleanupJob?.cancel()
        autoCleanupJob = scope.launch {
            delay(delayMs)
            Log.d(TAG, "Auto-cleanup triggered after ${delayMs}ms")
            cleanup()
        }
    }

    private fun updateState() {
        when (_sessionState.value) {
            is DirectSessionState.Discovering -> {
                _sessionState.value = DirectSessionState.SelectingPeers(_discoveredPeers.value)
            }
            is DirectSessionState.SelectingPeers -> {
                _sessionState.value = DirectSessionState.SelectingPeers(_discoveredPeers.value)
            }
            else -> {}
        }
    }

    /**
     * Cancela el modo directo sin transferir.
     */
    fun cancel() {
        bleController.stopDirectMode()
        autoCleanupJob?.cancel()
        _sessionState.value = DirectSessionState.Idle
        _discoveredPeers.value = emptyList()
        _selectedPeers.value = emptySet()
        Log.d(TAG, "Direct session cancelled")
    }

    /**
     * Limpia la sesión completamente.
     */
    fun cleanup() {
        bleController.stopDirectMode()
        transferController.cleanup()
        autoCleanupJob?.cancel()
        _sessionState.value = DirectSessionState.Idle
        _discoveredPeers.value = emptyList()
        _selectedPeers.value = emptySet()
        Log.d(TAG, "Direct session cleaned up")
    }

    private companion object {
        const val TAG = "EphemeralDirectSession"
        const val AUTO_CLEANUP_MS = 5 * 60 * 1000L
        const val EPHEMERAL_DEFAULT_PORT = 8766
    }
}
