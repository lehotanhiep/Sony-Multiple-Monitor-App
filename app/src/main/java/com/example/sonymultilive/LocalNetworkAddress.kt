package com.example.sonymultilive

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import java.net.Inet4Address
import java.net.NetworkInterface

/** IPv4/prefix lookup that also works when LocalOnlyHotspot has no normal Network handle. */
object LocalNetworkAddress {
    data class Address(val ip: Inet4Address, val prefixLength: Int, val interfaceName: String)

    fun find(context: Context, network: Network?): Address? = findAll(context, network).firstOrNull()

    /**
     * Returns all useful private IPv4 interfaces, ordered with Wi-Fi/hotspot interfaces first.
     * This matters when the phone is the AP: Android often does not expose LocalOnlyHotspot as a
     * ConnectivityManager Network, and the AP address lives on a second interface (ap0/swlan0/etc.).
     */
    fun findAll(context: Context, network: Network? = null): List<Address> {
        val candidates = linkedMapOf<String, Address>()

        if (network != null) {
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val lp = cm.getLinkProperties(network)
            lp?.linkAddresses
                ?.filter { it.address is Inet4Address }
                ?.forEach { link ->
                    val ip = link.address as Inet4Address
                    if (!ip.isLoopbackAddress && ip.isSiteLocalAddress) {
                        val item = Address(ip, link.prefixLength.coerceIn(1, 32), lp.interfaceName.orEmpty())
                        candidates[key(item)] = item
                    }
                }
        }

        val interfaces = NetworkInterface.getNetworkInterfaces()
        if (interfaces != null) {
            while (interfaces.hasMoreElements()) {
                val nic = interfaces.nextElement()
                if (!runCatching { nic.isUp }.getOrDefault(false) || nic.isLoopback) continue
                val lower = nic.name.orEmpty().lowercase()
                // Cellular private addresses are not camera-LAN candidates and can add a full /24
                // of useless probes. VPN/tunnel interfaces are skipped for the same reason.
                if (lower.contains("rmnet") || lower.contains("ccmni") || lower.contains("pdp") ||
                    lower.contains("tun") || lower.contains("vpn")) continue
                for (entry in nic.interfaceAddresses) {
                    val ip = entry.address as? Inet4Address ?: continue
                    if (ip.isLoopbackAddress || !ip.isSiteLocalAddress) continue
                    val item = Address(ip, entry.networkPrefixLength.toInt().coerceIn(1, 32), nic.name.orEmpty())
                    candidates[key(item)] = item
                }
            }
        }

        return candidates.values.sortedByDescending(::score)
    }

    fun hotspotCandidate(): Address? = findAllFromInterfaces().firstOrNull()

    private fun findAllFromInterfaces(): List<Address> {
        val candidates = mutableListOf<Address>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        while (interfaces.hasMoreElements()) {
            val nic = interfaces.nextElement()
            if (!runCatching { nic.isUp }.getOrDefault(false) || nic.isLoopback) continue
            val lower = nic.name.orEmpty().lowercase()
            if (lower.contains("rmnet") || lower.contains("ccmni") || lower.contains("pdp") ||
                lower.contains("tun") || lower.contains("vpn")) continue
            for (entry in nic.interfaceAddresses) {
                val ip = entry.address as? Inet4Address ?: continue
                if (ip.isLoopbackAddress || !ip.isSiteLocalAddress) continue
                candidates += Address(ip, entry.networkPrefixLength.toInt().coerceIn(1, 32), nic.name.orEmpty())
            }
        }
        return candidates.distinctBy(::key).sortedByDescending(::score)
    }

    private fun key(address: Address): String =
        "${address.ip.hostAddress}/${address.prefixLength}@${address.interfaceName}"

    private fun score(address: Address): Int {
        val name = address.interfaceName.lowercase()
        var result = 0
        if (name == "ap0" || name.startsWith("ap")) result += 150
        if (name.contains("swlan")) result += 140
        if (name.contains("softap")) result += 140
        if (name.contains("wlan")) result += 110
        if (name.contains("wifi")) result += 100
        val bytes = address.ip.address
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        if (first == 192 && second == 168) result += 30
        if (first == 172 && second in 16..31) result += 20
        if (first == 10) result += 10
        return result
    }
}
