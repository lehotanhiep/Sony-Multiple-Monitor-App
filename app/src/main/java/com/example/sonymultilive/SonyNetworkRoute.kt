package com.example.sonymultilive

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object SonyNetworkRoute {
    @Volatile
    private var cachedWifiNetwork: Network? = null

    fun findWifiNetwork(context: Context, timeoutMs: Long = 900): Network? =
        findWifiNetworkForHost(context, null, timeoutMs)

    /**
     * Finds the current Wi-Fi Network and, when a target host is supplied,
     * prefers the Wi-Fi network whose LinkProperties has a direct route to it.
     *
     * We intentionally do NOT bind the whole process. the Sony PTP/IP stack binds only its own sockets so other app traffic
     * cannot accidentally be routed onto the wrong network.
     */
    fun findWifiNetworkForHost(context: Context, host: String?, timeoutMs: Long = 900): Network? {
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val target = host?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }

        cachedWifiNetwork?.let { cached ->
            val caps = cm.getNetworkCapabilities(cached)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                if (target == null || matchesTarget(cm.getLinkProperties(cached), target)) return cached
            } else {
                cachedWifiNetwork = null
            }
        }

        // A camera-created DIRECT-* Wi-Fi often has no Internet capability and some
        // Android builds do not publish a conventional RouteInfo entry for the camera
        // subnet. The Network still exists, and its LinkAddress is enough to prove that
        // the camera is directly reachable. Check already-known networks synchronously
        // before waiting for NetworkCallback.
        cm.allNetworks.forEach { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@forEach
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@forEach
            val lp = cm.getLinkProperties(network)
            if (target == null || matchesTarget(lp, target)) {
                cachedWifiNetwork = network
                return network
            }
        }

        val matched = AtomicReference<Network?>(null)
        val fallback = AtomicReference<Network?>(null)
        val latch = CountDownLatch(1)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network) ?: return
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
                fallback.compareAndSet(null, network)
                if (target == null || matchesTarget(cm.getLinkProperties(network), target)) {
                    if (matched.compareAndSet(null, network)) latch.countDown()
                }
            }
        }

        return try {
            cm.registerNetworkCallback(request, callback)
            latch.await(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
            // When a target host is supplied, never return an unrelated Wi-Fi
            // Network merely because one exists. This is critical in phone
            // tethering mode where the camera is behind the SoftAP interface,
            // while ConnectivityManager may still expose another Wi-Fi network.
            val selected = if (target != null) matched.get() else (matched.get() ?: fallback.get())
            selected?.also { cachedWifiNetwork = it }
        } finally {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }

    fun describe(context: Context, host: String, network: Network? = null): String {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifi = network ?: findWifiNetworkForHost(context, host, 300)
        if (wifi == null) {
            val tether = TetheringNetworkRoute.findForHost(context, host)
            return if (tether != null) {
                "Tethering=${tether.label} AndroidNetwork=none"
            } else {
                "Wi-Fi/Tethering route=none"
            }
        }
        val lp = cm.getLinkProperties(wifi) ?: return "Wi-Fi LinkProperties=none"
        val target = runCatching { InetAddress.getByName(host) }.getOrNull()
        val ips = lp.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .joinToString(",") { it.hostAddress ?: "?" }
            .ifBlank { "no-ipv4" }
        val direct = target?.let { hasDirectRoute(lp, it) } ?: false
        val routes = lp.routes
            .mapNotNull { r -> r.destination?.toString() }
            .filterNot { it == "0.0.0.0/0" || it == "::/0" }
            .take(4)
            .joinToString(",")
            .ifBlank { "none" }
        return "Wi-Fi=$ips target=$host directRoute=$direct routes=$routes"
    }

    private fun matchesTarget(lp: LinkProperties?, target: InetAddress): Boolean {
        if (lp == null) return false
        if (hasDirectRoute(lp, target)) return true
        val target4 = target as? Inet4Address ?: return false
        return lp.linkAddresses.any { link ->
            val local4 = link.address as? Inet4Address ?: return@any false
            sameSubnet(local4, target4, link.prefixLength)
        }
    }

    private fun hasDirectRoute(lp: LinkProperties?, target: InetAddress): Boolean {
        if (lp == null) return false
        return lp.routes.any { route ->
            val prefix = route.destination ?: return@any false
            prefix.prefixLength > 0 && runCatching { prefix.contains(target) }.getOrDefault(false)
        }
    }

    private fun sameSubnet(a: Inet4Address, b: Inet4Address, prefixLength: Int): Boolean {
        val prefix = prefixLength.coerceIn(0, 32)
        if (prefix == 0) return true
        val av = ipv4ToLong(a.address)
        val bv = ipv4ToLong(b.address)
        val mask = if (prefix == 32) 0xffffffffL else (0xffffffffL shl (32 - prefix)) and 0xffffffffL
        return (av and mask) == (bv and mask)
    }

    private fun ipv4ToLong(bytes: ByteArray): Long =
        ((bytes[0].toLong() and 0xffL) shl 24) or
            ((bytes[1].toLong() and 0xffL) shl 16) or
            ((bytes[2].toLong() and 0xffL) shl 8) or
            (bytes[3].toLong() and 0xffL)

    /** Kept only to clear a stale binding made by an older app build. */
    fun clearProcessBinding(context: Context) {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.bindProcessToNetwork(null)
        cachedWifiNetwork = null
    }
}
