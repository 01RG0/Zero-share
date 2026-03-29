package com.ultrashare.telemetry

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * Feature #69: Glass-to-glass latency breakdown in real time.
 */
class LatencyTracker {

    data class FrameStats(
        val frameId: Long,
        val captureTimeNs: Long = 0,
        val encodeCompleteNs: Long = 0,
        val networkRttMs: Float = 0f,
        val decodeStartNs: Long = 0,
        val renderTimeNs: Long = 0
    ) {
        val encodeLatencyMs get() = (encodeCompleteNs - captureTimeNs) / 1_000_000f
        val decodeLatencyMs get() = (renderTimeNs - decodeStartNs) / 1_000_000f
        val totalEstimatedMs get() = encodeLatencyMs + networkRttMs / 2f + decodeLatencyMs
    }

    private val frames = ConcurrentHashMap<Long, FrameStats>()
    private val latencyHistory = ArrayDeque<Float>(300)  // 5 seconds at 60fps

    fun onCapture(frameId: Long) {
        frames[frameId] = FrameStats(frameId, captureTimeNs = SystemClock.elapsedRealtimeNanos())
    }

    fun onEncodeComplete(frameId: Long) {
        frames[frameId] = frames[frameId]?.copy(
            encodeCompleteNs = SystemClock.elapsedRealtimeNanos()
        ) ?: return
    }

    fun onDecodeStart(frameId: Long) {
        frames[frameId] = frames[frameId]?.copy(
            decodeStartNs = SystemClock.elapsedRealtimeNanos()
        ) ?: return
    }

    fun onRender(frameId: Long) {
        val stats = frames[frameId]?.copy(
            renderTimeNs = SystemClock.elapsedRealtimeNanos()
        ) ?: return
        frames[frameId] = stats
        latencyHistory.addLast(stats.totalEstimatedMs)
        if (latencyHistory.size > 300) latencyHistory.removeFirst()
    }

    fun getStats(): LatencyStats {
        val recent = latencyHistory.takeLast(60)  // Last 1 second
        if (recent.isEmpty()) return LatencyStats(0f, 0f, 0f, 0f, 0f)

        val sorted = recent.sorted()
        return LatencyStats(
            avgMs = recent.average().toFloat(),
            p50Ms = sorted[sorted.size / 2],
            p95Ms = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)],
            minMs = sorted.first(),
            maxMs = sorted.last()
        )
    }

    data class LatencyStats(
        val avgMs: Float,
        val p50Ms: Float,
        val p95Ms: Float,
        val minMs: Float,
        val maxMs: Float
    )
}
