package com.fenixhub.mobile.network

import android.util.Log
import com.fenixhub.mobile.data.ContentRepository
import com.fenixhub.mobile.data.ReceivedContentHandler
import com.fenixhub.mobile.data.SettingsStore
import com.fenixhub.mobile.model.AppSettings
import com.fenixhub.mobile.model.PeerContent
import com.fenixhub.mobile.model.WifiDirectTransferState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Sesión de transferencia por WiFi Direct.
 *
 * Orquesta el flujo completo Airdrop-like:
 *
 * ── SENDER (envía contenido) ──
 * 1. Crea grupo WiFi Direct → es el GO
 * 2. Arranca HTTP server en la interfaz p2p (si no está corriendo)
 * 3. Notifica al receiver (vía BLE) con: p2p_ip, puerto, contentId
 * 4. Espera a que el receiver haga HTTP pull
 *
 * ── RECEIVER (recibe contenido) ──
 * 1. Se conecta al grupo WiFi Direct del sender
 * 2. Obtiene la IP del GO desde la info de conexión
 * 3. Hace HTTP pull al GO usando la IP p2p
 * 4. Guarda el contenido localmente
 */
class WifiDirectTransferSession(
    private val context: android.content.Context,
    private val transferController: WifiDirectTransferController,
    private val settingsStore: SettingsStore,
    private val contentRepository: ContentRepository,
    private val receivedContentHandler: ReceivedContentHandler,
    private val httpClient: FenixHttpClient,
    private val httpServer: FenixHttpServer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _sessionState = MutableStateFlow<WifiDirectTransferState>(WifiDirectTransferState.Idle)
    val sessionState: StateFlow<WifiDirectTransferState> = _sessionState.asStateFlow()

    // ── SENDER: Iniciar transferencia ─────────────────────────────────────

    /**
     * Inicia una transferencia como SENDER.
     * Crea un grupo WiFi Direct y prepara el servidor HTTP.
     *
     * @param contentId ID del contenido a enviar (debe estar publicado)
     * @param onReady callback con la IP p2p y puerto del servidor
     */
    fun startSending(
        contentId: String,
        onReady: (p2pIp: String, port: Int, contentId: String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val item = contentRepository.getLocalContent(contentId)
        if (item == null || !item.isPublished) {
            onError("Contenido no encontrado o no publicado")
            return
        }

        _sessionState.value = WifiDirectTransferState.CreatingGroup
        Log.d(TAG, "Starting send session for $contentId")

        transferController.createGroup { groupInfo ->
            scope.launch {
                try {
                    val port = httpServer.startIfNeeded()
                    val p2pIp = transferController.getP2pLocalAddress()

                    if (p2pIp == null) {
                        // Fallback: usar la IP del GO desde la info del grupo
                        val goIp = resolveGoIp(groupInfo.groupOwnerAddress)
                        if (goIp != null) {
                            _sessionState.value = WifiDirectTransferState.Connected(
                                groupOwnerAddress = goIp,
                                isGroupOwner = true,
                                httpPort = port,
                            )
                            _sessionState.value = WifiDirectTransferState.WaitingForReceiver
                            Log.i(TAG, "Sender ready: $goIp:$port, waiting for receiver")
                            onReady(goIp, port, contentId)
                        } else {
                            onError("No se pudo obtener IP p2p del GO")
                            _sessionState.value = WifiDirectTransferState.Error("No p2p IP")
                        }
                    } else {
                        _sessionState.value = WifiDirectTransferState.Connected(
                            groupOwnerAddress = p2pIp,
                            isGroupOwner = true,
                            httpPort = port,
                        )
                        _sessionState.value = WifiDirectTransferState.WaitingForReceiver
                        Log.i(TAG, "Sender ready: $p2pIp:$port, waiting for receiver")
                        onReady(p2pIp, port, contentId)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting up sender", e)
                    onError("Error preparando envío: ${e.message}")
                    _sessionState.value = WifiDirectTransferState.Error(e.message ?: "Unknown")
                }
            }
        }
    }

    // ── RECEIVER: Recibir contenido ───────────────────────────────────────

    /**
     * Inicia la recepción como RECEIVER.
     * Se conecta al grupo WiFi Direct y hace HTTP pull.
     *
     * @param goDeviceAddress dirección MAC del GO (del BLE)
     * @param goP2pIp IP del GO en la red p2p (opcional, se detecta automáticamente)
     * @param port puerto del servidor HTTP del GO
     * @param contentId ID del contenido a recibir
     * @param onComplete callback con el contenido recibido
     */
    fun startReceiving(
        goDeviceAddress: String? = null,
        goP2pIp: String? = null,
        port: Int,
        contentId: String,
        onComplete: (Boolean, String) -> Unit,
    ) {
        _sessionState.value = WifiDirectTransferState.Transferring(
            contentId = contentId,
            bytesTransferred = 0,
            totalBytes = -1,
        )
        Log.d(TAG, "Starting receive session from $goP2pIp:$port for $contentId")

        scope.launch {
            try {
                // Si tenemos la IP del GO, intentar conectar directamente
                val targetIp = goP2pIp ?: discoverGoIp(goDeviceAddress)

                if (targetIp == null) {
                    // Conectar al grupo primero
                    if (goDeviceAddress != null) {
                        transferController.connectToGroup(goDeviceAddress = goDeviceAddress) { info ->
                            scope.launch {
                                val resolvedIp = info.groupOwnerAddress
                                pullContent(resolvedIp, port, contentId, onComplete)
                            }
                        }
                    } else {
                        onComplete(false, "No se pudo determinar la IP del GO")
                        _sessionState.value = WifiDirectTransferState.Error("No GO IP")
                    }
                } else {
                    // Ya tenemos la IP, hacer pull directo
                    pullContent(targetIp, port, contentId, onComplete)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in receive session", e)
                onComplete(false, "Error recibiendo: ${e.message}")
                _sessionState.value = WifiDirectTransferState.Error(e.message ?: "Unknown")
            }
        }
    }

    /**
     * Pull contenido desde el GO usando HTTP sobre la conexión p2p.
     */
    private suspend fun pullContent(
        goIp: String,
        port: Int,
        contentId: String,
        onComplete: (Boolean, String) -> Unit,
    ) {
        val settings = settingsStore.current()
        if (!settings.configured) {
            onComplete(false, "App no configurada")
            return
        }

        // Crear un PeerContent ficticio con la IP p2p
        val peer = PeerContent(
            peerIp = goIp,
            port = port,
            announcement = contentRepository.peers.value
                .firstOrNull { it.announcement.contentId == contentId }?.announcement
                ?: createFallbackAnnouncement(goIp, port, contentId, settings),
        )

        _sessionState.value = WifiDirectTransferState.Transferring(
            contentId = contentId,
            bytesTransferred = 0,
            totalBytes = -1,
        )

        // Verificar conectividad antes de hacer pull
        if (!waitForConnectivity(goIp, port)) {
            onComplete(false, "No se pudo conectar al peer por WiFi Direct")
            _sessionState.value = WifiDirectTransferState.Error("Connection timeout")
            return
        }

        val result = httpClient.pullContent(peer, settings)
        result.onSuccess { pulled ->
            val received = receivedContentHandler.handle(peer, pulled)
            contentRepository.addLocalContent(received.item)
            _sessionState.value = WifiDirectTransferState.Complete(contentId)
            transferController.transferComplete(contentId)
            Log.i(TAG, "Receive complete: $contentId (${pulled.bytes.size} bytes)")
            onComplete(true, received.message)
        }.onFailure { error ->
            Log.e(TAG, "Pull failed for $contentId from $goIp:$port", error)
            _sessionState.value = WifiDirectTransferState.Error(error.message ?: "Pull failed")
            onComplete(false, "Error al recibir contenido: ${error.message}")
        }
    }

    /**
     * Espera a que la conexión TCP al GO esté lista.
     * La interfaz p2p puede tardar unos segundos en estar operativa.
     */
    private fun waitForConnectivity(ip: String, port: Int, timeoutMs: Long = 15_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ip, port), 3000)
                    return true
                }
            } catch (_: Exception) {
                Thread.sleep(500)
            }
        }
        return false
    }

    /**
     * Intenta descubrir la IP del GO usando DNS p2p o resolución directa.
     */
    private fun discoverGoIp(goDeviceAddress: String?): String? {
        if (goDeviceAddress == null) return null

        // En WiFi Direct, la IP del GO suele ser 192.168.49.1
        // pero puede variar. Intentar primero la IP típica.
        val defaultGoIp = "192.168.49.1"
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(defaultGoIp, 8765), 2000)
                defaultGoIp
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resuelve la IP del GO desde su dirección MAC o nombre.
     */
    private fun resolveGoIp(groupOwnerAddress: String): String? {
        // Si ya es una IP, devolverla directamente
        if (groupOwnerAddress.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
            return groupOwnerAddress
        }

        // Intentar resolver por DNS (Android p2p hostname)
        return runCatching {
            java.net.InetAddress.getByName(groupOwnerAddress).hostAddress
        }.getOrNull()
    }

    /**
     * Crea un announcement de fallback cuando no tenemos el announcement del peer
     * (por ejemplo, cuando el sender inicia la transferencia y el receiver aún no
     * ha recibido el announcement por mDNS/BLE).
     */
    private fun createFallbackAnnouncement(
        goIp: String,
        port: Int,
        contentId: String,
        settings: AppSettings,
    ): com.fenixhub.mobile.model.Announcement {
        return com.fenixhub.mobile.model.Announcement(
            groupId = settings.groupId,
            contentId = contentId,
            deviceName = settings.deviceName,
            preview = "",
            contentType = com.fenixhub.mobile.model.HubContentType.FILE,
            sizeBytes = 0,
            sendMode = com.fenixhub.mobile.model.SendMode.Broadcast,
            createdAt = System.currentTimeMillis(),
            port = port,
        )
    }

    /**
     * Limpia la sesión y resetea el estado.
     */
    fun cleanup() {
        _sessionState.value = WifiDirectTransferState.Idle
        transferController.cleanup()
        Log.d(TAG, "Session cleaned up")
    }

    private companion object {
        const val TAG = "WifiDirectSession"
    }
}
