package com.streamlink.shared

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class KeyExchangeTest {
    @Test
    fun `session keys match on both sides after ECDH exchange`() {
        val phone = KeyExchange.generateEphemeralKeyPair()
        val watch = KeyExchange.generateEphemeralKeyPair()

        val phoneKey = KeyExchange.deriveSessionKey(phone, watch.publicKeyBase64, "123456")
        val watchKey = KeyExchange.deriveSessionKey(watch, phone.publicKeyBase64, "123456")

        assertArrayEquals(phoneKey, watchKey)  // ✅ لازم يتطابقوا
    }

    @Test
    fun `wrong pin produces different session key`() {
        val phone = KeyExchange.generateEphemeralKeyPair()
        val watch = KeyExchange.generateEphemeralKeyPair()

        val correctKey = KeyExchange.deriveSessionKey(phone, watch.publicKeyBase64, "123456")
        val wrongKey = KeyExchange.deriveSessionKey(phone, watch.publicKeyBase64, "654321")

        assertFalse(correctKey.contentEquals(wrongKey))  // ✅ الـ PIN الغلط لازم يبوظ المفتاح
    }

    @Test
    fun `invalid curve point is rejected`() {
        assertFalse(KeyExchange.validatePeerKey("not-a-valid-key"))
    }
}
