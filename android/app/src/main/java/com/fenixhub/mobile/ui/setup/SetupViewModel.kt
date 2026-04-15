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
    var validationError by mutableStateOf<String?>(null)
        private set
    var isSaving by mutableStateOf(false)
        private set

    fun onDeviceNameChange(value: String) {
        deviceName = value
        validationError = null
    }

    fun onPassphraseChange(value: String) {
        passphrase = value
        validationError = null
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
        if (isSaving) return

        if (deviceName.isBlank()) {
            validationError = "El nombre del dispositivo es obligatorio"
            return
        }

        CryptoUtils.validatePassphraseStrength(passphrase)?.let { message ->
            validationError = message
            return
        }

        viewModelScope.launch {
            isSaving = true
            runCatching {
                withContext(Dispatchers.Default) {
                    settingsStore.saveIdentity(passphrase, deviceName)
                }
            }.onFailure { error ->
                validationError = error.message ?: "No se pudo guardar la identidad"
            }.onSuccess {
                validationError = null
            }
            isSaving = false
        }
    }
}
