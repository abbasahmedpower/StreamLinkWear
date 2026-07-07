package com.streamlink.wear.security

import java.security.SecureRandom

object WatchPinEngine {
    private val secureRandom = SecureRandom()

    /**
     * توليد رمز مكون من 6 أرقام مؤمن تشفيرياً عشوائياً بالكامل.
     */
    fun generateSecurePin(): String {
        val pinDigits = IntArray(6) { secureRandom.nextInt(10) }
        return pinDigits.joinToString("")
    }
}
