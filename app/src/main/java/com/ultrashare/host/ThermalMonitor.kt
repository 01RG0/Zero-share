package com.ultrashare.host

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Feature #87: Monitor ThermalManager — detect overheating before it throttles.
 * Feature #88: Adaptive encode/decode pipeline on high load.
 * Feature #86: SUSTAINED_PERFORMANCE_MODE for stable 60fps without throttle.
 */
class ThermalMonitor(
    private val context: Context,
    private val onThermalWarning: (ThermalLevel) -> Unit
) {

    enum class ThermalLevel {
        NORMAL,     // Full quality, 60fps, max bitrate
        WARM,       // Reduce to 30fps, 80% bitrate
        HOT,        // Reduce to 720p, 50% bitrate
        CRITICAL    // Minimum: 480p, 15fps, suspend audio
    }

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    fun startMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.addThermalStatusListener { status ->
                val level = when (status) {
                    PowerManager.THERMAL_STATUS_NONE,
                    PowerManager.THERMAL_STATUS_LIGHT    -> ThermalLevel.NORMAL
                    PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.WARM
                    PowerManager.THERMAL_STATUS_SEVERE   -> ThermalLevel.HOT
                    PowerManager.THERMAL_STATUS_CRITICAL,
                    PowerManager.THERMAL_STATUS_EMERGENCY,
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalLevel.CRITICAL
                    else                                 -> ThermalLevel.NORMAL
                }
                Log.d("ThermalMonitor", "Thermal status: $level")
                onThermalWarning(level)
            }
        }
    }

    /**
     * Feature #86: SUSTAINED_PERFORMANCE_MODE — prevents encoder from
     * running at high frequency then thermal-throttling to low frequency.
     * Stable mid-range frequency is better for consistent latency.
     */
    fun requestSustainedPerformance(window: android.view.Window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            window.setSustainedPerformanceMode(true)
        }
    }

    /**
     * Feature #89: Battery saver mode degrades latency — warn user.
     */
    fun isBatterySaverActive(): Boolean {
        return powerManager.isPowerSaveMode
    }
}
