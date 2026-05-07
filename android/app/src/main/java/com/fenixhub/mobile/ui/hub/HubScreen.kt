package com.fenixhub.mobile.ui.hub

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Publish
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fenixhub.mobile.model.HubContentType
import com.fenixhub.mobile.model.LocalContent
import com.fenixhub.mobile.model.PeerContent
import com.fenixhub.mobile.model.MeshStatus
import com.fenixhub.mobile.ui.theme.HubBlue
import com.fenixhub.mobile.ui.theme.HubNight
import com.fenixhub.mobile.ui.theme.HubPanel
import com.fenixhub.mobile.ui.theme.HubPanelSoft
import com.fenixhub.mobile.ui.theme.HubTextSoft
import com.fenixhub.mobile.util.PreviewUtils

@Composable
fun HubScreen(
    uiState: HubUiState,
    meshViewModel: MeshViewModel,
    overlayPermissionGranted: Boolean,
    notificationPermissionGranted: Boolean,
    onOpenOverlay: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onPasteText: () -> Unit,
    onPickImage: () -> Unit,
    onPublishSelected: () -> Unit,
    onCopySelected: () -> Unit,
    onSelectLocalContent: (String) -> Unit,
    onReceivePeer: (PeerContent) -> Unit,
) {
    var showMeshModal by remember { mutableStateOf(false) }
    val meshState by meshViewModel.state.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(HubNight, Color(0xFF10172B), Color(0xFF131B34)),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "FenixHub",
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AssistChip(
                    onClick = {},
                    label = { Text(uiState.settings.deviceName.ifBlank { "Dispositivo" }) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Person, contentDescription = null)
                    },
                    colors = AssistChipDefaults.assistChipColors(containerColor = HubPanelSoft),
                )
                AssistChip(
                    onClick = {},
                    label = { Text(uiState.settings.groupId.ifBlank { "Sin group_id" }) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = HubPanelSoft),
                )
            }
            PermissionBanner(
                overlayPermissionGranted = overlayPermissionGranted,
                notificationPermissionGranted = notificationPermissionGranted,
                onRequestOverlayPermission = onRequestOverlayPermission,
                onRequestNotificationPermission = onRequestNotificationPermission,
            )
            ComposerCard(
                onOpenOverlay = onOpenOverlay,
                onPasteText = onPasteText,
                onPickImage = onPickImage,
                onPublish = onPublishSelected,
                onCopySelected = onCopySelected,
            )
            SectionTitle("Tu contenido")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (uiState.localContent.isEmpty()) {
                    EmptyCard("Todavia no has añadido texto ni imagenes.")
                }
                uiState.localContent.forEach { item ->
                    LocalContentCard(
                        item = item,
                        selected = item.contentId == uiState.selectedLocalContentId,
                        onSelect = { onSelectLocalContent(item.contentId) },
                    )
                }
            }
            SectionTitle("Peers descubiertos")
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (uiState.peers.isEmpty()) {
                    EmptyCard("No hay anuncios mDNS activos en este grupo.")
                }
                uiState.peers.forEach { peer ->
                    PeerContentCard(peer = peer, onReceive = { onReceivePeer(peer) })
                }
            }
        }
    }

    if (showMeshModal) {
        val publishedContentIds = uiState.localContent
            .filter { it.isPublished }
            .map { it.contentId }
        MeshModal(
            state            = meshState,
            onDismiss        = { showMeshModal = false },
            onStartHost      = { meshViewModel.startAsHost(publishedContentIds) },
            onStartDevice    = { meshViewModel.startAsDevice() },
            onAccept         = { meshViewModel.acceptDevice(it) },
            onReject         = { meshViewModel.rejectDevice(it) },
            onCreateMesh     = { meshViewModel.createMesh() },
            onRequestJoin    = { meshViewModel.requestJoin(it) },
            onKick           = { meshViewModel.kickDevice(it) },
            onEndMesh        = {
                meshViewModel.endMesh()
                showMeshModal = false
            },
        )
    }
}

@Composable
private fun PermissionBanner(
    overlayPermissionGranted: Boolean,
    notificationPermissionGranted: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
) {
    if (overlayPermissionGranted && notificationPermissionGranted) return

    Card(
        colors = CardDefaults.cardColors(containerColor = HubPanel),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Permisos", color = Color.White, style = MaterialTheme.typography.titleMedium)
            if (!overlayPermissionGranted) {
                ElevatedButton(onClick = onRequestOverlayPermission) {
                    Text("Permitir overlay")
                }
            }
            if (!notificationPermissionGranted) {
                ElevatedButton(onClick = onRequestNotificationPermission) {
                    Icon(Icons.Outlined.Notifications, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Permitir notificaciones")
                }
            }
        }
    }
}

@Composable
private fun ComposerCard(
    onOpenOverlay: () -> Unit,
    onPasteText: () -> Unit,
    onPickImage: () -> Unit,
    onPublish: () -> Unit,
    onCopySelected: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = HubPanel),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Panel rapido", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ElevatedButton(onClick = onPasteText, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.ContentPaste, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Pegar")
                }
                ElevatedButton(onClick = onPickImage, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Image, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Archivo")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ElevatedButton(onClick = onPublish, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Publish, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Publicar")
                }
                ElevatedButton(onClick = onCopySelected, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.SaveAlt, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copiar")
                }
            }
            ElevatedButton(onClick = onOpenOverlay, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Overlay")
            }
            ElevatedButton(
                onClick = { showMeshModal = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (meshState.isActive) "Mesh ●" else "Mesh")
            }
        }
    }
}

@Composable
private fun LocalContentCard(
    item: LocalContent,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF203260) else HubPanel),
        modifier = Modifier
            .width(220.dp)
            .clickable(onClick = onSelect),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(localIcon(item.contentType), contentDescription = null, tint = HubBlue)
                Text(
                    text = if (item.isPublished) "Publicado" else "Borrador",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                text = when (item.contentType) {
                    HubContentType.TEXT -> item.preview
                    HubContentType.IMAGE -> "Vista previa de imagen"
                    HubContentType.FILE -> item.fileName ?: item.preview
                },
                color = HubTextSoft,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "${item.sizeBytes} bytes",
                color = HubTextSoft,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun PeerContentCard(
    peer: PeerContent,
    onReceive: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = HubPanel),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(localIcon(peer.announcement.contentType), contentDescription = null, tint = HubBlue)
                Text(peer.announcement.deviceName, color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
            val imageBytes = remember(peer.announcement.preview) {
                PreviewUtils.decodeImageDataUrl(peer.announcement.preview)
            }
            if (imageBytes != null) {
                val bitmap = remember(imageBytes) {
                    BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                    )
                }
            } else {
                Text(
                    text = peer.announcement.preview,
                    color = HubTextSoft,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${peer.peerIp}:${peer.port}",
                    color = HubTextSoft,
                    style = MaterialTheme.typography.labelMedium,
                )
                ElevatedButton(onClick = onReceive) {
                    Icon(Icons.Outlined.SaveAlt, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Recibir")
                }
            }
        }
    }
}

@Composable
private fun EmptyCard(text: String) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = HubPanel,
        shadowElevation = 10.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            color = HubTextSoft,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = Color.White,
        fontWeight = FontWeight.Medium,
    )
}

private fun localIcon(type: HubContentType) = when (type) {
    HubContentType.TEXT -> Icons.Outlined.ContentPaste
    HubContentType.IMAGE -> Icons.Outlined.Image
    HubContentType.FILE -> Icons.Outlined.Description
}
