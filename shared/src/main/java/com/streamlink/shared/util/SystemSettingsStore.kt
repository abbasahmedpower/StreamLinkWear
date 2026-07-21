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

    // ✅ BUG-03 FIX: Instant Sync — تخزين حقيقي في SharedPrefs
    @Volatile var isInstantSyncEnabled: Boolean = prefs.getBoolean("instant_sync", true)
        private set

    // ✅ BUG-04 FIX: اسم وعنوان الجهاز المتصل — يُحدَّث عند الاتصال الناجح
    @Volatile var connectedWatchName: String = prefs.getString("watch_name", "") ?: ""
        private set

    @Volatile var connectedWatchIp: String = prefs.getString("watch_ip", "") ?: ""
        private set

    // ✅ BUG-05 FIX: Callback لإرسال تحديث Jitter Buffer للساعة عبر control channel
    // يُعيَّن من DirectSocketServer عند الاتصال
    @Volatile var onJitterBufferUpdateRequested: ((Int) -> Unit)? = null

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

    // ✅ BUG-03 FIX: حفظ Instant Sync حقيقي
    fun setInstantSync(enabled: Boolean) {
        isInstantSyncEnabled = enabled
        prefs.edit().putBoolean("instant_sync", enabled).apply()
    }

    // ✅ BUG-04 FIX: تحديث اسم الجهاز المتصل
    fun setConnectedWatch(name: String, ip: String) {
        connectedWatchName = name
        connectedWatchIp = ip
        prefs.edit()
            .putString("watch_name", name)
            .putString("watch_ip", ip)
            .apply()
    }

    fun clearConnectedWatch() {
        connectedWatchName = ""
        connectedWatchIp = ""
        prefs.edit()
            .remove("watch_name")
            .remove("watch_ip")
            .apply()
    }
}
