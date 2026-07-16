package com.streamlink.shared.telemetry

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * مجمّع مقاييس الأداء فائق الخفة - مصمم ليعمل في مسار الـ Render/Network
 * بدون إنتاج أي كائنات جديدة (Zero Object Allocation) لتجنب الـ GC pauses.
 */
object TelemetryCollector {

    // متغيرات ذرية بدائية (Primitive Atomics) سريعة جداً ومقاومة لسباق البيانات
    private val frameCount = AtomicInteger(0)
    private val droppedFrameCount = AtomicInteger(0)
    private val totalLatencyMs = AtomicLong(0)
    private val latencySampleCount = AtomicLong(0)
    private val bytesTransferred = AtomicLong(0)

    private var lastFpsCalculationTime = System.currentTimeMillis()
    
    // قيم جاهزة للقراءة من الـ UI
    @Volatile var currentFps: Int = 0
        private set
    @Volatile var currentDroppedFps: Int = 0
        private set
    @Volatile var averageLatencyMs: Float = 0f
        private set
    @Volatile var bandwidthMbps: Float = 0f
        private set

    /**
     * يُستدعى عند استقبال أو رسم فريم جديد بنجاح.
     */
    fun recordFrame() {
        frameCount.incrementAndGet()
        updateCalculationsIfNeeded()
    }

    /**
     * يُستدعى عند إسقاط فريم (Frame Drop).
     */
    fun recordFrameDrop() {
        droppedFrameCount.incrementAndGet()
    }

    /**
     * يُستدعى لقياس زمن التأخير الفعلي (Latency) بالملي ثانية.
     */
    fun recordLatency(latencyMs: Long) {
        totalLatencyMs.addAndGet(latencyMs)
        latencySampleCount.incrementAndGet()
    }

    /**
     * يُستدعى لتسجيل حجم البيانات المستهلكة لحساب الـ Bandwidth.
     */
    fun recordBytes(bytes: Long) {
        bytesTransferred.addAndGet(bytes)
    }

    /**
     * حساب دوري للمعدلات لتجنب الحساب المستمر مع كل فريم.
     * يعمل فقط إذا مر 500ms على آخر عملية حسابية.
     */
    private fun updateCalculationsIfNeeded() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastFpsCalculationTime
        if (elapsed >= 500) { // نافذة حسابية كل نصف ثانية
            val elapsedSeconds = elapsed / 1000f
            
            val frames = frameCount.getAndSet(0)
            val drops = droppedFrameCount.getAndSet(0)
            val bytes = bytesTransferred.getAndSet(0)
            
            currentFps = (frames / elapsedSeconds).toInt()
            currentDroppedFps = (drops / elapsedSeconds).toInt()
            
            // حساب سرعة النقل بالميغابت في الثانية (Mbps)
            bandwidthMbps = (bytes * 8) / (elapsedSeconds * 1024 * 1024)

            // حساب زمن التأخير المتوسط
            val samples = latencySampleCount.getAndSet(0)
            val sumLatency = totalLatencyMs.getAndSet(0)
            if (samples > 0) {
                averageLatencyMs = sumLatency.toFloat() / samples
            }

            lastFpsCalculationTime = now
        }
    }
}
