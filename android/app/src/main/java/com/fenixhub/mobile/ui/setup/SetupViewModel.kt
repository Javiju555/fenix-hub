package com.fenixhub.mobile.ui.setup

import android.app.Application
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fenixhub.mobile.FenixHubApplication
import com.fenixhub.mobile.model.AppSettings
import com.fenixhub.mobile.util.CryptoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SetupViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as FenixHubApplication).container
    private val settingsStore = container.settingsStore
    private var derivePreviewJob: Job? = null

    val settings: StateFlow<AppSettings> = settingsStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = settingsStore.current(),
    )

    var deviceName by mutableStateOf(Build.MODEL ?: "Huawei Pura 70 Ultra")
        private set
    var passphrase by mutableStateOf("")
        private set
    var groupIdPreview by mutableStateOf("")
        private set
    var isSaving by mutableStateOf(false)
        private set

    fun onDeviceNameChange(value: String) {
        deviceName = value
    }

    fun onPassphraseChange(value: String) {
        passphrase = value
        if (value.isBlank()) {
            derivePreviewJob?.cancel()
            groupIdPreview = ""
            return
        }

        derivePreviewJob?.cancel()
        derivePreviewJob = viewModelScope.launch {
            val preview = withContext(Dispatchers.Default) {
                CryptoUtils.groupIdFromKey(CryptoUtils.deriveGroupKey(value))
            }
            if (passphrase == value) {
                groupIdPreview = preview
            }
        }
    }

    fun saveIdentity() {
        if (deviceName.isBlank() || passphrase.isBlank() || isSaving) return
        viewModelScope.launch {
            isSaving = true
            withContext(Dispatchers.Default) {
                settingsStore.saveIdentity(passphrase, deviceName)
            }
            isSaving = false
        }
    }
}
