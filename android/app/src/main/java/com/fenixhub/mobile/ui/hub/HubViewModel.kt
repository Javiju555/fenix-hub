package com.fenixhub.mobile.ui.hub

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fenixhub.mobile.FenixHubApplication
import com.fenixhub.mobile.model.AppSettings
import com.fenixhub.mobile.model.LocalContent
import com.fenixhub.mobile.model.PeerContent
import com.fenixhub.mobile.model.SendMode
import com.fenixhub.mobile.model.WifiDirectTransferState
import com.fenixhub.mobile.service.FenixHubService
import com.fenixhub.mobile.service.HotspotManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HubUiState(
    val settings: AppSettings = AppSettings(),
    val localContent: List<LocalContent> = emptyList(),
    val peers: List<PeerContent> = emptyList(),
    val selectedLocalContentId: String? = null,
)

class HubViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as FenixHubApplication).container
    private val repository = container.contentRepository
    private val settingsStore = container.settingsStore
    private val localContentFactory = container.localContentFactory

    val uiState: StateFlow<HubUiState> = combine(
        settingsStore.settingsFlow,
        repository.localContent,
        repository.peers,
        repository.selectedLocalContentId,
    ) { settings, localContent, peers, selectedId ->
        HubUiState(
            settings = settings,
            localContent = localContent,
            peers = peers,
            selectedLocalContentId = selectedId,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = HubUiState(),
    )

    /** Estado del hotspot local (sin internet). Observar para actualizar la UI. */
    val hotspotState: StateFlow<HotspotManager.State> = container.hotspotManager.state

    /** Estado de la transferencia WiFi Direct. */
    val wifiDirectTransferState: StateFlow<WifiDirectTransferState> =
        container.wifiDirectTransferController.transferState

    // ── Contenido ─────────────────────────────────────────────────────────────

    fun importText(text: String) {
        if (text.isBlank()) return
        repository.addLocalContent(localContentFactory.fromText(text))
    }

    fun importImage(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            localContentFactory.fromUri(uri)?.let(repository::addLocalContent)
        }
    }

    fun selectLocalContent(contentId: String) {
        repository.setSelectedLocalContent(contentId)
    }

    fun publishSelected(sendMode: SendMode = SendMode.Broadcast) {
        repository.publishSelected(sendMode)
    }

    fun unpublish(contentId: String) {
        repository.unpublish(contentId)
    }

    // ── Hotspot local ─────────────────────────────────────────────────────────

    /**
     * Activa el hotspot local sin internet.
     * El servicio arranca la red y llama a [HotspotManager.start].
     * Requiere permiso ACCESS_FINE_LOCATION (API 31–32) o
     * NEARBY_WIFI_DEVICES (API 33+) concedido previamente.
     */
    fun startHotspot() {
        FenixHubService.start(getApplication(), FenixHubService.ACTION_START_HOTSPOT)
    }

    /**
     * Desactiva el hotspot y cierra la reserva del sistema.
     */
    fun stopHotspot() {
        FenixHubService.start(getApplication(), FenixHubService.ACTION_STOP_HOTSPOT)
    }

    val publishedContentIds: List<String>
        get() = repository.localContent.value
            .filter { it.isPublished }
            .map { it.contentId }

    // ── WiFi Direct Transfer ──────────────────────────────────────────────

    /**
     * Inicia una transferencia por WiFi Direct del contenido seleccionado.
     * El contenido debe estar publicado previamente.
     */
    fun sendViaWifiDirect() {
        val contentId = uiState.value.selectedLocalContentId ?: return
        val app = getApplication<Application>()
        val service = app.getSystemService(FenixHubService::class.java)
            ?: return
        // Usar el servicio directamente si está corriendo
        FenixHubService.start(app, FenixHubService.ACTION_SHOW_OVERLAY)
    }

    /**
     * Inicia una transferencia por WiFi Direct de un contenido específico.
     */
    fun sendViaWifiDirect(contentId: String) {
        val app = getApplication<Application>()
        FenixHubService.start(app, FenixHubService.ACTION_SHOW_OVERLAY)
    }
}
