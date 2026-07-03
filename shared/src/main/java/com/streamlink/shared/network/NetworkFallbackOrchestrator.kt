package com.streamlink.shared.network

import com.streamlink.shared.TouchPhase
import java.util.concurrent.atomic.AtomicReference

enum class NetworkTransportMode {
    LOCAL_TCP,
    GLOBAL_WEBRTC,
    DISCONNECTED
}

/**
 * Lock-free transport selector: local TCP first, seamless WebRTC HOTC fallback.
 *
 * ملاحظة: webRtcSender ممكن يكون null دلوقتي — لسه محتاج تنفيذ حقيقي لـWebRTC
 * على جانب الساعة (Phase 2). لغاية ما ده يتعمل، الـorchestrator هيشتغل على
 * LOCAL_TCP بس وهيرفض بهدوء (false) لو الاتصال المحلي مقطوع، بدل ما يكراش.
 */
class NetworkFallbackOrchestrator(
    private val localSender: LocalTouchSender,
    private val webRtcSender: WebRtcHotcSender?
) {
    private val currentMode = AtomicReference(NetworkTransportMode.DISCONNECTED)

    fun setTransportMode(mode: NetworkTransportMode) {
        currentMode.set(mode)
    }

    fun currentMode(): NetworkTransportMode = currentMode.get()

    /**
     * بيحدد المسار تلقائيًا من حالة الاتصال الفعلية بدل ما يعتمد على حد يستدعي
     * markLocalConnected/markDisconnected يدويًا (محدش كان بيستدعيهم أصلاً).
     */
    fun dispatchTouch(phase: TouchPhase, pointerId: Int, nx: Float, ny: Float, seq: Int, timestampUs: Long): Boolean {
        if (localSender.isConnected()) {
            currentMode.set(NetworkTransportMode.LOCAL_TCP)
            localSender.sendTouch(phase, pointerId, nx, ny, seq, timestampUs)
            return true
        }
        if (webRtcSender != null && webRtcSender.isReady()) {
            currentMode.set(NetworkTransportMode.GLOBAL_WEBRTC)
            return webRtcSender.sendTouch(phase, pointerId, nx, ny, seq, timestampUs)
        }
        currentMode.set(NetworkTransportMode.DISCONNECTED)
        return false
    }

    fun markLocalConnected() = currentMode.set(NetworkTransportMode.LOCAL_TCP)
    fun markDisconnected() = currentMode.set(NetworkTransportMode.DISCONNECTED)
}

interface LocalTouchSender {
    fun sendTouch(phase: TouchPhase, pointerId: Int, nx: Float, ny: Float, seq: Int, timestampUs: Long)
    fun isConnected(): Boolean
}

interface WebRtcHotcSender {
    fun sendTouch(phase: TouchPhase, pointerId: Int, nx: Float, ny: Float, seq: Int, timestampUs: Long): Boolean
    fun isReady(): Boolean
}
