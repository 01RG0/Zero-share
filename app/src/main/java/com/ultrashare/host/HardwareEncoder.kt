package com.ultrashare.host

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

class HardwareEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int = 60,                     // Feature #7: 60fps
    private val onEncodedFrame: (ByteArray, Long, Boolean) -> Unit,
    private val onEncoderReady: (Surface) -> Unit,
    private val onError: (String) -> Unit
) {

    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val isRunning = AtomicBoolean(false)

    companion object {
        // Feature #13: Prefer H.265, fallback to H.264
        const val MIME_HEVC = "video/hevc"
        const val MIME_H264 = "video/avc"
        const val MIME_AV1  = "video/av01"      // Feature #14: AV1 option

        // Target bitrates by content type (Feature #19)
        const val BITRATE_STATIC_SCREEN = 500_000      // 500 Kbps — static UI
        const val BITRATE_ACTIVE_UI     = 4_000_000    // 4 Mbps — scrolling/animation
        const val BITRATE_VIDEO_CONTENT = 10_000_000   // 10 Mbps — video playback

        const val TAG = "HardwareEncoder"
    }

    /**
     * Feature #2: Force hardware encoder.
     * Returns null if no HW encoder found — caller should show warning.
     */
    private fun findHardwareEncoder(mime: String): String? {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        for (info in codecList.codecInfos) {
            if (!info.isEncoder) continue
            if (info.name.contains("google", ignoreCase = true)) continue // Skip SW encoder
            if (info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) {
                Log.d(TAG, "Found HW encoder: ${info.name}")
                return info.name
            }
        }
        return null
    }

    fun startEncoding() {
        val mime = when {
            findHardwareEncoder(MIME_HEVC) != null -> MIME_HEVC
            findHardwareEncoder(MIME_H264) != null -> MIME_H264
            else -> {
                onError("No hardware encoder found")
                return
            }
        }

        val encoderName = findHardwareEncoder(mime)!!
        encoder = MediaCodec.createByCodecName(encoderName)

        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            // Feature #2: Hardware acceleration
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)

            // Feature #19: Start with active UI bitrate, adapt later
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE_ACTIVE_UI)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)  // Feature #7: 60fps

            // Feature #5: Short I-frame interval — fast recovery after packet loss
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)

            // Feature #3: LOW_LATENCY mode — eliminates B-frames, single-frame lookahead
            // This is the single most important encoder setting for latency
            setInteger(MediaFormat.KEY_LATENCY, 0)

            // Feature #20: Explicitly zero B-frames
            setInteger("num-b-frames", 0)

            // Feature #6: Intra-refresh instead of keyframe burst
            // Spreads keyframe data across 30 frames, no single huge packet
            setInteger(MediaFormat.KEY_INTRA_REFRESH_PERIOD, 30)

            // Feature #17: High Profile for efficiency (H.264 only)
            if (mime == MIME_H264) {
                setInteger(MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_LEVEL,
                    MediaCodecInfo.CodecProfileLevel.AVCLevel42)
            }

            // Feature #18: QP floor — never let quality drop below readable
            // QP 18 = near-lossless, QP 26 = good quality, QP 51 = terrible
            setInteger(MediaFormat.KEY_VIDEO_QP_MIN, 18)
            setInteger(MediaFormat.KEY_VIDEO_QP_MAX, 35)

            // Feature #21: Priority hint to OS scheduler
            setInteger(MediaFormat.KEY_PRIORITY, 0) // 0 = real-time

            // Feature #22: Always output frame even if screen unchanged
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 0L)

            // Feature #16: Request surface timestamps (hardware clock)
            setInteger("use-surface-timestamp", 1)
        }

        encoder?.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                // Surface mode — input comes from VirtualDisplay, not manual buffers
            }

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo
            ) {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    // SPS/PPS — send to viewer for decoder init
                    val buffer = codec.getOutputBuffer(index)!!
                    val configData = ByteArray(info.size)
                    buffer.get(configData)
                    onEncodedFrame(configData, info.presentationTimeUs, true)
                    codec.releaseOutputBuffer(index, false)
                    return
                }

                if (info.size > 0) {
                    val buffer = codec.getOutputBuffer(index)!!
                    val frameData = ByteArray(info.size)
                    buffer.get(frameData)
                    val isKeyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    // Feature #12: Hardware timestamp in presentationTimeUs
                    onEncodedFrame(frameData, info.presentationTimeUs, isKeyFrame)
                }
                codec.releaseOutputBuffer(index, false)
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                onError("Encoder error: ${e.message}")
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                Log.d(TAG, "Encoder format changed: $format")
            }
        }, null) // null = use calling thread's looper or new thread

        encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder?.createInputSurface()  // Surface for VirtualDisplay
        encoder?.start()

        inputSurface?.let { onEncoderReady(it) }
        isRunning.set(true)
    }

    /**
     * Feature #10: Dynamic bitrate adjustment for resolution/content changes
     */
    fun adaptBitrate(newBitrate: Int) {
        val params = Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate)
        }
        encoder?.setParameters(params)
    }

    /**
     * Feature #11: Force immediate keyframe (on viewer reconnect or quality request)
     */
    fun requestKeyFrame() {
        val params = Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        }
        encoder?.setParameters(params)
    }

    fun stopEncoding() {
        isRunning.set(false)
        encoder?.signalEndOfInputStream()
        encoder?.stop()
        encoder?.release()
        inputSurface?.release()
    }
}
