package com.fenixhub.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fenixhub.mobile.FenixHubApplication
import com.fenixhub.mobile.MainActivity
import com.fenixhub.mobile.R
import com.fenixhub.mobile.model.MeshEvent
import com.fenixhub.mobile.model.MeshRole
import com.fenixhub.mobile.model.PeerContent
import com.fenixhub.mobile.model.SendMode
import com.fenixhub.mobile.network.DirectBlePeer
import com.fenixhub.mobile.network.EphemeralDirectSession
import com.fenixhub.mobile.network.FenixHttpServer
import com.fenixhub.mobile.network.NsdController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.net.Inet4Address
import java.net.NetworkInterface

class FenixHubService : Service() {
    inner class LocalBinder : Binder() {
        fun getService(): FenixHubService = this@FenixHubService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val container by lazy { (application as FenixHubApplication).container }
    private val repository by lazy { container.contentRepository }
    private val settingsStore by lazy { container.settingsStore }
    private val tempClipboardStore by lazy { container.tempClipboardStore }
    private val httpClient by lazy { container.httpClient }
    private val receivedHandler by lazy { container.receivedContentHandler }
    private val localContentFactory by lazy { container.localContentFactory }
    private val httpServer by lazy { FenixHttpServer(settingsStore, repository) }
    private val nsdController by lazy { NsdController(this, repository, settingsStore) }
    private val bleIdentityController by lazy { container.bleIdentityController }
    private val wifiDirectController by lazy { container.wifiDirectController }
    private val bleDirectController by lazy { container.bleDirectController }
    private val wifiDirectTransferController by lazy { container.wifiDirectTransferController }
    private val hotspotManager by lazy { container.hotspotManager }
    private val meshManager by lazy { container.meshManager }
    private var meshHttpServer: FenixHttpServer? = null

    private val overlayController by lazy {
        OverlayController(
            context = this,
            repository = repository,
            settingsStore = settingsStore,
            localContentFactory = localContentFactory,
            tempClipboardStore = tempClipboardStore,
            receivedContentHandler = receivedHandler,
            httpClient = httpClient,
        )
    }

    private val ephemeralSession by lazy {
        EphemeralDirectSession(
            context = this,
            bleController = bleDirectController,
            transferController = wifiDirectTransferController,
            contentRepository = repository,
            receivedHandler = receivedHandler,
            httpClient = httpClient,
            httpServer = httpServer,
        )
    }

    private var syncJob: Job? = null
    private var publishGuardJob: Job? = null
    private var sensorManager: SensorManager? = null
    private var shakeDetector: ShakeDetector? = null
    private var accelerometer: Sensor? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var edgeTriggerView: EdgeTriggerView? = null

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SensorManager::class.java)
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val wm = getSystemService(WindowManager::class.java)
        edgeTriggerView = EdgeTriggerView(this, wm) { showOverlayIfPermitted() }

