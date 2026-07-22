package com.streamlink.app.benchmark

import com.streamlink.shared.telemetry.PerformanceBudget
import java.util.Collections

class BenchmarkSuite {
    
    data class MetricResult(
        val name: String,
        val avgMs: Float,
        val medianMs: Float,
        val p95Ms: Float,
        val p99Ms: Float,
        val worstMs: Float,
        val budgetMs: Float,
        val budgetPassed: Boolean
    )

    fun runAssessment(latencies: List<Float>, metricName: String, budgetMs: Float): MetricResult {
        if (latencies.isEmpty()) {
            return MetricResult(metricName, 0f, 0f, 0f, 0f, 0f, budgetMs, true)
        }

        val sorted = latencies.sorted()
        val count = sorted.size
        
        val avg = sorted.average().toFloat()
        val median = sorted[count / 2]
        
        val p95Idx = (count * 0.95).toInt().coerceAtMost(count - 1)
        val p95 = sorted[p95Idx]
        
        val p99Idx = (count * 0.99).toInt().coerceAtMost(count - 1)
        val p99 = sorted[p99Idx]
        
        val worst = sorted.last()
        val passed = avg <= budgetMs

        return MetricResult(
            name = metricName,
            avgMs = avg,
            medianMs = median,
            p95Ms = p95,
            p99Ms = p99,
            worstMs = worst,
            budgetMs = budgetMs,
            budgetPassed = passed
        )
    }

    /**
     * Executes validation for all pipelines.
     */
    fun benchmarkAll(
        encoderLatencies: List<Float>,
        decoderLatencies: List<Float>,
        cryptoLatencies: List<Float>,
        networkLatencies: List<Float>,
        g2gLatencies: List<Float>
    ): List<MetricResult> {
        return listOf(
            runAssessment(encoderLatencies, "Encoder", PerformanceBudget.BUDGET_ENCODE_MS),
            runAssessment(decoderLatencies, "Decoder", PerformanceBudget.BUDGET_DECODE_MS),
            runAssessment(cryptoLatencies, "Crypto", PerformanceBudget.BUDGET_CRYPTO_MS),
            runAssessment(networkLatencies, "Network", PerformanceBudget.BUDGET_NETWORK_MS),
            runAssessment(g2gLatencies, "Glass-to-Glass Total", PerformanceBudget.BUDGET_TOTAL_MS)
        )
    }
}
