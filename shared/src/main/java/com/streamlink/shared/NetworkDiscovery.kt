package com.streamlink.shared

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * NetworkDiscovery — Automatic Phone/Watch IP discovery via NSD (Bonjour/mDNS).
 *
 * Phone PUBLISHES  → "_streamlink._tcp" on port DIRECT_SOCKET_PORT
 * Watch DISCOVERS  → listens for "_streamlink._tcp", extracts IP
 *
 * Eliminates the hardcoded "192.168.1.100" problem.
 */
class NetworkDiscovery(private val context: Context) {
    private val tag = "NetworkDiscovery"
    private val SERVICE_TYPE = "_streamlink._tcp."
    private val SERVICE_NAME = "StreamLink-Phone"

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _discoveredHost = MutableStateFlow<String?>(null)
    val discoveredHost: StateFlow<String?> = _discoveredHost

    // ── Phone: Publish service ─────────────────────────────────────────────

    fun publishService(port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        Log.i(tag, "Publishing NSD service on port $port")
    }

    fun stopPublish() {
        try { nsdManager.unregisterService(registrationListener) } catch (_: Exception) {}
    }

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
            Log.i(tag, "Service registered: ${info.serviceName}")
        }
        override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
            Log.e(tag, "Registration failed: $code")
        }
        override fun onServiceUnregistered(info: NsdServiceInfo) {
            Log.i(tag, "Service unregistered")
        }
        override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {
            Log.e(tag, "Unregistration failed: $code")
        }
    }

    // ── Watch: Discover service ────────────────────────────────────────────

    fun startDiscovery() {
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        Log.i(tag, "Starting NSD discovery for $SERVICE_TYPE")
    }

    fun stopDiscovery() {
        try { nsdManager.stopServiceDiscovery(discoveryListener) } catch (_: Exception) {}
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) { Log.i(tag, "Discovery started") }
        override fun onDiscoveryStopped(type: String) { Log.i(tag, "Discovery stopped") }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.i(tag, "Service found: ${serviceInfo.serviceName}")
            if (serviceInfo.serviceType.contains("_streamlink")) {
                nsdManager.resolveService(serviceInfo, resolveListener)
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.w(tag, "Service lost: ${serviceInfo.serviceName}")
            if (_discoveredHost.value != null) _discoveredHost.value = null
        }

        override fun onStartDiscoveryFailed(type: String, code: Int) {
            Log.e(tag, "Discovery start failed: $code")
        }
        override fun onStopDiscoveryFailed(type: String, code: Int) {
            Log.e(tag, "Discovery stop failed: $code")
        }
    }

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
            Log.e(tag, "Resolve failed: $code")
        }

        override fun onServiceResolved(info: NsdServiceInfo) {
            val ip = info.host.hostAddress
            Log.i(tag, "✅ Phone found at $ip:${info.port}")
            _discoveredHost.value = ip
        }
    }
}
