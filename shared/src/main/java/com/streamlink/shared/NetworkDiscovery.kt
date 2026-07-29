package com.streamlink.shared

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.streamlink.shared.util.safeSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class DiscoveredHost(
    val ip: String,
    val timestampMs: Long = System.currentTimeMillis()
) {
    /**
     * TTL: 30 seconds. After this, the host is considered stale and the watch
     * should re-discover. Prevents silently holding a dead connection.
     */
    val isExpired: Boolean
        get() = (System.currentTimeMillis() - timestampMs) > 30_000L
}

/**
 * Lifecycle state for the *discovery* side (Watch → looking for the phone).
 *
 * [Idle]     nothing running, safe to call [NetworkDiscovery.startDiscovery]
 * [Starting] discoverServices() was called, waiting for NSD's async callback
 * [Active]   onDiscoveryStarted fired — NSD confirmed the browse is live
 * [Stopping] stopServiceDiscovery() was called, waiting for confirmation
 * [Failed]   a failure callback fired; behaves like [Idle] for restart purposes
 *            but keeps the reason around for logs/telemetry
 */
sealed class DiscoveryState {
    data object Idle : DiscoveryState()
    data object Starting : DiscoveryState()
    data object Active : DiscoveryState()
    data object Stopping : DiscoveryState()
    data class Failed(val code: Int, val reason: String) : DiscoveryState()
}

/** Same shape as [DiscoveryState] but for the *publish* side (Phone → advertising itself). */
sealed class PublishState {
    data object Idle : PublishState()
    data object Starting : PublishState()
    data object Active : PublishState()
    data object Stopping : PublishState()
    data class Failed(val code: Int, val reason: String) : PublishState()
}

/**
 * NetworkDiscovery — Automatic Phone/Watch IP discovery via NSD (Bonjour/mDNS).
 *
 * Phone PUBLISHES  → "_streamlink._tcp" on port DIRECT_SOCKET_PORT
 * Watch DISCOVERS  → listens for "_streamlink._tcp", extracts IP
 *
 * Eliminates the hardcoded "192.168.1.100" problem.
 *
 * ── Threading & state model ─────────────────────────────────────────────
 * NsdManager delivers every callback (onServiceRegistered, onStartDiscoveryFailed,
 * etc.) on an internal binder/callback thread — never guaranteed to be the
 * caller's thread. That means "set a flag, read a flag" is a check-then-act
 * race the moment two threads touch it at once (e.g. the UI calling
 * startDiscovery() again right as onStartDiscoveryFailed fires for the
 * previous attempt).
 *
 * Instead of two independent booleans (isDiscovering / isPublishing) that
 * can each be set optimistically in one place and never reset in another,
 * this class keeps ONE authoritative value per concern —
 * [discoveryState] and [publishState] — mutated only inside [stateLock].
 * Every public boolean (`isDiscovering`, `isPublishing`) is a *derived*,
 * read-only view of that state — never written to directly. There is
 * exactly one source of truth; nothing else "suggests" a different value.
 *
 * A per-attempt "generation" counter fences out stale callbacks: if
 * stopDiscovery() + startDiscovery() happen back-to-back, a late callback
 * from the *previous* attempt can no longer corrupt the *new* attempt's
 * state, because it's tagged with an old generation number and is ignored.
 */
class NetworkDiscovery(private val context: Context) {
    private val tag = "NetworkDiscovery"
    private val SERVICE_TYPE = "_streamlink._tcp."
    private val SERVICE_NAME = "StreamLink-Phone"

    /** How long we wait for NSD's async callback before declaring the attempt dead.
     *  Real devices (some Samsung/MIUI builds) are known to silently drop NSD
     *  callbacks; without this watchdog a Starting/Stopping state can hang forever,
     *  which is the exact class of bug this rewrite exists to eliminate. */
    private val CALLBACK_TIMEOUT_MS = 10_000L

    private val nsdManager: NsdManager? = context.safeSystemService(Context.NSD_SERVICE)
    val isDiscoveryAvailable: Boolean get() = nsdManager != null

    private val _discoveredHost = MutableStateFlow<DiscoveredHost?>(null)
    val discoveredHost: StateFlow<DiscoveredHost?> = _discoveredHost

