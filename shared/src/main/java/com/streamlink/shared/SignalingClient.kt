package com.streamlink.shared

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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

    fun connect() {
        val url = "$backendUrl/signal/$userId/$deviceType"
        val request = Request.Builder().url(url).build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(tag, "WebSocket Connected to $url")
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
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(tag, "WebSocket Failure", t)
            }
        })
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
        webSocket?.close(1000, "Normal closure")
        webSocket = null
    }
}
