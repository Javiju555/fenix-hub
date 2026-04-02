package com.fenixhub.mobile.service

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.WindowManager
import androidx.webkit.WebViewAssetLoader
import com.fenixhub.mobile.MainActivity
import com.fenixhub.mobile.data.ContentRepository
import com.fenixhub.mobile.data.ReceivedContentHandler
import com.fenixhub.mobile.data.SettingsStore
import com.fenixhub.mobile.data.TempClipboardStore
import com.fenixhub.mobile.network.FenixHttpClient
import com.fenixhub.mobile.util.LocalContentFactory

class OverlayController(
    private val context: Context,
    repository: ContentRepository,
    settingsStore: SettingsStore,
    localContentFactory: LocalContentFactory,
    tempClipboardStore: TempClipboardStore,
    receivedContentHandler: ReceivedContentHandler,
    httpClient: FenixHttpClient,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    private val repository = repository
    private val settingsStore = settingsStore
    private val localContentFactory = localContentFactory
    private val tempClipboardStore = tempClipboardStore
    private val receivedContentHandler = receivedContentHandler
    private val httpClient = httpClient

    private var bridge: OverlayWebBridge? = null
    private var webView: WebView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    fun show() {
        if (!Settings.canDrawOverlays(context) || webView != null) return

        val currentBridge = OverlayWebBridge(
            context = context,
            repository = repository,
            settingsStore = settingsStore,
            localContentFactory = localContentFactory,
            tempClipboardStore = tempClipboardStore,
            receivedContentHandler = receivedContentHandler,
            httpClient = httpClient,
            onOpenMainApp = ::openMainApp,
        )
        bridge = currentBridge
        val view = createWebView(currentBridge)
        val params = baseLayoutParams()
        webView = view
        layoutParams = params
        currentBridge.attach(view)
        windowManager.addView(view, params)
        view.loadUrl(OVERLAY_URL)
    }

    fun dismiss() {
        webView?.let { current ->
            runCatching { windowManager.removeView(current) }
            current.removeJavascriptInterface(BRIDGE_NAME)
            current.destroy()
        }
        webView = null
        layoutParams = null
        bridge?.destroy()
        bridge = null
    }

    private fun createWebView(bridge: OverlayWebBridge): WebView {
        return WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.TRANSPARENT)
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

    private fun baseLayoutParams(): WindowManager.LayoutParams {
        val bounds = currentBounds()
        val portrait = bounds.height() >= bounds.width()
        val width = if (portrait) (bounds.width() * WIDTH_RATIO).toInt() else bounds.width()
        val height = if (portrait) bounds.height() else (bounds.height() * HEIGHT_RATIO).toInt()

        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = if (portrait) Gravity.END or Gravity.TOP else Gravity.TOP or Gravity.START
        }
    }

    private fun currentBounds(): Rect {
        return windowManager.currentWindowMetrics.bounds
    }

    private fun openMainApp() {
        dismiss()
        val intent = MainActivity.createIntent(context).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        context.startActivity(intent)
    }

    companion object {
        private const val BRIDGE_NAME = "FenixHubBridge"
        private const val OVERLAY_URL = "https://appassets.androidplatform.net/assets/overlay.html"
        private const val WIDTH_RATIO = 0.30f
        private const val HEIGHT_RATIO = 0.30f
    }
}
