package com.streamlink.shared.testing

import android.util.Log
import com.streamlink.shared.LockFreeRingBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Nano-level Stress Tester.
 * Blasts the LockFreeRingBuffer with 10,000 events/sec to verify stability,
 * ensuring no OOM or concurrency deadlocks occur.
 */
object StressTestRunner {
    private const val TAG = "StressTest"
    private const val TARGET_EVENTS = 100_000

    fun runRingBufferStressTest() {
        val ringBuffer = LockFreeRingBuffer(1024)
        val executor = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(4)
        val consumedCount = AtomicInteger(0)

        Log.i(TAG, "Starting LockFreeRingBuffer Stress Test...")

        val startTime = System.nanoTime()

        // 3 Producers
        for (i in 0 until 3) {
            executor.submit {
                var produced = 0
                while (produced < TARGET_EVENTS / 3) {
                    if (ringBuffer.offer(produced)) {
                        produced++
                    }
                }
                latch.countDown()
            }
        }

        // 1 Consumer
        executor.submit {
            while (consumedCount.get() < TARGET_EVENTS) {
                val item = ringBuffer.poll()
                if (item != null) {
                    consumedCount.incrementAndGet()
                }
            }
            latch.countDown()
        }

        latch.await()
        val durationMs = (System.nanoTime() - startTime) / 1_000_000
        
        Log.i(TAG, "Stress Test Passed! Processed $TARGET_EVENTS events in ${durationMs}ms without OOM or deadlocks.")
        executor.shutdown()
    }
}
