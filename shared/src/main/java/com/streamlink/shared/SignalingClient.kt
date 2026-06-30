package com.streamlink.shared

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.pow

class SignalingClient(
    private val backendUrl: String,
    private val userId: String,
    private val deviceType: String // "PHONE" or "WATCH"
) {
    private val tag = "SignalingClient"
    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .build()
        
    private var webSocket: WebSocket? = null
    
    private val _messages = MutableSharedFlow<JSONObject>(extraBufferCapacity = 64)
    val messages: SharedFlow<JSONObject> = _messages.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isClosedIntentionally = AtomicBoolean(false)
    private var reconnectAttempt = 0
    private var isConnecting = AtomicBoolean(false)

    fun connect() {
        if (isClosedIntentionally.get() || isConnecting.getAndSet(true)) return
        val url = "$backendUrl/signal/$userId/$deviceType"
        val request = Request.Builder()
            .url(url)
            .addHeader("X-Horus-Authorization", BuildConfig.HORUS_SECRET_TOKEN)
            .build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(tag, "WebSocket Connected to $url")
                isConnecting.set(false)
                reconnectAttempt = 0 // Reset on successful connection
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    _messages.tryEmit(json)
                } catch (e: Exception) {
                    Log.e(tag, "Failed to parse signaling message: $text", e)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(tag, "WebSocket Closed: $code $reason")
                isConnecting.set(false)
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(tag, "WebSocket Failure", t)
                isConnecting.set(false)
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (isClosedIntentionally.get()) return

        scope.launch {
            // Exponential backoff: 1s, 2s, 4s, 8s, up to 15s max
            val baseDelayMs = 1000L
            val maxDelayMs = 15000L
            val multiplier = 2.0.pow(reconnectAttempt.toDouble()).toLong()
            val delayMs = min(maxDelayMs, baseDelayMs * multiplier)

            Log.i(tag, "Scheduling reconnect in ${delayMs}ms (Attempt ${reconnectAttempt + 1})")
            delay(delayMs)
            
            reconnectAttempt++
            connect()
        }
    }

    fun sendMessage(type: String, to: String, payload: String) {
        val env = JSONObject().apply {
            put("type", type)
            put("from", deviceType)
            put("to", to)
            put("payload", payload)
            put("ts", System.currentTimeMillis())
        }
        webSocket?.send(env.toString())
    }

    fun close() {
        isClosedIntentionally.set(true)
        webSocket?.close(1000, "Normal closure")
        webSocket = null
    }
}
