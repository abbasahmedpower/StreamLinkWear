package com.streamlink.app.core.input

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.hardware.input.InputManager
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.util.Log
import com.streamlink.shared.ai.KinematicPredictionEngine

/**
 * متحكم استقبال وحقن أحداث اللمس مع التنبؤ الحركي الذكي على مستوى الهاتف.
 *
 * القاعدة الذهبية:
 *  - DOWN/UP → إحداثيات حقيقية (Real Coordinates) لضمان دقة النقر
 *  - MOVE    → إحداثيات متنبأة ومنعمة (Predicted + Smoothed) لإلغاء الجيتر الشبكي
 */
class TouchInputController(
    private val inputManager: InputManager,
    private val displayMetrics: DisplayMetrics,
    private val predictionEngine: KinematicPredictionEngine
) {

    private val tag = "TouchInputController"
    private val injectionLock = Any()

    // زمن آخر حدث MOVE لحساب deltaTime للـ Kinematic Prediction
    private var lastMoveTimeMs = 0L
    // downTime يجب أن يظل ثابتاً طوال دورة الضغط (DOWN → MOVE → UP)
    private var downTime = 0L

    /**
     * نقطة الدخول الرئيسية: يستقبل الإحداثيات الخام من الساعة ويحقنها بذكاء.
     *
     * @param action    MotionEvent.ACTION_DOWN / ACTION_MOVE / ACTION_UP / ACTION_CANCEL
     * @param nx        إحداثية X الخام مقسومة على عرض الشاشة (0.0f → 1.0f)
     * @param ny        إحداثية Y الخام مقسومة على ارتفاع الشاشة (0.0f → 1.0f)
     * @param pointerId رقم الإصبع (للمستقبل)
     */
    fun handleIncomingWatchTouch(
        action: Int,
        nx: Float,
        ny: Float,
        pointerId: Int
    ): Boolean {
        // تحويل الإحداثيات النسبية (0→1) لأبعاد الشاشة الفعلية بالبكسل
        val realX = (nx * displayMetrics.widthPixels).coerceIn(0f, (displayMetrics.widthPixels - 1).toFloat())
        val realY = (ny * displayMetrics.heightPixels).coerceIn(0f, (displayMetrics.heightPixels - 1).toFloat())

        val (finalX, finalY) = when (action) {
            MotionEvent.ACTION_DOWN -> {
                // نقطة البداية: نعيد ضبط المحرك ونحفظ downTime
                predictionEngine.reset()
                downTime = SystemClock.uptimeMillis()
                lastMoveTimeMs = downTime
                // DOWN → إحداثيات حقيقية 100% (لا تنبؤ)
                realX to realY
            }
            MotionEvent.ACTION_MOVE -> {
                val now = SystemClock.uptimeMillis()
                val deltaMs = (now - lastMoveTimeMs).toFloat().coerceAtLeast(1f)
                lastMoveTimeMs = now

                // تحويل الإحداثيات الحقيقية إلى نسبية للـ Prediction Engine (يعمل على [0,1])
                val normX = realX / displayMetrics.widthPixels
                val normY = realY / displayMetrics.heightPixels

                val predicted = predictionEngine.updateAndPredict(
                    rawX = normX,
                    rawY = normY,
                    deltaTimeMs = deltaMs,
                    predictionWindowMs = 16f // نافذة تنبؤ بمقدار فريم واحد (16ms = 60fps)
                )

                // إعادة التحويل للبكسل الفعلي + منع الخروج عن حدود الشاشة
                val px = (predicted.predictedX * displayMetrics.widthPixels).coerceIn(0f, (displayMetrics.widthPixels - 1).toFloat())
                val py = (predicted.predictedY * displayMetrics.heightPixels).coerceIn(0f, (displayMetrics.heightPixels - 1).toFloat())
                px to py
            }
            else -> {
                // UP / CANCEL → إحداثيات حقيقية (لا تنبؤ) لضمان دقة نقطة الرفع
                realX to realY
            }
        }

        return injectMotionEvent(action, finalX, finalY)
    }

    private fun injectMotionEvent(action: Int, x: Float, y: Float): Boolean {
        val eventTime = SystemClock.uptimeMillis()
        // downTime يجب أن يكون ثابتاً طوال دورة الضغط
        val effectiveDownTime = if (action == MotionEvent.ACTION_DOWN) eventTime else downTime

        val motionEvent = MotionEvent.obtain(
            effectiveDownTime,
            eventTime,
            action,
            x,
            y,
            0 // metaState
        ).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }

        return synchronized(injectionLock) {
            try {
                val injectMethod = inputManager.javaClass.getMethod(
                    "injectInputEvent",
                    InputEvent::class.java,
                    Int::class.javaPrimitiveType
                )
                (injectMethod.invoke(inputManager, motionEvent, 0) as? Boolean) ?: false
            } catch (e: Exception) {
                Log.w(tag, "InputManager injection failed: ${e.message}")
                false
            } finally {
                motionEvent.recycle()
            }
        }
    }
}
