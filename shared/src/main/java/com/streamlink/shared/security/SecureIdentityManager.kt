package com.streamlink.shared.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.*
import java.io.IOException
import java.util.UUID

class SecureIdentityManager(private val context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs = EncryptedSharedPreferences.create(
        context,
        "secure_identity_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val httpClient = OkHttpClient()

    /**
     * Gets the stored verified identity token, or registers a new one with the backend.
     * The token is of the format: `UUID.Signature`
     */
    fun getOrRegisterIdentity(serverUrl: String, globalToken: String, onComplete: (String?) -> Unit) {
        val secureToken = securePrefs.getString("backend_identity_token", null)
        
        if (secureToken != null) {
            onComplete(secureToken)
            return
        }

        // Generate a new UUID on first run
        val newUserId = "streamlink_" + UUID.randomUUID().toString().replace("-", "").take(16)

        // Request a signature from the backend for this UUID
        val formBody = FormBody.Builder().add("userId", newUserId).build()
        val request = Request.Builder()
            .url("$serverUrl/api/v1/register")
            .post(formBody)
            .addHeader("X-Horus-Global-Token", globalToken)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onComplete(null)
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    // Extract token from JSON: {"token":"<token>"}
                    val receivedToken = responseBody?.substringAfter("\"token\":\"")?.substringBefore("\"")
                    if (receivedToken != null && receivedToken.isNotBlank()) {
                        securePrefs.edit().putString("backend_identity_token", receivedToken).apply()
                        onComplete(receivedToken)
                        return
                    }
                }
                onComplete(null)
            }
        })
    }

    /**
     * Helper to retrieve the current token synchronously, if it exists.
     */
    fun getStoredToken(): String? {
        return securePrefs.getString("backend_identity_token", null)
    }

    /**
     * Helper to clear the token if the server rotates keys or invalidates it.
     */
    fun clearToken() {
        securePrefs.edit().remove("backend_identity_token").apply()
    }
}
