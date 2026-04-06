package com.fenixhub.mobile.web

import android.content.ClipboardManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.fenixhub.mobile.AppContainer
import com.fenixhub.mobile.model.Announcement
import com.fenixhub.mobile.model.HubContentType
import com.fenixhub.mobile.model.LocalContent
import com.fenixhub.mobile.model.PeerContent
import com.fenixhub.mobile.model.SendMode
import com.fenixhub.mobile.service.FenixHubService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AndroidHubBridge(
    private val activity: ComponentActivity,
    private val container: AppContainer,
    private val launchFilePicker: suspend () -> Uri?,
    private val readClipboardText: () -> String?,
    private val openOverlayPermissionSettings: () -> Unit,
) {
    @Volatile
    private var webView: WebView? = null

    fun attach(webView: WebView) {
        this.webView = webView
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

        activity.lifecycleScope.launch {
            runCatching { handle(command, args) }
                .onSuccess { dispatchSuccess(requestId, it) }
                .onFailure { dispatchError(requestId, it.message ?: "Operacion fallida") }
        }
    }

    suspend fun importPickedFile(): LocalContent? {
        val uri = launchFilePicker() ?: return null
        return withContext(Dispatchers.IO) {
            container.localContentFactory.fromUri(uri)?.also(container.contentRepository::addLocalContent)
        }
    }

    private suspend fun handle(command: String, args: JSONObject?): String {
        return when (command) {
            "get_identity" -> identityJson().toString()
            "setup_identity" -> setupIdentityJson(requirePayloadArgs(args)).toString()
            "get_local_content" -> JSONArray().apply {
                container.contentRepository.localContent.value.forEach { put(localContentJson(it)) }
            }.toString()
            "get_peers" -> JSONArray().apply {
                container.contentRepository.peers.value.forEach { put(peerJson(it)) }
            }.toString()
            "add_text_content" -> addTextContent(args).toString()
            "paste_clipboard_text" -> pasteClipboardText().toString()
            "add_binary_content" -> addBinaryContent(requirePayloadArgs(args)).toString()
            "pick_file" -> importPickedFile()?.let(::localContentJson)?.toString() ?: "null"
            "copy_local_content" -> JSONObject.quote(copyLocalContent(args))
            "remove_content" -> {
                removeContent(args)
                "null"
            }
            "publish_content" -> {
                publishContent(requirePayloadArgs(args))
                "null"
            }
            "unpublish_content" -> {
                unpublishContent(args)
                "null"
            }
            "stop_server" -> {
                container.contentRepository.unpublishAll()
                "null"
            }
            "pull_peer_content" -> pullPeerContent(args).toString()
            "open_overlay" -> if (openOverlay()) "true" else "false"
            else -> error("Comando no soportado: $command")
        }
    }

    private fun identityJson(): JSONObject {
        val settings = container.settingsStore.current()
        return JSONObject()
            .put("device_name", settings.deviceName)
            .put("group_id", settings.groupId)
            .put("configured", settings.configured)
    }

    private suspend fun setupIdentityJson(args: JSONObject): JSONObject {
        val passphrase = args.optString("passphrase").trim()
        val deviceName = args.optString("device_name").trim()
        require(passphrase.isNotBlank()) { "La frase de acceso es obligatoria" }
        require(deviceName.isNotBlank()) { "El nombre del dispositivo es obligatorio" }

        val settings = withContext(Dispatchers.Default) {
            container.settingsStore.saveIdentity(passphrase, deviceName)
        }

        withContext(Dispatchers.Main.immediate) {
            FenixHubService.start(activity)
        }

        return JSONObject()
            .put("device_name", settings.deviceName)
            .put("group_id", settings.groupId)
            .put("configured", settings.configured)
    }

    private suspend fun addTextContent(args: JSONObject?): JSONObject {
        val text = args?.optString("text")?.trim().orEmpty()
        require(text.isNotBlank()) { "No hay texto para anadir" }
        val item = withContext(Dispatchers.Default) {
            container.localContentFactory.fromText(text).also(container.contentRepository::addLocalContent)
        }
        return localContentJson(item)
    }

    private suspend fun pasteClipboardText(): JSONObject {
        val text = readClipboardText()?.trim().orEmpty()
        if (text.isNotBlank()) {
            require(text.length <= MAX_PASTE_TEXT_CHARS) {
                "Texto demasiado grande para pegar"
            }
            val item = withContext(Dispatchers.Default) {
                container.localContentFactory.fromText(text).also(container.contentRepository::addLocalContent)
            }
            Log.i(TAG, "Pasted clipboard text into main hub (${text.length} chars)")
            return localContentJson(item)
        }

        val uri = clipboardPrimaryUri() ?: error("El portapapeles no contiene texto ni archivo")
        val item = withContext(Dispatchers.IO) {
            container.localContentFactory.fromUri(uri)
        } ?: error("No se pudo importar el archivo del portapapeles")
        container.contentRepository.addLocalContent(item)
        Log.i(TAG, "Imported clipboard URI into main hub: $uri")
        return localContentJson(item)
    }

    private suspend fun addBinaryContent(args: JSONObject): JSONObject {
        val fileName = args.optString("file_name").ifBlank { "fenixhub-item" }
        val mimeType = args.optString("mime_type").ifBlank { "application/octet-stream" }
        val preview = args.optString("preview").takeIf(String::isNotBlank)
        val bytesBase64 = args.optString("bytes_base64")
        require(bytesBase64.isNotBlank()) { "No hay contenido binario" }

        val item = withContext(Dispatchers.IO) {
            val bytes = Base64.decode(bytesBase64, Base64.DEFAULT)
            val contentType = if (mimeType.startsWith("image/")) HubContentType.IMAGE else HubContentType.FILE
            container.tempClipboardStore.createBinaryContent(
                bytes = bytes,
                contentType = contentType,
                mimeType = mimeType,
                fileName = fileName,
                previewOverride = preview,
            ).also(container.contentRepository::addLocalContent)
        }
        return localContentJson(item)
    }

    private fun copyLocalContent(args: JSONObject?): String {
        val contentId = args?.optString("id").orEmpty()
        require(contentId.isNotBlank()) { "Contenido invalido" }
        val item = container.contentRepository.getLocalContent(contentId)
            ?: error("Contenido local no encontrado")
        return container.receivedContentHandler.copyToSystemClipboard(item)
    }

    private fun removeContent(args: JSONObject?) {
        val contentId = args?.optString("id").orEmpty()
        require(contentId.isNotBlank()) { "Contenido invalido" }
        val removed = container.contentRepository.removeLocalContent(contentId) ?: return
        container.tempClipboardStore.deleteContent(removed.contentId)
    }

    private fun publishContent(args: JSONObject) {
        val contentId = args.optString("content_id").trim()
        require(contentId.isNotBlank()) { "Contenido invalido" }
        val targetDevice = args.optString("target_device").trim().ifBlank { null }
        val sendMode = targetDevice?.let(SendMode::Direct) ?: SendMode.Broadcast
        FenixHubService.start(activity)
        container.contentRepository.publish(contentId, sendMode)
    }

    private fun unpublishContent(args: JSONObject?) {
        val contentId = args?.optString("content_id").orEmpty()
        require(contentId.isNotBlank()) { "Contenido invalido" }
        container.contentRepository.unpublish(contentId)
    }

    private suspend fun pullPeerContent(args: JSONObject?): JSONObject {
        val contentId = args?.optString("content_id").orEmpty()
        require(contentId.isNotBlank()) { "Peer invalido" }
        val peer = container.contentRepository.getPeer(contentId) ?: error("Peer no encontrado")
        val settings = container.settingsStore.current()
        require(settings.configured) { "Configura FenixHub antes de recibir contenido" }

        val pulled = container.httpClient.pullContent(peer, settings)
            .getOrElse { throw IllegalStateException("No se pudo recibir el contenido") }
        val received = withContext(Dispatchers.IO) {
            container.receivedContentHandler.handle(peer, pulled)
        }
        container.contentRepository.addLocalContent(received.item)
        return localContentJson(received.item)
    }

    private suspend fun openOverlay(): Boolean {
        return withContext(Dispatchers.Main.immediate) {
            if (Settings.canDrawOverlays(activity)) {
                FenixHubService.start(activity, FenixHubService.ACTION_SHOW_OVERLAY)
                true
            } else {
                openOverlayPermissionSettings()
                false
            }
        }
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
            .put("transfer_path", JSONObject.NULL)
            .put("is_published", item.isPublished)
    }

    private fun peerJson(peer: PeerContent): JSONObject {
        return announcementJson(peer.announcement)
            .put("port", peer.port)
    }

    private fun announcementJson(announcement: Announcement): JSONObject {
        return JSONObject()
            .put("protocol_version", announcement.protocolVersion)
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

    private fun clipboardPrimaryUri(): Uri? {
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val item = clip.getItemAt(0)
        return item.uri ?: item.intent?.data
    }

    private fun nullable(value: String?): Any = value ?: JSONObject.NULL

    private fun dispatchSuccess(requestId: String, payload: String) {
        val script = "window.__fenixResolve && window.__fenixResolve(${JSONObject.quote(requestId)}, $payload);"
        dispatch(script)
    }

    private fun dispatchError(requestId: String, message: String) {
        val script = "window.__fenixReject && window.__fenixReject(${JSONObject.quote(requestId)}, ${JSONObject.quote(message)});"
        dispatch(script)
    }

    private fun dispatch(script: String) {
        val currentWebView = webView ?: return
        currentWebView.post {
            currentWebView.evaluateJavascript(script, null)
        }
    }

    private companion object {
        const val TAG = "FenixHubAndroidBridge"
        const val MAX_PASTE_TEXT_CHARS = 8_192
    }
}
