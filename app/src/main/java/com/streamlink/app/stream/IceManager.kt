package com.streamlink.app.stream

import android.util.Log
import com.streamlink.shared.StreamProtocol
import kotlinx.coroutines.*
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * IceManager — production ICE/NAT traversal manager.
 *
 * Handles:
 * - Candidate trickling
 * - ICE restart with exponential backoff
 * - TURN fallback when STUN fails
 * - LAN candidate priority boost (for same-network phone/watch)
 */
class IceManager(
    private val scope: CoroutineScope,
    private val onRestartNeeded: () -> Unit = {}
) {
    private val tag = "IceManager"

    enum class NatStrategy { STUN_ONLY, STUN_PLUS_TURN, RELAY_ONLY }

    private val _strategy = AtomicReference(NatStrategy.STUN_ONLY)
    val strategy: NatStrategy get() = _strategy.get()

    private val pendingCandidates = CopyOnWriteArrayList<IceCandidate>()
    private val restartAttempts = AtomicInteger(0)
    private val maxRestartAttempts = 5
    private var restartJob: Job? = null

    fun buildIceServers(): List<PeerConnection.IceServer> {
        val servers = mutableListOf<PeerConnection.IceServer>()

        // Public STUN servers
        servers += PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        servers += PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()

        // TURN — only added if strategy requires it
        if (_strategy.get() != NatStrategy.STUN_ONLY) {
            StreamProtocol.TURN_SERVERS.forEach { (url, user, cred) ->
                servers += PeerConnection.IceServer.builder(url)
                    .setUsername(user)
                    .setPassword(cred)
                    .createIceServer()
            }
        }
        return servers
    }

    fun onIceConnectionFailed() {
        val attempts = restartAttempts.incrementAndGet()
        Log.w(tag, "ICE failed — attempt #$attempts/$maxRestartAttempts, strategy=${_strategy.get()}")

        if (attempts >= maxRestartAttempts) {
            Log.e(tag, "ICE exhausted all strategies")
            restartAttempts.set(0)
            return
        }

        // Escalate strategy
        when {
            attempts == 2 -> {
                _strategy.set(NatStrategy.STUN_PLUS_TURN)
                Log.i(tag, "Escalated to STUN+TURN")
            }
            attempts >= 4 -> {
                _strategy.set(NatStrategy.RELAY_ONLY)
                Log.i(tag, "Escalated to RELAY only")
            }
        }
        scheduleRestart(attempts)
    }

    private fun scheduleRestart(attempt: Int) {
        restartJob?.cancel()
        val delayMs = minOf(1000L * (1 shl (attempt - 1)), 16_000L)
        Log.d(tag, "ICE restart scheduled in ${delayMs}ms")
        restartJob = scope.launch {
            delay(delayMs)
            onRestartNeeded()
        }
    }

    /** Filter candidates: reject reflexive candidates on localhost (indicates no network) */
    fun isValidCandidate(sdp: String): Boolean {
        if (sdp.contains("127.0.0.1")) return false     // loopback
        if (sdp.contains("0.0.0.0")) return false        // unspecified
        return true
    }

    /** Prioritize: LAN (192.168.x.x / 10.x.x.x) > STUN reflexive > TURN */
    fun prioritizeCandidate(sdp: String): Int = when {
        sdp.contains("192.168.") || sdp.contains("10.") -> 100  // LAN first
        sdp.contains("typ srflx") -> 50                          // STUN reflexive
        sdp.contains("typ relay") -> 10                          // TURN last
        else -> 0
    }

    fun queueCandidate(candidate: IceCandidate) {
        pendingCandidates.add(candidate)
    }

    fun drainPendingCandidates(): List<IceCandidate> {
        val drained = pendingCandidates.toList()
        pendingCandidates.clear()
        return drained
    }

    fun resetStrategy() {
        _strategy.set(NatStrategy.STUN_ONLY)
        restartAttempts.set(0)
        restartJob?.cancel()
        pendingCandidates.clear()
    }
}
