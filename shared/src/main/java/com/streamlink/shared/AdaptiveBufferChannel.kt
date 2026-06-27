package com.streamlink.shared

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult

import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded channel with proper frame release on overflow.
 * capacity = 64 eliminates platform-dependent Channel.BUFFERED sizing.
 */
class AdaptiveBufferChannel<T>(
    capacity: Int = 64,
    private val onDropped: ((T) -> Unit)? = null
) {
    private val count = AtomicInteger(0)
    private val cap = capacity.toFloat()

    private val _channel = Channel<T>(
        capacity = capacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { item ->
            count.decrementAndGet()
            if (item is FramePacket) item.release()
            onDropped?.invoke(item)
        }
    )

    val fillRatio: Float
        get() = (count.get() / cap).coerceIn(0f, 1f)

    suspend fun send(value: T): Boolean {
        count.incrementAndGet()
        val result = _channel.trySend(value)
        if (!result.isSuccess) count.decrementAndGet()
        return result.isSuccess
    }

    fun trySend(value: T): ChannelResult<Unit> {
        count.incrementAndGet()
        val result = _channel.trySend(value)
        if (!result.isSuccess) count.decrementAndGet()
        return result
    }

    suspend fun consumeEach(action: suspend (T) -> Unit) {
        for (item in _channel) {
            count.decrementAndGet()
            action(item)
        }
    }

    fun close() = _channel.close()
    val isClosedForSend: Boolean get() = _channel.isClosedForSend
}
