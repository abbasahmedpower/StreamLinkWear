package com.streamlink.shared

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Dedicated dispatchers to prevent thread starvation on Wear OS's limited cores.
 */
object AppDispatchers {
    // Dedicated single thread for network I/O to maintain strict ordering
    val Network: CoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    
    // Dedicated thread pool for heavy decoding/encoding tasks
    val Codec: CoroutineDispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
    
    // Default CPU-bound operations
    val Default: CoroutineDispatcher = Dispatchers.Default
    
    // UI operations
    val Main: CoroutineDispatcher = Dispatchers.Main
}
