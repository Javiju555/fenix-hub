package com.fenixhub.mobile.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.fenixhub.mobile.data.ContentRepository
import com.fenixhub.mobile.data.SettingsStore
import com.fenixhub.mobile.model.Announcement
import com.fenixhub.mobile.model.LocalContent
import com.fenixhub.mobile.model.PeerContent
import com.fenixhub.mobile.model.SendMode
import com.fenixhub.mobile.util.AnnouncementCodec
import com.fenixhub.mobile.util.TxtRecordCodec

class NsdController(
    context: Context,
    private val repository: ContentRepository,
    private val settingsStore: SettingsStore,
) {
    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val registrations = linkedMapOf<String, RegistrationHandle>()

    fun startDiscovery() {
        if (discoveryListener != null) return

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery start failed: $errorCode")
                stopDiscoveryInternal()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery stop failed: $errorCode")
                stopDiscoveryInternal()
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != SERVICE_TYPE) return
                if (registrations.values.any { it.serviceName == serviceInfo.serviceName }) return

                nsdManager.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.d(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                        }

                        override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                            val raw = TxtRecordCodec.decode(resolvedServiceInfo.attributes) ?: return
                            val announcement = AnnouncementCodec.decode(raw) ?: return
                            val settings = settingsStore.current()

                            if (!settings.configured || announcement.groupId != settings.groupId) return
                            if (announcement.deviceName == settings.deviceName &&
                                repository.getLocalContent(announcement.contentId) != null
                            ) {
                                return
                            }

                            if (announcement.sendMode is SendMode.Direct &&
                                announcement.sendMode.targetDevice != settings.deviceName
                            ) {
                                return
                            }

                            val host = resolvedServiceInfo.host?.hostAddress?.substringBefore('%') ?: return
                            mainHandler.post {
                                repository.upsertPeer(
                                    PeerContent(
                                        peerIp = host,
                                        port = resolvedServiceInfo.port,
                                        announcement = announcement,
                                    ),
                                )
                            }
                        }
                    },
                )
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val contentId = contentIdFromServiceName(serviceInfo.serviceName) ?: return
                mainHandler.post { repository.removePeer(contentId) }
            }
        }

        discoveryListener = listener
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun syncPublishedContent(items: List<LocalContent>, port: Int) {
        val settings = settingsStore.current()
        if (!settings.configured) return

        val desiredIds = items.map { it.contentId }.toSet()
        val obsoleteIds = registrations.keys.filterNot(desiredIds::contains)
        obsoleteIds.forEach(::unregisterInternal)

        items.forEach { item ->
            val announcement = Announcement(
                groupId = settings.groupId,
                contentId = item.contentId,
                deviceName = settings.deviceName,
                preview = item.preview,
                contentType = item.contentType,
                sizeBytes = item.sizeBytes,
                fileName = item.fileName,
                mimeType = item.mimeType,
                sendMode = item.sendMode,
                createdAt = item.createdAt,
                port = port,
            )
            val payload = AnnouncementCodec.encode(announcement)
            val existing = registrations[item.contentId]
            if (existing?.payload == payload) {
                return@forEach
            }
            unregisterInternal(item.contentId)
            register(item.contentId, payload, port)
        }
    }

    fun stop() {
        stopDiscoveryInternal()
        registrations.keys.toList().forEach(::unregisterInternal)
        repository.clearPeers()
    }

    private fun register(contentId: String, payload: String, port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "fenixhub-$contentId"
            serviceType = SERVICE_TYPE
            setPort(port)
            TxtRecordCodec.encode(payload).forEach { (key, value) ->
                setAttribute(key, value)
            }
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

    private data class RegistrationHandle(
        val serviceName: String,
        val payload: String,
        val listener: NsdManager.RegistrationListener,
    )

    private companion object {
        const val TAG = "FenixHubNsd"
        const val SERVICE_TYPE = "_fenixhub._tcp."
    }
}
