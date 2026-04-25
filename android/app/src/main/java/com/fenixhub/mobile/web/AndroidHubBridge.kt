package com.fenixhub.mobile.web

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.fenixhub.mobile.AppContainer
import com.fenixhub.mobile.model.AppSettings
import com.fenixhub.mobile.model.Announcement
import com.fenixhub.mobile.model.HubContentType
import com.fenixhub.mobile.model.LocalContent
import com.fenixhub.mobile.model.PeerContent
import com.fenixhub.mobile.model.PulledContent
import com.fenixhub.mobile.model.SendMode
import com.fenixhub.mobile.model.MeshRole
import com.fenixhub.mobile.model.MeshStatus
import com.fenixhub.mobile.model.MeshDevice
import com.fenixhub.mobile.network.BleDirectController
import com.fenixhub.mobile.network.DirectBleListener
import com.fenixhub.mobile.network.DirectBlePeer
import com.fenixhub.mobile.network.EphemeralDirectSession
import com.fenixhub.mobile.network.FenixHttpServer
import com.fenixhub.mobile.service.FenixHubService
import com.fenixhub.mobile.util.CryptoUtils
import com.fenixhub.mobile.util.TransportHardwareInspector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class AndroidHubBridge(
    private val activity: ComponentActivity,
    private val container: AppContainer,
    private val launchFilePicker: suspend () -> Uri?,
    private val launchCreateDocument: suspend (fileName: String, mimeType: String) -> Uri?,
    private val readClipboardText: () -> String?,
    private val openOverlayPermissionSettings: () -> Unit,
    private val requestRuntimePermissions: suspend () -> Boolean,
) {
    @Volatile
    private var webView: WebView? = null

    private val selectedDirectPeerIds = mutableSetOf<String>()

    private val directModeListener = object : DirectBleListener {
        override fun onPeerDiscovered(peer: DirectBlePeer) {
            Log.d(TAG, "Direct peer discovered: ${peer.deviceName}")
        }
        override fun onPeerLost(peerId: String) {
            Log.d(TAG, "Direct peer lost: $peerId")
            selectedDirectPeerIds.remove(peerId)
        }
        override fun onIncomingInvite(peer: DirectBlePeer) {
            Log.i(TAG, "Incoming invite from: ${peer.deviceName}")
        }
        override fun onError(reason: String) {
            Log.w(TAG, "Direct mode error: $reason")
        }
    }

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
            container.localContentFactory.fromUri(uri, deferLargeImagePreview = true)
                ?.also(container.contentRepository::addLocalContent)
                ?.also(::scheduleDeferredPreviewRefresh)
        }
    }

    private suspend fun handle(command: String, args: JSONObject?): String {
        return when (command) {
            "get_identity" -> identityJson().toString()
            "setup_identity" -> setupIdentityJson(requirePayloadArgs(args)).toString()
            "update_identity" -> updateIdentityJson(requirePayloadArgs(args)).toString()
            "delete_identity_only" -> {
                deleteIdentityOnly()
                "null"
            }
            "list_identity_profiles" -> profilesPayloadJson().toString()
            "save_current_identity_profile" -> saveCurrentIdentityProfileJson(requirePayloadArgs(args)).toString()
            "activate_identity_profile" -> activateIdentityProfileJson(requirePayloadArgs(args)).toString()
            "delete_identity_profile" -> deleteIdentityProfileJson(requirePayloadArgs(args)).toString()
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
            "copy_peer_content" -> JSONObject.quote(copyPeerContent(args))
            "save_peer_content_as" -> savePeerContentAs(args)?.let(JSONObject::quote) ?: "null"
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
            "start_direct_mode_sender" -> {
                startDirectModeSender()
                "null"
            }
            "accept_direct_peers" -> {
                acceptDirectPeers(args)
                "null"
            }
            "cancel_direct_mode" -> {
                cancelDirectMode()
                "null"
            }
            "start_direct_mode_receiver" -> {
                startDirectModeReceiver()
                "null"
            }
            "get_current_inviter" -> {
                getCurrentInviterJson().toString()
            }
            "accept_direct_invite" -> {
                acceptDirectInvite()
                "null"
            }
            "reject_direct_invite" -> {
                container.ephemeralSession.rejectIncomingInvite()
                "null"
            }
            "toggle_direct_peer" -> {
                toggleDirectPeer(args)
                "null"
            }
            "get_direct_peers" -> {
                getDirectPeersJson().toString()
            }
            "get_direct_session_state" -> {
                getDirectSessionStateJson().toString()
            }
            "get_mesh_state" -> getMeshStateJson().toString()
            "refresh_direct_peers" -> getDirectPeersJson().toString()
            "stop_server" -> {
                container.contentRepository.unpublishAll()
                "null"
            }
            "pull_peer_content" -> pullPeerContent(args).toString()
            "get_transport_hardware" -> getTransportHardwareJson().toString()
            "clear_received_cache" -> {
                clearReceivedCache()
                "null"
            }
            "confirm_reset" -> "true"
            "reset_all_data" -> {
                resetAllData()
                "null"
            }
            "open_overlay" -> if (openOverlay()) "true" else "false"
            "publish_all_local" -> {
                publishAllLocal()
                "null"
            }
            "mesh_start_host" -> {
                val contentPool = args?.optJSONArray("content_pool")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList()
                container.meshManager.dispatch(
                    com.fenixhub.mobile.service.MeshManager.MeshCommand.StartAsHost(contentPool)
                )
                "null"
            }
            "mesh_start_device" -> {
                container.meshManager.dispatch(com.fenixhub.mobile.service.MeshManager.MeshCommand.StartAsDevice)
                "null"
            }
            "mesh_modal_open" -> {
                container.meshManager.dispatch(com.fenixhub.mobile.service.MeshManager.MeshCommand.ModalOpened)
                "null"
            }
            "mesh_modal_close" -> {
                container.meshManager.dispatch(com.fenixhub.mobile.service.MeshManager.MeshCommand.ModalClosed)
                "null"
            }
            "mesh_accept_device" -> {
                val deviceId = args?.optString("device_id").orEmpty()
                if (deviceId.isNotBlank()) {
                    container.meshManager.dispatch(
                        com.fenixhub.mobile.service.MeshManager.MeshCommand.AcceptDevice(deviceId)
                    )
                }
                "null"
            }
            "mesh_reject_device" -> {
                val deviceId = args?.optString("device_id").orEmpty()
                if (deviceId.isNotBlank()) {
                    container.meshManager.dispatch(
                        com.fenixhub.mobile.service.MeshManager.MeshCommand.RejectDevice(deviceId)
                    )
                }
                "null"
            }
            "mesh_expel_device" -> {
                val deviceId = args?.optString("device_id").orEmpty()
                if (deviceId.isNotBlank()) {
                    container.meshManager.dispatch(
                        com.fenixhub.mobile.service.MeshManager.MeshCommand.ExpelDevice(deviceId)
                    )
                }
                "null"
            }
            "mesh_request_join" -> {
                val hostMeshId = args?.optString("host_mesh_id").orEmpty()
                val hostName = args?.optString("host_name").orEmpty()
                container.meshManager.dispatch(
                    com.fenixhub.mobile.service.MeshManager.MeshCommand.RequestJoin(hostMeshId, hostName)
                )
                "null"
            }
            "mesh_cancel_discovery" -> {
                container.meshManager.dispatch(com.fenixhub.mobile.service.MeshManager.MeshCommand.CancelDiscovery)
                "null"
            }
            "mesh_close_modal" -> {
                container.meshManager.dispatch(com.fenixhub.mobile.service.MeshManager.MeshCommand.CloseModal)
                "null"
            }
            "mesh_leave" -> {
                container.meshManager.dispatch(com.fenixhub.mobile.service.MeshManager.MeshCommand.LeaveMesh)
                "null"
            }
            "mesh_finalize" -> {
                container.meshManager.dispatch(com.fenixhub.mobile.service.MeshManager.MeshCommand.FinalizeTransfer)
                "null"
            }
            "mesh_confirm_received" -> {
                container.meshManager.dispatch(com.fenixhub.mobile.service.MeshManager.MeshCommand.ConfirmAllReceived)
                "null"
            }
            "mesh_get_state" -> getMeshStateJson().toString()
            else -> error("Comando no soportado: $command")
        }
    }

    private fun getTransportHardwareJson(): JSONObject {
        val settings = container.settingsStore.current()
        if (settings.configured) {
            container.bleIdentityController.ensureRunning(settings.groupId, settings.deviceName)
            container.wifiDirectController.ensureRunning()
        }

        val snapshot = TransportHardwareInspector.snapshot(activity)
        val bleStatus = container.bleIdentityController.status()
        val wifiStatus = container.wifiDirectController.status()
        val blePeers = container.bleIdentityController.snapshotPeers()
        val wifiPeers = container.wifiDirectController.snapshotPeers()

        val handoffCandidates = JSONArray().apply {
            blePeers.forEach { blePeer ->
                val wifiPeer = wifiPeers.firstOrNull { candidate ->
                    candidate.deviceName.trim().equals(blePeer.deviceName.trim(), ignoreCase = true)
                }
                if (wifiPeer != null) {
                    put(
                        JSONObject()
                            .put("device_name", blePeer.deviceName)
                            .put("ble_id", blePeer.deviceId)
                            .put("wifi_direct_id", wifiPeer.deviceAddress)
                            .put("flow", "ble_discovery_then_wifi_direct_transfer"),
                    )
                }
            }
        }

        val blePeersJson = JSONArray().apply {
            blePeers.forEach { peer ->
                put(
                    JSONObject()
                        .put("id", peer.deviceId)
                        .put("device_name", peer.deviceName)
                        .put("rssi", peer.rssi)
                        .put("last_seen_elapsed_ms", peer.lastSeenElapsedMs),
                )
            }
        }

        val wifiPeersJson = JSONArray().apply {
            wifiPeers.forEach { peer ->
                put(
                    JSONObject()
                        .put("id", peer.deviceAddress)
                        .put("device_name", peer.deviceName)
                        .put("status", peer.status)
                        .put("last_seen_elapsed_ms", peer.lastSeenElapsedMs),
                )
            }
        }

        val airdropReady = snapshot.ble.supported && snapshot.ble.enabled && snapshot.ble.permissionsReady &&
            snapshot.wifiDirect.supported && snapshot.wifiDirect.enabled && snapshot.wifiDirect.permissionsReady

        return JSONObject()
            .put(
                "ble",
                JSONObject()
                    .put("supported", snapshot.ble.supported)
                    .put("enabled", snapshot.ble.enabled)
                    .put("permissions_ready", snapshot.ble.permissionsReady)
                    .put("adapter_name", nullable(snapshot.ble.adapterName))
                    .put("scanning", bleStatus.scanning)
                    .put("advertising", bleStatus.advertising)
                    .put("last_error", nullable(bleStatus.lastError)),
            )
            .put(
                "wifi_direct",
                JSONObject()
                    .put("supported", snapshot.wifiDirect.supported)
                    .put("enabled", snapshot.wifiDirect.enabled)
                    .put("permissions_ready", snapshot.wifiDirect.permissionsReady)
                    .put("discovering", wifiStatus.discovering)
                    .put("last_error", nullable(wifiStatus.lastError)),
            )
            .put("airdrop_ready", airdropReady)
            .put("flow", "ble_discovery_then_wifi_direct_transfer")
            .put("ble_peers", blePeersJson)
            .put("wifi_direct_peers", wifiPeersJson)
            .put("handoff_candidates", handoffCandidates)
    }

    private fun identityJson(): JSONObject {
        val settings = container.settingsStore.current()
        return identityJson(settings)
    }

    private fun identityJson(settings: AppSettings): JSONObject {
        return JSONObject()
            .put("device_name", settings.deviceName)
            .put("group_id", settings.groupId)
            .put("configured", settings.configured)
            .put("device_type", settings.deviceType)
    }

    private suspend fun setupIdentityJson(args: JSONObject): JSONObject {
        val passphrase = args.optString("passphrase").trim()
        val deviceName = args.optString("device_name").trim()
        val deviceType = optionalTrimmedString(args, "device_type")
        require(passphrase.isNotBlank()) { "La frase de acceso es obligatoria" }
        require(deviceName.isNotBlank()) { "El nombre del dispositivo es obligatorio" }
        CryptoUtils.validatePassphraseStrength(passphrase)?.let { message ->
            error(message)
        }

        val settings = withContext(Dispatchers.Default) {
            container.settingsStore.saveIdentity(passphrase, deviceName, deviceType)
        }

        requestIdentityPermissionsIfNeeded()

        withContext(Dispatchers.Main.immediate) {
            FenixHubService.start(activity)
        }

        return identityJson(settings)
    }

    private suspend fun updateIdentityJson(args: JSONObject): JSONObject {
        val current = container.settingsStore.current()
        require(current.configured) { "Identity not configured" }

        val passphrase = optionalTrimmedString(args, "passphrase")
        val deviceName = args.optString("device_name").trim()
        val deviceType = optionalTrimmedString(args, "device_type")
        require(deviceName.isNotBlank()) { "Device name is required" }

        val settings = withContext(Dispatchers.Default) {
            container.settingsStore.updateIdentity(passphrase, deviceName, deviceType)
        }
        val groupChanged = current.groupId.isNotBlank() && current.groupId != settings.groupId

        requestIdentityPermissionsIfNeeded()

        withContext(Dispatchers.Main.immediate) {
            FenixHubService.start(activity, FenixHubService.ACTION_REFRESH_IDENTITY)
        }

        return JSONObject()
            .put("identity", identityJson(settings))
            .put("group_changed", groupChanged)
            .put("requires_restart", groupChanged)
    }

    private fun profilesPayloadJson(): JSONObject {
        return profilesPayloadJson(container.settingsStore.listProfiles())
    }

    private fun profilesPayloadJson(
        profiles: List<com.fenixhub.mobile.model.IdentityProfileInfo>,
    ): JSONObject {
        val payload = JSONArray()
        profiles.forEach { profile ->
            payload.put(
                JSONObject()
                    .put("name", profile.name)
                    .put("device_name", profile.deviceName)
                    .put("group_id", profile.groupId)
                    .put("device_type", profile.deviceType)
                    .put("active", profile.active),
            )
        }
        return JSONObject().put("profiles", payload)
    }

    private fun saveCurrentIdentityProfileJson(args: JSONObject): JSONObject {
        val name = args.optString("name").trim()
        val makeActive = args.optBoolean("make_active", false)
        val profiles = container.settingsStore.saveCurrentProfile(name, makeActive)
        return profilesPayloadJson(profiles)
    }

    private suspend fun activateIdentityProfileJson(args: JSONObject): JSONObject {
        val name = args.optString("name").trim()
        require(name.isNotBlank()) { "Profile name is required" }

        val previousGroup = container.settingsStore.current().groupId
        val settings = withContext(Dispatchers.Default) {
            container.settingsStore.activateProfile(name)
        } ?: error("Profile not found")

        requestIdentityPermissionsIfNeeded()

        withContext(Dispatchers.Main.immediate) {
            FenixHubService.start(activity, FenixHubService.ACTION_REFRESH_IDENTITY)
        }

        val groupChanged = previousGroup.isNotBlank() && previousGroup != settings.groupId
        return JSONObject()
            .put("identity", identityJson(settings))
            .put("group_changed", groupChanged)
            .put("requires_restart", true)
    }

    private fun deleteIdentityProfileJson(args: JSONObject): JSONObject {
        val name = args.optString("name").trim()
        require(name.isNotBlank()) { "Profile name is required" }
        val profiles = container.settingsStore.removeProfile(name)
        return profilesPayloadJson(profiles)
    }

    private suspend fun deleteIdentityOnly() {
        withContext(Dispatchers.Default) {
            container.contentRepository.unpublishAll()
            container.contentRepository.clearPeers()
            container.settingsStore.clearIdentityOnly()
        }
        stopRuntimeNetworking()
    }

    private suspend fun clearReceivedCache() {
        withContext(Dispatchers.Default) {
            clearLocalHubData()
            val receivedDir = File(activity.cacheDir, "fenixhub/received")
            if (receivedDir.exists()) {
                receivedDir.deleteRecursively()
            }
        }
    }

    private suspend fun resetAllData() {
        withContext(Dispatchers.Default) {
            clearLocalHubData()
            val receivedDir = File(activity.cacheDir, "fenixhub/received")
            if (receivedDir.exists()) {
                receivedDir.deleteRecursively()
            }
            container.contentRepository.clearPeers()
            container.settingsStore.resetAllData()
        }
        stopRuntimeNetworking()
    }

    private fun clearLocalHubData() {
        val localIds = container.contentRepository.localContent.value.map { it.contentId }
        localIds.forEach { contentId ->
            container.contentRepository.removeLocalContent(contentId)?.let { removed ->
                container.tempClipboardStore.deleteContent(removed.contentId)
            }
        }
        container.tempClipboardStore.clearAll()
    }

    private suspend fun stopRuntimeNetworking() {
        withContext(Dispatchers.Main.immediate) {
            container.bleIdentityController.stop()
            container.wifiDirectController.stop()
            container.hotspotManager.stop()
            runCatching {
                activity.stopService(Intent(activity, FenixHubService::class.java))
            }
        }
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
            container.localContentFactory.fromUri(uri, deferLargeImagePreview = true)
        } ?: error("No se pudo importar el archivo del portapapeles")
        container.contentRepository.addLocalContent(item)
        scheduleDeferredPreviewRefresh(item)
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

    // ── Direct Mode (AirDrop-like) ───────────────────────────────────────

    private suspend fun startDirectModeSender() {
        ensureDirectModePermissionsReady()
        FenixHubService.start(activity)
        selectedDirectPeerIds.clear()
        activity.lifecycleScope.launch(Dispatchers.Main.immediate) {
            container.bleDirectController.startDirectMode(
                role = BleDirectController.Role.SENDER,
                deviceName = container.settingsStore.current().deviceName,
                listener = directModeListener,
            )
            Log.i(TAG, "Direct mode sender started")
        }
    }

    private suspend fun startDirectModeReceiver() {
        ensureDirectModePermissionsReady()
        FenixHubService.start(activity)
        activity.lifecycleScope.launch(Dispatchers.Main.immediate) {
            container.ephemeralSession.startAsReceiver(container.settingsStore.current().deviceName)
        }
    }

    private suspend fun acceptDirectInvite() {
        ensureDirectModePermissionsReady()
        activity.lifecycleScope.launch(Dispatchers.Main.immediate) {
            container.ephemeralSession.acceptIncomingInvite { senderIp, senderPort, contentId ->
                Log.i(TAG, "Direct invite accepted: $senderIp:$senderPort contentId=$contentId")
            }
        }
    }

    private fun toggleDirectPeer(args: JSONObject?) {
        val peerId = args?.optString("peer_id").orEmpty()
        if (peerId.isBlank()) return
        if (selectedDirectPeerIds.contains(peerId)) {
            selectedDirectPeerIds.remove(peerId)
        } else {
            selectedDirectPeerIds.add(peerId)
        }
        Log.d(TAG, "Toggled peer selection: $peerId, selected: $selectedDirectPeerIds")
    }

    private fun acceptDirectPeers(args: JSONObject?) {
        val selectedIds = args?.optJSONArray("selected_peer_ids")
            ?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }.toSet()
            }
            ?: emptySet()
        val contentId = args?.optString("content_id").orEmpty()
            .ifBlank { container.contentRepository.latestLocalContent()?.contentId }
            ?: return

        activity.lifecycleScope.launch(Dispatchers.Main.immediate) {
            val session = createEphemeralSession()
            selectedIds.forEach { session.togglePeerSelection(it) }
            session.acceptSelectedPeers(contentId) { ephemeralId, peerCount ->
                Log.i(TAG, "Direct mode accepted: ephemeralId=$ephemeralId, peers=$peerCount")
            }
        }
    }

    private fun cancelDirectMode() {
        container.bleDirectController.stopDirectMode()
        selectedDirectPeerIds.clear()
    }

    private fun createEphemeralSession(): EphemeralDirectSession {
        return EphemeralDirectSession(
            context = activity,
            bleController = container.bleDirectController,
            transferController = container.wifiDirectTransferController,
            contentRepository = container.contentRepository,
            receivedHandler = container.receivedContentHandler,
            httpClient = container.httpClient,
            httpServer = FenixHttpServer(container.settingsStore, container.contentRepository),
        )
    }

    private fun getDirectPeersJson(): JSONObject {
        val peers = container.bleDirectController.snapshotPeers()
        val status = container.bleDirectController.status()
        return JSONObject()
            .put("peers", JSONArray().apply {
                peers.forEach { peer ->
                    put(JSONObject()
                        .put("device_id", peer.deviceId)
                        .put("device_name", peer.deviceName)
                        .put("ephemeral_group_id", peer.ephemeralGroupId)
                        .put("rssi", peer.rssi)
                        .put("selected", selectedDirectPeerIds.contains(peer.deviceId))
                    )
                }
            })
            .put("discovering", status.discovering)
            .put("advertising", status.advertising)
    }

    private fun getDirectSessionStateJson(): JSONObject {
        val status = container.bleDirectController.status()
        val ephemeralId = container.bleDirectController.currentEphemeralGroupId()
        return JSONObject()
            .put("discovering", status.discovering)
            .put("advertising", status.advertising)
            .put("ephemeral_group_id", ephemeralId)
            .put("peer_count", container.bleDirectController.snapshotPeers().size)
    }

    private fun getMeshStateJson(): JSONObject {
        val state = container.meshManager.state.value
        return JSONObject()
            .put("role", state.role.name.lowercase())
            .put("status", state.status.name.lowercase())
            .put("mesh_id", state.meshId ?: JSONObject.NULL)
            .put("passphrase_set", state.passphrase != null)
            .put("pending_devices", JSONArray().apply {
                state.pendingDevices.forEach { device ->
                    put(JSONObject()
                        .put("id", device.id)
                        .put("name", device.name)
                        .put("mesh_id", device.meshId ?: JSONObject.NULL)
                        .put("rssi", device.rssi)
                        .put("status", device.status.name.lowercase()))
                }
            })
            .put("active_devices", JSONArray().apply {
                state.activeDevices.forEach { device ->
                    put(JSONObject()
                        .put("id", device.id)
                        .put("name", device.name)
                        .put("mesh_id", device.meshId ?: JSONObject.NULL)
                        .put("rssi", device.rssi)
                        .put("status", device.status.name.lowercase())
                        .put("joined_at", device.joinedAt ?: JSONObject.NULL))
                }
            })
            .put("local_content_pool", JSONArray().apply {
                state.localContentPool.forEach { put(it) }
            })
            .put("is_active", state.isActive)
            .put("can_add_devices", state.canAddDevices)
            .put("can_leave", state.canLeave)
            .put("pending_count", state.pendingCount)
    }

    private fun getCurrentInviterJson(): JSONObject {
        val inviter = container.ephemeralSession.getCurrentInviter()
        return if (inviter != null) {
            JSONObject()
                .put("has_invite", true)
                .put("device_name", inviter.deviceName)
                .put("ephemeral_group_id", inviter.ephemeralGroupId)
                .put("sender_ip", inviter.senderIp ?: "")
                .put("sender_port", inviter.senderPort ?: 0)
                .put("content_id", inviter.contentId ?: "")
        } else {
            JSONObject().put("has_invite", false)
        }
    }


    private suspend fun pullPeerContent(args: JSONObject?): JSONObject {
        val transfer = resolvePeerTransfer(args)
        val received = withContext(Dispatchers.IO) {
            container.receivedContentHandler.createLocalContent(transfer.peer, transfer.pulled)
        }
        container.contentRepository.addLocalContent(received)
        return localContentJson(received)
    }

    private suspend fun copyPeerContent(args: JSONObject?): String {
        val transfer = resolvePeerTransfer(args)
        return withContext(Dispatchers.IO) {
            val item = container.receivedContentHandler.createLocalContent(transfer.peer, transfer.pulled)
            container.receivedContentHandler.copyToSystemClipboard(item)
        }
    }

    private suspend fun savePeerContentAs(args: JSONObject?): String? {
        val transfer = resolvePeerTransfer(args)
        val fileName = exportFileName(transfer.peer, transfer.pulled)
        val mimeType = exportMimeType(transfer.peer, transfer.pulled)
        val targetUri = launchCreateDocument(fileName, mimeType) ?: return null

        withContext(Dispatchers.IO) {
            activity.contentResolver.openOutputStream(targetUri)?.use { output ->
                transfer.pulled.file?.inputStream()?.buffered()?.use { input ->
                    input.copyTo(output, COPY_BUFFER_SIZE)
                } ?: output.write(transfer.pulled.bytes)
            } ?: error("No se pudo abrir el destino de guardado")
            transfer.pulled.file?.delete()
        }

        return when (transfer.peer.announcement.contentType) {
            HubContentType.TEXT -> "Texto guardado en el destino elegido"
            HubContentType.IMAGE -> "Imagen guardada en el destino elegido"
            HubContentType.FILE -> "Archivo guardado en el destino elegido"
        }
    }

    private suspend fun openOverlay(): Boolean {
        requestRuntimePermissions()
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

    private suspend fun requestIdentityPermissionsIfNeeded() {
        requestRuntimePermissions()
        withContext(Dispatchers.Main.immediate) {
            if (!Settings.canDrawOverlays(activity)) {
                openOverlayPermissionSettings()
            }
        }
    }

    private suspend fun ensureDirectModePermissionsReady() {
        requestRuntimePermissions()

        val hasBle = TransportHardwareInspector.hasBlePermissions(activity) &&
            TransportHardwareInspector.hasBleAdvertisePermission(activity)
        val hasWifiDirect = TransportHardwareInspector.hasWifiDirectPermissions(activity)

        if (!hasBle || !hasWifiDirect) {
            error("Faltan permisos de Bluetooth/Nearby para usar modo directo")
        }
    }

    private fun publishAllLocal() {
        FenixHubService.start(activity)
        val items = container.contentRepository.localContent.value
        if (items.isEmpty()) return
        items.forEach { item ->
            if (!item.isPublished) {
                container.contentRepository.publish(item.contentId, SendMode.Broadcast)
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

    private fun optionalTrimmedString(args: JSONObject, key: String): String? {
        if (!args.has(key) || args.isNull(key)) return null
        return args.optString(key).trim().ifBlank { null }
    }

    private suspend fun resolvePeerTransfer(args: JSONObject?): PeerTransfer {
        val contentId = args?.optString("content_id").orEmpty()
        require(contentId.isNotBlank()) { "Peer invalido" }
        val peer = container.contentRepository.getPeer(contentId) ?: error("Peer no encontrado")
        val settings = container.settingsStore.current()
        require(settings.configured) { "Configura FenixHub antes de recibir contenido" }

        val pulled = container.httpClient.pullContent(peer, settings)
            .getOrElse { throw IllegalStateException("No se pudo recibir el contenido") }
        return PeerTransfer(peer = peer, pulled = pulled)
    }

    private fun exportFileName(peer: PeerContent, pulled: PulledContent): String {
        val preferredName = pulled.fileName?.takeIf(String::isNotBlank)
            ?: peer.announcement.fileName?.takeIf(String::isNotBlank)
        if (preferredName != null) {
            return sanitizeExportFileName(preferredName)
        }

        val baseName = "fenixhub-${UUID.randomUUID().toString().take(8)}"
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(exportMimeType(peer, pulled))
            ?: when (peer.announcement.contentType) {
                HubContentType.TEXT -> "txt"
                HubContentType.IMAGE -> "jpg"
                HubContentType.FILE -> "bin"
            }
        return "$baseName.$extension"
    }

    private fun exportMimeType(peer: PeerContent, pulled: PulledContent): String {
        val rawMimeType = pulled.mimeType?.takeIf(String::isNotBlank)
            ?: peer.announcement.mimeType?.takeIf(String::isNotBlank)
            ?: when (peer.announcement.contentType) {
                HubContentType.TEXT -> "text/plain"
                HubContentType.IMAGE -> "image/jpeg"
                HubContentType.FILE -> "application/octet-stream"
            }
        return rawMimeType.substringBefore(';').trim()
    }

    private fun sanitizeExportFileName(fileName: String): String {
        return fileName.map { ch ->
            if (ch in charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')) '_' else ch
        }.joinToString("").ifBlank { "fenixhub-item" }
    }

    private fun clipboardPrimaryUri(): Uri? {
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val item = clip.getItemAt(0)
        return item.uri ?: item.intent?.data
    }

    private fun scheduleDeferredPreviewRefresh(item: LocalContent) {
        if (item.contentType != HubContentType.IMAGE || item.preview.startsWith("data:image")) {
            return
        }

        activity.lifecycleScope.launch(Dispatchers.IO) {
            val refreshed = container.localContentFactory.refreshDeferredImagePreview(item) ?: return@launch
            if (refreshed.preview == item.preview) {
                return@launch
            }

            container.contentRepository.addLocalContent(refreshed)
            dispatch("window.__fenixExternalRefresh && window.__fenixExternalRefresh();")
        }
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

    private data class PeerTransfer(
        val peer: PeerContent,
        val pulled: PulledContent,
    )

    private companion object {
        const val TAG = "FenixHubAndroidBridge"
        const val MAX_PASTE_TEXT_CHARS = 8_192
        const val COPY_BUFFER_SIZE = 256 * 1024
    }
}
