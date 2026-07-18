package com.streamlink.shared.util

import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A central registry to track and safely release resources.
 * Prevents memory leaks from unclosed sockets, uncancelled scopes,
 * dangling threads, and held wake locks.
 */
class ResourceRegistry : Closeable {
    private val tag = "ResourceRegistry"
    private val resources = ConcurrentLinkedQueue<Any>()

    fun register(resource: Any): Any {
        resources.add(resource)
        return resource
    }

    override fun close() {
        var closedCount = 0
        while (true) {
            val resource = resources.poll() ?: break
            try {
                when (resource) {
                    is Closeable -> resource.close()
                    is CoroutineScope -> resource.cancel("ResourceRegistry closing")
                    is PowerManager.WakeLock -> {
                        if (resource.isHeld) {
                            resource.release()
                        }
                    }
                    is Thread -> {
                        if (resource.isAlive) {
                            resource.interrupt()
                        }
                    }
                    else -> Log.w(tag, "Unknown resource type in registry: ${resource::class.java.name}")
                }
                closedCount++
            } catch (e: Exception) {
                Log.w(tag, "Error closing resource ${resource::class.java.name}: ${e.message}")
            }
        }
        if (closedCount > 0) {
            Log.i(tag, "ResourceRegistry closed $closedCount resources")
        }
    }
}
