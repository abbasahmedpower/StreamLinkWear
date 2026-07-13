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

    // ✅ FIXED: كان bufferSeconds بمدى 10-60 ثانية — delay حرفي كان هيكسر
    // أي تفاعل remote-control. بقى bufferJitterMs بمدى واقعي (0-800ms)،
    // وموصول فعليًا بـ DirectStreamPlayer على الساعة عبر control channel.
    private val _bufferJitterMs = MutableStateFlow(prefs.getInt("buffer_jitter_ms", 150))
    val bufferJitterMs: StateFlow<Int> = _bufferJitterMs

    fun setQuality(q: StreamQuality) {
        _quality.value = q
        prefs.edit().putString("quality", q.name).apply()
    }

    fun setBufferJitterMs(ms: Int) {
        val clamped = ms.coerceIn(0, 800)
        _bufferJitterMs.value = clamped
        prefs.edit().putInt("buffer_jitter_ms", clamped).apply()
    }

    companion object {
        @Volatile private var instance: SettingsPrefs? = null
        fun get(context: Context): SettingsPrefs =
            instance ?: synchronized(this) { instance ?: SettingsPrefs(context).also { instance = it } }
    }
}
