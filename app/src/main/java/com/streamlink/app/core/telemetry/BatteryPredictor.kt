package com.streamlink.app.core.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock

/**
 * Enterprise Battery Predictor
 * Predicts remaining streaming time based on real-time drain.
 */
class BatteryPredictor(private val context: Context) {
    
    private var startLevel: Int = -1
    private var startTimeMs: Long = 0L

    fun startTracking() {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            context.registerReceiver(null, ifilter)
        }
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        
        if (level != -1 && scale != -1) {
            startLevel = (level * 100) / scale
            startTimeMs = SystemClock.elapsedRealtime()
        }
    }

    /**
     * @return "Estimated Xh Ym" or "Calculating..." if not enough data
     */
    fun getEstimatedRemainingTime(): String {
        if (startLevel == -1 || startTimeMs == 0L) return "Calculating..."

        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            context.registerReceiver(null, ifilter)
        }
        val currentLevelRaw: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        
        if (currentLevelRaw == -1 || scale == -1) return "Calculating..."
        
        val currentLevel = (currentLevelRaw * 100) / scale
        val dropped = startLevel - currentLevel
        val elapsedMs = SystemClock.elapsedRealtime() - startTimeMs

        // Need at least 1% drop and 2 minutes elapsed to make a semi-accurate prediction
        if (dropped <= 0 || elapsedMs < 120_000) {
            return "Calculating..."
        }

        val msPerPercent = elapsedMs / dropped
        val remainingMs = msPerPercent * currentLevel
        
        val totalMinutes = remainingMs / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        
        return "Estimated ${hours}h ${minutes}m"
    }
}
