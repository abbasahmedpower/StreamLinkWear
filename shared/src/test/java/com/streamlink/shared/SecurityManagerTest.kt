package com.streamlink.shared

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SecurityManagerTest {

    private val masterKey = ByteArray(32) { 0x01.toByte() }

    @BeforeEach
    fun setUp() {
        SecurityManager.resetForTest()
        SecurityManager.setKey(masterKey)
    }

    @Test
    fun `verify flawless AES-256-GCM encryption and decryption roundtrip`() {
        val originalPayload = "NASA_STREAM_DATA_CRITICAL_2026"
        val sequenceNumber = 10

        val encryptedData = SecurityManager.encrypt(originalPayload, masterKey, sequenceNumber)
        assertNotNull(encryptedData)
        assertNotEquals(originalPayload, encryptedData)

        val decryptedData = SecurityManager.decrypt(encryptedData, masterKey)
        assertEquals(originalPayload, decryptedData, "Decrypted payload does not match original!")
    }

    @Test
    fun `verify Replay Attack prevention drops duplicated Nonces immediately`() {
        val payload = "SecurePacket"
        val sequenceNumber = 42

        val encrypted = SecurityManager.encrypt(payload, masterKey, sequenceNumber)
        val decryptedFirst = SecurityManager.decrypt(encrypted, masterKey)
        assertNotNull(decryptedFirst)

        // Attempting to decrypt the exact same encoded packet (which includes the same IV and Seq)
        // should return null because the IV cache will flag it as a replay attack.
        val decryptedSecond = SecurityManager.decrypt(encrypted, masterKey)
        assertNull(decryptedSecond, "Replay attack was successful! Nonce cache failed to block duplicate.")
    }

    @Test
    fun `verify authenticated metadata cannot be altered`() {
        val payload = "SecurePacket"
        val encrypted = SecurityManager.encrypt(payload, masterKey, sequenceNumber = 7)
        val decoded = java.util.Base64.getDecoder().decode(encrypted)

        val sequenceOffset = StreamProtocol.GCM_IV_BYTES + 2
        decoded[sequenceOffset] = (decoded[sequenceOffset].toInt() xor 0x01).toByte()

        val tampered = java.util.Base64.getEncoder().encodeToString(decoded)
        assertNull(SecurityManager.decrypt(tampered, masterKey))
    }

    @Test
    fun `verify Bounded Eviction does not crash when cache exceeds limits`() {
        val payload = "Data"
        
        repeat(150) { index ->
            val encrypted = SecurityManager.encrypt(payload, masterKey, index)
            SecurityManager.decrypt(encrypted, masterKey)
        }
        
        assertTrue(SecurityManager.nonceCacheSize() <= 1024, "Cache leaked beyond strict bounds!")
    }
}
