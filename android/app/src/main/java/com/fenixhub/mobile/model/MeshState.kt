package com.fenixhub.mobile.model

enum class MeshRole {
    NONE,
    HOST,
    DEVICE,
}

enum class MeshStatus {
    IDLE,         // no mesh
    DISCOVERING,  // BLE active, modal open
    PENDING,      // device accepted, waiting modal close
    FORMING,      // modal closed, passphrase shared
    ACTIVE,       // WiFi Direct group active
    TRANSFERRING, // host publishing
    DESTROYING,   // closing mesh
}

data class MeshState(
    val role: MeshRole = MeshRole.NONE,
    val status: MeshStatus = MeshStatus.IDLE,
    val meshId: String? = null,
    val passphrase: String? = null,
    val pendingDevices: List<MeshDevice> = emptyList(),
    val activeDevices: List<MeshDevice> = emptyList(),
    val localContentPool: List<String> = emptyList(),  // contentIds selected for this mesh
    val createdAt: Long? = null,
    val groupCreatedAt: Long? = null,
    val maxDevices: Int = MAX_MESH_DEVICES,
) {
    val isActive: Boolean get() = status == MeshStatus.ACTIVE || status == MeshStatus.TRANSFERRING
    val canAddDevices: Boolean get() = status == MeshStatus.DISCOVERING && role == MeshRole.HOST
    val canLeave: Boolean get() = status == MeshStatus.ACTIVE || status == MeshStatus.TRANSFERRING
    val pendingCount: Int get() = pendingDevices.size

    companion object {
        const val MAX_MESH_DEVICES = 5
    }
}

sealed class MeshEvent {
    data object DiscoveryStarted : MeshEvent()
    data class DeviceDiscovered(val device: MeshDevice) : MeshEvent()
    data class DeviceAccepted(val deviceId: String) : MeshEvent()
    data class DeviceRejected(val deviceId: String) : MeshEvent()
    data class DeviceJoined(val device: MeshDevice) : MeshEvent()
    data class DeviceLeft(val deviceId: String) : MeshEvent()
    data class DeviceExpelled(val deviceId: String) : MeshEvent()
    data class GroupFormed(val meshId: String) : MeshEvent()
    data object TransferStarted : MeshEvent()
    data class TransferFinished(val success: Boolean) : MeshEvent()
    data object MeshDestroyed : MeshEvent()
    data class Error(val message: String) : MeshEvent()
}