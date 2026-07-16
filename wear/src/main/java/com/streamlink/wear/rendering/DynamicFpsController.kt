package com.streamlink.wear.rendering

import android.util.Log
import java.nio.ByteBuffer

/**
 * نظام توفير الطاقة الذكي: يكتشف ثبات الشاشة ويخفض الـ FPS إلى 1 لتوفير البطارية.
 *
 * الخوارزمية:
 * 1. يأخذ عينات متفرقة من بيانات الفريم (وليس كل بكسل) لحساب Hash خفيف جداً
 * 2. يقارن الـ Hash مع الفريم السابق
 * 3. إذا تشابه الـ Hash → الشاشة ثابتة → يتجاوز الرسم حتى تنقضي 1000ms
 * 4. إذا اختلف → تغيير في الصورة → يرسم فوراً ويعيد ضبط المؤقت
 */
class DynamicFpsController {

    private val tag = "DynamicFpsController"

    // عدد العينات لحساب الـ Hash (أقل = أسرع، المصفوفة 32 عينة تكفي)
    private val sampleCount = 32
    private var lastHash = 0L
    private var lastRenderTimeMs = 0L

    // الحد الأدنى للزمن بين الفريمات عند الكشف عن ثبات (1000ms = 1 FPS)
    private val throttledFrameIntervalMs = 1000L

    /**
     * @param frameBuffer بيانات الفريم الحالي القادم من الـ Video Decoder
     * @return true إذا يجب رسم هذا الفريم، false إذا يمكن تخطيه لتوفير الطاقة
     */
    fun shouldRender(frameBuffer: ByteBuffer): Boolean {
        val now = System.currentTimeMillis()
        val currentHash = calculateFrameHash(frameBuffer)

        return if (currentHash != lastHash) {
            // تغيّرت الصورة → ارسم فوراً واستعد لـ 60 FPS
            lastHash = currentHash
            lastRenderTimeMs = now
            true
        } else {
            // الصورة ثابتة → اسمح بفريم واحد فقط كل ثانية
            val timeSinceLastRender = now - lastRenderTimeMs
            if (timeSinceLastRender >= throttledFrameIntervalMs) {
                lastRenderTimeMs = now
                Log.v(tag, "Static screen detected → throttling to 1 FPS (saved ${timeSinceLastRender}ms)")
                true
            } else {
                false // تخطي هذا الفريم → توفير الطاقة
            }
        }
    }

    /**
     * حساب Hash خفيف عن طريق أخذ عينات متفرقة من البيانات الخام للفريم.
     * لا GC، لا allocations، يعمل مباشرة على الـ ByteBuffer بدون نسخ.
     */
    private fun calculateFrameHash(buffer: ByteBuffer): Long {
        val capacity = buffer.capacity()
        if (capacity == 0) return 0L

        val step = (capacity / sampleCount).coerceAtLeast(1)
        var hash = 1L
        val savedPosition = buffer.position()

        try {
            buffer.position(0)
            var i = 0
            while (i < capacity) {
                // FNV-1a variant — fast and low-collision for image data
                hash = hash xor (buffer.get(i).toLong() and 0xFF)
                hash *= 1099511628211L
                i += step
            }
        } finally {
            buffer.position(savedPosition)
        }
        return hash
    }

    /**
     * إعادة ضبط الحالة عند بدء جلسة جديدة.
     */
    fun reset() {
        lastHash = 0L
        lastRenderTimeMs = 0L
    }
}
