package com.streamlink.shared.concurrency

import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

object StreamDispatchers {
    
    // Dedicated thread pool for network operations (Sockets/WebRTC) with high priority
    val Network: CoroutineDispatcher = Executors.newFixedThreadPool(2, PriorityThreadFactory("stream-net", Thread.MAX_PRIORITY))
        .asCoroutineDispatcher()

    // Single thread for sensors to avoid distracting big cores
    val Sensor: CoroutineDispatcher = Executors.newSingleThreadExecutor(PriorityThreadFactory("stream-sensor", Thread.NORM_PRIORITY))
        .asCoroutineDispatcher()

    // Dedicated thread pool for Video Decoding / TFLite processing
    val Decoder: CoroutineDispatcher = Executors.newFixedThreadPool(2, PriorityThreadFactory("stream-decode", Thread.MAX_PRIORITY))
        .asCoroutineDispatcher()
}

class PriorityThreadFactory(private val prefix: String, private val priority: Int) : ThreadFactory {
    private val counter = AtomicInteger(1)
    override fun newThread(r: Runnable): Thread {
        return Thread(r, "$prefix-${counter.getAndIncrement()}").apply {
            this.priority = java.lang.Math.max(Thread.MIN_PRIORITY, java.lang.Math.min(Thread.MAX_PRIORITY, priority))
        }
    }
}
