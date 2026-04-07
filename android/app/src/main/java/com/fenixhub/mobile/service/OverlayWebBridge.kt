package com.fenixhub.mobile.service

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.fenixhub.mobile.data.ContentRepository
import com.fenixhub.mobile.data.ReceivedContentHandler
import com.fenixhub.mobile.data.SettingsStore
import com.fenixhub.mobile.data.TempClipboardStore
import com.fenixhub.mobile.model.Announcement
import com.fenixhub.mobile.model.LocalContent
import com.fenixhub.mobile.model.PeerContent
import com.fenixhub.mobile.model.SendMode
import com.fenixhub.mobile.network.FenixHttpClient
import com.fenixhub.mobile.util.LocalContentFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class OverlayWebBridge(
    private val context: Context,
    private val repository: ContentRepository,
    private val settingsStore: SettingsStore,
    private val localContentFactory: LocalContentFactory,
    private val tempClipboardStore: TempClipboardStore,
    private val receivedContentHandler: ReceivedContentHandler,
    private val httpClient: FenixHttpClient,
    private val onOpenMainApp: () -> Unit,
    private val onMinimizeOverlay: () -> Unit,
    private val onExpandOverlay: () -> Unit,
    private val onCloseOverlay: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var webView: WebView? = null

    fun attach(webView: WebView) {
        this.webView = webView
    }

    fun destroy() {
        scope.cancel()
        webView = null
    }

    @JavascriptInterface
    fun postMessage(rawRequest: String) {
        val request = runCatching { JSONObject(rawRequest) }.getOrElse { error ->
            dispatchError("", error.message ?: "Peticion invalida")
            return
        }
        val requestId = request.optString("id")
        val command = request.optString("cmd")
        val args = request.optJSONObject("args")

        scope.launch {
            runCatching { handle(command, args) }
                .onSuccess { dispatchSuccess(requestId, it) }
                .onFailure { dispatchError(requestId, it.message ?: "Operacion fallida") }
        }
    }

    private suspend fun handle(command: String, args: JSONObject?): String {
        return when (command) {
            "get_local_content" -> JSONArray().apply {
                repository.localContent.value.forEach { put(localContentJson(it)) }
            }.toString()
            "get_peers" -> JSONArray().apply {
                repository.peers.value.forEach { put(peerJson(it)) }
            }.toString()
            "paste_clipboard_text" -> pasteClipboardText().toString()
            "publish_content" -> {
                publishContent(requirePayloadArgs(args))
                "null"
            }
            "unpublish_content" -> {
                unpublishContent(args)
                "null"
            }
            "remove_content" -> {
                removeContent(args)
                "null"
            }
            "copy_local_content" -> JSONObject.quote(copyLocalContent(args))
            "pull_peer_content" -> pullPeerContent(args).toString()
            "copy_peer_content" -> JSONObject.quote(copyPeerContent(args))
            "ignore_peer_content" -> {
                ignorePeerContent(args)
                "null"
            }
            "open_full_app" -> {
                onOpenMainApp()
                "null"
            }
            "minimize_overlay" -> {
                Log.i(TAG, "Overlay command: minimize_overlay")
                onMinimizeOverlay()
                "true"
            }
            "expand_overlay" -> {
                Log.i(TAG, "Overlay command: expand_overlay")
                onExpandOverlay()
                "true"
            }
            "close_overlay" -> {
                Log.i(TAG, "Overlay command: close_overlay")
                onCloseOverlay()
                "true"
            }
            else -> error("Comando no soportado: $command")
        }
    }

    private suspend fun pasteClipboardText(): JSONObject {
        val clip = clipboardPrimaryClip() ?: error("El portapapeles esta vacio")
        val firstItem = clip.getItemAt(0)
        val uri = firstItem.uri ?: firstItem.intent?.data

        if (uri != null && !clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) {
            val item = withContext(Dispatchers.IO) {
                localContentFactory.fromUri(uri, deferLargeImagePreview = true)
            } ?: error("No se pudo leer el contenido del portapapeles")
            repository.addLocalContent(item)
            scheduleDeferredPreviewRefresh(item)
            Log.i(TAG, "Imported clipboard URI into overlay hub: $uri")
            return localContentJson(item)
        }

        val text = firstItem.coerceToText(context)?.toString()?.trim().orEmpty()
        require(text.isNotBlank()) { "El portapapeles no contiene texto" }
        require(text.length <= MAX_PASTE_TEXT_CHARS) {
            "Texto demasiado grande para pegar desde overlay"
        }

        val item = withContext(Dispatchers.Default) {
            localContentFactory.fromText(text).also(repository::addLocalContent)
        }
        Log.i(TAG, "Pasted clipboard text into overlay hub (${text.length} chars)")
        return localContentJson(item)
    }

    private fun publishContent(args: JSONObject) {
        val contentId = args.optString("content_id").trim()
        require(contentId.isNotBlank()) { "Contenido invalido" }
        settingsStore.clearIgnoredPeerContent(contentId)
        val targetDevice = args.optString("target_device").trim().ifBlank { null }
        val sendMode = targetDevice?.let(SendMode::Direct) ?: SendMode.Broadcast
        repository.publish(contentId, sendMode)
    }

    private fun unpublishContent(args: JSONObject?) {
        val contentId = args?.optString("content_id").orEmpty()
        require(contentId.isNotBlank()) { "Contenido invalido" }
        repository.unpublish(contentId)
    }

    private fun removeContent(args: JSONObject?) {
        val contentId = args?.optString("id").orEmpty()
        require(contentId.isNotBlank()) { "Contenido invalido" }
        repository.removeLocalContent(contentId)?.let { removed ->
            tempClipboardStore.deleteContent(removed.contentId)
        }
    }

    private fun copyLocalContent(args: JSONObject?): String {
        val contentId = args?.optString("id").orEmpty()
        require(contentId.isNotBlank()) { "Contenido invalido" }
        val item = repository.getLocalContent(contentId) ?: error("Contenido local no encontrado")
        val message = receivedContentHandler.copyToSystemClipboard(item)
        Log.i(TAG, "Copied local content to system clipboard from overlay: $contentId")
        return message
    }

    private suspend fun pullPeerContent(args: JSONObject?): JSONObject {
        val contentId = args?.optString("content_id").orEmpty()
        require(contentId.isNotBlank()) { "Peer invalido" }
        val peer = repository.getPeer(contentId) ?: error("Peer no encontrado")
        val settings = settingsStore.current()
        require(settings.configured) { "Configura FenixHub antes de recibir contenido" }

        val pulled = httpClient.pullContent(peer, settings)
            .getOrElse { throw IllegalStateException("No se pudo recibir el contenido") }
        val item = withContext(Dispatchers.IO) {
            receivedContentHandler.createLocalContent(peer, pulled)
        }
        repository.addLocalContent(item)
        return localContentJson(item)
    }

    private suspend fun copyPeerContent(args: JSONObject?): String {
        val contentId = args?.optString("content_id").orEmpty()
        require(contentId.isNotBlank()) { "Peer invalido" }
        val peer = repository.getPeer(contentId) ?: error("Peer no encontrado")
        val settings = settingsStore.current()
        require(settings.configured) { "Configura FenixHub antes de recibir contenido" }

        val pulled = httpClient.pullContent(peer, settings)
            .getOrElse { throw IllegalStateException("No se pudo recibir el contenido") }
        return withContext(Dispatchers.IO) {
            val item = receivedContentHandler.createLocalContent(peer, pulled)
            receivedContentHandler.copyToSystemClipboard(item)
        }
    }

    private fun ignorePeerContent(args: JSONObject?) {
        val contentId = args?.optString("content_id").orEmpty()
        require(contentId.isNotBlank()) { "Peer invalido" }
        settingsStore.ignorePeerContent(contentId)
        repository.removePeer(contentId)
    }

    private fun peerJson(peer: PeerContent): JSONObject {
        return announcementJson(peer.announcement)
            .put("port", peer.port)
    }

    private fun announcementJson(announcement: Announcement): JSONObject {
        return JSONObject()
            .put("group_id", announcement.groupId)
            .put("content_id", announcement.contentId)
            .put("device_name", announcement.deviceName)
            .put("preview", announcement.preview)
            .put("content_type", announcement.contentType.wireValue)
            .put("size_bytes", announcement.sizeBytes)
            .put("send_mode", sendModeJson(announcement.sendMode))
            .put("created_at", announcement.createdAt)
            .put("port", announcement.port)
            .put("file_name", nullable(announcement.fileName))
            .put("mime_type", nullable(announcement.mimeType))
    }

    private fun localContentJson(item: LocalContent): JSONObject {
        return JSONObject()
            .put("id", item.contentId)
            .put("content_type", item.contentType.wireValue)
            .put("preview", item.preview)
            .put("size_bytes", item.sizeBytes)
            .put("created_at", item.createdAt)
            .put("file_name", nullable(item.fileName))
            .put("mime_type", nullable(item.mimeType))
            .put("is_published", item.isPublished)
    }

    private fun sendModeJson(sendMode: SendMode): JSONObject {
        return when (sendMode) {
            SendMode.Broadcast -> JSONObject().put("Broadcast", JSONObject.NULL)
            is SendMode.Direct -> JSONObject().put(
                "Direct",
                JSONObject().put("target_device", sendMode.targetDevice),
            )
        }
    }

    private fun requirePayloadArgs(args: JSONObject?): JSONObject {
        return args?.optJSONObject("args") ?: args ?: JSONObject()
    }

    private fun clipboardPrimaryClip(): ClipData? {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        return clipboard.primaryClip
    }

    private fun scheduleDeferredPreviewRefresh(item: LocalContent) {
        if (item.contentType != com.fenixhub.mobile.model.HubContentType.IMAGE ||
            item.preview.startsWith("data:image")
        ) {
            return
        }

        scope.launch(Dispatchers.IO) {
            val refreshed = localContentFactory.refreshDeferredImagePreview(item) ?: return@launch
            if (refreshed.preview == item.preview) {
                return@launch
            }

            repository.addLocalContent(refreshed)
            dispatch("window.__fenixExternalRefresh && window.__fenixExternalRefresh();")
        }
    }

    private fun nullable(value: String?): Any = value ?: JSONObject.NULL

    private fun dispatchSuccess(requestId: String, payload: String) {
        dispatch("window.__fenixResolve && window.__fenixResolve(${JSONObject.quote(requestId)}, $payload);")
    }

    private fun dispatchError(requestId: String, message: String) {
        dispatch("window.__fenixReject && window.__fenixReject(${JSONObject.quote(requestId)}, ${JSONObject.quote(message)});")
    }

    private fun dispatch(script: String) {
        val currentWebView = webView ?: return
        currentWebView.post {
            currentWebView.evaluateJavascript(script, null)
        }
    }

    private companion object {
        const val TAG = "FenixHubOverlayBridge"
        const val MAX_PASTE_TEXT_CHARS = 8_192
    }
}
