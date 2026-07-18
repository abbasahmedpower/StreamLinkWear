package com.streamlink.app.core

import android.util.Log
import com.streamlink.shared.TouchEvent
import com.streamlink.shared.TouchPhase
import com.streamlink.shared.ai.TouchPerceptionHub
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TouchPipeline — owns the SPSC lock-free ring buffer for real-time touch delivery.
 * Completely isolated from the main orchestration thread, enabling
 * sub-millisecond latency from watch → phone screen.
 */
@Singleton
class TouchPipeline @Inject constructor(
    private val accessibilityService: dagger.Lazy<com.streamlink.app.control.RemoteControlAccessibilityService?>
) {
    private val tag = "TouchPipeline"

    private val ringCapacity = 1024
    private val mask = ringCapacity - 1

    private val phaseBuffer = ByteArray(ringCapacity)
    private val pointerBuffer = IntArray(ringCapacity)
    private val nxBuffer = FloatArray(ringCapacity)
    private val nyBuffer = FloatArray(ringCapacity)
    private val timeBuffer = LongArray(ringCapacity)

    private val writeIndex = AtomicInteger(0)
    private val readIndex = AtomicInteger(0)

    @Volatile private var running = true
    private val droppedTouchEvents = AtomicLong(0L)

    fun publishTouch(phase: Byte, pointerId: Int, nx: Float, ny: Float, timestampUs: Long) {
        val w = writeIndex.get()
        val next = (w + 1) and mask

        if (next == readIndex.get()) {
            readIndex.incrementAndGet() // drop oldest on overflow
            val dropped = droppedTouchEvents.incrementAndGet()
            if (dropped % 100 == 0L) {
                Log.w(tag, "Touch ring buffer overflow — total dropped: $dropped events")
            }
        }

        val idx = w and mask
        phaseBuffer[idx] = phase
        pointerBuffer[idx] = pointerId
        nxBuffer[idx] = nx
        nyBuffer[idx] = ny
        timeBuffer[idx] = timestampUs

        writeIndex.lazySet(next)
    }

    fun startRealtimeConsumer() {
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            while (running) {
                val r = readIndex.get()
                val w = writeIndex.get()

                if (r == w) {
                    LockSupport.parkNanos(1_000_000) // 1ms — imperceptible, saves CPU spin
                    continue
                }

                val idx = r and mask
                val phaseByte = phaseBuffer[idx]
                val pointerId = pointerBuffer[idx]
                val nx = nxBuffer[idx]
                val ny = nyBuffer[idx]
                val ts = timeBuffer[idx]

                processRealtimeTouch(phaseByte, pointerId, nx, ny, ts)

                readIndex.lazySet((r + 1) and mask)
            }
        }, "SL-TouchPipeline").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
    }

    private fun processRealtimeTouch(phaseByte: Byte, pointerId: Int, nx: Float, ny: Float, ts: Long) {
        val phase = when (phaseByte) {
            1.toByte() -> TouchPhase.DOWN
            2.toByte() -> TouchPhase.MOVE
            3.toByte() -> TouchPhase.UP
            else -> TouchPhase.CANCEL
        }
        val event = TouchEvent(phase, pointerId, nx, ny, 0, ts)
        TouchPerceptionHub.onRealTouch(event)
        com.streamlink.app.control.RemoteControlAccessibilityService.instance?.handle(event)
    }

    fun stop() {
        running = false
    }
}
