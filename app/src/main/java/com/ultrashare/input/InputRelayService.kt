package com.ultrashare.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * Feature #53: Relay touch events from viewer to host.
 * Uses AccessibilityService — no root required.
 */
class InputRelayService : AccessibilityService() {

    companion object {
        var instance: InputRelayService? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}
    override fun onInterrupt() {}

    /**
     * Simulate a tap at given screen coordinates.
     */
    fun simulateTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Simulate a swipe gesture.
     */
    fun simulateSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
