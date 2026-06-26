package com.streamlink.app.usecase

import android.content.Intent
import com.streamlink.app.core.StreamingOrchestrator
import com.streamlink.shared.EventPipeline
import com.streamlink.shared.GlobalStreamState
import com.streamlink.shared.SessionBrain

/**
 * StartStreamingUseCase — separates UI intent from engine execution.
 *
 * This is the ONLY path from UI to StreamingOrchestrator.
 * Validates inputs, checks preconditions, logs analytics.
 */
class StartStreamingUseCase(
    private val orchestrator: StreamingOrchestrator,
    private val events: EventPipeline
) {
    sealed class Result {
        data class Success(val sessionId: String) : Result()
        data class Failure(val reason: String, val recoverable: Boolean = true) : Result()
    }

    suspend fun execute(
        url: String,
        resultCode: Int,
        projectionData: Intent?,
        isDrm: Boolean,
        networkQuality: Float
    ): Result {
        // Precondition: not already streaming
        if (GlobalStreamState.current == GlobalStreamState.State.STREAMING) {
            return Result.Failure("Already streaming", recoverable = false)
        }
        // Precondition: screen capture permission granted for mirror mode
        if (url.isBlank() && (projectionData == null || resultCode == 0)) {
            return Result.Failure("Screen capture permission required", recoverable = false)
        }
        // Generate session ID
        val sessionId = SessionBrain.state.sessionId.ifEmpty {
            "SL-${System.currentTimeMillis()}"
        }
        return try {
            events.sessionStart(sessionId, if (url.isBlank()) "mirror" else "direct")
            orchestrator.startStream(url, resultCode, projectionData, isDrm, networkQuality)
            Result.Success(sessionId)
        } catch (e: Exception) {
            events.error("START_FAIL", e.message ?: "unknown", recoverable = true)
            Result.Failure(e.message ?: "Failed to start stream")
        }
    }
}

class StopStreamingUseCase(
    private val orchestrator: StreamingOrchestrator,
    private val events: EventPipeline
) {
    suspend fun execute(sessionId: String, startTimeMs: Long) {
        val duration = System.currentTimeMillis() - startTimeMs
        orchestrator.stopStream()
        events.sessionEnd(sessionId, duration)
        GlobalStreamState.transition(GlobalStreamState.State.STOPPED)
    }
}
