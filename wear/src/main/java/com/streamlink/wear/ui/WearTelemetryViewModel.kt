package com.streamlink.wear.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import com.streamlink.shared.StreamProtocol

/**
 * WearTelemetryViewModel
 *
 * Listens for telemetry messages arriving via Wearable MessageClient from the phone.
 * Updates StateFlows consumed by WearTelemetryScreen's Compose UI.
 *
 * Message Path: /telemetry_stream
 * Payload: JSON { battery: Int%, congestion: Int%, bitrate: Int (kbps) }
 */
class WearTelemetryViewModel(application: Application) :
    AndroidViewModel(application),
    MessageClient.OnMessageReceivedListener {

    private val tag = "WearTelemetryVM"
    private val gson = Gson()
    private val messageClient = Wearable.getMessageClient(application)

    private val _battery = MutableStateFlow("--")
    val battery: StateFlow<String> = _battery.asStateFlow()

    private val _congestionPct = MutableStateFlow(0)
    val congestionPct: StateFlow<Int> = _congestionPct.asStateFlow()

    private val _bitrate = MutableStateFlow("-- Kbps")
    val bitrate: StateFlow<String> = _bitrate.asStateFlow()

    private val _isStressed = MutableStateFlow(false)
    val isStressed: StateFlow<Boolean> = _isStressed.asStateFlow()

    private var simJob: Job? = null
    var isDemoMode = false
        private set

    var lastHeartbeatTimestamp: Long = 0L

    init {
        messageClient.addListener(this)
        Log.d(tag, "WearTelemetryViewModel initialized — listening on /telemetry_stream")
    }

    // دالة لتشغيل/إيقاف المحاكاة للتجريب على الـ Emulator
    fun toggleSimulationMode() {
        isDemoMode = !isDemoMode
        if (isDemoMode) {
            simJob = viewModelScope.launch {
                var stressed = false
                while (isDemoMode) {
                    if (stressed) {
                        // 🟢 حالة مستقرة (Nominal State)
                        _congestionPct.value = 12
                        _bitrate.value = "2450 Kbps"
                        _battery.value = "82%"
                        _isStressed.value = false
                    } else {
                        // 🚨 حالة اختناق شبكة (Critical Throttling)
                        _congestionPct.value = 85
                        _bitrate.value = "600 Kbps"
                        _battery.value = "81%"
                        _isStressed.value = true
                    }
                    stressed = !stressed
                    delay(3000) // التغيير كل 3 ثوانٍ
                }
            }
        } else {
            simJob?.cancel()
            // Reset to default
            _congestionPct.value = 0
            _bitrate.value = "-- Kbps"
            _battery.value = "--%"
            _isStressed.value = false
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == StreamProtocol.PATH_HEARTBEAT_PING) {
            onHeartbeatReceived()
            return
        }

        if (isDemoMode) return // إذا كان طور المحاكاة نشطاً، نتجاهل بيانات الموبايل مؤقتاً للتجربة
        if (event.path != "/telemetry_stream") return

        try {
            val json = String(event.data, Charsets.UTF_8)
            val type = object : TypeToken<Map<String, Number>>() {}.type
            val data: Map<String, Number> = gson.fromJson(json, type)

            val battery    = data["battery"]?.toInt() ?: 0
            val congestion = data["congestion"]?.toInt() ?: 0
            val bitrate    = data["bitrate"]?.toInt() ?: 0

            _battery.value       = "$battery%"
            _congestionPct.value = congestion
            _bitrate.value       = "$bitrate Kbps"
            _isStressed.value    = congestion > 70  // >70% queue = stressed

            Log.d(tag, "Received telemetry: battery=$battery%, congestion=$congestion%, bitrate=$bitrate kbps")
        } catch (e: Exception) {
            Log.w(tag, "Failed to parse telemetry payload: ${e.message}")
        }
    }

    private fun onHeartbeatReceived() {
        lastHeartbeatTimestamp = System.currentTimeMillis()
        // Reset session timeout watchdogs here without triggering full UI re-renders
        Log.d(tag, "Heartbeat Ping received from Phone. Session kept alive.")
    }

    override fun onCleared() {
        super.onCleared()
        messageClient.removeListener(this)
        simJob?.cancel()
    }
}