        serviceScope.launch {
            meshManager.events.collect { event ->
                when (event) {
                    is MeshEvent.GroupFormed -> {
                        val state = meshManager.state.value
                        if (state.role == MeshRole.HOST) {
                            onMeshActive(state.localContentPool, isHost = true)
                        }
                    }
                    is MeshEvent.MeshDestroyed -> {
                        stopMeshHttpServer()
                    }
                    is MeshEvent.Error -> {
                        showToast(event.message)
                    }
                    else -> {}
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireMulticastLock()

        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> {
                startNetworkStack()
                showOverlayIfPermitted()
            }

            ACTION_REFRESH_IDENTITY -> restartNetworkStack()

            ACTION_START_HOTSPOT -> {
                startNetworkStack()
                hotspotManager.start()
            }

            ACTION_STOP_HOTSPOT -> hotspotManager.stop()

            else -> startNetworkStack()
        }

        return START_STICKY
    }

    fun receivePeer(peer: PeerContent) {
        serviceScope.launch {
            val settings = settingsStore.current()
            if (!settings.configured) {
                showToast("Configura FenixHub antes de recibir contenido")
                return@launch
            }

            val result = httpClient.pullContent(peer, settings)
            result.onSuccess { pulled ->
                val received = receivedHandler.handle(peer, pulled)
                repository.addLocalContent(received.item)
                showToast(received.message)
            }.onFailure {
                showToast("No se pudo recibir el contenido")
            }
        }
    }

    fun publishSelected(sendMode: SendMode = SendMode.Broadcast) {
        val published = repository.publishSelected(sendMode)
        if (published == null) {
            showToast("No hay contenido local listo para publicar")
        } else {
            showToast("Contenido publicado en LAN")
        }
    }

    // ── Direct Mode (AirDrop-like) ──────────────────────────────────────────────

    /**
     * Inicia modo directo como SENDER. Comienza a descubrir receivers por BLE.
     */
    fun startDirectModeSender() {
        val settings = settingsStore.current()
        if (!settings.configured) {
            showToast("Configura FenixHub primero")
            return
        }
        val contentId = repository.selectedLocalContentId.value
            ?: repository.latestLocalContent()?.contentId
        if (contentId == null) {
            showToast("Añade contenido al hub antes de enviar")
            return
        }
        ephemeralSession.startAsSender(settings.deviceName)
        Log.i(TAG, "Direct mode sender started")
    }

    /**
     * Confirma la selección de peers y crea el grupo WiFi Direct.
     * @param selectedPeerIds IDs de los peers seleccionados
     * @param contentToSendId ID del contenido a enviar
     */
    fun acceptDirectPeers(selectedPeerIds: Set<String>, contentToSendId: String) {
        selectedPeerIds.forEach { ephemeralSession.togglePeerSelection(it) }
        ephemeralSession.acceptSelectedPeers(contentToSendId) { ephemeralId, peerCount ->
            showToast("Grupo creado ($ephemeralId) - esperando $peerCount destinatario(s)")
        }
    }

    /**
     * Cancela el modo directo.
     */
    fun cancelDirectMode() {
        ephemeralSession.cancel()
    }

    /**
     * Inicia modo receiver. Escucha invitaciones de senders cercanos.
     */
    fun startDirectModeReceiver() {
        val settings = settingsStore.current()
        if (!settings.configured) {
            showToast("Configura FenixHub primero")
            return
        }
        ephemeralSession.startAsReceiver(settings.deviceName)
        Log.i(TAG, "Direct mode receiver started")
    }

    /**
     * Obtiene la invitación entrante actual (si hay).
     */
    fun getCurrentInviter() = ephemeralSession.getCurrentInviter()

    /**
     * Acepta la invitación entrante.
     * @param onContentReceived callback cuando el contenido llega
     */
    fun acceptDirectInvite(onContentReceived: (item: com.fenixhub.mobile.model.LocalContent) -> Unit) {
        ephemeralSession.acceptIncomingInvite { senderIp, senderPort, contentId ->
            showToast("Conectando al grupo directo...")
            serviceScope.launch {
                val success = ephemeralSession.pullFromSender(senderIp, senderPort, contentId)
                if (success) {
                    val latest = repository.localContent.value.firstOrNull()
                    latest?.let { onContentReceived(it) }
                }
            }
        }
    }

    /**
     * Rechaza la invitación entrante.
     */
    fun rejectDirectInvite() {
        ephemeralSession.rejectIncomingInvite()
    }

    /**
     * Obtiene el estado de la sesión directa.
     */
    val directSessionState get() = ephemeralSession.sessionState

    /**
     * Obtiene la lista de peers descubiertos en modo directo.
     */
    val discoveredDirectPeers get() = ephemeralSession.discoveredPeers

    /**
     * Obtiene los peers seleccionados.
     */
    val selectedDirectPeers get() = ephemeralSession.selectedPeers

    /**
     * Obtiene el estado actual del mesh.
     */
    val meshState get() = meshManager.state

    /**
     * Inicia el HTTP server del mesh en la interfaz p2p.
     * Se llama después de crear el grupo WiFi Direct.
     */
    fun startMeshHttpServer(contentIds: List<String>): Int {
        val server = FenixHttpServer(settingsStore, repository)
        meshHttpServer = server
        contentIds.forEach { contentId ->
            repository.publish(contentId, SendMode.Broadcast)
        }
        val port = server.startIfNeededEphemeral()
        Log.i(TAG, "Mesh HTTP server started on port $port")
        return port
    }

    /**
     * Detiene el HTTP server del mesh.
     */
    fun stopMeshHttpServer() {
        meshHttpServer?.stop()
        meshHttpServer = null
        repository.unpublishAll()
        Log.d(TAG, "Mesh HTTP server stopped, content unpublished")
    }

    /**
     * Callback cuando el mesh se activa. Inicia el servidor HTTP con el content pool.
     */
    private fun onMeshActive(contentPool: List<String>, isHost: Boolean) {
        if (!isHost) return
        serviceScope.launch {
            try {
                val port = startMeshHttpServer(contentPool)
                val localItems = repository.localContent.value.filter { contentPool.contains(it.contentId) }
                nsdController.syncPublishedContent(localItems, port)
                showToast("Mesh activo. Publicando ${contentPool.size} contenido(s).")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start mesh HTTP server", e)
                showToast("Error al iniciar mesh: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        overlayController.dismiss()
        stopNetworkStack(clearPeers = true)
        hotspotManager.stop()
        releaseMulticastLock()
        ephemeralSession.cleanup()
        edgeTriggerView?.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startNetworkStack() {
        val settings = settingsStore.current()
        if (!settings.configured) return

        httpServer.startIfNeeded()
        nsdController.startDiscovery()
        bleIdentityController.ensureRunning(settings.groupId, settings.deviceName)
        wifiDirectController.ensureRunning()

        startShakeDetection()
        startPublishGuardIfNeeded()
        if (Settings.canDrawOverlays(this)) {
            edgeTriggerView?.start()
        } else {
            edgeTriggerView?.stop()
        }

        if (syncJob?.isActive != true) {
            syncJob = serviceScope.launch {
                repository.localContent.collectLatest { items ->
                    val port = httpServer.activePort ?: return@collectLatest
                    nsdController.syncPublishedContent(items.filter { it.isPublished }, port)
                }
            }
        }
    }

    private fun restartNetworkStack() {
        stopNetworkStack(clearPeers = false)
        startNetworkStack()
    }

    private fun stopNetworkStack(clearPeers: Boolean) {
        syncJob?.cancel()
        syncJob = null
        publishGuardJob?.cancel()
        publishGuardJob = null
        stopShakeDetection()
        bleIdentityController.stop()
        wifiDirectController.stop()
        nsdController.stop()
        httpServer.stop()
        if (clearPeers) {
            repository.clearPeers()
        }
    }

    private fun startPublishGuardIfNeeded() {
        if (publishGuardJob?.isActive == true) return

        publishGuardJob = serviceScope.launch {
            var publishNetworkIp: String? = null

            while (isActive) {
                delay(PUBLISH_GUARD_POLL_MS)

                val published = repository.localContent.value.filter { it.isPublished }
                if (published.isEmpty()) {
                    publishNetworkIp = null
                    continue
                }

                val oldestPublishedAt = published.mapNotNull { it.publishedAt }.minOrNull()
                    ?: System.currentTimeMillis()
                if (System.currentTimeMillis() - oldestPublishedAt > MAX_PUBLISH_LIFETIME_MS) {
                    repository.unpublishAll()
                    httpServer.stop()
                    showToast("Publicacion expirada por seguridad. Vuelve a compartir.")
                    publishNetworkIp = null
                    continue
                }

                val currentIp = currentLanIpv4()
                if (publishNetworkIp == null) {
                    publishNetworkIp = currentIp
                    continue
                }

                if (currentIp != null && publishNetworkIp != currentIp) {
                    repository.unpublishAll()
                    httpServer.stop()
                    showToast("Cambio de red detectado. Publicacion detenida por seguridad.")
                    publishNetworkIp = null
                }
            }
        }
    }

    private fun currentLanIpv4(): String? {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback || iface.name.startsWith("p2p")) {
                    continue
                }

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address) {
                        val host = address.hostAddress
                        if (!host.isNullOrBlank()) {
                            return@runCatching host
                        }
                    }
                }
            }
            null
        }.getOrNull()
    }

