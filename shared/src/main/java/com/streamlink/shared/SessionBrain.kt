package com.streamlink.shared

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Session Brain — Single source of truth for Phone↔Watch state.
 * Prevents state drift during network transitions.
 */
data class FrameSyncPoint(
    val frameId: Long,
    val timestampUs: Long,
    val isKeyframe: Boolean,
    val rtpTimestamp: Long = SystemClock.elapsedRealtime()
)

data class SessionState(
    val sessionId: String = "",
    val activeDevice: String = "PHONE",   // "PHONE" or "WATCH"
    val streamMode: String = StreamProtocol.MODE_MIRROR,
    val lastSyncPoint: FrameSyncPoint? = null,
    val bitrateKbps: Int = StreamProtocol.WEAR_BPS_FULL,
    val networkProfile: String = "WIFI",  // "WIFI", "BT", "RELAY"
    val reconnectCount: Int = 0,
    val sessionStartMs: Long = SystemClock.elapsedRealtime()
)

object SessionBrain {
    private val _state = AtomicReference(SessionState())
    private val frameCounter = AtomicLong(0L)

    val state: SessionState get() = _state.get()

    fun update(block: SessionState.() -> SessionState) {
        while (true) {
            val current = _state.get()
            val updated = current.block()
            if (_state.compareAndSet(current, updated)) break
        }
    }

    fun recordFrame(timestampUs: Long, isKeyframe: Boolean): FrameSyncPoint {
        val id = frameCounter.incrementAndGet()
        val sync = FrameSyncPoint(id, timestampUs, isKeyframe)
        if (isKeyframe) {
            update { copy(lastSyncPoint = sync) }
        }
        return sync
    }

    fun initiateHandoff(targetDevice: String) {
        update {
            copy(
                activeDevice = targetDevice,
                lastSyncPoint = lastSyncPoint  // Preserve last known sync
            )
        }
    }

    fun recordReconnect() {
        update { copy(reconnectCount = reconnectCount + 1) }
    }

    fun reset() {
        _state.set(SessionState())
        frameCounter.set(0L)
    }
}
