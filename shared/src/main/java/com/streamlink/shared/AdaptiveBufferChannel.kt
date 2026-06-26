package com.streamlink.shared

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult

/**
 * Bounded channel with proper frame release on overflow.
 * capacity = 64 eliminates platform-dependent Channel.BUFFERED sizing.
 */
class AdaptiveBufferChannel<T>(
    capacity: Int = 64,
    private val onDropped: ((T) -> Unit)? = null
) {
    private val _channel = Channel<T>(
        capacity = capacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { item ->
            if (item is FramePacket) item.release()
            onDropped?.invoke(item)
        }
    )

    val fillRatio: Float
        get() = _channel.isEmpty.let { empty ->
            // Approximate — Channel doesn't expose current size directly
            if (empty) 0f else 0.5f
        }

    suspend fun send(value: T): Boolean = _channel.trySend(value).isSuccess

    fun trySend(value: T): ChannelResult<Unit> = _channel.trySend(value)

    suspend fun consumeEach(action: suspend (T) -> Unit) {
        for (item in _channel) action(item)
    }

    fun close() = _channel.close()
    val isClosedForSend: Boolean get() = _channel.isClosedForSend
}
