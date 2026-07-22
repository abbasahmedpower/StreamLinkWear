package com.streamlink.app.core.crypto

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayIntegrityManager @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    private val tag = "PlayIntegrity"
    private var standardIntegrityManager: StandardIntegrityManager? = null
    private var tokenProvider: StandardIntegrityManager.StandardIntegrityTokenProvider? = null

    // Cache to avoid hitting rate limits. Tokens are valid for ~15 minutes.
    private var cachedToken: String? = null
    private var tokenTimestampMs: Long = 0

    // 15 Minutes Cache
    private val CACHE_DURATION_MS = 15 * 60 * 1000L

    init {
        try {
            standardIntegrityManager = IntegrityManagerFactory.createStandard(context)
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize Play Integrity. Play Store might be missing.", e)
        }
    }

    suspend fun prepareIntegrityToken(cloudProjectNumber: Long) {
        if (standardIntegrityManager == null) return
        try {
            val request = StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                .setCloudProjectNumber(cloudProjectNumber)
                .build()
            
            tokenProvider = standardIntegrityManager?.prepareIntegrityToken(request)?.await()
            Log.i(tag, "Play Integrity Token Provider prepared successfully.")
        } catch (e: Exception) {
            Log.e(tag, "Failed to prepare Integrity Token Provider.", e)
        }
    }

    suspend fun requestToken(requestHash: String): String? {
        if (tokenProvider == null) {
            Log.w(tag, "Token provider not ready. Cannot request token.")
            return null
        }

        val now = System.currentTimeMillis()
        if (cachedToken != null && (now - tokenTimestampMs) < CACHE_DURATION_MS) {
            Log.d(tag, "Returning cached Integrity Token")
            return cachedToken
        }

        return try {
            val request = StandardIntegrityTokenRequest.builder()
                .setRequestHash(requestHash)
                .build()

            val result = tokenProvider?.request(request)?.await()
            val token = result?.token()
            
            if (token != null) {
                cachedToken = token
                tokenTimestampMs = now
                Log.i(tag, "New Play Integrity Token generated and cached.")
            }
            token
        } catch (e: Exception) {
            Log.e(tag, "Error requesting Integrity Token", e)
            null
        }
    }
}
