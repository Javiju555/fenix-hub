package com.fenixhub.mobile.service

import android.content.Context
import android.util.Log

class MeshBleBridge(
    private val context: Context,
    private val meshManager: MeshManager,
    private var bleExchange: MeshBleExchange? = null,
) {
    fun startMeshDiscovery(meshId: String? = null) {
        bleExchange?.stop()
        bleExchange = MeshBleExchange(
            context = context,
            meshId = meshId ?: "MESH",
            passphrase = "",
            deviceName = android.os.Build.MODEL,
            isHost = meshId == null,
            listener = object : MeshPassphraseListener {
                override fun onPassphraseReceived(exchange: MeshPassphraseExchange) {
                    meshManager.onBleDeviceFound(
                        deviceId = exchange.hostDeviceId,
                        deviceName = exchange.hostName,
                        rssi = exchange.rssi,
                        meshId = exchange.meshId,
                    )
                }
            },
            discoveryMode = true,
        )
        bleExchange.start()
        Log.d(TAG, "Mesh BLE discovery started (meshId=${meshId ?: "auto"})")
    }

    fun stopMeshDiscovery() {
        bleExchange?.stop()
        bleExchange = null
        Log.d(TAG, "Mesh BLE discovery stopped")
    }
}

private const val TAG = "MeshBleBridge"