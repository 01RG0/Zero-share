package com.ultrashare.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Feature #46: SurfaceView — compositor thread, never blocks main UI thread.
 * Feature #47: 120Hz display rate support.
 * Feature #77: Cursor/touch visualization overlay.
 */
class FrameRenderer(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private var cursorX = 0f
    private var cursorY = 0f
    private var showCursor = false
    private val cursorPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        alpha = 180
    }

    init {
        holder.addCallback(this)
        // Feature #46: Keep surface in background (compositor independent)
        setZOrderOnTop(false)
        // Feature #47: Request highest available refresh rate
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            display?.let { disp ->
                val modes = disp.supportedModes
                val highest = modes.maxByOrNull { it.refreshRate }
                holder.setFrameRate(
                    highest?.refreshRate ?: 60f,
                    android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
                )
            }
        }
    }

    /**
     * Feature #77: Show host touch position on viewer screen.
     */
    fun updateCursorPosition(hostX: Float, hostY: Float) {
        // Convert host coordinates to viewer screen space
        val scaleX = width.toFloat() / 1080f  // Assume 1080p host
        val scaleY = height.toFloat() / 1920f
        cursorX = hostX * scaleX
        cursorY = hostY * scaleY
        showCursor = true
        invalidate()
    }

    fun hideCursor() {
        showCursor = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (showCursor) {
            canvas.drawCircle(cursorX, cursorY, 20f, cursorPaint)
        }
    }

    // Returns Surface for decoder to render into — Feature #44: zero-copy
    fun getDecoderSurface() = holder.surface

    override fun surfaceCreated(holder: SurfaceHolder) {}
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) {}
}