    private fun showOverlayIfPermitted() {
        if (Settings.canDrawOverlays(this)) {
            edgeTriggerView?.start()
            edgeTriggerView?.setOverlayVisible(true)
            overlayController.show { edgeTriggerView?.setOverlayVisible(false) }
        } else {
            showToast("Concede permiso de overlay para abrir el hub flotante")
        }
    }

    private fun startShakeDetection() {
        if (shakeDetector != null || accelerometer == null) return
        val detector = ShakeDetector(onShake = ::showOverlayIfPermitted)
        shakeDetector = detector
        sensorManager?.registerListener(
            detector,
            accelerometer,
            SensorManager.SENSOR_DELAY_GAME,
        )
    }

    private fun stopShakeDetection() {
        val detector = shakeDetector ?: return
        sensorManager?.unregisterListener(detector)
        shakeDetector = null
    }

    private fun acquireMulticastLock() {
        val current = multicastLock
        if (current != null && current.isHeld) return

        val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
        multicastLock = wifiManager.createMulticastLock("FenixHubMulticast").apply {
            setReferenceCounted(false)
            acquire()
        }
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "FenixHubWifiLock",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        multicastLock = null
        wifiLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wifiLock = null
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            10,
            MainActivity.createIntent(this),
            pendingIntentFlags(),
        )
        val overlayIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, FenixHubService::class.java).setAction(ACTION_SHOW_OVERLAY),
            pendingIntentFlags(),
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_hub)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.notification_action_open), overlayIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_service_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_service_description)
        }
        manager.createNotificationChannel(channel)
    }

    private fun pendingIntentFlags(): Int {
        return PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }

    private fun showToast(message: String) {
        android.os.Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val TAG = "FenixHubService"
        const val ACTION_SHOW_OVERLAY = "com.fenixhub.mobile.action.SHOW_OVERLAY"
        const val ACTION_REFRESH_IDENTITY = "com.fenixhub.mobile.action.REFRESH_IDENTITY"
        const val ACTION_START_HOTSPOT = "com.fenixhub.mobile.action.START_HOTSPOT"
        const val ACTION_STOP_HOTSPOT = "com.fenixhub.mobile.action.STOP_HOTSPOT"
        private const val CHANNEL_ID = "fenixhub-service"
        private const val NOTIFICATION_ID = 3106
        private const val MAX_PUBLISH_LIFETIME_MS = 10 * 60 * 1000L
        private const val PUBLISH_GUARD_POLL_MS = 5_000L

        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, FenixHubService::class.java)
            action?.let(intent::setAction)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
