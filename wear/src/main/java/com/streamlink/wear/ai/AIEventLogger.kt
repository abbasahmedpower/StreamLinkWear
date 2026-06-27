package com.streamlink.wear.ai

import android.util.Log

import com.streamlink.wear.db.AITrainingDao
import com.streamlink.wear.db.AITrainingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AIEventLogger — logs AI-relevant streaming events for offline training.
 * Saves directly to Room DB for dataset generation.
 */
@Singleton
class AIEventLogger @Inject constructor(
    private val dao: AITrainingDao
) {
    private val tag = "AIEventLogger"
    private val scope = CoroutineScope(Dispatchers.IO)

    fun log(event: String, metadata: Map<String, Any> = emptyMap()) {
        Log.d(tag, "Event: $event | $metadata")
        
        val dbEvent = AITrainingEvent(
            timestamp = System.currentTimeMillis(),
            motionIntensity = (metadata["motionIntensity"] as? Number)?.toFloat() ?: 0f,
            rttMs = (metadata["rttMs"] as? Number)?.toLong() ?: 0L,
            packetLossPct = (metadata["packetLossPct"] as? Number)?.toFloat() ?: 0f,
            thermalLevel = (metadata["thermalLevel"] as? Number)?.toInt() ?: 0,
            recommendedBitrate = (metadata["recommendedBitrate"] as? Number)?.toInt() ?: 0,
            chosenBitrate = (metadata["chosenBitrate"] as? Number)?.toInt() ?: 0
        )
        
        scope.launch {
            dao.insert(dbEvent)
        }
    }
}
