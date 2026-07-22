package com.streamlink.shared.telemetry

import android.util.Log

/**
 * Tracks the performance budget limits for each stage of the streaming pipeline.
 * Raises alerts if a stage violates its budget.
 */
object PerformanceBudget {
    private const val TAG = "PerformanceBudget"

    // Budgets in milliseconds
    const val BUDGET_CAPTURE_MS = 2.0f
    const val BUDGET_ENCODE_MS = 8.0f
    const val BUDGET_CRYPTO_MS = 1.0f
    const val BUDGET_NETWORK_MS = 15.0f
    const val BUDGET_DECODE_MS = 8.0f
    const val BUDGET_RENDER_MS = 8.0f

    const val BUDGET_TOTAL_MS = BUDGET_CAPTURE_MS + BUDGET_ENCODE_MS + BUDGET_CRYPTO_MS + BUDGET_NETWORK_MS + BUDGET_DECODE_MS + BUDGET_RENDER_MS // 42 ms

    fun validateStage(stageName: String, actualMs: Float, budgetMs: Float): Boolean {
        if (actualMs > budgetMs) {
            Log.w(TAG, "🚨 PERFORMANCE BUDGET VIOLATION! Stage: $stageName | Actual: ${actualMs}ms | Budget: ${budgetMs}ms | Over: ${actualMs - budgetMs}ms")
            return false
        }
        return true
    }

    fun validateTotal(actualTotalMs: Float): Boolean {
        return validateStage("Glass-to-Glass Total", actualTotalMs, BUDGET_TOTAL_MS)
    }
}
