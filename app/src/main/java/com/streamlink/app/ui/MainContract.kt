package com.streamlink.app.ui

import android.content.Intent

data class MainState(
    val isStreaming: Boolean = false,
    val isConnecting: Boolean = false,
    val latencyMs: Long = 0,
    val bitrateKbps: Int = 0,
    val fps: Int = 0,
    val isPrivacyBlackoutEnabled: Boolean = false,
    val aiOptimizerEnabled: Boolean = true
)

sealed interface MainIntent {
    object StartCaptureRequested : MainIntent
    data class StreamResultReceived(
        val resultCode: Int,
        val projectionData: Intent
    ) : MainIntent
    /** Dispatched when the MediaProjection permission is denied or returns null data. */
    object StreamPermissionDenied : MainIntent
    object StopStream : MainIntent
    data class SetAiOptimizer(val enabled: Boolean) : MainIntent
    object RequestOverlayPermission : MainIntent
    data class SetPrivacyBlackout(val enabled: Boolean) : MainIntent
    object ResetPairing : MainIntent
}

sealed interface MainEffect {
    object LaunchScreenCapture : MainEffect
    object LaunchQrScanner : MainEffect
    data class ShowToast(val message: String, val isLong: Boolean = false) : MainEffect
    object RequestOverlayPermission : MainEffect
}
