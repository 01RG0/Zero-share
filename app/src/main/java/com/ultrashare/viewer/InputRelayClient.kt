package com.ultrashare.viewer

import android.view.MotionEvent
import com.ultrashare.transport.DataChannel

/**
 * Feature #53: Map viewer coordinates to host screen space, send via data channel.
 */
class InputRelayClient(
    private val dataChannel: DataChannel,
    private val hostW: Int,
    private val hostH: Int,
    private val viewerW: Int,
    private val viewerH: Int
) {
    private val scaleX = hostW.toFloat() / viewerW.toFloat()
    private val scaleY = hostH.toFloat() / viewerH.toFloat()

    fun relayTouch(event: MotionEvent) {
        // Map viewer coordinates to host coordinate space
        val hostX = event.x * scaleX
        val hostY = event.y * scaleY

        dataChannel.sendQueue.offer(
            DataChannel.ControlMessage.Touch(hostX, hostY, event.actionMasked)
        )
    }

    fun relayKey(keyCode: Int, action: Int) {
        dataChannel.sendQueue.offer(
            DataChannel.ControlMessage.Key(keyCode, action)
        )
    }
}
