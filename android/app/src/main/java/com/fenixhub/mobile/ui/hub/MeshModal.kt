package com.fenixhub.mobile.ui.hub

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fenixhub.mobile.model.MeshDeviceStatus
import com.fenixhub.mobile.model.MeshRole
import com.fenixhub.mobile.model.MeshState
import com.fenixhub.mobile.model.MeshStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshModal(
    state: MeshState,
    onDismiss: () -> Unit,
    onStartHost: () -> Unit,
    onStartDevice: () -> Unit,
    onAccept: (String) -> Unit,
    onReject: (String) -> Unit,
    onCreateMesh: () -> Unit,
    onRequestJoin: (String) -> Unit,
    onKick: (String) -> Unit,
    onEndMesh: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        when {
            state.status == MeshStatus.IDLE -> MeshModeSelector(
                onStartHost = onStartHost,
                onStartDevice = onStartDevice,
            )
            state.role == MeshRole.HOST && state.status == MeshStatus.DISCOVERING ->
                MeshHostLobby(state, onAccept, onReject, onCreateMesh, onDismiss)
            state.role == MeshRole.HOST && state.isActive ->
                MeshHostManagement(state, onKick, onEndMesh, onDismiss)
            state.role == MeshRole.DEVICE && state.status == MeshStatus.DISCOVERING ->
                MeshDeviceScan(state, onRequestJoin, onDismiss)
            state.role == MeshRole.DEVICE && state.status == MeshStatus.PENDING ->
                MeshDeviceWaiting(state, onDismiss)
            state.role == MeshRole.DEVICE && state.isActive ->
                MeshDeviceConnected(state, onDismiss)
            else -> MeshModeSelector(onStartHost, onStartDevice)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun MeshModeSelector(onStartHost: () -> Unit, onStartDevice: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Nearby Mesh", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onStartHost, Modifier.fillMaxWidth()) {
            Text("Create mesh (Host)")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onStartDevice, Modifier.fillMaxWidth()) {
            Text("Join a mesh (Device)")
        }
    }
}

@Composable
private fun MeshHostLobby(
    state: MeshState,
    onAccept: (String) -> Unit,
    onReject: (String) -> Unit,
    onCreateMesh: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Lobby — Waiting for devices", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        val accepted = state.pendingDevices.filter { it.status == MeshDeviceStatus.CONNECTED }
        val pending  = state.pendingDevices.filter { it.status == MeshDeviceStatus.PENDING }

        if (pending.isNotEmpty()) {
            Text("Requests", style = MaterialTheme.typography.labelMedium)
            LazyColumn {
                items(pending, key = { it.id }) { device ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(device.name, Modifier.weight(1f))
                        TextButton(onClick = { onAccept(device.id) }) { Text("Accept") }
                        TextButton(onClick = { onReject(device.id) }) { Text("Reject") }
                    }
                }
            }
        }

        if (accepted.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("In lobby (${accepted.size})", style = MaterialTheme.typography.labelMedium)
            LazyColumn {
                items(accepted, key = { it.id }) { device ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(device.name, Modifier.weight(1f))
                        TextButton(onClick = { onReject(device.id) }) { Text("Remove") }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onCreateMesh,
            enabled = accepted.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Create mesh (${accepted.size} devices)") }

        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onCancel, Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

@Composable
private fun MeshHostManagement(
    state: MeshState,
    onKick: (String) -> Unit,
    onEndMesh: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Mesh active — ${state.activeDevices.size} connected",
            style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        LazyColumn {
            items(state.activeDevices, key = { it.id }) { device ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(device.name, Modifier.weight(1f))
                    TextButton(onClick = { onKick(device.id) }) { Text("Kick") }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = onEndMesh,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) { Text("End mesh") }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onClose, Modifier.fillMaxWidth()) { Text("Close") }
    }
}

@Composable
private fun MeshDeviceScan(
    state: MeshState,
    onRequestJoin: (String) -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Searching for nearby mesh hosts...", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        if (state.pendingDevices.isEmpty()) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        } else {
            LazyColumn {
                items(state.pendingDevices, key = { it.id }) { host ->
                    Card(
                        onClick = { onRequestJoin(host.id) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Text(host.name, Modifier.padding(16.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onCancel, Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

@Composable
private fun MeshDeviceWaiting(state: MeshState, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val hostName = state.activeDevices.firstOrNull()?.name
            ?: state.pendingDevices.firstOrNull()?.name ?: "host"
        Text("Waiting for $hostName to create mesh",
            style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onCancel) { Text("Leave lobby") }
    }
}

@Composable
private fun MeshDeviceConnected(state: MeshState, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Connected to mesh", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("You can now see and download the host's content",
            style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onClose) { Text("Close") }
    }
}
