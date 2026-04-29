package com.ultrashare.session

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject

/**
 * Feature #61: QR code pairing — exchanges IP, port, session key, codec info.
 */
object QrPairing {

    data class SessionInfo(
        val hostIp: String,
        val videoPort: Int = 5004,
        val controlPort: Int = 5005,
        val sessionKey: String,
        val codec: String = "hevc",
        val sessionId: String
    )

    fun generateQrBitmap(info: SessionInfo, sizePx: Int = 512): Bitmap {
        val payload = JSONObject().apply {
            put("ip", info.hostIp)
            put("vp", info.videoPort)
            put("cp", info.controlPort)
            put("key", info.sessionKey)
            put("codec", info.codec)
            put("sid", info.sessionId)
        }.toString()

        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val writer = QRCodeWriter()
        val matrix = writer.encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)

        return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565).apply {
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
        }
    }

    fun parseQrPayload(json: String): SessionInfo? {
        return try {
            val obj = JSONObject(json)
            SessionInfo(
                hostIp = obj.getString("ip"),
                videoPort = obj.getInt("vp"),
                controlPort = obj.getInt("cp"),
                sessionKey = obj.getString("key"),
                codec = obj.optString("codec", "hevc"),
                sessionId = obj.getString("sid")
            )
        } catch (e: Exception) { null }
    }
}
