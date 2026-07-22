package com.streamlink.app.core

import android.util.Log
import com.streamlink.shared.GlobalStreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Stage 5: Background Self-Healing Worker
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
                    initiateSelfHealing()
                }
            }
        }
    }

    private fun initiateSelfHealing() {
        if (isSelfHealing) return
        isSelfHealing = true
        
        Log.e(tag, "🔥 FATAL STREAM ERROR DETECTED! Initiating Background Self-Healing Worker...")

        scope.launch {
            try {
                // 1. Force state to PRELOADING to indicate active healing
                GlobalStreamState.transition(GlobalStreamState.State.PRELOADING)
                
                // 2. Tear down network and encoder completely
                Log.w(tag, "Self-Healing Step 1: Tearing down faulty transport")
                orchestrator.stopStream()
                
                // 3. Allow OS to reclaim resources (MediaCodec buffers take a moment to release natively)
                delay(1500)
                
                // 4. Retrieve last known watch IP (if available)
                val lastHost = orchestrator.lastKnownLocalHost()
                
                // 5. Attempt Restart
                Log.w(tag, "Self-Healing Step 2: Booting fresh instances")
                if (lastHost != null) {
                    // Start standard stream flow
                    // Assuming no DRM and auto quality for auto-recovery
                    orchestrator.startStream(
                        url = lastHost, // This normally expects the watch IP/URL
                        resultCode = -1, // Dummy result code for recovery
                        projectionData = null, // Will reuse existing MediaProjection internally if still valid
                        isDrm = false,
                        networkQuality = 1.0f
                    )
                    Log.i(tag, "✅ Background Self-Healing Complete. Stream Resumed.")
                } else {
                    Log.e(tag, "❌ Self-Healing Failed: No known host to reconnect to. Dropping to IDLE.")
                    GlobalStreamState.transition(GlobalStreamState.State.IDLE)
                }

            } catch (e: Exception) {
                Log.e(tag, "Self-Healing crashed: ${e.message}")
                GlobalStreamState.transition(GlobalStreamState.State.IDLE)
            } finally {
                isSelfHealing = false
            }
        }
    }
}
