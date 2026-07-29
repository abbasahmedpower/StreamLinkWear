package com.streamlink.app.core

import android.util.Log
import com.streamlink.app.core.telemetry.ProductionAnalytics

/**
 * M-13: Centralized Exception Handling Utilities
 *
 * Philosophy: Generic `catch (e: Exception)` blocks are necessary for defensive
 * programming at system boundaries (MediaCodec, sockets, sensors). However,
 * they must NEVER silently swallow exceptions. Every catch must:
 *   1. Log the error with a meaningful tag and context.
 *   2. Optionally report to ProductionAnalytics for production tracking.
 *   3. Apply a fallback action if recovery is possible.
 *
 * These extension functions enforce that contract uniformly across the codebase.
 */

/**
 * Runs [block] safely, logging any exception with [tag] and [context].
 * Returns null on failure.
 *
 * Usage:
 *   val result = safeRun("HardwareEncoder", "forceKeyframe") { encoder.forceKeyframe() }
 */
inline fun <T> safeRun(
    tag: String,
    context: String,
    report: Boolean = false,
    fallback: T? = null,
    block: () -> T
): T? {
    return try {
        block()
    } catch (e: Exception) {
        Log.e(tag, "[$context] failed: ${e.message}", e)
        if (report) {
            ProductionAnalytics.logException(tag, context, e)
        }
        fallback
    }
}

/**
 * Runs [block] safely, logging any exception but NOT rethrowing.
 * For fire-and-forget operations where the return value doesn't matter.
 *
 * Usage:
 *   safeExec("ThermalMonitor", "stop") { thermalMonitor.stop() }
 */
inline fun safeExec(
    tag: String,
    context: String,
    report: Boolean = false,
    block: () -> Unit
) {
    try {
        block()
    } catch (e: Exception) {
        Log.e(tag, "[$context] failed: ${e.message}", e)
        if (report) {
            ProductionAnalytics.logException(tag, context, e)
        }
    }
}

/**
 * Wraps a coroutine suspend block safely. Logs the exception but does not rethrow.
 * Use inside coroutine scopes for non-critical operations.
 */
inline fun <T> safeRunCatching(
    tag: String,
    context: String,
    fallback: T? = null,
    block: () -> T
): T? = safeRun(tag, context, report = false, fallback = fallback, block = block)
