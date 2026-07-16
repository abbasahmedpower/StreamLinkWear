package com.streamlink.app.core.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.View
import android.view.WindowManager

/**
 * مدير طبقة الخصوصية والتعتيم التام للهاتف أثناء البث.
 * يسمح بمرور أحداث اللمس المحقونة للتطبيقات في الخلفية دون أي عائق.
 */
class PrivacyBlackoutOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null

    @Volatile
    var isActive: Boolean = false
        private set

    /**
     * تفعيل وضع التعتيم التام.
     */
    fun enable() {
        if (isActive) return

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or // سر تمرير أحداث اللمس للخلف
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // تقليل سطوع الشاشة المادي لأقصى درجة لتوفير طاقة الهاتف بشكل كامل
            screenBrightness = 0.01f 
        }

        overlayView = View(context).apply {
            setBackgroundColor(Color.BLACK) // أسود داكن متوافق مع شاشات OLED لتوفير الطاقة
        }

        try {
            windowManager.addView(overlayView, params)
            isActive = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * إلغاء التعتيم واستعادة سطوع الشاشة الطبيعي فوراً.
     */
    fun disable() {
        if (!isActive || overlayView == null) return

        try {
            windowManager.removeView(overlayView)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            overlayView = null
            isActive = false
        }
    }
}
