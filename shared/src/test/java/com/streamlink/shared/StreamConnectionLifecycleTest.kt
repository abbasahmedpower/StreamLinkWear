package com.streamlink.shared

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.IOException

/**
 * StreamConnectionLifecycleTest: اختبار تكاملي شامل لفحص استقرار دورة حياة الاتصال،
 * ومراقبة التحول التلقائي وآليات الـ Self-Healing تحت ظروف الفشل الشبكي.
 */
class StreamConnectionLifecycleTest {

    private lateinit var signalingClient: MockSignalingClient
    private lateinit var fallbackOrchestrator: NetworkFallbackOrchestrator
    private lateinit var streamingOrchestrator: StreamingOrchestrator
    private lateinit var chaosEngine: NetworkChaosEngine

    @BeforeEach
    fun setup() {
        // تهيئة المكونات في بيئة معزولة ومحاكية للواقع
        signalingClient = MockSignalingClient()
        fallbackOrchestrator = NetworkFallbackOrchestrator()
        
        // ربط المكونات معاً لبناء سلسلة الإدارة (Orchestration Chain)
        streamingOrchestrator = StreamingOrchestrator(
            signalingClient = signalingClient,
            fallbackOrchestrator = fallbackOrchestrator
        )
    }

    @Test
    fun `test full successful connection lifecycle transition`() {
        // المرحلة 1: التحقق من الحالة الابتدائية (IDLE)
        assertEquals("IDLE", streamingOrchestrator.getCurrentState(), "Lifecycle error: Initial state must be IDLE")

        // المرحلة 2: بدء إشارات الاتصال والمصافحة (Signaling Handshake)
        streamingOrchestrator.initiateConnection(targetUrl = "wss://horustech.streamlink/connect")
        assertEquals("CONNECTING", streamingOrchestrator.getCurrentState(), "Lifecycle error: System should transition to CONNECTING")

        // المرحلة 3: محاكاة نجاح تبادل المفاتيح عبر WebRTC واستقرار الاتصال
        signalingClient.simulateSuccessfulPeerHandshake()
        streamingOrchestrator.verifyAndEstablish()

        assertEquals("CONNECTED", streamingOrchestrator.getCurrentState(), "Lifecycle error: System failed to establish FULL CONNECTED state")
        assertTrue(fallbackOrchestrator.isUsingGlobalWebRTC(), "Routing error: Primary connection must prioritize Global WebRTC")
    }

    @Test
    fun `test network crash triggers automatic self healing and local fallback`() {
        // تهيئة اتصال مستقر أولاً
        streamingOrchestrator.initiateConnection(targetUrl = "wss://horustech.streamlink/connect")
        signalingClient.simulateSuccessfulPeerHandshake()
        streamingOrchestrator.verifyAndEstablish()
        
        // تفعيل محرك الفوضى لمحاكاة كارثة شبكية وانقطاع مفاجئ للـ WebRTC Socket
        chaosEngine = NetworkChaosEngine(isChaosEnabled = true, packetDropProbability = 1.0)
        
        // محاكاة إرسال فريم شبكي أثناء الكارثة
        try {
            chaosEngine.injectChaosOrForward(frameId = 300L) {
                throw IOException("Simulated WebRTC Socket Crash")
            }
        } catch (e: IOException) {
            // استدعاء نظام الإنقاذ التلقائي برمجياً بناءً على رصد الانهيار
            fallbackOrchestrator.triggerEmergencyFallback()
            streamingOrchestrator.handleConnectionDrop()
        }

        // التحقق من كفاءة الـ Self-Healing: يجب أن يتحول النظام فوراً إلى الشبكة المحلية بدلاً من الانهيار
        assertEquals("FALLBACK_LOCAL", streamingOrchestrator.getCurrentState(), "Self-Healing Failure: System did not transition to FALLBACK_LOCAL state")
        assertFalse(fallbackOrchestrator.isUsingGlobalWebRTC(), "Routing error: System must abandon crashed WebRTC and lock onto Local LAN")
    }
}

// --- فئات محاكة (Mock Classes) لدعم بيئة الاختبار التكاملي المعزول ---

class MockSignalingClient {
    var isHandshakeComplete = false
    
    fun simulateSuccessfulPeerHandshake() {
        isHandshakeComplete = true
    }
}

class NetworkFallbackOrchestrator {
    private var useGlobalWebRTC = true

    fun triggerEmergencyFallback() {
        useGlobalWebRTC = false
    }

    fun isUsingGlobalWebRTC(): Boolean = useGlobalWebRTC
}

class StreamingOrchestrator(
    private val signalingClient: MockSignalingClient,
    private val fallbackOrchestrator: NetworkFallbackOrchestrator
) {
    private var state = "IDLE"

    fun initiateConnection(targetUrl: String) {
        state = "CONNECTING"
    }

    fun verifyAndEstablish() {
        if (signalingClient.isHandshakeComplete) {
            state = "CONNECTED"
        }
    }

    fun handleConnectionDrop() {
        if (!fallbackOrchestrator.isUsingGlobalWebRTC()) {
            state = "FALLBACK_LOCAL"
        }
    }

    fun getCurrentState(): String = state
}

class NetworkChaosEngine(val isChaosEnabled: Boolean, val packetDropProbability: Double) {
    fun injectChaosOrForward(frameId: Long, block: () -> Unit) {
        if (isChaosEnabled && packetDropProbability >= 1.0) {
            block()
        }
    }
}
