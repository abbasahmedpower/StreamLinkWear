package com.streamlink.shared.util

import android.content.Context
import android.content.SharedPreferences

/**
 * نظام تخزين إعدادات فائق الأداء يعتمد على الـ Memory Caching لمنع الـ Frame Drops.
 */
class SystemSettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("streamlink_settings", Context.MODE_PRIVATE)

    // كاش محلي سريع جداً في الذاكرة
    @Volatile var isDynamicFpsEnabled: Boolean = prefs.getBoolean("dynamic_fps", true)
        private set

    @Volatile var isPrivacyBlackoutEnabled: Boolean = prefs.getBoolean("privacy_blackout", false)
        private set

    @Volatile var isImuGesturesEnabled: Boolean = prefs.getBoolean("imu_gestures", false)
        private set

    fun setDynamicFps(enabled: Boolean) {
        isDynamicFpsEnabled = enabled
        prefs.edit().putBoolean("dynamic_fps", enabled).apply()
    }

    fun setPrivacyBlackout(enabled: Boolean) {
        isPrivacyBlackoutEnabled = enabled
        prefs.edit().putBoolean("privacy_blackout", enabled).apply()
    }

    fun setImuGestures(enabled: Boolean) {
        isImuGesturesEnabled = enabled
        prefs.edit().putBoolean("imu_gestures", enabled).apply()
    }
}
