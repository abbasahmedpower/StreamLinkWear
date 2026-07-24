package com.streamlink.app.core

import android.util.Log
import com.streamlink.shared.GlobalStreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.math.min

/**
 * Stage 5: Background Self-Healing Worker with Exponential Backoff
 * Operates entirely independent of the UI. If the Stream State Machine hits FAILED,
 * it will perform a complete teardown and restart of the internal components.
 */
class StreamRecoveryManager(
    private val scope: CoroutineScope,
    private val orchestrator: StreamingOrchestrator
) {
    private val tag = "StreamRecoveryManager"
    private var isSelfHealing = false

    fun startListening() {
        scope.launch {
            GlobalStreamState.snapshot.collect { snapshot ->
                if (snapshot.state == GlobalStreamState.State.FAILED && !isSelfHealing) {
                    if (snapshot.isFatal) {
                        Log.e(tag, "Fatal error detected. Auto-recovery aborted.")
                        return@collect
                    }
                    initiateSelfHealing()
                }
            }
        }
    }

    private fun initiateSelfHealing() {
        if (isSelfHealing) return
        isSelfHealing = true
        
        Log.e(tag, "🔥 STREAM ERROR DETECTED! Initiating Exponential Backoff Recovery...")

        scope.launch {
            try {
                val lastHost = orchestrator.lastKnownLocalHost()
                if (lastHost == null) {
                    Log.e(tag, "❌ Self-Healing Failed: No known host to reconnect to. Dropping to IDLE.")
                    GlobalStreamState.transition(GlobalStreamState.State.IDLE)
                    return@launch
                }

                var attempt = 0
                val maxAttempts = 5
                
                while (attempt < maxAttempts && isActive) {
                    attempt++
                    
                    GlobalStreamState.transition(GlobalStreamState.State.RECONNECTING)
                    
                    Log.w(tag, "Self-Healing Attempt $attempt/$maxAttempts: Tearing down faulty transport")
                    orchestrator.stopStream()
                    
                    // Exponential backoff: min(1000 * 2^(attempt-1), 10000) + jitter(0..500)
                    val baseDelay = min(1000L * (1 shl (attempt - 1)), 10000L)
                    val jitter = Random.nextLong(0, 500)
                    val totalDelay = baseDelay + jitter
                    
                    Log.w(tag, "Waiting ${totalDelay}ms before retry...")
                    delay(totalDelay)
                    
                    Log.w(tag, "Booting fresh instances")
                    orchestrator.startStream(
                        url = lastHost, // This normally expects the watch IP/URL
                        resultCode = -1, // Dummy result code for recovery
                        projectionData = null, // Will reuse existing MediaProjection internally if still valid
                        isDrm = false,
                        networkQuality = 1.0f
                    )
                    
                    // Give it some time to connect
                    delay(3000)
                    
                    val currentState = GlobalStreamState.current
                    if (currentState == GlobalStreamState.State.STREAMING) {
                        Log.i(tag, "✅ Background Self-Healing Complete. Stream Resumed.")
                        return@launch
                    }
                    
                    if (GlobalStreamState.snapshot.value.isFatal) {
                         Log.e(tag, "Fatal error encountered during recovery. Aborting.")
                         break
                    }
                }
                
                Log.e(tag, "❌ Self-Healing Exhausted after $maxAttempts attempts. Dropping to FAILED.")
                GlobalStreamState.transition(GlobalStreamState.State.FAILED) {
                    copy(isFatal = true, errorMessage = "Recovery failed after $maxAttempts attempts")
                }

            } catch (e: Exception) {
                Log.e(tag, "Self-Healing crashed: ${e.message}")
                GlobalStreamState.transition(GlobalStreamState.State.FAILED)
            } finally {
                isSelfHealing = false
            }
        }
    }
}
