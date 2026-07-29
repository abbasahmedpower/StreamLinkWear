package com.streamlink.app.core.telemetry

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Stage 7: Production Monitoring Analytics
 * Centralized wrapper for logging actionable events to Firebase.
 * Ensures metrics align with the "10/10 Contract".
 */
object ProductionAnalytics {

    private var firebaseAnalytics: FirebaseAnalytics? = null

    fun initialize(context: Context) {
        try {
            firebaseAnalytics = FirebaseAnalytics.getInstance(context)
        } catch (e: Exception) {
            // Ignored if google-services.json is missing during dev builds
        }
    }

    /**
     * Logs the beginning of a stream session, measuring the Startup Profile.
     */
    fun logStreamStarted(startupTimeMs: Long, isWarmStart: Boolean, connectionType: String) {
        val bundle = Bundle().apply {
            putLong("startup_time_ms", startupTimeMs)
            putBoolean("is_warm_start", isWarmStart)
            putString("connection_type", connectionType)
        }
        firebaseAnalytics?.logEvent("stream_started", bundle)
    }

    /**
     * Logs when the RTT (Round Trip Time) spikes above 50ms (Glass-to-Glass contract).
     */
    fun logLatencySpike(peakLatencyMs: Long, currentBitrateKbps: Int) {
        val bundle = Bundle().apply {
            putLong("peak_latency_ms", peakLatencyMs)
            putInt("bitrate_kbps", currentBitrateKbps)
        }
        firebaseAnalytics?.logEvent("latency_spike", bundle)
    }

    /**
     * Logs when the HardwareWatchdog successfully performs a flushAndRestart().
     */
    fun logCodecRecovery() {
        firebaseAnalytics?.logEvent("codec_recovery_triggered", Bundle())
    }

    /**
     * Logs Memory or GPU budget violations.
     */
    fun logBudgetViolation(component: String, allocatedMb: Int, limitMb: Int) {
        val bundle = Bundle().apply {
            putString("component", component)
            putInt("allocated_mb", allocatedMb)
            putInt("limit_mb", limitMb)
        }
        firebaseAnalytics?.logEvent("budget_violation", bundle)
    }

    /**
     * M-13: Logs a caught exception to Firebase for production monitoring.
     * Call via safeRun(report=true) for critical system boundary catches.
     */
    fun logException(tag: String, context: String, e: Exception) {
        val bundle = Bundle().apply {
            putString("tag", tag)
            putString("context", context)
            putString("exception_type", e.javaClass.simpleName)
            putString("message", e.message?.take(100) ?: "unknown")
        }
        firebaseAnalytics?.logEvent("caught_exception", bundle)
    }
}
