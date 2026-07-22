package com.streamlink.app.core.telemetry

import android.util.Log
import com.streamlink.shared.GlobalStreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * HardwareWatchdog ensures the hardware (MediaCodec / Socket) hasn't hung.
 * If a heartbeat isn't received within the timeout limit, it forces a Codec reset.
 */
class HardwareWatchdog(
    private val scope: CoroutineScope,
    private val hardwareEncoder: com.streamlink.app.capture.HardwareEncoder
) {
    
    private val tag = "HardwareWatchdog"
    
    // Timeouts
    private val TIMEOUT_MS = 3000L
    private val RECOVERY_TIMEOUT_MS = 5000L
    
    private var lastHeartbeatMs: Long = 0L
    private var watchdogJob: Job? = null
    
    @Volatile
    private var isRecovering = false

    fun start() {
        if (watchdogJob?.isActive == true) return
        
        lastHeartbeatMs = System.currentTimeMillis()
        isRecovering = false
        
        watchdogJob = scope.launch {
            while (isActive) {
                val state = GlobalStreamState.current
                if (state != GlobalStreamState.State.STREAMING && state != GlobalStreamState.State.RECOVERING && state != GlobalStreamState.State.REPAIRING) {
                    delay(1000)
                    continue
                }

                val now = System.currentTimeMillis()
                val elapsed = now - lastHeartbeatMs

                if (!isRecovering && elapsed > TIMEOUT_MS) {
                    Log.e(tag, "CRITICAL: Watchdog detected Hardware/Network hang! Initiating Recovery...")
                    isRecovering = true
                    GlobalStreamState.transition(GlobalStreamState.State.RECOVERING)
                    
                    // Trigger actual hardware encoder flush/restart here
                    hardwareEncoder.flushAndRestart()
                    com.streamlink.app.core.telemetry.ProductionAnalytics.logCodecRecovery()
                    
                    GlobalStreamState.transition(GlobalStreamState.State.REPAIRING)
                    lastHeartbeatMs = System.currentTimeMillis() // Reset for recovery grace period
                } else if (isRecovering && elapsed > RECOVERY_TIMEOUT_MS) {
                    Log.e(tag, "FATAL: Watchdog Recovery failed. Terminating Stream.")
                    GlobalStreamState.transition(GlobalStreamState.State.FAILED)
                    stop()
                }

                delay(1000) // Poll every second
            }
        }
    }

    fun stop() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    fun beat() {
        lastHeartbeatMs = System.currentTimeMillis()
        if (isRecovering) {
            Log.i(tag, "Watchdog: Hardware recovered successfully!")
            isRecovering = false
            scope.launch {
                GlobalStreamState.transition(GlobalStreamState.State.STREAMING)
            }
        }
    }
}
