package com.ultrashare.host

import android.os.Handler
import android.os.Looper

/**
 * Feature #93: Pause RTP stream when screen hasn't changed.
 * Saves significant battery on both sides during static display.
 * Sends heartbeat every 1s so viewer knows connection is alive.
 */
class IdleFrameSuppressor(
    private val onSuppressStart: () -> Unit,
    private val onSuppressEnd: () -> Unit,
    private val onHeartbeat: () -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private var lastChangeMs = System.currentTimeMillis()
    private var isSuppressed = false
    private val idleThresholdMs = 500L
    private val heartbeatIntervalMs = 1000L

    private val checkIdleRunnable = object : Runnable {
        override fun run() {
            val idleMs = System.currentTimeMillis() - lastChangeMs
            if (idleMs >= idleThresholdMs && !isSuppressed) {
                isSuppressed = true
                onSuppressStart()
            }
            if (isSuppressed) {
                onHeartbeat()  // Keep-alive packet
            }
            handler.postDelayed(this, heartbeatIntervalMs)
        }
    }

    fun notifyScreenChanged() {
        lastChangeMs = System.currentTimeMillis()
        if (isSuppressed) {
            isSuppressed = false
            onSuppressEnd()
        }
    }

    fun start() { handler.post(checkIdleRunnable) }
    fun stop() { handler.removeCallbacks(checkIdleRunnable) }
}
