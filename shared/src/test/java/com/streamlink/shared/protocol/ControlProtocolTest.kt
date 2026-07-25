package com.streamlink.shared.protocol

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Phase 1.5 — Protocol Contract Tests
 *
 * Verifies that the JSON ControlMessage ser/de contract is stable.
 * A break here = phone & watch will lose ability to communicate settings.
 */
class ControlProtocolTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── Serialization round-trip ──────────────────────────────────────────────

    @Test
    fun `SettingsUpdate round-trips through JSON without data loss`() {
        val original = ControlMessage.SettingsUpdate(
            dynamicFps = true,
            imuGestures = false,
            jitterBufferMs = 250
        )
        val encoded = Json.encodeToString(ControlMessage.serializer(), original)
        val decoded = Json.decodeFromString(ControlMessage.serializer(), encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun `SettingsUpdate with null jitterBufferMs serializes cleanly`() {
        val msg = ControlMessage.SettingsUpdate(dynamicFps = false, imuGestures = true, jitterBufferMs = null)
        val encoded = Json.encodeToString(ControlMessage.serializer(), msg)
        val decoded = Json.decodeFromString(ControlMessage.serializer(), encoded) as ControlMessage.SettingsUpdate

        assertNull(decoded.jitterBufferMs)
        assertTrue(decoded.imuGestures)
        assertFalse(decoded.dynamicFps)
    }

    @Test
    fun `Ack round-trips through JSON`() {
        val ack = ControlMessage.Ack(messageId = "abc-123", status = "ok")
        val encoded = Json.encodeToString(ControlMessage.serializer(), ack)
        val decoded = Json.decodeFromString(ControlMessage.serializer(), encoded) as ControlMessage.Ack

        assertEquals("abc-123", decoded.messageId)
        assertEquals("ok", decoded.status)
    }

    @Test
    fun `Handshake round-trips with correct protocolVersion`() {
        val msg = ControlMessage.Handshake(protocolVersion = 1)
        val encoded = Json.encodeToString(ControlMessage.serializer(), msg)
        val decoded = Json.decodeFromString(ControlMessage.serializer(), encoded) as ControlMessage.Handshake

        assertEquals(1, decoded.protocolVersion)
    }

    @Test
    fun `ignoreUnknownKeys allows forward-compat decoding`() {
        // Watch on older version receiving a message from newer phone with extra fields
        val futureJson = """{"type":"settings","dynamicFps":true,"imuGestures":true,"jitterBufferMs":200,"newFutureField":"xyz"}"""
        val decoded = json.decodeFromString(ControlMessage.serializer(), futureJson) as ControlMessage.SettingsUpdate

        assertTrue(decoded.dynamicFps)
        assertTrue(decoded.imuGestures)
        assertEquals(200, decoded.jitterBufferMs)
    }
}
