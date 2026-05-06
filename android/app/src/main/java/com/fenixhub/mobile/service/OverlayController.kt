package com.fenixhub.mobile.service

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.WindowManager
import androidx.webkit.WebViewAssetLoader
import kotlin.math.abs
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
    private var minimized = false
    private var snapSide: String = "right"

    // Native drag state
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartParamX = 0
    private var dragStartParamY = 0
    private var isDragging = false

    fun show(onDismissed: (() -> Unit)? = null) {
        if (!Settings.canDrawOverlays(context)) return

        if (webView != null) {
            if (minimized) {
                expand()
            }
            return
        }

        val currentBridge = OverlayWebBridge(
            context = context,
            repository = repository,
            settingsStore = settingsStore,
            localContentFactory = localContentFactory,
            tempClipboardStore = tempClipboardStore,
            receivedContentHandler = receivedContentHandler,
            httpClient = httpClient,
            onOpenMainApp = ::openMainApp,
            onMinimizeOverlay = ::minimize,
            onExpandOverlay = ::expand,
            onCloseOverlay = {
                onDismissed?.invoke()
                dismiss()
            },
            onSnapOverlay = ::snapOverlay,
        )
        bridge = currentBridge
        val view = createWebView(currentBridge)
        val params = baseLayoutParams()
        webView = view
        layoutParams = params
        minimized = false
        currentBridge.attach(view)
        view.alpha = 0f
        windowManager.addView(view, params)

        view.viewTreeObserver.addOnGlobalLayoutListener(
            object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (view.width == 0) return
                    view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    view.translationX = view.width.toFloat()
                    view.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(SLIDE_IN_DURATION_MS)
                        .setInterpolator(android.view.animation.OvershootInterpolator(0.8f))
                        .start()
                }
            },
        )
        view.loadUrl(OVERLAY_URL)
    }

    fun minimize() {
        val current = webView ?: return
        if (minimized) return
        if (current.parent == null) return

        val params = minimizedLayoutParams()
        layoutParams = params
        windowManager.updateViewLayout(current, params)
        minimized = true
        installDragListener(current)
        setOverlayMinimized(true)
    }

    fun snapOverlay(side: String) {
        val current = webView ?: return
        if (!minimized) return
        if (current.parent == null) return
        snapSide = side
        snapToSide(snapSide)
        val script = "window.__fenixOverlaySetSnapSide && window.__fenixOverlaySetSnapSide('$side');"
        current.post { current.evaluateJavascript(script, null) }
    }

    fun expand() {
        val current = webView ?: return
        if (!minimized) return
        if (current.parent == null) return

        current.setOnTouchListener(null)
        val params = baseLayoutParams()
        layoutParams = params
        windowManager.updateViewLayout(current, params)
        minimized = false
        setOverlayMinimized(false)
    }

    private fun installDragListener(view: WebView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val size = dp(MINIMIZED_SIZE_DP)
            view.systemGestureExclusionRects = listOf(Rect(0, 0, size, size))
        }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    dragStartParamX = layoutParams?.x ?: 0
                    dragStartParamY = layoutParams?.y ?: 0
                    isDragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - dragStartRawX).toInt()
                    val dy = (event.rawY - dragStartRawY).toInt()
                    if (!isDragging && (abs(dx) > 8 || abs(dy) > 8)) isDragging = true
                    if (isDragging) {
                        moveBubbleTo(dragStartParamX + dx, dragStartParamY + dy)
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        isDragging = false
                        snapToNearestEdge()
                        true
                    } else false
                }
                else -> false
            }
        }
    }

    private fun moveBubbleTo(x: Int, y: Int) {
        val current = webView ?: return
        if (current.parent == null) return
        val params = layoutParams ?: return
        val bounds = currentBounds()
        val size = dp(MINIMIZED_SIZE_DP)
        params.x = x.coerceIn(0, bounds.width() - size)
        params.y = y.coerceIn(0, bounds.height() - size)
        runCatching { windowManager.updateViewLayout(current, params) }
    }

    private fun snapToNearestEdge() {
        val bounds = currentBounds()
        val size = dp(MINIMIZED_SIZE_DP)
        val margin = dp(MINIMIZED_MARGIN_DP)
        val currentX = layoutParams?.x ?: 0
        val currentY = layoutParams?.y ?: 0
        val newSide = if (currentX + size / 2 < bounds.width() / 2) "left" else "right"
        snapSide = newSide
        val snapX = if (newSide == "left") margin else bounds.width() - size - margin
        moveBubbleTo(snapX, currentY)
        val script = "window.__fenixOverlaySetSnapSide && window.__fenixOverlaySetSnapSide('$newSide');"
        webView?.post { webView?.evaluateJavascript(script, null) }
    }

    private fun snapToSide(side: String) {
        val bounds = currentBounds()
        val size = dp(MINIMIZED_SIZE_DP)
        val margin = dp(MINIMIZED_MARGIN_DP)
        val currentY = layoutParams?.y ?: margin
        val snapX = if (side == "left") margin else bounds.width() - size - margin
        moveBubbleTo(snapX, currentY)
    }

    fun dismiss() {
        webView?.let { current ->
            if (current.parent != null) {
                runCatching { windowManager.removeView(current) }
            }
            current.removeJavascriptInterface(BRIDGE_NAME)
            current.destroy()
        }
        webView = null
        layoutParams = null
        minimized = false
        bridge?.destroy()
        bridge = null
    }

    private fun setOverlayMinimized(isMinimized: Boolean) {
        val current = webView ?: return
        val script = "window.__fenixOverlaySetMinimized && window.__fenixOverlaySetMinimized(${if (isMinimized) "true" else "false"});"
        current.post {
            current.evaluateJavascript(script, null)
        }
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
            setOnDragListener { _, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> true
                    DragEvent.ACTION_DRAG_ENTERED -> {
                        if (minimized) expand()
                        true
                    }
                    DragEvent.ACTION_DROP -> bridge.importDroppedClipData(event.clipData)
                    DragEvent.ACTION_DRAG_ENDED -> true
                    else -> true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    return assetLoader.shouldInterceptRequest(request.url)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    setOverlayMinimized(minimized)
                    if (minimized) {
                        val script = "window.__fenixOverlaySetSnapSide && window.__fenixOverlaySetSnapSide('$snapSide');"
                        view?.post { view.evaluateJavascript(script, null) }
                    }
                }
            }
        }
    }

    private fun baseLayoutParams(): WindowManager.LayoutParams {
        val bounds = currentBounds()
        val portrait = bounds.height() >= bounds.width()
        val maxWidth = if (portrait) {
            (bounds.width() * PANEL_WIDTH_RATIO_PORTRAIT).toInt()
        } else {
            (bounds.width() * PANEL_WIDTH_RATIO_LANDSCAPE).toInt()
        }
        val maxHeight = if (portrait) {
            (bounds.height() * PANEL_HEIGHT_RATIO_PORTRAIT).toInt()
        } else {
            (bounds.height() * PANEL_HEIGHT_RATIO_LANDSCAPE).toInt()
        }
        val width = minOf(dp(PANEL_WIDTH_DP), maxWidth).coerceAtLeast(dp(PANEL_MIN_WIDTH_DP))
        val height = minOf(dp(PANEL_HEIGHT_DP), maxHeight).coerceAtLeast(dp(PANEL_MIN_HEIGHT_DP))

        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.TOP
            x = dp(PANEL_MARGIN_DP)
            y = dp(PANEL_MARGIN_DP)
        }
    }

    private fun minimizedLayoutParams(): WindowManager.LayoutParams {
        val bounds = currentBounds()
        val size = dp(MINIMIZED_SIZE_DP)
        val margin = dp(MINIMIZED_MARGIN_DP)
        val snapX = if (snapSide == "left") margin else bounds.width() - size - margin
        return WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = snapX
            y = margin
        }
    }

    private fun dp(value: Int): Int {
        val density = context.resources.displayMetrics.density
        return (value * density).toInt()
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

        private const val PANEL_WIDTH_DP = 340
        private const val PANEL_HEIGHT_DP = 660
        private const val PANEL_MIN_WIDTH_DP = 160
        private const val PANEL_MIN_HEIGHT_DP = 330
        private const val PANEL_MARGIN_DP = 12
        private const val PANEL_WIDTH_RATIO_PORTRAIT = 0.42f
        private const val PANEL_HEIGHT_RATIO_PORTRAIT = 0.82f
        private const val PANEL_WIDTH_RATIO_LANDSCAPE = 0.46f
        private const val PANEL_HEIGHT_RATIO_LANDSCAPE = 0.86f

        private const val MINIMIZED_SIZE_DP = 58
        private const val MINIMIZED_MARGIN_DP = 12
        private const val SLIDE_IN_DURATION_MS = 280L
    }
}
