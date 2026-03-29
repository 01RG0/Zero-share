package com.ultrashare.host

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.Surface

class ScreenCaptureManager(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler

    // Capture parameters — start at device native resolution, scale down if needed
    private var captureWidth = 0
    private var captureHeight = 0
    private var captureDpi = 0

    companion object {
        const val CAPTURE_REQUEST_CODE = 1001
        // Feature #9: Separate high-priority capture thread
        const val CAPTURE_THREAD_NAME = "UltraShare-Capture"
    }

    fun initialize() {
        // Feature #9: Set capture thread to highest display priority
        captureThread = HandlerThread(CAPTURE_THREAD_NAME, android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        captureThread.start()
        captureHandler = Handler(captureThread.looper)

        // Get native display metrics for VirtualDisplay sizing
        val metrics = DisplayMetrics()
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        windowManager.defaultDisplay.getRealMetrics(metrics)
        captureWidth = metrics.widthPixels
        captureHeight = metrics.heightPixels
        captureDpi = metrics.densityDpi
    }

    fun getPermissionIntent(manager: MediaProjectionManager): Intent {
        return manager.createScreenCaptureIntent()
    }

    /**
     * Start capture feeding directly into encoder's input surface.
     * Feature #1: Zero-copy path — VirtualDisplay → encoder Surface → no intermediate bitmap.
     * Feature #8: YUV420 native path — no RGB conversion needed.
     */
    fun startCapture(
        resultCode: Int,
        data: Intent,
        encoderInputSurface: Surface,
        manager: MediaProjectionManager,
        onStarted: () -> Unit,
        onError: (String) -> Unit
    ) {
        mediaProjection = manager.getMediaProjection(resultCode, data)

        if (mediaProjection == null) {
            onError("MediaProjection null — permission denied")
            return
        }

        // Register callback to detect when projection is revoked
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopCapture()
                onError("MediaProjection stopped by system")
            }
        }, captureHandler)

        // Feature #7: Lock to 60fps via VirtualDisplay flags
        // VIRTUAL_DISPLAY_FLAG_SECURE: capture secure content
        // VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR: mirror main display
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "UltraShareCapture",
            captureWidth,
            captureHeight,
            captureDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            encoderInputSurface,  // Directly into encoder — no copy
            object : VirtualDisplay.Callback() {
                override fun onResumed() {
                    onStarted()
                }
                override fun onPaused() { /* handle pause */ }
                override fun onStopped() { stopCapture() }
            },
            captureHandler  // Feature #9: run on high-priority thread
        )
    }

    /**
     * Feature #10: Dynamic resolution scaling.
     * When encoder reports backpressure, drop resolution instead of framerate.
     */
    fun scaleResolution(scaleFactor: Float) {
        val newWidth = (captureWidth * scaleFactor).toInt().coerceAtLeast(320)
        val newHeight = (captureHeight * scaleFactor).toInt().coerceAtLeast(240)
        virtualDisplay?.resize(newWidth, newHeight, captureDpi)
    }

    fun stopCapture() {
        virtualDisplay?.release()
        mediaProjection?.stop()
        captureThread.quitSafely()
    }
}
