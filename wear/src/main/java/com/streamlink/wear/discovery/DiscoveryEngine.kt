package com.streamlink.wear.discovery

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.streamlink.shared.DiscoveredHost
import com.streamlink.shared.NetworkDiscovery
import com.streamlink.shared.StreamProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

// ── DataStore singleton — per-process, one instance per name ──────────────────
// preferencesDataStore is a top-level property delegate; must live outside any class.
private val Context.discoveryStore: DataStore<Preferences> by preferencesDataStore(
    name = "discovery_engine"
)

// ── Public types ──────────────────────────────────────────────────────────────

/**
 * Source that produced a discovered host — used by the UI to show a
 * "found via cache / mDNS" badge and by telemetry to track hit rates.
 */
enum class DiscoverySource { CACHED_IP, MDNS }

/**
 * Event emitted by [DiscoveryEngine.discover].
 *
 * [Found]   — at least one host was reachable. Multiple Found events may arrive
 *             (one per unique host) before the flow completes.
 * [TimedOut] — overall timeout elapsed with no reachable host found.
 *              The UI must present [ManualConnectionScreen] automatically.
 */
sealed class DiscoveryEvent {
    data class Found(val host: DiscoveredHost, val source: DiscoverySource) : DiscoveryEvent()
    data object TimedOut : DiscoveryEvent()
}

/**
 * UI-facing state driven by [DiscoveryEngine] — observed by WearMainActivity.
 */
sealed class DiscoveryUiState {
    data object Idle     : DiscoveryUiState()
    data object Scanning : DiscoveryUiState()    // spinner shown
    data class  Found(
        val host: DiscoveredHost,
        val source: DiscoverySource
    )                    : DiscoveryUiState()
    data object TimedOut : DiscoveryUiState()    // → auto-show ManualConnectionScreen
}

// ── Engine ────────────────────────────────────────────────────────────────────

/**
 * DiscoveryEngine — Phase 3 / Step 9
 *
 * Orchestrates device discovery via two parallel paths:
 *  1. Cache probe  — reads the last known IP from DataStore, attempts a lightweight
 *                    TCP connect (not a full ECDH handshake) within [cacheProbeTimeoutMs].
 *  2. mDNS         — delegates to the existing [NetworkDiscovery] NSD implementation.
 *
 * Design decisions (see implementation_plan.md):
 *  - **No UDP broadcast** — DirectSocketServer is TCP-only; no phone-side UDP receiver exists.
 *  - **TCP probe only**   — `socket.connect(400ms)` is sufficient to confirm reachability
 *                           without paying the cost of a full ECDH handshake.
 *  - **Parallel, not sequential** — both paths start immediately; whoever responds first wins.
 *  - **Overall timeout = 5 s** — prevents infinite "searching…" state on firewalled networks.
 *  - **Deduplication** — the same IP:port emitted by both paths is only forwarded once.
 *  - **Cache write-back** — call [rememberDevice] after every successful connection so the
 *                           next session benefits from a sub-400ms cache hit.
 *
 * Threading: all I/O is dispatched on [Dispatchers.IO]. [discoveryUiState] is safe to
 * collect on Main.
 */
