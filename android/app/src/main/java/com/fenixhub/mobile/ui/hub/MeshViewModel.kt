package com.fenixhub.mobile.ui.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fenixhub.mobile.model.MeshState
import com.fenixhub.mobile.model.MeshStatus
import com.fenixhub.mobile.service.MeshManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class MeshViewModel(private val meshManager: MeshManager) : ViewModel() {

    val state: StateFlow<MeshState> = meshManager.state.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        MeshState(),
    )

    fun startAsHost(contentIds: List<String>) =
        meshManager.dispatch(MeshManager.MeshCommand.StartAsHost(contentIds))

    fun startAsDevice() =
        meshManager.dispatch(MeshManager.MeshCommand.StartAsDevice)

    fun acceptDevice(deviceId: String) =
        meshManager.dispatch(MeshManager.MeshCommand.AcceptDevice(deviceId))

    fun rejectDevice(deviceId: String) =
        meshManager.dispatch(MeshManager.MeshCommand.RejectDevice(deviceId))

    fun createMesh() =
        meshManager.dispatch(MeshManager.MeshCommand.CreateMesh)

    fun requestJoin(hostId: String) =
        meshManager.dispatch(MeshManager.MeshCommand.RequestJoin(hostId, ""))

    fun openModal() =
        meshManager.dispatch(MeshManager.MeshCommand.ModalOpened)

    fun closeModal() =
        meshManager.dispatch(MeshManager.MeshCommand.ModalClosed)

    fun kickDevice(deviceId: String) =
        meshManager.dispatch(MeshManager.MeshCommand.ExpelDevice(deviceId))

    fun endMesh() =
        meshManager.dispatch(MeshManager.MeshCommand.LeaveMesh)

    fun cancelDiscovery() =
        meshManager.dispatch(MeshManager.MeshCommand.CancelDiscovery)

    val isMeshActive: Boolean get() = state.value.isActive
}
