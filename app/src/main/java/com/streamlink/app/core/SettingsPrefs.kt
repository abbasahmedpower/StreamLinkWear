package com.streamlink.app.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class StreamQuality(val label: String) { HD720("720p (HD)"), FHD1080("1080p (FHD)"), QHD1440("1440p (QHD)") }

class SettingsPrefs private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("streamlink_settings", Context.MODE_PRIVATE)

    private val _quality = MutableStateFlow(
        StreamQuality.entries.find { it.name == prefs.getString("quality", null) } ?: StreamQuality.FHD1080
    )
    val quality: StateFlow<StreamQuality> = _quality

    private val _bufferSeconds = MutableStateFlow(prefs.getInt("buffer_s", 30))
    val bufferSeconds: StateFlow<Int> = _bufferSeconds

    fun setQuality(q: StreamQuality) {
        _quality.value = q
        prefs.edit().putString("quality", q.name).apply()
        // TODO: غير موصولة بمحرك التشفير الفعلي (HardwareEncoder) — راجع StartStreamingUseCase.kt
        // لتوصيل القيمة دي فعليًا بـ profile الترميز.
    }
    fun setBufferSeconds(s: Int) {
        _bufferSeconds.value = s
        prefs.edit().putInt("buffer_s", s).apply()
        // TODO: غير موصولة بـ BackpressureController.kt الفعلي.
    }

    companion object {
        @Volatile private var instance: SettingsPrefs? = null
        fun get(context: Context): SettingsPrefs =
            instance ?: synchronized(this) { instance ?: SettingsPrefs(context).also { instance = it } }
    }
}