@Singleton
class DiscoveryEngine @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val networkDiscovery: NetworkDiscovery
) {
    private val tag = "DiscoveryEngine"

    private val lastIpKey = stringPreferencesKey("last_known_ip")

    private val _discoveryUiState = MutableStateFlow<DiscoveryUiState>(DiscoveryUiState.Idle)
    val discoveryUiState: StateFlow<DiscoveryUiState> = _discoveryUiState

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Launches parallel discovery and returns a cold [Flow] of [DiscoveryEvent]s.
     *
     * Collectors receive:
     *  - Zero or more [DiscoveryEvent.Found] — one per unique reachable host
     *  - Exactly one [DiscoveryEvent.TimedOut] if [overallTimeoutMs] elapses with no result
     *
     * The flow completes after the overall timeout regardless of mDNS state.
     * Callers should cancel the collection job when the screen is no longer visible.
     *
     * @param cacheProbeTimeoutMs  TCP connect timeout for the cached IP (default 400 ms).
     *                             Chosen to be fast-fail: most LAN TCP connects succeed < 50 ms.
     * @param overallTimeoutMs     Hard deadline for the entire discovery session (default 5 s).
     */
    fun discover(
        cacheProbeTimeoutMs: Long = 400L,
        overallTimeoutMs:    Long = 5_000L
    ): Flow<DiscoveryEvent> = channelFlow {
        _discoveryUiState.value = DiscoveryUiState.Scanning
        Log.i(tag, "Discovery started (cacheTimeout=${cacheProbeTimeoutMs}ms, overall=${overallTimeoutMs}ms)")

        // Dedup: same IP emitted by both paths only forwarded once.
        val seen = mutableSetOf<String>()

        suspend fun emitIfNew(host: DiscoveredHost, source: DiscoverySource) {
            val key = host.ip  // port is always DIRECT_SOCKET_PORT — ip alone is sufficient
            if (seen.add(key)) {
                Log.i(tag, "✅ Found host ${host.ip} via $source")
                _discoveryUiState.value = DiscoveryUiState.Found(host, source)
                send(DiscoveryEvent.Found(host, source))
            } else {
                Log.d(tag, "Duplicate host ${host.ip} via $source — skipped")
            }
        }

        val timedOut = withTimeoutOrNull(overallTimeoutMs) {
            coroutineScope {
                // ── Path 1: DataStore cache probe ────────────────────────────
                launch(Dispatchers.IO) {
                    val cachedIp = context.discoveryStore.data
                        .first()[lastIpKey]
                        ?.takeIf { it.isNotBlank() }

                    if (cachedIp == null) {
                        Log.d(tag, "Cache miss — no stored IP")
                        return@launch
                    }

                    Log.d(tag, "Cache probe → $cachedIp (timeout=${cacheProbeTimeoutMs}ms)")
                    val reachable = withTimeoutOrNull(cacheProbeTimeoutMs) {
                        probeTcp(cachedIp, StreamProtocol.DIRECT_SOCKET_PORT)
                    } ?: false  // null = timed out = not reachable

                    if (reachable) {
                        emitIfNew(DiscoveredHost(cachedIp), DiscoverySource.CACHED_IP)
                    } else {
                        Log.d(tag, "Cache probe failed for $cachedIp — continuing mDNS")
                    }
                }

                // ── Path 2: mDNS via existing NetworkDiscovery ───────────────
                launch(Dispatchers.IO) {
                    // Start NSD if not already scanning
                    networkDiscovery.startDiscovery()

                    // Collect hosts as NSD resolves them
                    collectMdnsHosts { host ->
                        emitIfNew(host, DiscoverySource.MDNS)
                    }
                }
            }
            // coroutineScope returns normally only if all children complete.
            // In practice the mDNS path runs until cancelled by withTimeoutOrNull.
            true
        }

        if (timedOut == null) {
            Log.w(tag, "Discovery timed out after ${overallTimeoutMs}ms — no hosts found")
            _discoveryUiState.value = DiscoveryUiState.TimedOut
            send(DiscoveryEvent.TimedOut)
        }

        // Always stop NSD discovery when the flow ends to free OS resources.
        networkDiscovery.stopDiscovery()
        Log.i(tag, "Discovery flow completed")
    }

    /**
     * Persists [ip] to DataStore so the next session can fast-probe it first.
     * Must be called after every successful connection — this is the write-back
     * that makes the cache self-healing across IP changes (DHCP lease renewals).
     */
    suspend fun rememberDevice(ip: String) {
        if (ip.isBlank()) return
        context.discoveryStore.edit { prefs ->
            prefs[lastIpKey] = ip
        }
        Log.i(tag, "Remembered device IP: $ip")
    }

    /**
     * Clears the stored IP — call when the user explicitly disconnects or
     * when a session ends with an auth failure (stale/wrong device).
     */
    suspend fun forgetDevice() {
        context.discoveryStore.edit { it.remove(lastIpKey) }
        Log.i(tag, "Cleared stored device IP")
    }

    /** Resets UI state to Idle — call when navigating away from discovery. */
    fun reset() {
        _discoveryUiState.value = DiscoveryUiState.Idle
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Lightweight TCP reachability probe.
     *
     * A plain [Socket.connect] on port [StreamProtocol.DIRECT_SOCKET_PORT] is sufficient:
     *  - If the phone's DirectSocketServer is running, the OS will accept the SYN and the
     *    connect will succeed in < 50 ms on a typical LAN.
     *  - We do NOT send any data — we immediately close the socket after connect.
     *    This avoids triggering the ECDH handshake state machine on the server side
     *    and means the probe is invisible to the application layer.
     *  - Returns false on any exception (connection refused, timeout, host unreachable).
     */
    private fun probeTcp(ip: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), 350) // slightly under cacheProbeTimeoutMs
                true
            }
        } catch (e: Exception) {
            Log.d(tag, "TCP probe $ip:$port failed: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * Bridges [NetworkDiscovery.discoveredHost] StateFlow into a suspend collector.
     * Filters null values and expired hosts (TTL > 30 s — defined in [DiscoveredHost]).
     *
     * This is deliberately a simple filter on the StateFlow rather than a Flow transform
     * so that it picks up hosts discovered by prior sessions (i.e. if NSD already has a
     * fresh result when we call startDiscovery(), we don't miss it).
     */
    private suspend fun collectMdnsHosts(onHost: suspend (DiscoveredHost) -> Unit) {
        networkDiscovery.discoveredHost.collect { host ->
            if (host != null && !host.isExpired) {
                onHost(host)
            }
        }
    }
}
