package com.streamlink.shared

import android.content.Context
import android.os.PowerManager
import android.util.Log
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
    private val qualityController: QualityController? = null
) {
    private val tag = "ThermalMonitor"
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val _thermalLevel = MutableStateFlow(0)
    val thermalLevel: StateFlow<Int> = _thermalLevel

    private val listener = PowerManager.OnThermalStatusChangedListener { status ->
        val level = mapStatusToLevel(status)
        Log.i(tag, "Thermal status: $status → level $level/10")
        _thermalLevel.value = level
        qualityController?.thermalLevel = level
        qualityController?.thermalCeilingKbps = ceilingForLevel(level)
    }

    fun start() {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            powerManager.addThermalStatusListener(listener)
            // Immediately apply current thermal state
            val current = mapStatusToLevel(powerManager.currentThermalStatus)
            _thermalLevel.value = current
            qualityController?.thermalLevel = current
            Log.i(tag, "ThermalMonitor started — current level: $current/10")
        } else {
            Log.i(tag, "ThermalMonitor: API < 29, thermal monitoring unavailable")
        }
    }

    fun stop() {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            powerManager.removeThermalStatusListener(listener)
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
