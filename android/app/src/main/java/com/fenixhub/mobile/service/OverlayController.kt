package com.fenixhub.mobile.service

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Publish
import androidx.compose.material.icons.outlined.DeleteOutline
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.fenixhub.mobile.data.ContentRepository
import com.fenixhub.mobile.model.HubContentType
import com.fenixhub.mobile.model.LocalContent
import com.fenixhub.mobile.model.PeerContent
import com.fenixhub.mobile.ui.theme.FenixHubTheme
import com.fenixhub.mobile.util.PreviewUtils

class OverlayController(
    private val context: Context,
    private val repository: ContentRepository,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onPasteTextRequested()
        fun onPickImageRequested()
        fun onPublishRequested()
        fun onCopySelectedRequested()
        fun onReceivePeer(peer: PeerContent)
    }

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var composeView: ComposeView? = null
    private var composeOwner: OverlayComposeOwner? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    fun show() {
        if (!Settings.canDrawOverlays(context) || composeView != null) return

        val owner = OverlayComposeOwner().also { it.onCreate() }
        val params = baseLayoutParams()
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                FenixHubTheme {
                    OverlayContent(
                        repository = repository,
                        onDismiss = ::dismiss,
                        onEngaged = { setFocusable(true) },
                        onPasteText = callbacks::onPasteTextRequested,
                        onPickImage = callbacks::onPickImageRequested,
                        onPublish = callbacks::onPublishRequested,
                        onCopySelected = callbacks::onCopySelectedRequested,
                        onReceivePeer = callbacks::onReceivePeer,
                    )
                }
            }
        }

        composeOwner = owner
        composeView = view
        layoutParams = params
        windowManager.addView(view, params)
    }

    fun dismiss() {
        composeView?.let { windowManager.removeView(it) }
        composeView = null
        composeOwner?.onDestroy()
        composeOwner = null
        layoutParams = null
    }

    private fun setFocusable(focusable: Boolean) {
        val params = layoutParams ?: return
        val desiredFlags = if (focusable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (desiredFlags == params.flags) return
        params.flags = desiredFlags
        composeView?.let { windowManager.updateViewLayout(it, params) }
    }

    private fun baseLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
        }
    }
}

