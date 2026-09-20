package com.example.sonymultilive

import android.content.Context
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Route helper for Android Wi-Fi tethering / portable hotspot mode.
 *
 * Android often does not expose the SoftAP interface (ap0/swlan0/...) as a
 * ConnectivityManager TRANSPORT_WIFI Network. The kernel still owns a directly
 * connected route to tethering clients. We therefore find the local IPv4
 * address whose prefix contains the camera and bind client sockets to that
 * source address instead of requiring an android.net.Network handle.
 */
object TetheringNetworkRoute {
    data class Route(
        val localAddress: Inet4Address,
        val prefixLength: Int,
        val interfaceName: String,
        val targetAddress: Inet4Address
    ) {
        val label: String
            get() = "${localAddress.hostAddress}/$prefixLength@$interfaceName -> ${targetAddress.hostAddress}"

        fun bind(socket: Socket) {
            if (!socket.isBound) {
                socket.bind(InetSocketAddress(localAddress, 0))
            }
        }
    }

    fun findForHost(context: Context, host: String): Route? {
        val target = runCatching { InetAddress.getByName(host) as? Inet4Address }.getOrNull() ?: return null
        return LocalNetworkAddress.findAll(context, null)
            .asSequence()
            .filter { sameSubnet(it.ip, target, it.prefixLength) }
            .sortedByDescending { score(it) }
            .map { Route(it.ip, it.prefixLength, it.interfaceName, target) }
            .firstOrNull()
    }

    private fun score(address: LocalNetworkAddress.Address): Int {
        val name = address.interfaceName.lowercase()
        var score = 0
        if (name == "ap0" || name.startsWith("ap")) score += 300
        if (name.contains("swlan")) score += 280
        if (name.contains("softap")) score += 280
        if (name.contains("wlan")) score += 180
        if (name.contains("wifi")) score += 160
        // Prefer normal LAN-sized prefixes over very broad virtual ranges.
        if (address.prefixLength >= 24) score += 40
        return score
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
}
