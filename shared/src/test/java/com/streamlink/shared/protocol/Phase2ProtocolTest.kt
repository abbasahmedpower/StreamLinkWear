package com.streamlink.shared.protocol

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Phase 2 — Protocol Unit Tests (JUnit 5)
 * يغطي: ExtensibleHeader, FrameWindow, AckBitmap, BackpressureEngine
 *
 * كل test هنا بيتشغل بدون شبكة حقيقية — pure logic, zero side effects.
 * ده بالظبط الـ Checklist item: "Unit tests لكل encode/decode مع corrupt/malformed packets"
 */
class Phase2ProtocolTest {

    // ── ExtensibleHeader Tests ─────────────────────────────────────────────────

    @Test
    fun `ExtensibleHeader - base header roundtrip (no extensions)`() {
        val header = ExtensibleHeader.FrameHeader(
            sequence = 42u,
            chunkIndex = 0u,
            chunkCount = 1u,
            payloadLength = 1200u,
            flags = 0,
        )
        val buf = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN)
        ExtensibleHeader.encode(buf, header)
        buf.flip()

        val decoded = ExtensibleHeader.decode(buf)
        assertEquals(42u, decoded.sequence)
        assertEquals(0u.toUShort(), decoded.chunkIndex)
        assertEquals(1u.toUShort(), decoded.chunkCount)
        assertEquals(1200u.toUShort(), decoded.payloadLength)
        assertEquals(0, decoded.flags)
        assertNull(decoded.streamId)
        assertNull(decoded.frameId)
        assertNull(decoded.timestampUs)
        assertNull(decoded.nalType)
        assertNull(decoded.priority)
    }

    @Test
    fun `ExtensibleHeader - wireSize base only = 13 bytes`() {
        val h = ExtensibleHeader.FrameHeader(0u, 0u, 1u, 100u, flags = 0)
        assertEquals(13, h.wireSize())
    }

    @Test
    fun `ExtensibleHeader - wireSize with all extensions = 31 bytes`() {
        val flags = ExtensibleHeader.Flags.FULL or ExtensibleHeader.Flags.HAS_CRC32C
        val h = ExtensibleHeader.FrameHeader(
            sequence = 1u, chunkIndex = 0u, chunkCount = 1u, payloadLength = 500u,
            flags = flags, streamId = 1u, frameId = 10u, timestampUs = 999u,
            nalType = 5u, priority = 1u
        )
        // 13 (base) + 4 (streamId) + 4 (frameId) + 4 (timestamp) + 2 (nal+prio) + 4 (crc) = 31
        assertEquals(31, h.wireSize())
    }

    @Test
    fun `ExtensibleHeader - all extensions roundtrip`() {
        val flags = ExtensibleHeader.Flags.FULL
        val header = ExtensibleHeader.FrameHeader(
            sequence = 9999u,
            chunkIndex = 3u,
            chunkCount = 5u,
            payloadLength = 3800u,
            flags = flags,
            streamId = 2u,
            frameId = 777u,
            timestampUs = 123456u,
            nalType = 5u,
            priority = 0u,
        )
        val buf = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN)
        ExtensibleHeader.encode(buf, header)
        buf.flip()

        val decoded = ExtensibleHeader.decode(buf)
        assertEquals(9999u, decoded.sequence)
        assertEquals(2u, decoded.streamId)
        assertEquals(777u, decoded.frameId)
        assertEquals(123456u, decoded.timestampUs)
        assertEquals(5u.toUByte(), decoded.nalType)
        assertTrue(decoded.isKeyframe)
    }

    @Test
    fun `ExtensibleHeader - rejects bad magic byte`() {
        assertThrows<IllegalArgumentException> {
            val buf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            buf.put(0x00.toByte())  // bad magic
            buf.put(0x30.toByte())  // version 3
            buf.flip()
            ExtensibleHeader.decode(buf)
        }
    }

    @Test
    fun `ExtensibleHeader - rejects wrong version`() {
        assertThrows<IllegalArgumentException> {
            val buf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            buf.put(0x48.toByte())  // correct magic
            buf.put(0x10.toByte())  // version 1 (wrong, expected 3)
            buf.flip()
            ExtensibleHeader.decode(buf)
        }
    }

    @Test
    fun `ExtensibleHeader - forward compatibility unknown flag bits ignored`() {
        // Old reader should not crash on new flag bits (6-7)
        val header = ExtensibleHeader.FrameHeader(
            sequence = 5u, chunkIndex = 0u, chunkCount = 1u, payloadLength = 100u,
            flags = 0b11000000  // bits 6-7 set — unknown to current reader
        )
        val buf = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        ExtensibleHeader.encode(buf, header)
        buf.flip()

        // Should decode without crash, unknown flags preserved in flags field
        val decoded = ExtensibleHeader.decode(buf)
        assertEquals(5u, decoded.sequence)
        assertEquals(0b11000000, decoded.flags)
    }

    // ── FrameWindow Tests ─────────────────────────────────────────────────────

    private lateinit var window: FrameWindow

    @BeforeEach
    fun setupWindow() {
        window = FrameWindow(capacity = 64)  // small for testing
    }

    @Test
    fun `FrameWindow - register and ack basic flow`() {
        window.register(1u, nowMs = 1000L)
        assertFalse(window.isAcked(1u))
        window.onAck(1u)
        assertTrue(window.isAcked(1u))
    }

    @Test
    fun `FrameWindow - unregistered sequence returns false`() {
        assertFalse(window.isAcked(99u))
        assertEquals(-1L, window.sentAt(99u))
    }

    @Test
    fun `FrameWindow - RTT calculation`() {
        window.register(10u, nowMs = 1000L)
        val rtt = window.rttMs(10u, nowMs = 1060L)
        assertEquals(60L, rtt)
    }

    @Test
    fun `FrameWindow - duplicate ACK triggers at threshold only once`() {
        window.register(20u, nowMs = 0L)
        assertFalse(window.onDuplicateAck(20u, threshold = 3))
        assertFalse(window.onDuplicateAck(20u, threshold = 3))
        assertTrue(window.onDuplicateAck(20u, threshold = 3))   // threshold hit
        assertFalse(window.onDuplicateAck(20u, threshold = 3))  // already past threshold
    }

    @Test
    fun `FrameWindow - slot collision overwrites old sequence`() {
        // seq=0 and seq=64 map to same slot in capacity-64 window
        window.register(0u, nowMs = 1000L)
        window.onAck(0u)
        assertTrue(window.isAcked(0u))

        // Registering seq=64 overwrites slot
        window.register(64u, nowMs = 2000L)
        assertFalse(window.isAcked(0u))  // old seq evicted
    }

    @Test
    fun `FrameWindow - reset clears all state`() {
        window.register(1u, 1000L)
        window.register(2u, 1001L)
        window.onAck(1u)
        window.reset()
        assertFalse(window.isAcked(1u))
        assertEquals(0, window.pendingCount)
    }

    // ── AckBitmap Tests ───────────────────────────────────────────────────────

    @Test
    fun `AckBitmap - build and apply roundtrip`() {
        val w = FrameWindow(64)
        // Register and ack sequences 10, 11, 12; 13 stays pending
        for (seq in 10u..12u) { w.register(seq, 1000L); w.onAck(seq) }
        w.register(13u, 1000L) // pending

        val frame = AckBitmap.build(w, largestAcked = 13u)
        assertTrue(frame.covers(12u))
        assertTrue(frame.covers(11u))
        assertTrue(frame.covers(10u))
        assertFalse(frame.covers(13u))  // 13 not acked
    }

    @Test
    fun `AckBitmap - encode decode roundtrip`() {
        val original = AckBitmap.AckFrame(largestAcked = 100u, bitmap = 0b1011L)
        val bytes = AckBitmap.encode(original)
        val decoded = AckBitmap.decode(bytes)
        assertEquals(100u, decoded.largestAcked)
        assertEquals(0b1011L, decoded.bitmap)
    }

    @Test
    fun `AckBitmap - RTT loss detection finds stale unacked frames`() {
        val w = FrameWindow(64)
        w.register(1u, nowMs = 0L)    // sent 200ms ago → lost
        w.register(2u, nowMs = 150L)  // sent 50ms ago → not yet lost

        val lost = AckBitmap.detectRttLoss(
            window = w,
            lastSentSeq = 2u,
            timeoutMs = 100L,
            nowMs = 200L
        )
        assertTrue(lost.contains(1u))
        assertFalse(lost.contains(2u))
    }

    @Test
    fun `AckBitmap - covers returns false for sequences outside window`() {
        val frame = AckBitmap.AckFrame(largestAcked = 10u, bitmap = -1L)
        assertFalse(frame.covers(100u))  // too far ahead
        assertTrue(frame.covers(10u))
        assertTrue(frame.covers(0u))     // just at edge (64-10 < 64)
    }

    // ── BackpressureEngine Tests ───────────────────────────────────────────────

    // Fresh engine per test (no shared state between tests)
    private val engine = BackpressureEngine(targetRttMs = 60)

    @Test
    fun `BackpressureEngine - green conditions yield INCREASE_BITRATE`() {
        val sample = BackpressureEngine.TelemetrySample(
            rttMs = 30, lossPct = 0.0f, thermalState = 0,
            queueAgeMs = 0, batteryPct = 90, cpuLoadPct = 20
        )
        val result = engine.computePressure(sample)
        assertTrue(result.score < 50, "Expected low pressure, got ${result.score}")
        assertEquals(BackpressureEngine.PressureResult.Action.INCREASE_BITRATE, result.action)
    }

    @Test
    fun `BackpressureEngine - critical thermal increases pressure score`() {
        val sample = BackpressureEngine.TelemetrySample(
            rttMs = 60, lossPct = 0.0f, thermalState = 3,  // HEAVY/SEVERE
            queueAgeMs = 0, batteryPct = 80, cpuLoadPct = 30
        )
        val result = engine.computePressure(sample)
        // Thermal weight=0.20 × score=100 = 20 points minimum
        assertTrue(result.score >= 15, "Expected elevated pressure, got ${result.score}")
    }

    @Test
    fun `BackpressureEngine - high packet loss pushes above hold threshold`() {
        val sample = BackpressureEngine.TelemetrySample(
            rttMs = 60, lossPct = 0.20f, thermalState = 0,
            queueAgeMs = 0, batteryPct = 80, cpuLoadPct = 20
        )
        // Send sample multiple times to overcome the initial lastScore=0 hysteresis (0.7 weight)
        // and reach the steady state raw score (~30.1).
        repeat(4) { engine.computePressure(sample) }
        val result = engine.computePressure(sample)
        
        // Loss score = (0.15/0.20) × 100 × 0.30 weight = 22.5
        // RTT score = (60/60)/3 × 100 × 0.20 weight = 6.66
        // Raw score ~30. After 2 rounds of smoothing, should be > 20 and action should not be INCREASE
        assertTrue(result.score > 20, "Expected score > 20, got ${result.score}")
        assertNotEquals(BackpressureEngine.PressureResult.Action.INCREASE_BITRATE, result.action)
    }

    @Test
    fun `BackpressureEngine - low battery contributes high battery score`() {
        val sample = BackpressureEngine.TelemetrySample(
            rttMs = 20, lossPct = 0.0f, thermalState = 0,
            queueAgeMs = 0, batteryPct = 8,  // < 10 → 100 score
            cpuLoadPct = 10
        )
        val result = engine.computePressure(sample)
        assertEquals(100f, result.breakdown.batteryScore)
    }

    @Test
    fun `BackpressureEngine - thermalStatusToState mapping`() {
        assertEquals(0, BackpressureEngine.thermalStatusToState(0))  // NONE
        assertEquals(0, BackpressureEngine.thermalStatusToState(1))  // LIGHT
        assertEquals(1, BackpressureEngine.thermalStatusToState(2))  // MODERATE
        assertEquals(2, BackpressureEngine.thermalStatusToState(3))  // SEVERE
        assertEquals(3, BackpressureEngine.thermalStatusToState(4))  // CRITICAL
        assertEquals(3, BackpressureEngine.thermalStatusToState(5))  // EMERGENCY
    }

    @Test
    fun `BackpressureEngine - bitrateMultiplier values are sane`() {
        assertTrue(BackpressureEngine.bitrateMultiplier(BackpressureEngine.PressureResult.Action.INCREASE_BITRATE) > 1.0f)
        assertEquals(1.0f, BackpressureEngine.bitrateMultiplier(BackpressureEngine.PressureResult.Action.HOLD))
        assertTrue(BackpressureEngine.bitrateMultiplier(BackpressureEngine.PressureResult.Action.REDUCE_BITRATE) < 1.0f)
        assertTrue(BackpressureEngine.bitrateMultiplier(BackpressureEngine.PressureResult.Action.REDUCE_HARD) < 0.80f)
    }
}
