package com.streamlink.shared.telemetry

import android.util.Log

/**
 * NANO-04: Watchdog Thread
 * Continuously polls HealthMonitor to ensure all subsystems are updating their heartbeats.
 */
class SystemWatchdog(
    private val staleThresholdMs: Long = 3000L,
    private val pollIntervalMs: Long = 1000L
) : Thread("SystemWatchdog") {

    @Volatile
    private var isRunning = true

    var onSubsystemDead: ((Int, String) -> Unit)? = null

    init {
        isDaemon = true
    }

    override fun run() {
        Log.i("SystemWatchdog", "Watchdog started")
        while (isRunning && !isInterrupted) {
            try {
                sleep(pollIntervalMs)
                
                for (id in HealthMonitor.getSubsystemIds()) {
                    val staleness = HealthMonitor.getStalenessMs(id)
                    // If staleness > 3s, consider it DEAD
                    if (staleness > staleThresholdMs) {
                        val name = HealthMonitor.getSubsystemName(id)
                        Log.e("SystemWatchdog", "💀 Subsystem DEAD: $name (stale by ${staleness}ms)")
                        onSubsystemDead?.invoke(id, name)
                        
                        // To avoid spamming, we reset its heartbeat to give it a chance to recover
                        HealthMonitor.ping(id)
                    }
                }
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    fun shutdown() {
        isRunning = false
        interrupt()
    }
}