    /**
     * Last successfully resolved IP — persisted in-memory across discovery restarts.
     * Enables instant reconnect without waiting for mDNS re-resolve on resume.
     * Cleared only when the service is explicitly lost (onServiceLost).
     */
    @Volatile var lastKnownIp: String? = null
        private set

    /** Internal lifecycle scope. Lives for the process lifetime (this class is an
     *  app-scoped Hilt singleton); only used for short watchdog timers, never for
     *  hot-path work. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Guards every read-modify-write of [discoveryState]/[publishState] and their
     *  generation counters. Contention is negligible — NSD events are rare — so a
     *  plain lock is simpler and easier to prove correct than a lock-free CAS loop. */
    private val stateLock = Any()

    // ── Discovery (Watch side) ──────────────────────────────────────────────

    private val _discoveryState = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    /** Authoritative discovery lifecycle state. Observe this instead of a boolean
     *  if you need to distinguish "requested, awaiting confirmation" from "confirmed
     *  running" — e.g. to show a spinner only while Starting. */
    val discoveryState: StateFlow<DiscoveryState> = _discoveryState

    /** Convenience derived flag. NEVER assigned directly — always read from
     *  [discoveryState]. Kept for callers that only care about "is it running". */
    val isDiscovering: Boolean
        get() = when (_discoveryState.value) {
            DiscoveryState.Starting, DiscoveryState.Active -> true
            else -> false
        }

    private var discoveryGeneration = 0
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var discoveryWatchdog: Job? = null

