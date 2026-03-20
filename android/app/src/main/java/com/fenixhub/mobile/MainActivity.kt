package com.fenixhub.mobile

import android.Manifest
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fenixhub.mobile.service.FenixHubService
import com.fenixhub.mobile.ui.hub.HubScreen
import com.fenixhub.mobile.ui.hub.HubViewModel
import com.fenixhub.mobile.ui.setup.SetupScreen
import com.fenixhub.mobile.ui.setup.SetupViewModel
import com.fenixhub.mobile.ui.theme.FenixHubTheme

class MainActivity : ComponentActivity() {
    private val setupViewModel: SetupViewModel by viewModels()
    private val hubViewModel: HubViewModel by viewModels()
    private val container by lazy { (application as FenixHubApplication).container }

    private var boundService: FenixHubService? = null
    private val overlayPermissionGranted = mutableStateOf(false)
    private val notificationPermissionGranted = mutableStateOf(false)
    private val pendingImagePickerLaunch = mutableStateOf(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            boundService = (service as? FenixHubService.LocalBinder)?.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPermissionState()
        pendingImagePickerLaunch.value = intent?.getBooleanExtra(EXTRA_LAUNCH_IMAGE_PICKER, false) == true

        setContent {
            FenixHubTheme {
                val settings by setupViewModel.settings.collectAsStateWithLifecycle()
                val hubState by hubViewModel.uiState.collectAsStateWithLifecycle()
                val context = LocalContext.current

                val imagePickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetContent(),
                ) { uri: Uri? ->
                    uri?.let(hubViewModel::importImage)
                    pendingImagePickerLaunch.value = false
                }

                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                ) {
                    refreshPermissionState()
                }

                LaunchedEffect(settings.configured) {
                    if (settings.configured) {
                        FenixHubService.start(context)
                    }
                }

                LaunchedEffect(settings.configured, pendingImagePickerLaunch.value) {
                    if (settings.configured && pendingImagePickerLaunch.value) {
                        imagePickerLauncher.launch("*/*")
                    }
                }

                if (!settings.configured) {
                    SetupScreen(
                        deviceName = setupViewModel.deviceName,
                        passphrase = setupViewModel.passphrase,
                        groupIdPreview = setupViewModel.groupIdPreview,
                        isSaving = setupViewModel.isSaving,
                        overlayPermissionGranted = overlayPermissionGranted.value,
                        onDeviceNameChange = setupViewModel::onDeviceNameChange,
                        onPassphraseChange = setupViewModel::onPassphraseChange,
                        onRequestOverlayPermission = ::openOverlayPermissionSettings,
                        onSave = setupViewModel::saveIdentity,
                    )
                } else {
                    HubScreen(
                        uiState = hubState,
                        overlayPermissionGranted = overlayPermissionGranted.value,
                        notificationPermissionGranted = notificationPermissionGranted.value,
                        onOpenOverlay = {
                            if (overlayPermissionGranted.value) {
                                FenixHubService.start(context, FenixHubService.ACTION_SHOW_OVERLAY)
                            } else {
                                openOverlayPermissionSettings()
                            }
                        },
                        onRequestOverlayPermission = ::openOverlayPermissionSettings,
                        onRequestNotificationPermission = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onPasteText = {
                            val clipboardText = clipboardText()
                            if (clipboardText.isNullOrBlank()) {
                                toast("El portapapeles no contiene texto")
                            } else {
                                hubViewModel.importText(clipboardText)
                            }
                        },
                        onPickImage = {
                            pendingImagePickerLaunch.value = true
                            imagePickerLauncher.launch("*/*")
                        },
                        onPublishSelected = {
                            boundService?.publishSelected() ?: hubViewModel.publishSelected()
                        },
                        onCopySelected = {
                            val selected = hubState.localContent.firstOrNull { it.contentId == hubState.selectedLocalContentId }
                                ?: hubState.localContent.firstOrNull()
                            if (selected == null) {
                                toast("No hay contenido local seleccionado")
                            } else {
                                toast(container.receivedContentHandler.copyToSystemClipboard(selected))
                            }
                        },
                        onSelectLocalContent = hubViewModel::selectLocalContent,
                        onReceivePeer = { peer ->
                            boundService?.receivePeer(peer) ?: toast("Servicio FenixHub no conectado")
                        },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, FenixHubService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        runCatching { unbindService(serviceConnection) }
        boundService = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_LAUNCH_IMAGE_PICKER, false) == true) {
            pendingImagePickerLaunch.value = true
        }
    }

    private fun refreshPermissionState() {
        overlayPermissionGranted.value = Settings.canDrawOverlays(this)
        notificationPermissionGranted.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun openOverlayPermissionSettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun clipboardText(): String? {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val clip = clipboard.primaryClip ?: return null
        return if (clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) {
            clip.getItemAt(0).coerceToText(this)?.toString()
        } else {
            clip.getItemAt(0).coerceToText(this)?.toString()
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val EXTRA_LAUNCH_IMAGE_PICKER = "extra_launch_image_picker"

        fun createIntent(context: Context, launchImagePicker: Boolean = false): Intent {
            return Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_LAUNCH_IMAGE_PICKER, launchImagePicker)
            }
        }
    }
}
