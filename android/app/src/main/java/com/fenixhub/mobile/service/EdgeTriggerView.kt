package com.fenixhub.mobile.service

import android.content.Context
import android.graphics.PixelFormat
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class EdgeTriggerView(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onDragNearEdge: () -> Unit,
) {
    private var triggerView: View? = null
    private var isOverlayVisible = false

    fun start() {
        if (triggerView != null) return

        val view = View(context).apply {
            alpha = 0f
            setOnDragListener { _, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION -> {
                        if (!isOverlayVisible) {
                            onDragNearEdge()
                        }
                    }
                    DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                        // Keep overlay visible until user closes it
                    }
                }
                true
            }
        }

        val params = WindowManager.LayoutParams(
            dp(TRIGGER_WIDTH_DP),
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.TOP
            x = 0
            y = 0
        }

        triggerView = view
        windowManager.addView(view, params)
    }

    fun stop() {
        triggerView?.let { view ->
            runCatching { windowManager.removeView(view) }
            triggerView = null
        }
    }

    fun setOverlayVisible(visible: Boolean) {
        isOverlayVisible = visible
    }

    private fun dp(value: Int): Int {
        val density = context.resources.displayMetrics.density
        return (value * density).toInt()
    }

    companion object {
        private const val TRIGGER_WIDTH_DP = 20
    }
}