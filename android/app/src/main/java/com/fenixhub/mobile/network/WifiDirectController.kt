package com.fenixhub.mobile.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.fenixhub.mobile.util.TransportHardwareInspector

data class WifiDirectPeer(
    val deviceAddress: String,
    val deviceName: String,
    val status: Int,
    val lastSeenElapsedMs: Long,
)

data class WifiDirectRuntimeStatus(
    val discovering: Boolean,
    val lastError: String?,
)

class WifiDirectController(context: Context) {
    private val appContext = context.applicationContext
    private val wifiP2pManager = appContext.getSystemService(WifiP2pManager::class.java)
    private val channel = wifiP2pManager?.initialize(appContext, Looper.getMainLooper(), null)

    private val peers = LinkedHashMap<String, WifiDirectPeer>()
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var discovering = false

    @Volatile
    private var receiverRegistered = false

    @Volatile
    private var lastError: String? = null

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!discovering) return
            discoverPeers()
            pruneStalePeers()
            mainHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    fun ensureRunning() {
        val hw = TransportHardwareInspector.snapshot(appContext).wifiDirect
        if (!hw.supported || !hw.enabled || !hw.permissionsReady || wifiP2pManager == null || channel == null) {
            stop()
            return
        }

        registerReceiverIfNeeded()
        discovering = true
        discoverPeers()

        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
    }

    fun stop() {
        mainHandler.removeCallbacks(refreshRunnable)
        discovering = false

        runCatching {
            if (wifiP2pManager != null && channel != null) {
                wifiP2pManager.stopPeerDiscovery(channel, null)
            }
        }

        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(wifiDirectReceiver) }
            receiverRegistered = false
        }

        synchronized(lock) { peers.clear() }
    }

    fun snapshotPeers(): List<WifiDirectPeer> {
        pruneStalePeers()
        synchronized(lock) {
            return peers.values
                .sortedByDescending { it.lastSeenElapsedMs }
                .toList()
        }
    }

    fun status(): WifiDirectRuntimeStatus {
        return WifiDirectRuntimeStatus(
            discovering = discovering,
            lastError = lastError,
        )
    }

    private fun discoverPeers() {
        val manager = wifiP2pManager ?: return
        val currentChannel = channel ?: return

        manager.discoverPeers(currentChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                requestPeers()
            }

            override fun onFailure(reason: Int) {
                lastError = "discover_failed_$reason"
                Log.w(TAG, "Wi-Fi Direct discover failed: $reason")
            }
        })
    }

    private fun requestPeers() {
        val manager = wifiP2pManager ?: return
        val currentChannel = channel ?: return

        manager.requestPeers(currentChannel) { peerList ->
            val now = SystemClock.elapsedRealtime()
            synchronized(lock) {
                peerList.deviceList.forEach { device ->
                    peers[device.deviceAddress] = device.toModel(now)
                }
            }
        }
    }

    private fun pruneStalePeers() {
        val cutoff = SystemClock.elapsedRealtime() - PEER_TTL_MS
        synchronized(lock) {
            val stale = peers.filterValues { it.lastSeenElapsedMs < cutoff }.keys
            stale.forEach(peers::remove)
        }
    }

    private fun registerReceiverIfNeeded() {
        if (receiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(wifiDirectReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(wifiDirectReceiver, filter)
        }
        receiverRegistered = true
    }

    private val wifiDirectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestPeers()
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED,
                    ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (!enabled) {
                        synchronized(lock) { peers.clear() }
                    }
                }
            }
        }
    }

    private fun WifiP2pDevice.toModel(now: Long): WifiDirectPeer {
        return WifiDirectPeer(
            deviceAddress = deviceAddress,
            deviceName = if (deviceName.isNullOrBlank()) "Peer Wi-Fi Direct" else deviceName,
            status = status,
            lastSeenElapsedMs = now,
        )
    }

    private companion object {
        const val TAG = "WifiDirectController"
        const val REFRESH_INTERVAL_MS = 12_000L
        const val PEER_TTL_MS = 40_000L
    }
}