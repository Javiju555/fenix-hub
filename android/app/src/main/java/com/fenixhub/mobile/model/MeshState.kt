package com.fenixhub.mobile.model

enum class MeshRole {
    NONE,
    HOST,
    DEVICE,
}

enum class MeshStatus {
    IDLE,
    DISCOVERING,  // BLE advertising active, lobby open
    PENDING,      // device in lobby, waiting for host to create mesh
    FORMING,      // mesh creation in progress (P2P group + key burst)
    ACTIVE,       // mesh active, modal open (BLE advertising ON for new invites)
    ACTIVE_GHOST, // mesh active, modal closed (BLE advertising OFF)
    TRANSFERRING,
    DESTROYING,
}

data class MeshState(
    val role: MeshRole = MeshRole.NONE,
    val status: MeshStatus = MeshStatus.IDLE,
    val meshId: String? = null,
    val passphrase: String? = null,
    val groupKey: ByteArray? = null,
    val pendingDevices: List<MeshDevice> = emptyList(),
    val activeDevices: List<MeshDevice> = emptyList(),
    val localContentPool: List<String> = emptyList(),
    val createdAt: Long? = null,
    val groupCreatedAt: Long? = null,
    val maxDevices: Int = MAX_MESH_DEVICES,
) {
    val isActive: Boolean get() = status == MeshStatus.ACTIVE ||
            status == MeshStatus.ACTIVE_GHOST ||
            status == MeshStatus.TRANSFERRING
    val canAddDevices: Boolean get() = status == MeshStatus.DISCOVERING && role == MeshRole.HOST
    val canLeave: Boolean get() = isActive
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
    data class GroupFormed(
        val meshId: String,
        val groupKeyHex: String,
        val hostIp: String,
        val port: Int,
    ) : MeshEvent()
    data object TransferStarted : MeshEvent()
    data class TransferFinished(val success: Boolean) : MeshEvent()
    data object MeshDestroyed : MeshEvent()
    data class Error(val message: String) : MeshEvent()
    data object MeshGhostModeOn : MeshEvent()
    data object MeshGhostModeOff : MeshEvent()
    data class CredentialsReceived(
        val groupId: String,
        val groupKeyHex: String,
        val hostP2pAddress: String,
        val hostIp: String?,
        val port: Int,
    ) : MeshEvent()
    data class QrInviteGenerated(val uri: String, val token: String) : MeshEvent()
}
