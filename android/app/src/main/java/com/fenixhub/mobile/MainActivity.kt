package com.fenixhub.mobile

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import com.fenixhub.mobile.service.FenixHubService
import com.fenixhub.mobile.web.AndroidHubBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val container by lazy { (application as FenixHubApplication).container }

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var bridge: AndroidHubBridge

    private var pendingFilePicker: CompletableDeferred<Uri?>? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        pendingFilePicker?.takeIf { it.isActive }?.complete(uri)
        pendingFilePicker = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (tryOpenOverlayShortcut(intent)) {
            return
        }

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        bridge = AndroidHubBridge(
            activity = this,
            container = container,
            launchFilePicker = ::launchFilePicker,
            readClipboardText = ::clipboardText,
            openOverlayPermissionSettings = ::openOverlayPermissionSettings,
        )

        webView = createWebView()
        bridge.attach(webView)
        setContentView(webView)

        startServiceIfConfigured()
        webView.loadUrl(HUB_URL)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        startServiceIfConfigured()
        notifyFrontendRefresh()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (tryOpenOverlayShortcut(intent)) {
            return
        }
        handleIntent(intent)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface(BRIDGE_NAME)
            webView.destroy()
        }
        pendingFilePicker?.takeIf { it.isActive }?.complete(null)
        pendingFilePicker = null
        super.onDestroy()
    }

    private fun createWebView(): WebView {
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        return WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.BLACK)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = WebView.OVER_SCROLL_NEVER

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                allowFileAccess = false
                allowContentAccess = true
                builtInZoomControls = false
                displayZoomControls = false
                setSupportZoom(false)
            }

            addJavascriptInterface(bridge, BRIDGE_NAME)

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    return assetLoader.shouldInterceptRequest(request.url)
                }
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        val launchImagePicker = intent.getBooleanExtra(EXTRA_LAUNCH_IMAGE_PICKER, false)
        val sharedPayload = extractSharedPayload(intent)
        if (!launchImagePicker && sharedPayload == null) return

        intent.removeExtra(EXTRA_LAUNCH_IMAGE_PICKER)
        clearSharedPayload(intent)

        lifecycleScope.launch {
            var shouldRefresh = false

            if (sharedPayload != null) {
                val importedCount = runCatching { importSharedPayload(sharedPayload) }
                    .getOrElse {
                        toast("No se pudo importar el contenido compartido")
                        return@launch
                    }
                if (importedCount > 0) {
                    toast(
                        if (importedCount == 1) {
                            "Contenido compartido anadido al hub"
                        } else {
                            "$importedCount elementos anadidos al hub"
                        },
                    )
                    shouldRefresh = true
                }
            }

            if (launchImagePicker) {
                val item = runCatching { bridge.importPickedFile() }
                    .getOrElse {
                        toast("No se pudo anadir el archivo")
                        return@launch
                    }
                if (item != null) {
                    toast("Contenido anadido al hub")
                    shouldRefresh = true
                }
            }

            if (shouldRefresh) {
                notifyFrontendRefresh()
            }
        }
    }

    private fun tryOpenOverlayShortcut(intent: Intent?): Boolean {
        if (intent?.action != ACTION_OPEN_OVERLAY) return false
        if (!container.settingsStore.current().configured || !Settings.canDrawOverlays(this)) {
            return false
        }
        FenixHubService.start(this, FenixHubService.ACTION_SHOW_OVERLAY)
        finish()
        return true
    }

    private fun startServiceIfConfigured() {
        if (container.settingsStore.current().configured) {
            FenixHubService.start(this)
        }
    }

    private suspend fun launchFilePicker(): Uri? {
        if (pendingFilePicker?.isActive == true) {
            error("Ya hay un selector de archivos abierto")
        }
        val deferred = CompletableDeferred<Uri?>()
        pendingFilePicker = deferred
        withContext(Dispatchers.Main.immediate) {
            filePickerLauncher.launch("*/*")
        }
        return try {
            deferred.await()
        } finally {
            if (pendingFilePicker === deferred) {
                pendingFilePicker = null
            }
        }
    }

    private fun notifyFrontendRefresh() {
        if (!::webView.isInitialized) return
        webView.post {
            webView.evaluateJavascript(
                "window.__fenixExternalRefresh && window.__fenixExternalRefresh();",
                null,
            )
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
        val description = clip.description
        val hasText = description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
            description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
        if (!hasText) return null

        val text = clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isBlank() || text.length > MAX_CLIPBOARD_TEXT_CHARS) {
            return null
        }
        return text
    }

    private fun extractSharedPayload(intent: Intent): SharedPayload? {
        val action = intent.action ?: return null
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null

        val texts = linkedSetOf<String>()
        val uriStrings = linkedSetOf<String>()

        fun addText(rawText: CharSequence?) {
            val text = rawText?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) {
                texts += text
            }
        }

        fun addUri(uri: Uri?) {
            if (uri != null) {
                uriStrings += uri.toString()
            }
        }

        addText(intent.getStringExtra(Intent.EXTRA_TEXT))
        addText(intent.getStringExtra(Intent.EXTRA_HTML_TEXT))

        intent.clipData?.let { clipData ->
            repeat(clipData.itemCount) { index ->
                val item = clipData.getItemAt(index)
                val uri = item.uri ?: item.intent?.data
                addUri(uri)
                if (uri == null) {
                    addText(item.coerceToText(this))
                }
            }
        }

        addUri(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?.forEach(::addUri)

        val uris = uriStrings.map(Uri::parse)
        if (texts.isEmpty() && uris.isEmpty()) return null

        return SharedPayload(
            texts = texts.toList(),
            uris = uris,
        )
    }

    private suspend fun importSharedPayload(payload: SharedPayload): Int {
        return withContext(Dispatchers.IO) {
            var importedCount = 0

            payload.texts.forEach { text ->
                container.localContentFactory.fromText(text)
                    .also(container.contentRepository::addLocalContent)
                importedCount += 1
            }

            payload.uris.forEach { uri ->
                container.localContentFactory.fromUri(uri)
                    ?.also(container.contentRepository::addLocalContent)
                    ?.let { importedCount += 1 }
            }

            importedCount
        }
    }

    private fun clearSharedPayload(intent: Intent) {
        intent.action = null
        intent.type = null
        intent.clipData = null
        intent.removeExtra(Intent.EXTRA_TEXT)
        intent.removeExtra(Intent.EXTRA_HTML_TEXT)
        intent.removeExtra(Intent.EXTRA_STREAM)
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private data class SharedPayload(
        val texts: List<String>,
        val uris: List<Uri>,
    )

    companion object {
        private const val BRIDGE_NAME = "FenixHubBridge"
        private const val EXTRA_LAUNCH_IMAGE_PICKER = "extra_launch_image_picker"
        private const val HUB_URL = "https://appassets.androidplatform.net/assets/index.html"
        private const val MAX_CLIPBOARD_TEXT_CHARS = 8_192
        const val ACTION_OPEN_OVERLAY = "com.fenixhub.mobile.action.OPEN_OVERLAY"

        fun createIntent(context: Context, launchImagePicker: Boolean = false): Intent {
            return Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_LAUNCH_IMAGE_PICKER, launchImagePicker)
            }
        }
    }
}
