package com.streamlink.backend.security

import com.streamlink.backend.ServerIdentityVerifier
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServerIdentityVerifierTest {

    @BeforeAll
    fun setup() {
        // Inject a deterministic test key so the test is hermetic and fast
        System.setProperty("SERVER_PRIVATE_KEY_OVERRIDE_FOR_TEST", "test_secret_key_do_not_use_in_prod")
    }

    // ── Happy Path ─────────────────────────────────────────────────────────────

    @Test
    fun `should generate token with dot-separated format`() {
        val token = ServerIdentityVerifier.generateClientToken("streamlink_test1234")
        assertTrue(token.contains("."), "Token must be UUID.Signature format")
        val parts = token.split(".")
        assertEquals(2, parts.size, "Token must have exactly 2 parts separated by a dot")
    }

    @Test
    fun `should generate and successfully verify valid client token`() {
        val userId = "streamlink_phone_a1b2c3d4e5f6"
        val token = ServerIdentityVerifier.generateClientToken(userId)

        val verifiedId = ServerIdentityVerifier.verifyClientToken(token)
        assertEquals(userId, verifiedId, "Verified userId must exactly match the original")
    }

    @Test
    fun `same userId always produces same token (deterministic HMAC)`() {
        val userId = "streamlink_phone_deterministic"
        val token1 = ServerIdentityVerifier.generateClientToken(userId)
        val token2 = ServerIdentityVerifier.generateClientToken(userId)
        assertEquals(token1, token2, "HMAC must be deterministic for the same userId + key")
    }

    @Test
    fun `different userIds produce different tokens`() {
        val token1 = ServerIdentityVerifier.generateClientToken("streamlink_phone_user1")
        val token2 = ServerIdentityVerifier.generateClientToken("streamlink_phone_user2")
        assertNotEquals(token1, token2, "Different userIds must produce different tokens")
    }

    // ── Attack Scenarios ───────────────────────────────────────────────────────

    @Test
    fun `should reject tampered signature (flip last char)`() {
        val userId = "streamlink_phone_a1b2c3d4e5f6"
        val token = ServerIdentityVerifier.generateClientToken(userId)
        val parts = token.split(".")
        // Replace last char of signature with a different one
        val badChar = if (parts[1].last() == 'z') 'a' else 'z'
        val tampered = "${parts[0]}.${parts[1].dropLast(1)}$badChar"

        val result = ServerIdentityVerifier.verifyClientToken(tampered)
        assertNull(result, "Verifier must reject a tampered signature")
    }

    @Test
    fun `should reject completely fabricated signature`() {
        val forged = "streamlink_phone_attacker.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        assertNull(ServerIdentityVerifier.verifyClientToken(forged),
            "Verifier must reject a fully fabricated token")
    }

    @Test
    fun `should reject spoofed userId with valid signature from other user`() {
        // Attacker takes Alice's valid token and replaces only the userId part with Bob's
        val aliceId = "streamlink_phone_alice"
        val aliceToken = ServerIdentityVerifier.generateClientToken(aliceId)
        val parts = aliceToken.split(".")

        val spoofed = "streamlink_phone_bob.${parts[1]}"   // Bob's ID + Alice's signature
        val result = ServerIdentityVerifier.verifyClientToken(spoofed)
        assertNull(result, "Verifier must reject a mismatched userId/signature pair")
    }

    @Test
    fun `should reject empty token`() {
        assertNull(ServerIdentityVerifier.verifyClientToken(""),
            "Empty token must be rejected")
    }

    @Test
    fun `should reject token without dot separator`() {
        assertNull(ServerIdentityVerifier.verifyClientToken("nodottoken"),
            "Token without a dot separator must be rejected")
    }

    @Test
    fun `should reject token with empty userId part`() {
        val fakeToken = ".AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        assertNull(ServerIdentityVerifier.verifyClientToken(fakeToken),
            "Token with empty userId part must be rejected")
    }

    @Test
    fun `should reject token with empty signature part`() {
        assertNull(ServerIdentityVerifier.verifyClientToken("streamlink_phone_abc."),
            "Token with empty signature must be rejected")
    }
}
