package com.streamlink.shared.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.net.wifi.WifiManager
import android.os.PowerManager

/**
 * MICRO-04: Device Monitor
 * Tracks hardware specifics: Battery level, charging state, thermal status, WiFi RSSI.
 */
object DeviceMonitor {

    @Volatile var batteryPercent: Int = -1
        private set
        
    @Volatile var isCharging: Boolean = false
        private set
        
    @Volatile var thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE
        private set
        
    @Volatile var wifiRssi: Int = -127
        private set

    /**
     * Updates device metrics by taking a snapshot of the current hardware state.
     * Call this periodically (e.g. every 5-10 seconds) on a background thread.
     */
    fun updateSnapshot(context: Context) {
        try {
            // 1. Battery
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
                context.registerReceiver(null, ifilter)
            }
            if (batteryStatus != null) {
                val level: Int = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale: Int = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                batteryPercent = (level * 100 / scale.toFloat()).toInt()
                
                val status: Int = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL
            }

            // 2. Thermals
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                powerManager?.let { pm ->
                    thermalStatus = pm.currentThermalStatus
                    // Automatically trigger Thermal Governor
                    ThermalGovernor.applyPolicy(thermalStatus)
                }
            }

            // 3. WiFi RSSI
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.connectionInfo?.let { info ->
                wifiRssi = info.rssi
            }
        } catch (e: Exception) {
            // Ignore snapshot failures to prevent crashes in production
        }
    }
}
