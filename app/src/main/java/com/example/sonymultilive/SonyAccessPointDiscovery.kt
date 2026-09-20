package com.example.sonymultilive

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Sony discovery for normal Wi-Fi Access Point / router / Android tethering mode.
 *
 * This class is intentionally discovery-only. It does NOT restore ScalarWebAPI,
 * HTTP LiveView, SSH, D278, or any legacy camera transport. It only finds a Sony
 * host on the local IPv4 LAN using Sony DD :64321 and the Sony PTP/IP port
 * :15740. Once found, SonyCameraSession still uses the same Sony PTP stack.
 *
 * Why this is needed: many APs suppress SSDP multicast/client multicast. ZV-E10M2
 * also normally receives a DHCP address instead of using ZV-1's fixed
 * 192.168.122.1 camera-Wi-Fi address.
 */
object SonyAccessPointDiscovery {
    const val SONY_DD_PORT = 64321
    const val SONY_PTP_IP_PORT = 15740

    data class HostProbe(
        val host: String,
        val ddOpen: Boolean,
        val ptpOpen: Boolean,
        val camera: SonyCamera?
    )

    data class Result(
        val cameras: List<SonyCamera>,
        val subnetLabel: String,
        val probedHosts: Int,
        val serviceHosts: Int,
        val probes: List<HostProbe>
    )

    fun scan(
        context: Context,
        maxCameras: Int = 8,
        connectTimeoutMs: Int = 120
    ): Result {
        val app = context.applicationContext
        val wifiNetwork = SonyNetworkRoute.findWifiNetwork(app, 500)
        val addresses = LocalNetworkAddress.findAll(app, wifiNetwork)
        if (addresses.isEmpty()) {
            return Result(emptyList(), "No local IPv4", 0, 0, emptyList())
        }

        // One representative per /24. Sony/AP networks are normally /24 or
        // smaller. Capping broad private ranges to /24 prevents scanning tens of
        // thousands of addresses on VPN/enterprise-style prefixes.
        val subnets = linkedMapOf<Int, LocalNetworkAddress.Address>()
        for (address in addresses) {
            val localInt = ipv4ToInt(address.ip.address)
            val networkInt = localInt and 0xffffff00.toInt()
            subnets.putIfAbsent(networkInt, address)
            if (subnets.size >= 4) break
        }

        val cameras = Collections.synchronizedList(mutableListOf<SonyCamera>())
        val probes = Collections.synchronizedList(mutableListOf<HostProbe>())
        val seenHosts = Collections.synchronizedSet(mutableSetOf<String>())
        val acceptedCameraKeys = Collections.synchronizedSet(mutableSetOf<String>())
        val pool = Executors.newFixedThreadPool(40)
        var probedHosts = 0

        for ((networkInt, localAddress) in subnets) {
            val localInt = ipv4ToInt(localAddress.ip.address)
            val bindNetwork = networkForAddress(app, wifiNetwork, localAddress)
            for (lastOctet in 1 until 255) {
                val ipInt = networkInt + lastOctet
                if (ipInt == localInt) continue
                probedHosts++
                pool.execute {
                    if (cameras.size >= maxCameras) return@execute
                    val host = intToIpv4(ipInt)
                    if (!seenHosts.add(host)) return@execute

                    // DD is the cheapest positive Sony identifier. PTP is also
                    // probed so bodies that expose PTP before DD are not missed.
                    val ddOpen = portOpen(bindNetwork, localAddress, host, SONY_DD_PORT, connectTimeoutMs)
                    val ptpOpen = portOpen(bindNetwork, localAddress, host, SONY_PTP_IP_PORT, connectTimeoutMs)
                    if (!ddOpen && !ptpOpen) return@execute

                    var camera: SonyCamera? = null
                    if (ddOpen) {
                        camera = runCatching {
                            SonyDeviceDescription.fetchByHost(host, bindNetwork)
                        }.getOrNull()
                    }
                    if (camera == null && ptpOpen) {
                        // Keep the host usable even when DD is hidden/suppressed.
                        // The PTP handshake remains on the stable Sony PTP/IP implementation.
                        camera = SonyCamera.manual(host).copy(
                            friendlyName = "Sony PTP/IP ($host)",
                            modelName = "Sony PTP/IP"
                        )
                    }

                    probes += HostProbe(host, ddOpen, ptpOpen, camera)
                    if (camera != null) {
                        val key = camera.id.ifBlank { camera.host }
                        val hostKey = "host:${camera.host}"
                        val accepted = synchronized(acceptedCameraKeys) {
                            if (key in acceptedCameraKeys || hostKey in acceptedCameraKeys) false
                            else {
                                acceptedCameraKeys += key
                                acceptedCameraKeys += hostKey
                                true
                            }
                        }
                        if (accepted) cameras += camera
                    }
                }
            }
        }

        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.SECONDS)

        val subnetLabel = subnets.entries.joinToString(" · ") { (networkInt, address) ->
            "${intToIpv4(networkInt)}/24@${address.interfaceName}"
        }.ifBlank { "unknown" }

        val sortedProbes = probes.sortedBy { it.host }
        return Result(
            cameras = cameras.take(maxCameras).sortedBy { it.host },
            subnetLabel = subnetLabel,
            probedHosts = probedHosts,
            serviceHosts = sortedProbes.size,
            probes = sortedProbes
        )
    }

    private fun networkForAddress(
        context: Context,
        network: Network?,
        address: LocalNetworkAddress.Address
    ): Network? {
        if (network == null) return null
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.getLinkProperties(network) ?: return null
        val belongs = lp.linkAddresses.any { link ->
            val ip = link.address as? Inet4Address
            ip?.hostAddress == address.ip.hostAddress
        }
        return if (belongs) network else null
    }

    private fun portOpen(
        network: Network?,
        localAddress: LocalNetworkAddress.Address,
        host: String,
        port: Int,
        timeoutMs: Int
    ): Boolean {
        val socket = Socket()
        return try {
            if (network != null) {
                network.bindSocket(socket)
            } else if (!socket.isBound) {
                // Portable Hotspot / SoftAP often has no ConnectivityManager
                // Network. Bind the probe to ap0/swlan0/wlan* explicitly.
                socket.bind(InetSocketAddress(localAddress.ip, 0))
            }
            socket.connect(InetSocketAddress(host, port), timeoutMs.coerceAtLeast(50))
            true
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun ipv4ToInt(bytes: ByteArray): Int =
        ((bytes[0].toInt() and 0xff) shl 24) or
            ((bytes[1].toInt() and 0xff) shl 16) or
            ((bytes[2].toInt() and 0xff) shl 8) or
            (bytes[3].toInt() and 0xff)

    private fun intToIpv4(v: Int): String = listOf(
        (v ushr 24) and 0xff,
        (v ushr 16) and 0xff,
        (v ushr 8) and 0xff,
        v and 0xff
    ).joinToString(".")
}
