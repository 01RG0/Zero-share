package com.ultrashare.telemetry

import android.content.Context
import android.graphics.*
import android.view.View

/**
 * Feature #69: Live glass-to-glass latency breakdown.
 * Feature #78: Signal quality badge.
 */
class StatsOverlay(context: Context) : View(context) {

    var stats: LatencyTracker.LatencyStats? = null
    var currentCodec = "H.265"
    var currentResolution = "1080p"
    var signalBars = 4  // 0–4

    private val bgPaint = Paint().apply {
        color = Color.argb(160, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
    }
    private val goodPaint  = Paint(textPaint).apply { color = Color.GREEN }
    private val warnPaint  = Paint(textPaint).apply { color = Color.YELLOW }
    private val errorPaint = Paint(textPaint).apply { color = Color.RED }

    override fun onDraw(canvas: Canvas) {
        val s = stats ?: return
        val avgMs = s.avgMs
        val p95Ms = s.p95Ms

        val latencyColor = when {
            avgMs < 25f  -> goodPaint
            avgMs < 50f  -> warnPaint
            else         -> errorPaint
        }

        // Background pill
        canvas.drawRoundRect(10f, 10f, 280f, 120f, 12f, 12f, bgPaint)

        // Latency line
        canvas.drawText("⬤ ${avgMs.toInt()}ms avg", 20f, 42f, latencyColor)
        canvas.drawText("p95: ${p95Ms.toInt()}ms", 20f, 72f, textPaint)

        // Codec + resolution badge
        canvas.drawText("$currentCodec · $currentResolution", 20f, 102f, textPaint)

        // Signal bars
        for (i in 0 until 4) {
            val barPaint = if (i < signalBars) goodPaint else bgPaint
            canvas.drawRect(
                240f + i * 12f, 80f - i * 8f,
                250f + i * 12f, 110f,
                barPaint
            )
        }
    }

    fun update(newStats: LatencyTracker.LatencyStats, codec: String, res: String, bars: Int) {
        stats = newStats
        currentCodec = codec
        currentResolution = res
        signalBars = bars
        postInvalidate()
    }
}
