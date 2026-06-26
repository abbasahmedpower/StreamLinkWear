package com.streamlink.shared

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class EventPipeline(private val scope: CoroutineScope) {

    sealed class StreamEvent {
        data class SessionStart(val sessionId: String, val mode: String) : StreamEvent()
        data class SessionEnd(val sessionId: String, val durationMs: Long) : StreamEvent()
        data class BitrateChange(val fromKbps: Int, val toKbps: Int, val reason: String) : StreamEvent()
        data class RecoveryAttempt(val attempt: Int, val strategy: String, val success: Boolean) : StreamEvent()
        data class LatencySnapshot(val e2eMs: Long, val encodeMs: Long, val networkMs: Long) : StreamEvent()
        data class FrameDropped(val reason: String, val frameId: Long) : StreamEvent()
        data class StateTransition(val from: String, val to: String) : StreamEvent()
        data class ThermalEvent(val level: Int, val action: String) : StreamEvent()
        data class SecurityEvent(val type: String, val detail: String) : StreamEvent()
        data class NetworkSwitch(val from: String, val to: String) : StreamEvent()
        data class Error(val code: String, val message: String, val recoverable: Boolean) : StreamEvent()
    }

    private val buffer = Channel<StreamEvent>(
        capacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val _live = MutableSharedFlow<StreamEvent>(extraBufferCapacity = 64)
    val live: SharedFlow<StreamEvent> = _live

    private val sinks = mutableListOf<EventSink>()
    private var processorJob: Job? = null

    interface EventSink {
        suspend fun onEvent(event: StreamEvent)
    }

    fun addSink(sink: EventSink) = sinks.add(sink)

    fun start() {
        processorJob?.cancel()
        processorJob = scope.launch(Dispatchers.Default) {
            for (event in buffer) {
                _live.emit(event)
                sinks.forEach { sink ->
                    try { sink.onEvent(event) }
                    catch (e: Exception) { Log.w("EventPipeline", "Sink error: ${e.message}") }
                }
            }
        }
    }

    fun stop() {
        processorJob?.cancel()
        buffer.close()
    }

    private fun emit(event: StreamEvent) {
        buffer.trySend(event)
    }

    fun sessionStart(sessionId: String, mode: String) =
        emit(StreamEvent.SessionStart(sessionId, mode))

    fun sessionEnd(sessionId: String, durationMs: Long) =
        emit(StreamEvent.SessionEnd(sessionId, durationMs))

    fun bitrateChange(from: Int, to: Int, reason: String) =
        emit(StreamEvent.BitrateChange(from, to, reason))

    fun recovery(attempt: Int, strategy: String, success: Boolean) =
        emit(StreamEvent.RecoveryAttempt(attempt, strategy, success))

    fun latencySnapshot(e2e: Long, encode: Long, network: Long) =
        emit(StreamEvent.LatencySnapshot(e2e, encode, network))

    fun frameDrop(reason: String, frameId: Long = 0L) =
        emit(StreamEvent.FrameDropped(reason, frameId))

    fun stateChange(from: String, to: String) =
        emit(StreamEvent.StateTransition(from, to))

    fun thermal(level: Int, action: String) =
        emit(StreamEvent.ThermalEvent(level, action))

    fun error(code: String, message: String, recoverable: Boolean = true) =
        emit(StreamEvent.Error(code, message, recoverable))

    fun networkSwitch(from: String, to: String) =
        emit(StreamEvent.NetworkSwitch(from, to))
}
