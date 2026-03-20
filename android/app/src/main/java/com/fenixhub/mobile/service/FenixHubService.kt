package com.fenixhub.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipDescription
import android.content.ClipboardManager
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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fenixhub.mobile.FenixHubApplication
import com.fenixhub.mobile.MainActivity
import com.fenixhub.mobile.R
import com.fenixhub.mobile.model.PeerContent
import com.fenixhub.mobile.model.SendMode
import com.fenixhub.mobile.network.FenixHttpServer
import com.fenixhub.mobile.network.NsdController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class FenixHubService : Service(), OverlayController.Callbacks {
    inner class LocalBinder : Binder() {
        fun getService(): FenixHubService = this@FenixHubService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val container by lazy { (application as FenixHubApplication).container }
    private val repository by lazy { container.contentRepository }
    private val settingsStore by lazy { container.settingsStore }
    private val httpClient by lazy { container.httpClient }
    private val receivedHandler by lazy { container.receivedContentHandler }
    private val localContentFactory by lazy { container.localContentFactory }
    private val httpServer by lazy { FenixHttpServer(settingsStore, repository) }
    private val nsdController by lazy { NsdController(this, repository, settingsStore) }
    private val overlayController by lazy { OverlayController(this, repository, this) }

    private var syncJob: Job? = null
    private var sensorManager: SensorManager? = null
    private var shakeDetector: ShakeDetector? = null
    private var accelerometer: Sensor? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SensorManager::class.java)
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
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

    override fun onPasteTextRequested() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val clip = clipboard.primaryClip
        val text = if (clip != null && clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) {
            clip.getItemAt(0).coerceToText(this)?.toString()
        } else {
            clip?.getItemAt(0)?.coerceToText(this)?.toString()
        }
        if (text.isNullOrBlank()) {
            showToast("El portapapeles no contiene texto")
            return
        }
        repository.addLocalContent(localContentFactory.fromText(text))
        showToast("Texto añadido al hub")
    }

    override fun onPickImageRequested() {
        overlayController.dismiss()
        val intent = MainActivity.createIntent(this, launchImagePicker = true).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    override fun onPublishRequested() {
        publishSelected()
    }

    override fun onCopySelectedRequested() {
        val selectedId = repository.selectedLocalContentId.value ?: repository.latestLocalContent()?.contentId
        val selected = selectedId?.let(repository::getLocalContent)
        if (selected == null) {
            showToast("No hay contenido local seleccionado")
            return
        }
        showToast(receivedHandler.copyToSystemClipboard(selected))
    }

    override fun onReceivePeer(peer: PeerContent) {
        receivePeer(peer)
    }

    override fun onDestroy() {
        overlayController.dismiss()
        stopNetworkStack(clearPeers = true)
        releaseMulticastLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startNetworkStack() {
        val settings = settingsStore.current()
        if (!settings.configured) return

        httpServer.startIfNeeded()
        nsdController.startDiscovery()
        startShakeDetection()

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
        stopShakeDetection()
        nsdController.stop()
        httpServer.stop()
        if (clearPeers) {
            repository.clearPeers()
        }
    }

    private fun showOverlayIfPermitted() {
        if (Settings.canDrawOverlays(this)) {
            overlayController.show()
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
        multicastLock = wifiManager.createMulticastLock("FenixHubLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
        multicastLock = null
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
        const val ACTION_SHOW_OVERLAY = "com.fenixhub.mobile.action.SHOW_OVERLAY"
        const val ACTION_REFRESH_IDENTITY = "com.fenixhub.mobile.action.REFRESH_IDENTITY"
        private const val CHANNEL_ID = "fenixhub-service"
        private const val NOTIFICATION_ID = 3106

        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, FenixHubService::class.java)
            action?.let(intent::setAction)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
