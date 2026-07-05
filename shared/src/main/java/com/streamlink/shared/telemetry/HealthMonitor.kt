package com.streamlink.shared.telemetry

import java.util.concurrent.atomic.AtomicLong
import android.util.Log

/**
 * MICRO-01: Health Monitor
 * Lock-free heartbeat tracking for critical subsystems using AtomicLong.
 */
object HealthMonitor {

    private const val TAG = "HealthMonitor"

    // Subsystem IDs
    const val SUBSYSTEM_ENCODER = 0
    const val SUBSYSTEM_NETWORK = 1
    const val SUBSYSTEM_CAPTURE = 2
    const val SUBSYSTEM_ORCHESTRATOR = 3
    const val SUBSYSTEM_AI = 4
    
    private const val MAX_SUBSYSTEMS = 5

    // Zero-allocation arrays for lock-free tracking
    private val heartbeats = Array(MAX_SUBSYSTEMS) { AtomicLong(System.currentTimeMillis()) }
    private val names = arrayOf("Encoder", "Network", "Capture", "Orchestrator", "AI")

    /**
     * Called by subsystems (e.g. inside their main loop) to report they are alive.
     */
    fun ping(subsystemId: Int) {
        if (subsystemId in 0 until MAX_SUBSYSTEMS) {
            heartbeats[subsystemId].lazySet(System.currentTimeMillis())
        }
    }

    /**
     * Returns the time since the last ping for the given subsystem.
     */
    fun getStalenessMs(subsystemId: Int): Long {
        if (subsystemId !in 0 until MAX_SUBSYSTEMS) return -1L
        return System.currentTimeMillis() - heartbeats[subsystemId].get()
    }

    fun getSubsystemName(subsystemId: Int): String {
        return names.getOrElse(subsystemId) { "Unknown" }
    }

    fun getSubsystemIds(): IntRange {
        return 0 until MAX_SUBSYSTEMS
    }
}
