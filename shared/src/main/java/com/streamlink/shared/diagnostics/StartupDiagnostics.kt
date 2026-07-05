package com.streamlink.shared.diagnostics

import android.util.Log

/**
 * StartupDiagnostics — breadcrumb logger for the app initialization pipeline.
 *
 * Usage:
 *   StartupDiagnostics.step("Loading JNI")
 *   ... do work ...
 *   StartupDiagnostics.step("JNI loaded")
 *
 * On crash, call StartupDiagnostics.dump() from uncaughtExceptionHandler
 * to see exactly where the startup sequence stopped.
 */
object StartupDiagnostics {

    private const val TAG = "StartupDiag"
    private const val MAX_STEPS = 64

    data class Step(val name: String, val timestampMs: Long, val threadName: String)

    private val steps = ArrayDeque<Step>(MAX_STEPS)
    private val lock = Any()

    @Volatile var lastStep: String = "NOT_STARTED"
        private set

    /**
     * Record a startup breadcrumb. Call at every major initialization point:
     * App.onCreate → Hilt Init → Network → Encoder → Surface → READY
     */
    fun step(name: String) {
        synchronized(lock) {
            if (steps.size >= MAX_STEPS) steps.removeFirst()
            steps.addLast(Step(name, System.currentTimeMillis(), Thread.currentThread().name))
        }
        lastStep = name
        Log.d(TAG, "▶ $name")
    }

    /**
     * Mark a step as successfully completed.
     */
    fun ok(name: String) {
        synchronized(lock) {
            if (steps.size >= MAX_STEPS) steps.removeFirst()
            steps.addLast(Step("✅ $name", System.currentTimeMillis(), Thread.currentThread().name))
        }
        lastStep = "✅ $name"
        Log.d(TAG, "✅ $name")
    }

    /**
     * Mark a step as failed (non-fatal).
     */
    fun warn(name: String, reason: String) {
        val msg = "⚠️ $name: $reason"
        synchronized(lock) {
            if (steps.size >= MAX_STEPS) steps.removeFirst()
            steps.addLast(Step(msg, System.currentTimeMillis(), Thread.currentThread().name))
        }
        lastStep = msg
        Log.w(TAG, msg)
    }

    /**
     * Dump the full startup trace to Logcat.
     * Call this from CrashReporter or uncaughtExceptionHandler.
     */
    fun dump(): String {
        val sb = StringBuilder()
        sb.appendLine("═══ StartupDiagnostics Trace ═══")
        synchronized(lock) {
            steps.forEachIndexed { i, step ->
                sb.appendLine("  [$i] ${step.name} (+${step.timestampMs}ms) [${step.threadName}]")
            }
        }
        sb.appendLine("  LastStep → $lastStep")
        sb.appendLine("═══════════════════════════════")
        val result = sb.toString()
        Log.e(TAG, result)
        return result
    }

    /**
     * Reset all breadcrumbs (call at session restart).
     */
    fun reset() {
        synchronized(lock) { steps.clear() }
        lastStep = "RESET"
    }
}
