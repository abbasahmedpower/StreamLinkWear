package com.streamlink.wear.ai

import android.util.Log

/**
 * AIEventLogger — logs AI-relevant streaming events for offline training.
 * Hilt-injectable (no Context dependency needed for basic logging).
 */
class AIEventLogger {
    private val tag = "AIEventLogger"

    fun log(event: String, metadata: Map<String, Any> = emptyMap()) {
        Log.d(tag, "Event: $event | $metadata")
        // TODO: batch write to local Room DB for training data export
    }
}
