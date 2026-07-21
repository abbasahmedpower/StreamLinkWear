package com.streamlink.shared.util

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * نظام تخزين إعدادات فائق الأداء يعتمد على الـ Memory Caching لمنع الـ Frame Drops.
 */
class SystemSettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("streamlink_settings", Context.MODE_PRIVATE)

    // كاش محلي سريع جداً في الذاكرة مع StateFlow للـ UI
    private val _isDynamicFpsEnabled = MutableStateFlow(prefs.getBoolean("dynamic_fps", true))
    val isDynamicFpsEnabled: StateFlow<Boolean> = _isDynamicFpsEnabled.asStateFlow()

    private val _isPrivacyBlackoutEnabled = MutableStateFlow(prefs.getBoolean("privacy_blackout", false))
    val isPrivacyBlackoutEnabled: StateFlow<Boolean> = _isPrivacyBlackoutEnabled.asStateFlow()

    private val _isImuGesturesEnabled = MutableStateFlow(prefs.getBoolean("imu_gestures", false))
    val isImuGesturesEnabled: StateFlow<Boolean> = _isImuGesturesEnabled.asStateFlow()

    private val _isInstantSyncEnabled = MutableStateFlow(prefs.getBoolean("instant_sync", true))
    val isInstantSyncEnabled: StateFlow<Boolean> = _isInstantSyncEnabled.asStateFlow()

    private val _connectedWatchName = MutableStateFlow(prefs.getString("watch_name", "") ?: "")
    val connectedWatchName: StateFlow<String> = _connectedWatchName.asStateFlow()

    private val _connectedWatchIp = MutableStateFlow(prefs.getString("watch_ip", "") ?: "")
    val connectedWatchIp: StateFlow<String> = _connectedWatchIp.asStateFlow()

    private val _themeMode = MutableStateFlow(prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM")
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    // Callback لإرسال تحديث Jitter Buffer للساعة عبر control channel
    @Volatile var onJitterBufferUpdateRequested: ((Int) -> Unit)? = null

    fun setDynamicFps(enabled: Boolean) {
        _isDynamicFpsEnabled.value = enabled
        prefs.edit().putBoolean("dynamic_fps", enabled).apply()
    }

    fun setPrivacyBlackout(enabled: Boolean) {
        _isPrivacyBlackoutEnabled.value = enabled
        prefs.edit().putBoolean("privacy_blackout", enabled).apply()
    }

    fun setImuGestures(enabled: Boolean) {
        _isImuGesturesEnabled.value = enabled
        prefs.edit().putBoolean("imu_gestures", enabled).apply()
    }

    fun setInstantSync(enabled: Boolean) {
        _isInstantSyncEnabled.value = enabled
        prefs.edit().putBoolean("instant_sync", enabled).apply()
    }

    fun setConnectedWatch(name: String, ip: String) {
        _connectedWatchName.value = name
        _connectedWatchIp.value = ip
        prefs.edit()
            .putString("watch_name", name)
            .putString("watch_ip", ip)
            .apply()
    }

    fun clearConnectedWatch() {
        _connectedWatchName.value = ""
        _connectedWatchIp.value = ""
        prefs.edit()
            .remove("watch_name")
            .remove("watch_ip")
            .apply()
    }

    fun setThemeMode(mode: String) {
        _themeMode.value = mode
        prefs.edit().putString("theme_mode", mode).apply()
    }
}
