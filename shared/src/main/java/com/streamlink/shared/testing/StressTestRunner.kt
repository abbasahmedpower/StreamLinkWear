package com.streamlink.shared.testing

import android.util.Log
import com.streamlink.shared.LockFreeRingBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Nano-level Stress Tester.
 * Blasts the LockFreeRingBuffer with 10,000 events/sec to verify stability,
 * ensuring no OOM or concurrency deadlocks occur.
 */
object StressTestRunner {
    private const val TAG = "StressTest"
    private const val TARGET_EVENTS = 100_000
    private const val PRODUCER_COUNT = 3
    private const val STRESS_TIMEOUT_SECONDS = 60L

    fun runRingBufferStressTest() {
        val ringBuffer = LockFreeRingBuffer(1024)
        val executor = Executors.newFixedThreadPool(PRODUCER_COUNT + 1)
        val latch = CountDownLatch(PRODUCER_COUNT + 1)
        val consumedCount = AtomicInteger(0)

        Log.i(TAG, "Starting LockFreeRingBuffer Stress Test...")

        val startTime = System.nanoTime()

        val baseEventsPerProducer = TARGET_EVENTS / PRODUCER_COUNT
        val remainderEvents = TARGET_EVENTS % PRODUCER_COUNT

        // Producers split the remainder, so the total is exactly TARGET_EVENTS.
        for (i in 0 until PRODUCER_COUNT) {
            val producerTarget = baseEventsPerProducer + if (i < remainderEvents) 1 else 0
            val producerStart = (i * baseEventsPerProducer) + minOf(i, remainderEvents)

            executor.submit {
                try {
                    var produced = 0
                    while (produced < producerTarget) {
                        if (ringBuffer.offer(producerStart + produced)) {
                            produced++
                        } else {
                            Thread.yield()
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        // 1 Consumer
        executor.submit {
            try {
                while (consumedCount.get() < TARGET_EVENTS) {
                    val item = ringBuffer.poll()
                    if (item != null) {
                        consumedCount.incrementAndGet()
                    } else {
                        Thread.yield()
                    }
                }
            } finally {
                latch.countDown()
            }
        }

        val completed = latch.await(STRESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!completed) {
            executor.shutdownNow()
            throw IllegalStateException(
                "Stress test timed out after ${STRESS_TIMEOUT_SECONDS}s; consumed=${consumedCount.get()} target=$TARGET_EVENTS"
            )
        }

        val durationMs = (System.nanoTime() - startTime) / 1_000_000
        
        Log.i(TAG, "Stress Test Passed! Processed $TARGET_EVENTS events in ${durationMs}ms without OOM or deadlocks.")
        executor.shutdown()
    }
}
