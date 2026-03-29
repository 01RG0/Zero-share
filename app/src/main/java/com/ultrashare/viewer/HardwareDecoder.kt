package com.ultrashare.viewer

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Feature #43: Hardware decoder — 2–5ms vs 20–40ms software
 * Feature #44: Zero-copy → surface direct (no intermediate bitmap)
 */
class HardwareDecoder(
    private val outputSurface: Surface,
    private val onFrameDecoded: (Long) -> Unit  // delivers presentation time for latency tracking
) {
    private var decoder: MediaCodec? = null
    private var isRunning = AtomicBoolean(false)

    // Feature #51: Initialize decoder BEFORE first frame arrives
    fun warmUp(mimeType: String = "video/hevc") {
        decoder = MediaCodec.createDecoderByType(mimeType)

        val format = MediaFormat.createVideoFormat(mimeType, 1920, 1080).apply {
            // Feature #3: Match encoder's LOW_LATENCY mode
            setInteger(MediaFormat.KEY_LATENCY, 0)
            // Feature #45: No output buffering
            setInteger("allow-frame-drop", 0)
            // Feature #52: Enable surface timestamps for vsync alignment
            setInteger("enable-surface-timestamp", 1)
        }

        decoder?.configure(format, outputSurface, null, 0)
        decoder?.start()
        isRunning.set(true)
    }

    fun configure(spsData: ByteArray, mimeType: String = "video/hevc") {
        val format = MediaFormat.createVideoFormat(mimeType, 1920, 1080).apply {
            setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(spsData))
            setInteger(MediaFormat.KEY_LATENCY, 0)
            setInteger("allow-frame-drop", 0)
        }
        // Re-configure with actual SPS/PPS
        decoder?.stop()
        decoder?.configure(format, outputSurface, null, 0)
        decoder?.start()
    }

    /**
     * Submit a frame for decoding.
     * Feature #50: Drop frames that are too late to display.
     */
    fun submitFrame(data: ByteArray, presentationTimeUs: Long) {
        val decoder = decoder ?: return

        // Feature #50: Check if frame is already stale (>2 frame periods late)
        val nowUs = System.nanoTime() / 1000
        val frameAgeUs = nowUs - presentationTimeUs
        if (frameAgeUs > 33_000) {  // >33ms late (2 frames @ 60fps)
            // Drop stale frame — showing it would increase perceived latency
            return
        }

        val inputIndex = decoder.dequeueInputBuffer(0)  // Non-blocking
        if (inputIndex < 0) return  // Decoder busy — skip frame rather than block

        val inputBuffer = decoder.getInputBuffer(inputIndex) ?: return
        inputBuffer.clear()
        inputBuffer.put(data)

        decoder.queueInputBuffer(
            inputIndex,
            0,
            data.size,
            presentationTimeUs,
            0
        )

        // Release output to surface immediately — Feature #44, #45
        val bufferInfo = MediaCodec.BufferInfo()
        val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)  // Non-blocking
        if (outputIndex >= 0) {
            // Feature #52: Surface timestamp for vsync alignment
            decoder.releaseOutputBuffer(outputIndex, bufferInfo.presentationTimeUs)
            onFrameDecoded(bufferInfo.presentationTimeUs)
        }
    }

    fun release() {
        isRunning.set(false)
        decoder?.stop()
        decoder?.release()
    }
}
