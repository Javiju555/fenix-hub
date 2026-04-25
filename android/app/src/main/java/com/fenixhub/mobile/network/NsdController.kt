package com.fenixhub.mobile.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.fenixhub.mobile.data.ContentRepository
import com.fenixhub.mobile.data.SettingsStore
import com.fenixhub.mobile.model.AppSettings
import com.fenixhub.mobile.model.Announcement
import com.fenixhub.mobile.model.LocalContent
import com.fenixhub.mobile.model.PeerContent
import com.fenixhub.mobile.model.HubContentType
import com.fenixhub.mobile.model.SendMode
import com.fenixhub.mobile.util.AnnouncementCodec
import com.fenixhub.mobile.util.PreviewUtils
import com.fenixhub.mobile.util.TxtRecordCodec
import java.io.File

class NsdController(
    context: Context,
    private val repository: ContentRepository,
    private val settingsStore: SettingsStore,
) {
    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var discoveryEnabled = false
    private var refreshRequested = false
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val registrations = linkedMapOf<String, RegistrationHandle>()
    private val peerLastSeenAt = linkedMapOf<String, Long>()
    // Last port used for registration — needed to re-announce on refresh cycles.
    @Volatile private var currentPort: Int = 0

    // Android 12+ only allows one resolveService() at a time.
    // Queue pending resolves and drain one-by-one after each completes.
    private var resolveInProgress = false
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private val refreshRunnable = Runnable {
        if (!discoveryEnabled) return@Runnable
        pruneExpiredPeers()
        if (discoveryListener == null) {
            startDiscoveryInternal()
        } else {
            refreshRequested = true
            stopDiscoveryInternal()
        }
        // Re-register all services so devices that started late receive a fresh
        // mDNS announcement. Android's NSD responder does not reply to PTR queries
        // for services that have been registered for a while, so periodic
        // re-registration is required for late-joining peers to discover us.
        reannounceAll()
        scheduleRefresh()
    }

    fun startDiscovery() {
        if (discoveryEnabled) return
        discoveryEnabled = true
        startDiscoveryInternal()
        scheduleRefresh()
    }

    fun syncPublishedContent(items: List<LocalContent>, port: Int) {
        currentPort = port
        val settings = settingsStore.current()
        if (!settings.configured) return

        val desiredIds = items.map { it.contentId }.toSet()
        val obsoleteIds = registrations.keys.filterNot(desiredIds::contains)
        obsoleteIds.forEach(::unregisterInternal)

        items.forEach { item ->
            val payload = payloadForAnnouncement(item, settings, port)
            val existing = registrations[item.contentId]
            if (existing?.payload == payload) {
                return@forEach
            }
            unregisterInternal(item.contentId)
            register(item.contentId, payload, port)
        }
    }

    /** Force re-register all services to trigger fresh mDNS announcements. */
    private fun reannounceAll() {
        val port = currentPort
        if (port == 0 || registrations.isEmpty()) return
        Log.d(TAG, "Re-announcing ${registrations.size} service(s) for late-joiner discovery")
        registrations.keys.toList().forEach { contentId ->
            val handle = registrations[contentId] ?: return@forEach
            runCatching { nsdManager.unregisterService(handle.listener) }
            registrations.remove(contentId)
            register(contentId, handle.payload, port)
        }
    }

    fun stop() {
        discoveryEnabled = false
        refreshRequested = false
        mainHandler.removeCallbacks(refreshRunnable)
        stopDiscoveryInternal()
        resolveQueue.clear()
        resolveInProgress = false
        registrations.keys.toList().forEach(::unregisterInternal)
        peerLastSeenAt.clear()
        repository.clearPeers()
    }

    private fun startDiscoveryInternal() {
        if (discoveryListener != null) return

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery start failed: $errorCode")
                stopDiscoveryInternal()
                scheduleRetry()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery stop failed: $errorCode")
                stopDiscoveryInternal()
                if (discoveryEnabled && refreshRequested) {
                    refreshRequested = false
                    scheduleRetry()
                }
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) {
                if (discoveryEnabled && refreshRequested) {
                    refreshRequested = false
                    startDiscoveryInternal()
                }
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(
                    TAG,
                    "Service found candidate: ${serviceInfo.serviceName} type='${serviceInfo.serviceType}'",
                )
                if (!isExpectedServiceType(serviceInfo.serviceType)) return
                if (registrations.values.any { it.serviceName == serviceInfo.serviceName }) return
                Log.d(TAG, "Service found: ${serviceInfo.serviceName} type='${serviceInfo.serviceType}'")
                mainHandler.post { enqueueResolve(serviceInfo) }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val contentId = contentIdFromServiceName(serviceInfo.serviceName) ?: return
                mainHandler.post {
                    peerLastSeenAt.remove(contentId)
                    repository.removePeer(contentId)
                }
            }
        }

        discoveryListener = listener
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun register(contentId: String, payload: String, port: Int) {
        val serviceInfo = runCatching {
            NsdServiceInfo().apply {
                serviceName = "fenixhub-$contentId"
                serviceType = SERVICE_TYPE
                setPort(port)
                TxtRecordCodec.encode(payload).forEach { (key, value) ->
                    setAttribute(key, value)
                }
            }
        }.getOrElse { error ->
            Log.e(TAG, "Cannot encode NSD payload for $contentId", error)
            return
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Registration failed for ${serviceInfo.serviceName}: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Unregistration failed for ${serviceInfo.serviceName}: $errorCode")
            }
        }

        registrations[contentId] = RegistrationHandle(
            serviceName = serviceInfo.serviceName,
            payload = payload,
            port = port,
            listener = listener,
        )
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun stopDiscoveryInternal() {
        val listener = discoveryListener ?: return
        runCatching { nsdManager.stopServiceDiscovery(listener) }
        discoveryListener = null
    }

    private fun unregisterInternal(contentId: String) {
        val handle = registrations.remove(contentId) ?: return
        runCatching { nsdManager.unregisterService(handle.listener) }
    }

    private fun contentIdFromServiceName(serviceName: String): String? {
        return serviceName.removePrefix("fenixhub-").takeIf { it.isNotBlank() }
    }

    private fun isExpectedServiceType(rawType: String?): Boolean {
        val type = rawType.orEmpty().trim().trim('.')
        val expected = SERVICE_TYPE.trim('.')
        return type == expected ||
            type.startsWith("$expected.") ||
            type.endsWith(".$expected")
    }

    private data class RegistrationHandle(
        val serviceName: String,
        val payload: String,
        val port: Int,
        val listener: NsdManager.RegistrationListener,
    )

    private fun payloadForAnnouncement(
        item: LocalContent,
        settings: AppSettings,
        port: Int,
    ): String {
        var announcement = Announcement(
            protocolVersion = PROTOCOL_VERSION,
            groupId = settings.groupId,
            contentId = item.contentId,
            deviceName = settings.deviceName,
            preview = previewForAnnouncement(item),
            contentType = item.contentType,
            sizeBytes = item.sizeBytes,
            fileName = item.fileName,
            mimeType = item.mimeType,
            sendMode = item.sendMode,
            createdAt = item.createdAt,
            port = port,
        )
        var payload = AnnouncementCodec.encode(announcement)
        if (payload.encodedSize() <= MAX_ANNOUNCEMENT_BYTES) {
            return payload
        }

        val fallbackPreview = fallbackPreview(announcement)
        if (announcement.preview != fallbackPreview) {
            announcement = announcement.copy(preview = fallbackPreview)
            payload = AnnouncementCodec.encode(announcement)
        }
        if (payload.encodedSize() <= MAX_ANNOUNCEMENT_BYTES) {
            return payload
        }

        announcement.fileName?.takeIf { it.length > MAX_ANNOUNCEMENT_FILE_NAME_CHARS }?.let { fileName ->
            announcement = announcement.copy(fileName = fileName.take(MAX_ANNOUNCEMENT_FILE_NAME_CHARS))
            payload = AnnouncementCodec.encode(announcement)
        }
        if (payload.encodedSize() <= MAX_ANNOUNCEMENT_BYTES) {
            return payload
        }

        if (announcement.mimeType != null) {
            announcement = announcement.copy(mimeType = null)
            payload = AnnouncementCodec.encode(announcement)
        }
        if (payload.encodedSize() <= MAX_ANNOUNCEMENT_BYTES) {
            return payload
        }

        var preview = announcement.preview
        while (payload.encodedSize() > MAX_ANNOUNCEMENT_BYTES && preview.length > MIN_ANNOUNCEMENT_PREVIEW_CHARS) {
            preview = preview.take((preview.length - ANNOUNCEMENT_PREVIEW_TRIM_STEP).coerceAtLeast(MIN_ANNOUNCEMENT_PREVIEW_CHARS))
            announcement = announcement.copy(preview = preview)
            payload = AnnouncementCodec.encode(announcement)
        }
        if (payload.encodedSize() <= MAX_ANNOUNCEMENT_BYTES) {
            return payload
        }

        if (announcement.fileName != null) {
            announcement = announcement.copy(fileName = null)
            payload = AnnouncementCodec.encode(announcement)
        }
        if (payload.encodedSize() <= MAX_ANNOUNCEMENT_BYTES) {
            return payload
        }

        announcement = announcement.copy(preview = compactKindLabel(announcement.contentType))
        return AnnouncementCodec.encode(announcement)
    }

    private fun fallbackPreview(announcement: Announcement): String {
        return when (announcement.contentType) {
            HubContentType.TEXT -> announcement.preview.take(MAX_ANNOUNCEMENT_FILE_NAME_CHARS)
            HubContentType.IMAGE -> announcement.fileName
                ?.let { "Imagen: ${it.take(48)}" }
                ?: "Imagen lista para descargar"
            HubContentType.FILE -> announcement.fileName
                ?.let { "Archivo: ${it.take(48)}" }
                ?: "Archivo listo para descargar"
        }
    }

    private fun compactKindLabel(contentType: HubContentType): String {
        return when (contentType) {
            HubContentType.TEXT -> "Texto"
            HubContentType.IMAGE -> "Imagen"
            HubContentType.FILE -> "Archivo"
        }
    }

    private fun scheduleRefresh() {
        mainHandler.removeCallbacks(refreshRunnable)
        if (discoveryEnabled) {
            mainHandler.postDelayed(refreshRunnable, DISCOVERY_REFRESH_MS)
        }
    }

    private fun scheduleRetry() {
        mainHandler.postDelayed(
            {
                if (discoveryEnabled && discoveryListener == null) {
                    startDiscoveryInternal()
                }
            },
            DISCOVERY_RETRY_MS,
        )
    }

    private fun pruneExpiredPeers() {
        val now = SystemClock.elapsedRealtime()
        val expiredIds = peerLastSeenAt
            .filterValues { lastSeen -> now - lastSeen > PEER_EXPIRY_MS }
            .keys
            .toList()

        expiredIds.forEach { contentId ->
            peerLastSeenAt.remove(contentId)
            repository.removePeer(contentId)
        }
    }

    // Must be called on mainHandler thread.
    private fun enqueueResolve(serviceInfo: NsdServiceInfo) {
        resolveQueue.addLast(serviceInfo)
        if (!resolveInProgress) drainResolveQueue()
    }

    private fun drainResolveQueue() {
        val serviceInfo = resolveQueue.removeFirstOrNull() ?: run {
            resolveInProgress = false
            return
        }
        resolveInProgress = true
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.d(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                mainHandler.post { drainResolveQueue() }
            }

            override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                val raw = TxtRecordCodec.decode(resolvedServiceInfo.attributes)
                val announcement = raw?.let { AnnouncementCodec.decode(it) }
                if (announcement != null) {
                    val settings = settingsStore.current()
                    val visibleToCurrentDevice = settings.configured &&
                        announcement.groupId == settings.groupId &&
                        !settingsStore.isIgnoredPeerContent(announcement.contentId) &&
                        !(announcement.deviceName == settings.deviceName &&
                            repository.getLocalContent(announcement.contentId) != null) &&
                        !(announcement.sendMode is SendMode.Direct &&
                            announcement.sendMode.targetDevice != settings.deviceName)

                    val host = resolvedServiceInfo.host?.hostAddress?.substringBefore('%')
                    mainHandler.post {
                        if (visibleToCurrentDevice && host != null) {
                            peerLastSeenAt[announcement.contentId] = SystemClock.elapsedRealtime()
                            repository.upsertPeer(PeerContent(
                                peerIp = host,
                                port = resolvedServiceInfo.port,
                                announcement = announcement,
                            ))
                        } else {
                            // Resolve succeeded but this item is no longer visible for this device.
                            // Remove stale UI entries immediately instead of waiting for expiry.
                            peerLastSeenAt.remove(announcement.contentId)
                            repository.removePeer(announcement.contentId)
                        }
                    }
                }
                mainHandler.post { drainResolveQueue() }
            }
        })
    }

    private fun String.encodedSize(): Int = toByteArray(Charsets.UTF_8).size

    private companion object {
        const val TAG = "FenixHubNsd"
        const val SERVICE_TYPE = "_fenixhub._tcp."
        const val PROTOCOL_VERSION = 3
        const val DISCOVERY_REFRESH_MS = 15_000L
        const val DISCOVERY_RETRY_MS = 3_000L
        const val PEER_EXPIRY_MS = 20_000L
        const val MAX_ANNOUNCEMENT_BYTES = 1_000
        const val MAX_ANNOUNCEMENT_FILE_NAME_CHARS = 80
        const val MIN_ANNOUNCEMENT_PREVIEW_CHARS = 24
        const val ANNOUNCEMENT_PREVIEW_TRIM_STEP = 8
    }

    private fun previewForAnnouncement(item: LocalContent): String {
        if (item.contentType != HubContentType.IMAGE) {
            return item.preview
        }
        return item.fileName?.let { "Imagen: ${it.take(48)}" } ?: "Imagen lista para descargar"
    }
}
