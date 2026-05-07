package com.fenixhub.mobile.model

import android.bluetooth.BluetoothDevice

data class MeshDevice(
    val id: String,
    val name: String,
    val rssi: Int = 0,
    val status: MeshDeviceStatus = MeshDeviceStatus.PENDING,
    val joinedAt: Long? = null,
    val meshId: String? = null,
    val gattDevice: BluetoothDevice? = null,
    val pubKeyBytes: ByteArray? = null,
)

enum class MeshDeviceStatus {
    PENDING,      // waiting to accept / being added
    CONNECTED,    // in active mesh
    EXPELLED,     // removed by host
    LEFT,         // voluntarily left
}
