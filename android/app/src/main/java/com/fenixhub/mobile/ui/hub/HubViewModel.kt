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
}
