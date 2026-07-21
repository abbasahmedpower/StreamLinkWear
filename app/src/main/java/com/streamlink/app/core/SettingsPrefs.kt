package com.streamlink.app.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SettingsPrefs private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("streamlink_settings", Context.MODE_PRIVATE)

    private val _quality = MutableStateFlow(
        com.streamlink.shared.QualityMode.fromName(prefs.getString("quality", null))
    )
    val quality: StateFlow<com.streamlink.shared.QualityMode> = _quality

    // ✅ FIXED: كان bufferSeconds بمدى 10-60 ثانية — delay حرفي كان هيكسر
    // أي تفاعل remote-control. بقى bufferJitterMs بمدى واقعي (0-800ms)،
    // وموصول فعليًا بـ DirectStreamPlayer على الساعة عبر control channel.
    private val _bufferJitterMs = MutableStateFlow(prefs.getInt("buffer_jitter_ms", 150))
    val bufferJitterMs: StateFlow<Int> = _bufferJitterMs

    // ✅ BUG-05 FIX: Callback لإرسال CMD_SET_BUFFER_JITTER_MS للساعة عند تغيير القيمة.
    // يُعيَّن من StreamingOrchestrator / DirectSocketServer عند نجاح الاتصال.
    @Volatile var onJitterBufferSendRequested: ((Int) -> Unit)? = null

    fun setQuality(q: com.streamlink.shared.QualityMode) {
        _quality.value = q
        prefs.edit().putString("quality", q.name).apply()
    }

    fun setBufferJitterMs(ms: Int) {
        val clamped = ms.coerceIn(0, 1000)
        _bufferJitterMs.value = clamped
        prefs.edit().putInt("buffer_jitter_ms", clamped).apply()
        // ✅ BUG-05: إرسال للساعة فوراً إذا كان هناك اتصال نشط
        onJitterBufferSendRequested?.invoke(clamped)
    }

    companion object {
        @Volatile private var instance: SettingsPrefs? = null
        fun get(context: Context): SettingsPrefs =
            instance ?: synchronized(this) { instance ?: SettingsPrefs(context).also { instance = it } }
    }
}