    fun startDiscovery() {
        val manager = nsdManager ?: run {
            Log.w(tag, "NSD unavailable — cannot auto-discover, user must enter IP manually")
            return
        }

        val myGeneration: Int
        val listener: NsdManager.DiscoveryListener

        synchronized(stateLock) {
            when (_discoveryState.value) {
                DiscoveryState.Starting, DiscoveryState.Active -> {
                    Log.d(tag, "startDiscovery() ignored — already ${_discoveryState.value}")
                    return
                }
                else -> Unit // Idle / Stopping / Failed → allowed to (re)start
            }
            discoveryGeneration++
            myGeneration = discoveryGeneration
            // Fresh listener instance per attempt — never reuse one that may still
            // be registered with the OS from a prior attempt (see class doc + the
            // "listener already in use" note on the publish side below).
            listener = buildDiscoveryListener(myGeneration)
            discoveryListener = listener
            _discoveryState.value = DiscoveryState.Starting
        }

        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            Log.i(tag, "Starting NSD discovery for $SERVICE_TYPE (gen=$myGeneration)")
        } catch (e: Exception) {
            // discoverServices() CAN throw synchronously (bad service type, NSD
            // service not ready yet). The old code only guarded registerService()
            // this way and left this path uncaught — a real crash risk on the
            // caller's coroutine. Treat it exactly like onStartDiscoveryFailed.
            Log.w(tag, "discoverServices() threw synchronously: ${e.message}")
            failDiscovery(myGeneration, -1, "sync-throw: ${e.message}")
            return
        }
        armWatchdog(myGeneration, isDiscoverySide = true)
    }

    fun stopDiscovery() {
        val manager = nsdManager
        val listener: NsdManager.DiscoveryListener?
        synchronized(stateLock) {
            when (_discoveryState.value) {
                DiscoveryState.Idle, DiscoveryState.Stopping, is DiscoveryState.Failed -> return
                else -> Unit
            }
            listener = discoveryListener
            _discoveryState.value = DiscoveryState.Stopping
        }
        try {
            if (manager != null && listener != null) manager.stopServiceDiscovery(listener)
        } catch (e: Exception) {
            /* intentional: discovery cleanup; NSD manager may have already stopped */
            Log.d(tag, "Error stopping discovery: ${e.message}")
        }
        // No callback is guaranteed to fire promptly on every OEM; force Idle so a
        // subsequent startDiscovery() is never blocked by a stuck Stopping state.
        synchronized(stateLock) {
            if (_discoveryState.value == DiscoveryState.Stopping) {
                _discoveryState.value = DiscoveryState.Idle
            }
        }
        discoveryWatchdog?.cancel()
    }

    private fun failDiscovery(generation: Int, code: Int, reason: String) {
        synchronized(stateLock) {
            if (generation != discoveryGeneration) return // stale callback, ignore
            _discoveryState.value = DiscoveryState.Failed(code, reason)
        }
    }

    private fun buildDiscoveryListener(generation: Int) = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) {
            Log.i(tag, "Discovery started (gen=$generation)")
            synchronized(stateLock) {
                if (generation == discoveryGeneration) _discoveryState.value = DiscoveryState.Active
            }
        }

        override fun onDiscoveryStopped(type: String) {
            Log.i(tag, "Discovery stopped (gen=$generation)")
            synchronized(stateLock) {
                if (generation == discoveryGeneration) _discoveryState.value = DiscoveryState.Idle
            }
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.i(tag, "Service found: ${serviceInfo.serviceName}")
            if (serviceInfo.serviceType.contains("_streamlink")) {
                nsdManager?.resolveService(serviceInfo, buildResolveListener())
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.w(tag, "Service lost: ${serviceInfo.serviceName}")
            // Do NOT clear lastKnownIp here — the phone may still be reachable at
            // the same address (transient NSD blip). Only expire via TTL.
            if (_discoveredHost.value != null) _discoveredHost.value = null
        }

        override fun onStartDiscoveryFailed(type: String, code: Int) {
            Log.e(tag, "Discovery start failed: $code (gen=$generation)")
            // ✅ this is the actual fix for C-6: the flag now always resets.
            failDiscovery(generation, code, "onStartDiscoveryFailed")
        }

        override fun onStopDiscoveryFailed(type: String, code: Int) {
            Log.e(tag, "Discovery stop failed: $code (gen=$generation)")
            // Whatever happens on the OS side, our intent was to stop. Moving to
            // Failed (which restarts exactly like Idle — see DiscoveryState doc)
            // rather than leaving Stopping, or reverting to Active, means the
            // next startDiscovery() is never blocked by this failure.
            failDiscovery(generation, code, "onStopDiscoveryFailed")
        }
    }

    private fun buildResolveListener() = object : NsdManager.ResolveListener {
        override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
            Log.e(tag, "Resolve failed: $code")
        }

        override fun onServiceResolved(info: NsdServiceInfo) {
            val ip = info.host.hostAddress ?: return
            Log.i(tag, "✅ Phone found at $ip:${info.port}")
            lastKnownIp = ip                          // cache for fast-reconnect
            _discoveredHost.value = DiscoveredHost(ip)
        }
    }

    // ── Publish (Phone side) ────────────────────────────────────────────────

    private val _publishState = MutableStateFlow<PublishState>(PublishState.Idle)
    /** Authoritative publish lifecycle state — same rationale as [discoveryState]. */
    val publishState: StateFlow<PublishState> = _publishState

    /** Convenience derived flag. NEVER assigned directly. */
    val isPublishing: Boolean
        get() = when (_publishState.value) {
            PublishState.Starting, PublishState.Active -> true
            else -> false
        }

    private var publishGeneration = 0
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var publishWatchdog: Job? = null

    fun publishService(port: Int) {
        val manager = nsdManager ?: run {
            Log.w(tag, "NSD unavailable — skipping publish, fallback mode expected")
            return
        }

        val myGeneration: Int
        val listener: NsdManager.RegistrationListener
        val staleListener: NsdManager.RegistrationListener?

        synchronized(stateLock) {
            // Capture whatever listener might still be live with the OS so we can
            // try to clean it up BEFORE registering a new one — this is what used
            // to throw "listener already in use" when the old listener was reused.
            staleListener = when (_publishState.value) {
                PublishState.Starting, PublishState.Active -> registrationListener
                else -> null
            }
            publishGeneration++
            myGeneration = publishGeneration
            listener = buildRegistrationListener(myGeneration) // always a NEW instance
            registrationListener = listener
            _publishState.value = PublishState.Starting
        }

        if (staleListener != null) {
            Log.i(tag, "Already publishing — unregistering stale listener before re-publish")
            try {
                manager.unregisterService(staleListener)
            } catch (e: Exception) {
                /* intentional: listener may not be registered; NSD manager handles gracefully */
                Log.d(tag, "Could not unregister stale listener: ${e.message}")
            }
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        try {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
            Log.i(tag, "Publishing NSD service on port $port (gen=$myGeneration)")
        } catch (e: IllegalArgumentException) {
            // Because every attempt now gets a brand-new listener object (never
            // reused across register/unregister pairs), this branch should be
            // structurally unreachable — kept only as a defensive backstop.
            Log.w(tag, "registerService rejected (listener race): ${e.message}")
            failPublish(myGeneration, -1, "sync-throw: ${e.message}")
            return
        }
        armWatchdog(myGeneration, isDiscoverySide = false)
    }

    fun stopPublish() {
        val manager = nsdManager
        val listener: NsdManager.RegistrationListener?
        synchronized(stateLock) {
            when (_publishState.value) {
                PublishState.Idle, PublishState.Stopping, is PublishState.Failed -> return
                else -> Unit
            }
            listener = registrationListener
            _publishState.value = PublishState.Stopping
        }
        try {
            if (manager != null && listener != null) manager.unregisterService(listener)
        } catch (e: Exception) {
            /* intentional: listener cleanup; NSD manager may have already cleared */
            Log.d(tag, "Error stopping publish: ${e.message}")
        }
        // Same reasoning as stopDiscovery(): don't wait forever on a callback that
        // may never come; the intent to stop is enough to free the state machine.
        synchronized(stateLock) {
            if (_publishState.value == PublishState.Stopping) {
                _publishState.value = PublishState.Idle
            }
        }
        publishWatchdog?.cancel()
    }

    private fun failPublish(generation: Int, code: Int, reason: String) {
        synchronized(stateLock) {
            if (generation != publishGeneration) return // stale callback, ignore
            _publishState.value = PublishState.Failed(code, reason)
        }
    }

    private fun buildRegistrationListener(generation: Int) = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
            Log.i(tag, "Service registered: ${info.serviceName} (gen=$generation)")
            synchronized(stateLock) {
                if (generation == publishGeneration) _publishState.value = PublishState.Active
            }
        }

        override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
            Log.e(tag, "Registration failed: $code (gen=$generation)")
            failPublish(generation, code, "onRegistrationFailed")
        }

        override fun onServiceUnregistered(info: NsdServiceInfo) {
            Log.i(tag, "Service unregistered (gen=$generation)")
            synchronized(stateLock) {
                if (generation == publishGeneration) _publishState.value = PublishState.Idle
            }
        }

        override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {
            Log.e(tag, "Unregistration failed: $code (gen=$generation)")
            // ✅ this is the fix for the onUnregistrationFailed half of C-6: we no
            // longer leave isPublishing silently mismatched with OS state. Forcing
            // Failed (not Active) is deliberate — we asked to stop; regardless of
            // whether the OS-side listener is still technically registered, the
            // NEXT publishService() call will always mint a fresh listener and a
            // fresh generation, so it can never collide with this one again.
            failPublish(generation, code, "onUnregistrationFailed")
        }
    }

    // ── Shared watchdog ──────────────────────────────────────────────────────

    /** Forces a stuck Starting state to Failed if NSD never calls back at all —
     *  a real, observed failure mode on some OEM builds, not a hypothetical. */
    private fun armWatchdog(generation: Int, isDiscoverySide: Boolean) {
        val job = scope.launch {
            delay(CALLBACK_TIMEOUT_MS)
            if (isDiscoverySide) {
                synchronized(stateLock) {
                    if (generation == discoveryGeneration && _discoveryState.value == DiscoveryState.Starting) {
                        Log.w(tag, "Discovery watchdog: no callback after ${CALLBACK_TIMEOUT_MS}ms (gen=$generation)")
                        _discoveryState.value = DiscoveryState.Failed(-2, "watchdog-timeout")
                    }
                }
            } else {
                synchronized(stateLock) {
                    if (generation == publishGeneration && _publishState.value == PublishState.Starting) {
                        Log.w(tag, "Publish watchdog: no callback after ${CALLBACK_TIMEOUT_MS}ms (gen=$generation)")
                        _publishState.value = PublishState.Failed(-2, "watchdog-timeout")
                    }
                }
            }
        }
        if (isDiscoverySide) {
            discoveryWatchdog?.cancel(); discoveryWatchdog = job
        } else {
            publishWatchdog?.cancel(); publishWatchdog = job
        }
    }
}