@Composable
private fun OverlayContent(
    repository: ContentRepository,
    onDismiss: () -> Unit,
    onEngaged: () -> Unit,
    onPasteText: () -> Unit,
    onPickImage: () -> Unit,
    onPublish: () -> Unit,
    onCopySelected: () -> Unit,
    onReceivePeer: (PeerContent) -> Unit,
) {
    val peers by repository.peers.collectAsState()
    val localItems by repository.localContent.collectAsState()
    val selectedId by repository.selectedLocalContentId.collectAsState()

    var dragOffset by remember { mutableFloatStateOf(0f) }
    val trashScale by animateFloatAsState(
        targetValue = if (dragOffset > 180f) 1.2f else 1f,
        label = "trashScale",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.36f))
            .clickable(onClick = onDismiss),
    ) {
        AnimatedVisibility(
            visible = dragOffset > 24f,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = Color(0xFFB83A4B).copy(alpha = 0.92f),
                shadowElevation = 18.dp,
                modifier = Modifier.size((72f * trashScale).dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        tint = Color.White,
                    )
                }
            }
        }

        val overlayBackground = Modifier
            .align(Alignment.BottomCenter)
            .padding(horizontal = 16.dp, vertical = 20.dp)
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { onEngaged() },
                    onVerticalDrag = { _, dragAmount ->
                        dragOffset = (dragOffset + dragAmount).coerceAtLeast(0f)
                    },
                    onDragEnd = {
                        if (dragOffset > 220f) {
                            onDismiss()
                        }
                        dragOffset = 0f
                    },
                    onDragCancel = { dragOffset = 0f },
                )
            }

        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xEB1A1A2E)),
            elevation = CardDefaults.cardElevation(defaultElevation = 18.dp),
            modifier = overlayBackground.then(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Modifier
                        .height(308.dp)
                        .padding(bottom = dragOffset.dp)
                        .background(Color.Transparent)
                        .blurIfAvailable()
                } else {
                    Modifier
                        .height(308.dp)
                        .padding(bottom = dragOffset.dp)
                },
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "Contenido en la red",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        peers.forEach { peer ->
                            PeerCard(peer = peer, onReceive = { onEngaged(); onReceivePeer(peer) })
                        }
                        if (peers.isEmpty()) {
                            EmptyPeerCard()
                        }
                    }
                }

                Column {
                    localItems.firstOrNull { it.contentId == selectedId }?.let { selected ->
                        SelectedLocalContentBanner(selected)
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OverlayActionButton(
                            icon = Icons.Outlined.ContentPaste,
                            label = "Pegar texto",
                            modifier = Modifier.weight(1f),
                            onClick = { onEngaged(); onPasteText() },
                        )
                        OverlayActionButton(
                            icon = Icons.Outlined.Image,
                            label = "Archivo",
                            modifier = Modifier.weight(1f),
                            onClick = { onEngaged(); onPickImage() },
                        )
                        OverlayActionButton(
                            icon = Icons.Outlined.Publish,
                            label = "Publicar",
                            modifier = Modifier.weight(1f),
                            onClick = { onEngaged(); onPublish() },
                        )
                    }
                    if (localItems.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        OverlayActionButton(
                            icon = Icons.Outlined.Download,
                            label = "Copiar al sistema",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onEngaged(); onCopySelected() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PeerCard(peer: PeerContent, onReceive: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF11172B)),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
        modifier = Modifier.width(220.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = peerIcon(peer.announcement.contentType),
                    contentDescription = null,
                    tint = Color(0xFF5E8CF4),
                )
                Text(
                    text = peer.announcement.deviceName,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                )
            }
            PreviewBlock(peer)
            ElevatedButton(onClick = onReceive, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Download, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Recibir")
            }
        }
    }
}

@Composable
private fun EmptyPeerCard() {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF11172B)),
        modifier = Modifier.width(220.dp),
    ) {
        Box(
            modifier = Modifier
                .height(166.dp)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Sin anuncios disponibles",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFB8C1EC),
            )
        }
    }
}

@Composable
private fun SelectedLocalContentBanner(item: LocalContent) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF11172B),
        shadowElevation = 10.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Person,
                contentDescription = null,
                tint = Color(0xFF5E8CF4),
            )
            Column {
                Text(
                    text = if (item.isPublished) "Listo para compartir" else "Borrador local",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                )
                Text(
                    text = when (item.contentType) {
                        HubContentType.TEXT -> item.preview
                        HubContentType.IMAGE -> "Imagen preparada"
                        HubContentType.FILE -> item.fileName ?: item.preview
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB8C1EC),
                )
            }
        }
    }
}

@Composable
private fun PreviewBlock(peer: PeerContent) {
    val imageBytes = remember(peer.announcement.preview) {
        PreviewUtils.decodeImageDataUrl(peer.announcement.preview)
    }

    if (imageBytes != null) {
        val bitmap = remember(imageBytes) {
            android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        }
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(92.dp),
            )
            return
        }
    }

    Text(
        text = peer.announcement.preview,
        style = MaterialTheme.typography.bodyMedium,
        color = Color(0xFFDFE7FF),
        modifier = Modifier.height(92.dp),
    )
}

@Composable
private fun OverlayActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    ElevatedButton(onClick = onClick, modifier = modifier.height(52.dp)) {
        Icon(icon, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(label)
    }
}

private fun Modifier.blurIfAvailable(): Modifier {
    return graphicsLayer {
        renderEffect = RenderEffect
            .createBlurEffect(18f, 18f, Shader.TileMode.CLAMP)
            .asComposeRenderEffect()
    }
}

private fun peerIcon(type: HubContentType) = when (type) {
    HubContentType.TEXT -> Icons.Outlined.ContentPaste
    HubContentType.IMAGE -> Icons.Outlined.Image
    HubContentType.FILE -> Icons.Outlined.Description
}
