package com.ultrashare.host

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/**
 * Selects the best available hardware codec based on device capability.
 * Priority: H.265 HW > H.264 HW > AV1 HW > H.264 SW (never VP8)
 *
 * Feature #15: VP8 is explicitly excluded — it has poor text clarity,
 * inconsistent hardware support, and inferior compression vs H.265.
 */
object CodecSelector {

    data class CodecChoice(
        val mimeType: String,
        val codecName: String,
        val isHardware: Boolean,
        val maxBitrate: Int,
        val maxFps: Int
    )

    fun selectBestVideoCodec(width: Int, height: Int): CodecChoice? {
        val preferences = listOf(
            "video/hevc",   // H.265 — Feature #13: Primary choice
            "video/avc",    // H.264 — Feature #17: Fallback
            "video/av01",   // AV1   — Feature #14: Low-bandwidth option
        )

        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)

        for (mime in preferences) {
            for (info in codecList.codecInfos) {
                if (!info.isEncoder) continue
                val isHw = !info.name.contains("google", ignoreCase = true)
                if (!isHw) continue // Skip SW encoders

                try {
                    val caps = info.getCapabilitiesForType(mime) ?: continue
                    val videoCaps = caps.videoCapabilities ?: continue

                    if (!videoCaps.isSizeSupported(width, height)) continue

                    val maxBitrate = videoCaps.bitrateRange.upper
                    val maxFps = videoCaps.getSupportedFrameRatesFor(width, height)
                        ?.upper?.toInt() ?: 30

                    return CodecChoice(
                        mimeType = mime,
                        codecName = info.name,
                        isHardware = true,
                        maxBitrate = maxBitrate,
                        maxFps = maxFps
                    )
                } catch (e: Exception) {
                    continue
                }
            }
        }
        return null
    }

    /**
     * Feature #19: Adaptive bitrate based on detected screen content type
     */
    fun recommendBitrate(contentType: ContentType, resolution: Int): Int {
        return when (contentType) {
            ContentType.STATIC_UI -> when {
                resolution >= 2073600 -> 800_000    // 1080p static: 800kbps
                resolution >= 921600  -> 500_000    // 720p static: 500kbps
                else                  -> 300_000    // Lower: 300kbps
            }
            ContentType.ACTIVE_UI -> when {
                resolution >= 2073600 -> 6_000_000  // 1080p active: 6Mbps
                resolution >= 921600  -> 4_000_000  // 720p active: 4Mbps
                else                  -> 2_000_000  // Lower: 2Mbps
            }
            ContentType.VIDEO_PLAYBACK -> when {
                resolution >= 2073600 -> 12_000_000 // 1080p video: 12Mbps
                resolution >= 921600  -> 8_000_000  // 720p video: 8Mbps
                else                  -> 4_000_000  // Lower: 4Mbps
            }
        }
    }

    enum class ContentType {
        STATIC_UI,       // Calculator, text document
        ACTIVE_UI,       // Scrolling browser, list views
        VIDEO_PLAYBACK   // YouTube, video player
    }
}
