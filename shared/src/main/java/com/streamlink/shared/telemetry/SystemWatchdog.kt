package com.streamlink.shared.telemetry

import android.util.Log

/**
 * NANO-04: Watchdog Thread
 * Continuously polls HealthMonitor to ensure all subsystems are updating their heartbeats.
 */
class SystemWatchdog(
    private val staleThresholdMs: Long = 3000L,
    private val pollIntervalMs: Long = 1000L,
    private val alertCooldownMs: Long = 10_000L
) : Thread("SystemWatchdog") {

    @Volatile
    private var isRunning = true

    var onSubsystemDead: ((Int, String) -> Unit)? = null

    private val lastAlertAtMs = LongArray(HealthMonitor.getSubsystemIds().count())

    init {
        isDaemon = true
    }

    override fun run() {
        Log.i("SystemWatchdog", "Watchdog started")
        while (isRunning && !isInterrupted) {
            try {
                sleep(pollIntervalMs)
                val now = System.currentTimeMillis()
                
                for (id in HealthMonitor.getSubsystemIds()) {
                    val staleness = HealthMonitor.getStalenessMs(id)
                    // If staleness > 3s and alert cooldown passed, trigger notification
                    if (staleness > staleThresholdMs && (id >= lastAlertAtMs.size || (now - lastAlertAtMs[id]) > alertCooldownMs)) {
                        val name = HealthMonitor.getSubsystemName(id)
                        Log.e("SystemWatchdog", "💀 Subsystem DEAD: $name (stale by ${staleness}ms)")
                        if (id < lastAlertAtMs.size) {
                            lastAlertAtMs[id] = now
                        }
                        onSubsystemDead?.invoke(id, name)
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
