package com.streamlink.shared.ai

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter

/**
 * NeuralPredictor - محرك التنبؤ العصبي منخفض الطاقة.
 * يقوم بتحليل مصفوفة الحركة السابقة لتوقع الـ Point القادمة بدقة ميكرو-ثانية.
 */
class NeuralPredictor(private val tfliteModel: ByteBuffer) {
    
    private var interpreter: Interpreter? = null

    init {
        val options = Interpreter.Options().apply {
            setNumThreads(2) // حجز نواتين فقط للحفاظ على بطارية الساعة
            setUseNNAPI(true) // تسريع العتاد عبر الأندرويد إن وجد
        }
        interpreter = Interpreter(tfliteModel, options)
    }

    /**
     * يتوقع الإحداثي القادم بناءً على آخر ٣ حركات مسجلة.
     * Input: Matrix of [3, 2] (X, Y historical coordinates)
     * Output: Array of [2] (Predicted X, Y)
     */
    fun predictNextTouch(history: Array<Pair<Float, Float>>): Pair<Float, Float> {
        if (history.size < 3) return history.lastOrNull() ?: Pair(0.0f, 0.0f)

        // تجهيز الـ Buffer الخاص بالمدخلات بدون تخصيص ذاكرة زائد في الـ Heap
        val inputBuffer = ByteBuffer.allocateDirect(3 * 2 * 4).order(ByteOrder.nativeOrder())
        for (point in history.takeLast(3)) {
            inputBuffer.putFloat(point.first)
            inputBuffer.putFloat(point.second)
        }
        inputBuffer.rewind()

        // مصفوفة المخرجات
        val outputBuffer = ByteBuffer.allocateDirect(2 * 4).order(ByteOrder.nativeOrder())

        // تشغيل الاستدلال الفوري
        interpreter?.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()

        val predX = outputBuffer.float
        val predY = outputBuffer.float

        // عمل Clamping لضمان عدم خروج الإحداثيات المتوقعة عن أبعاد شاشة الساعة
        return Pair(predX.coerceIn(0f, 1f), predY.coerceIn(0f, 1f))
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
