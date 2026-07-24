package com.streamlink.shared.telemetry

import android.os.Process
import android.os.SystemClock

/**
 * ProcessCpuTracker — قياس حقيقي لاستهلاك CPU العملية نفسها فقط (مش النظام كله).
 * يستخدم Process.getElapsedCpuTime() المتاحة بدون قيود من Android 8+.
 *
 * هذه هي النسخة الموحدة الوحيدة المعتمدة في المشروع.
 * امسح أي قيمة ثابتة مثل 0.5f أو 10f كانت تُمثّل CPU Load.
 */
class ProcessCpuTracker {
    private var lastCpuTimeMs = 0L
    private var lastRealTimeMs = 0L
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    /**
     * يُعيد نسبة استخدام CPU للعملية (0f..1f).
     * القراءة الأولى دائماً تُعيد 0.2f (قيمة أولية متحفظة).
     */
    fun sampleFraction(): Float {
        val cpuNow  = Process.getElapsedCpuTime()
        val wallNow = SystemClock.elapsedRealtime()

        if (lastRealTimeMs == 0L) {
            lastCpuTimeMs  = cpuNow
            lastRealTimeMs = wallNow
            return 0.2f // قيمة أولية متحفظة لأول قراءة فقط
        }

        val cpuDelta  = cpuNow  - lastCpuTimeMs
        val wallDelta = wallNow - lastRealTimeMs
        lastCpuTimeMs  = cpuNow
        lastRealTimeMs = wallNow

        if (wallDelta <= 0) return 0f
        return (cpuDelta.toFloat() / (wallDelta * cores)).coerceIn(0f, 1f)
    }
}
