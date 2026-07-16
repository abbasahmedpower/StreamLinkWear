package com.streamlink.shared

import com.streamlink.shared.util.LockFreeMpmcQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

class LockFreeMpmcQueueStressTest {

    @Test
    fun testConcurrentProducersAndConsumers() = runBlocking {
        // Queue size big enough to avoid full drops during the burst (must be power of 2)
        val queue = LockFreeMpmcQueue<Int>(131072)
        
        // Simulating the 3 concurrent producers:
        // 1. Audio Capture Thread
        // 2. Video Capture (HardenedStreamTextureView) Thread
        // 3. Control Message / Eviction Thread
        val producerCount = 3
        val itemsPerProducer = 20000 
        val totalExpected = producerCount * itemsPerProducer
        
        val consumedCount = AtomicInteger(0)
        
        println("Starting Concurrency Stress Test...")
        println("Simulating $producerCount producers pushing $itemsPerProducer frames each.")

        val time = measureTimeMillis {
            // Launch multiple producers
            val producers = List(producerCount) { producerId ->
                launch(Dispatchers.Default) {
                    for (i in 1..itemsPerProducer) {
                        while (!queue.offer(i)) {
                            // Busy wait if queue is full
                        }
                    }
                }
            }

            // Launch consumer (Simulating the Server Socket Sender Thread)
            val consumer = launch(Dispatchers.Default) {
                while (consumedCount.get() < totalExpected) {
                    val item = queue.poll()
                    if (item != null) {
                        consumedCount.incrementAndGet()
                    }
                }
            }

            producers.forEach { it.join() }
            consumer.join()
        }

        println("✅ Stress Test Completed in $time ms")
        println("Expected Items: $totalExpected")
        println("Consumed Items: ${consumedCount.get()}")
        println("Remaining in Queue: ${queue.size}")

        // Assert no data loss or race conditions occurred
        assertEquals(totalExpected, consumedCount.get(), "Should consume exactly the number of items produced")
        assertEquals(0, queue.size, "Queue should be empty at the end")
    }
}
