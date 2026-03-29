package com.ultrashare.viewer

import android.app.PictureInPictureParams
import android.os.Bundle
import android.util.Rational
import android.view.MotionEvent
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.ultrashare.transport.DataChannel
import com.ultrashare.transport.RtpReceiver
import com.ultrashare.telemetry.LatencyTracker

/**
 * Feature #44: Hosts FrameRenderer (SurfaceView compositor thread)
 * Feature #53: Captures touch events, relays to host
 * Feature #75: PiP mode
 */
class ViewerActivity : AppCompatActivity() {

    private lateinit var frameRenderer: FrameRenderer
    private lateinit var hardwareDecoder: HardwareDecoder
    private lateinit var rtpReceiver: RtpReceiver
    private lateinit var dataChannel: DataChannel
    private lateinit var latencyTracker: LatencyTracker
    private lateinit var inputRelay: InputRelayClient

    private var hostIp = ""
    private var hostWidth = 1080
    private var hostHeight = 1920

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Feature #47: Keep screen on during viewing
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        hostIp = intent.getStringExtra("hostIp") ?: return finish()

        frameRenderer = FrameRenderer(this)
        setContentView(frameRenderer)

        latencyTracker = LatencyTracker()

        // Feature #51: Warm up decoder BEFORE first frame arrives
        hardwareDecoder = HardwareDecoder(
            outputSurface = frameRenderer.getDecoderSurface(),
            onFrameDecoded = { pts ->
                latencyTracker.onRender(pts)
            }
        ).also { it.warmUp() }

        // Feature #55: Control channel — separate from video
        dataChannel = DataChannel().also { it.connectAsViewer(hostIp, 5005) }

        // Feature #53: Touch relay to host
        inputRelay = InputRelayClient(dataChannel, hostWidth, hostHeight,
            frameRenderer.width.coerceAtLeast(1), frameRenderer.height.coerceAtLeast(1))

        // Start RTP receiver
        rtpReceiver = RtpReceiver(
            localPort = 5004,
            onConfigFrame = { spsData ->
                hardwareDecoder.configure(spsData)
            },
            onVideoFrame = { data, pts ->
                latencyTracker.onDecodeStart(pts)
                hardwareDecoder.submitFrame(data, pts)
            }
        ).also { it.start() }

        // Feature #71: Touch listener for pinch-zoom + relay
        setupTouchHandler()
    }

    private fun setupTouchHandler() {
        frameRenderer.setOnTouchListener { _, event ->
            // Feature #53: Tap/swipe → relay to host
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_MOVE -> {
                    inputRelay.relayTouch(event)
                }
            }
            true
        }
    }

    /**
     * Feature #75: Enter Picture-in-Picture mode
     */
    fun enterPip() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(hostWidth, hostHeight))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onDestroy() {
        if (::rtpReceiver.isInitialized) rtpReceiver.stop()
        if (::hardwareDecoder.isInitialized) hardwareDecoder.release()
        if (::dataChannel.isInitialized) dataChannel.stop()
        super.onDestroy()
    }
}
