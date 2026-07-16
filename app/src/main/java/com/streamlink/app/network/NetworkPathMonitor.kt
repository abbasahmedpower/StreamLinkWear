package com.streamlink.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference

/**
 * Monitors physical network interfaces and triggers handover events 
 * when WiFi signal degrades or cellular fallback is required.
 */
class NetworkPathMonitor(context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager = 
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _currentPath = MutableStateFlow<NetworkPath>(NetworkPath.WIFI)
    val currentPath: StateFlow<NetworkPath> = _currentPath

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    enum class NetworkPath {
        WIFI,
        CELLULAR,
        DISCONNECTED
    }

    interface HandoverListener {
        fun onPathChanged(newPath: NetworkPath)
        fun onCriticalSignalDegradation()
    }

    private var listenerRef: WeakReference<HandoverListener>? = null

    fun setListener(listener: HandoverListener) {
        this.listenerRef = WeakReference(listener)
    }

    fun startMonitoring() {
        if (networkCallback != null) return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return
                
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    Log.d("NetworkPathMonitor", "WiFi Interface is Active.")
                    updatePath(NetworkPath.WIFI)
                } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    Log.d("NetworkPathMonitor", "Cellular Interface is Active (WAN Ready).")
                    updatePath(NetworkPath.CELLULAR)
                }
            }

            override fun onLost(network: Network) {
                Log.w("NetworkPathMonitor", "Active Network Connection lost.")
                // If WiFi lost, actively check if cellular is available to force handover
                evaluateFallback()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                // Monitor Signal Strength (RSSI) for proactive handover before connection drop
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    val signalStrength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        capabilities.signalStrength
                    } else {
                        // Fallback RSSI check
                        @Suppress("DEPRECATION")
                        capabilities.linkDownstreamBandwidthKbps
                    }
                    
                    // Trigger proactive handover if WiFi signal is extremely poor
                    if (signalStrength != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED && signalStrength < -85) {
                        Log.w("NetworkPathMonitor", "Critical WiFi signal degradation detected: $signalStrength dBm")
                        listenerRef?.get()?.onCriticalSignalDegradation()
                    }
                }
            }
        }

        connectivityManager.registerNetworkCallback(request, networkCallback!!)
    }

    fun stopMonitoring() {
        networkCallback?.let {
            connectivityManager.unregisterNetworkCallback(it)
            networkCallback = null
        }
    }

    private fun updatePath(path: NetworkPath) {
        if (_currentPath.value != path) {
            _currentPath.value = path
            listenerRef?.get()?.onPathChanged(path)
        }
    }

    private fun evaluateFallback() {
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        
        if (capabilities == null) {
            updatePath(NetworkPath.DISCONNECTED)
            return
        }

        when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> updatePath(NetworkPath.WIFI)
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> updatePath(NetworkPath.CELLULAR)
            else -> updatePath(NetworkPath.DISCONNECTED)
        }
    }
}
