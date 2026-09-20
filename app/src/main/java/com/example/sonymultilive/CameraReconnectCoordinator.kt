package com.example.sonymultilive

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper

/**
 * Watches camera Wi-Fi / tethering network changes and drives lightweight reconnect attempts.
 * It deliberately requests TRANSPORT_WIFI without INTERNET because Sony DIRECT-* networks
 * normally have no Internet capability.
 */
class CameraReconnectCoordinator(
    context: Context,
    private val onNetworkChanged: () -> Unit,
    private val onPeriodicCheck: () -> Unit
) : AutoCloseable {
    private val app = context.applicationContext
    private val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())
    private var started = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleNetworkChanged()
        override fun onLost(network: Network) = scheduleNetworkChanged()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = scheduleNetworkChanged()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!started) return
            onPeriodicCheck()
            handler.postDelayed(this, 3_500L)
        }
    }

    fun start() {
        if (started) return
        started = true
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching { cm.registerNetworkCallback(request, callback) }
        handler.postDelayed(ticker, 2_500L)
    }

    private fun scheduleNetworkChanged() {
        handler.removeCallbacks(networkChangedRunnable)
        handler.postDelayed(networkChangedRunnable, 1_200L)
    }

    private val networkChangedRunnable = Runnable { if (started) onNetworkChanged() }

    override fun close() {
        started = false
        handler.removeCallbacksAndMessages(null)
        runCatching { cm.unregisterNetworkCallback(callback) }
    }
}
