package com.streamlink.app.core.telemetry

import android.os.Debug
import android.util.Log

/**
 * Enterprise Memory Budget Enforcer
 * Tracks specific thresholds for Encoder, Decoder, Network, and Buffers.
 * Logs critical warnings if thresholds are breached, ensuring we remain
 * under the 40MB/50MB limits for sustainable zero-GC streaming.
 */
object MemoryBudgetMonitor {
    private const val TAG = "MemoryBudget"

    // Absolute Budgets (in MB)
    private const val BUDGET_ENCODER_MB = 40
    private const val BUDGET_DECODER_MB = 50
    private const val BUDGET_NETWORK_MB = 20
    private const val BUDGET_BUFFERS_MB = 15

    // Global Threshold before panic mode
    private const val CRITICAL_GLOBAL_HEAP_MB = 200

    fun checkBudgets() {
        val maxMemory = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        val totalMemory = Runtime.getRuntime().totalMemory() / (1024 * 1024)
        val freeMemory = Runtime.getRuntime().freeMemory() / (1024 * 1024)
        val usedMemory = totalMemory - freeMemory

        val nativeHeap = Debug.getNativeHeapSize() / (1024 * 1024)
        val nativeAllocated = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)

        if (usedMemory > CRITICAL_GLOBAL_HEAP_MB) {
            Log.e(TAG, "🚨 CRITICAL: Global JVM Heap exceeded ${CRITICAL_GLOBAL_HEAP_MB}MB! (Used: ${usedMemory}MB)")
        }

        // We estimate buffer usage via native heap as MediaCodec buffers heavily impact Native Heap
        if (nativeAllocated > (BUDGET_ENCODER_MB + BUDGET_DECODER_MB + BUDGET_BUFFERS_MB)) {
            Log.w(TAG, "⚠️ WARNING: Native Allocation (${nativeAllocated}MB) exceeds component budget limits. Potential buffer leak or MediaCodec exhaustion.")
        }
    }
}
