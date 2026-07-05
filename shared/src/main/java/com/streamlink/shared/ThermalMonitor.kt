package com.streamlink.shared

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.streamlink.shared.util.safeSystemService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ThermalMonitor — converts Android thermal status to [0..10] integer scale
 * and drives QualityController.thermalLevel.
 *
 * Android API 29+ thermal status:
 *   NONE=0, LIGHT=1, MODERATE=2, SEVERE=3, CRITICAL=4, EMERGENCY=5, SHUTDOWN=6
 *
 * Maps to 0..10 scale for QualityController.
 */
class ThermalMonitor(
    private val context: Context,
    private val intelEngine: StreamingIntelligenceEngine? = null
) {
    private val tag = "ThermalMonitor"
    private val powerManager: PowerManager? = context.safeSystemService(Context.POWER_SERVICE)

    private val _thermalLevel = MutableStateFlow(0)
    val thermalLevel: StateFlow<Int> = _thermalLevel

    private val listener = PowerManager.OnThermalStatusChangedListener { status ->
        val level = mapStatusToLevel(status)
        Log.i(tag, "Thermal status: $status → level $level/10")
        _thermalLevel.value = level
        intelEngine?.thermalLevel = level
        intelEngine?.thermalCeilingKbps = ceilingForLevel(level)
    }

    fun start() {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                powerManager?.addThermalStatusListener(listener)
                val currentStatus = powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
                listener.onThermalStatusChanged(currentStatus)
            } catch (e: Exception) {
                Log.e(tag, "Failed to start thermal monitor", e)
            }
        } else {
            Log.i(tag, "ThermalMonitor: API < 29, thermal monitoring unavailable")
        }
    }

    fun stop() {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                powerManager?.removeThermalStatusListener(listener)
            } catch (e: Exception) {
                Log.e(tag, "Failed to stop thermal monitor", e)
            }
        }
    }

    private fun mapStatusToLevel(status: Int): Int = when (status) {
        0 -> 0   // NONE
        1 -> 2   // LIGHT
        2 -> 5   // MODERATE
        3 -> 7   // SEVERE
        4 -> 9   // CRITICAL
        5 -> 10  // EMERGENCY
        6 -> 10  // SHUTDOWN
        else -> 0
    }

    private fun ceilingForLevel(level: Int): Int = when {
        level >= 9 -> 300
        level >= 7 -> 800
        level >= 5 -> 1200
        else       -> StreamProtocol.WEAR_BPS_FULL
    }
}
