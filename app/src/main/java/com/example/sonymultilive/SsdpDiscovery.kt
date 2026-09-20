package com.example.sonymultilive

import android.content.Context
import android.net.Network
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Locale

/** SSDP discovery copied from the Sony Imaging Edge flow; no service probing/fallback protocols. */
class SsdpDiscovery(private val context: Context) {
    data class Stats(val packets: Int = 0, val locations: Int = 0, val sonyServerPackets: Int = 0)

    companion object {
        private const val SSDP_HOST = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private val SEARCH_TARGETS = listOf(
            "urn:schemas-sony-com:service:ScalarWebAPI:1",
            "urn:schemas-sony-com:service:DigitalImaging:1",
            "upnp:rootdevice",
            "ssdp:all"
        )
    }

    @Volatile var lastStats: Stats = Stats(); private set

    fun discover(timeoutMs: Long = 2_800): List<String> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("SonyMultipleMonitor:ssdp").apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            val locations = linkedSetOf<String>()
            var packets = 0
            var sony = 0
            val wifiNetwork = SonyNetworkRoute.findWifiNetwork(context, 300)
            val addresses = LocalNetworkAddress.findAll(context, wifiNetwork)
            val tried = linkedSetOf<String>()

            if (wifiNetwork != null) {
                val r = discoverOnBinding(timeoutMs, wifiNetwork, null)
                locations += r.first
                packets += r.second.packets
                sony += r.second.sonyServerPackets
                LocalNetworkAddress.find(context, wifiNetwork)?.ip?.hostAddress?.let(tried::add)
            }
            val perInterface = if (wifiNetwork != null || addresses.size > 1) timeoutMs.coerceAtMost(1_800) else timeoutMs
            for (address in addresses) {
                val key = address.ip.hostAddress.orEmpty()
                if (key.isBlank() || !tried.add(key)) continue
                val r = discoverOnBinding(perInterface, null, address.ip)
                locations += r.first
                packets += r.second.packets
                sony += r.second.sonyServerPackets
            }
            if (wifiNetwork == null && addresses.isEmpty()) {
                val r = discoverOnBinding(timeoutMs, null, null)
                locations += r.first
                packets += r.second.packets
                sony += r.second.sonyServerPackets
            }
            lastStats = Stats(packets, locations.size, sony)
            return locations.toList()
        } finally {
            if (lock.isHeld) lock.release()
        }
    }

    fun discoverHost(host: String, timeoutMs: Long = 1_500): List<String> {
        val network = SonyNetworkRoute.findWifiNetworkForHost(context, host, 300)
        val socket = DatagramSocket(null)
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(0))
            network?.bindSocket(socket)
            val target = InetSocketAddress(InetAddress.getByName(host), SSDP_PORT)
            SEARCH_TARGETS.forEach { st ->
                val request = buildRequest(st)
                socket.send(DatagramPacket(request, request.size, target))
            }
            val result = receive(socket, timeoutMs)
            lastStats = result.second
            return result.first
        } finally {
            socket.close()
        }
    }

    private fun discoverOnBinding(timeoutMs: Long, network: Network?, localAddress: InetAddress?): Pair<List<String>, Stats> {
        val socket = DatagramSocket(null)
        try {
            socket.reuseAddress = true
            socket.bind(if (localAddress != null) InetSocketAddress(localAddress, 0) else InetSocketAddress(0))
            network?.bindSocket(socket)
            val target = InetSocketAddress(InetAddress.getByName(SSDP_HOST), SSDP_PORT)
            SEARCH_TARGETS.forEach { st ->
                val request = buildRequest(st)
                runCatching { socket.send(DatagramPacket(request, request.size, target)) }
            }
            return receive(socket, timeoutMs)
        } finally {
            socket.close()
        }
    }

    private fun receive(socket: DatagramSocket, timeoutMs: Long): Pair<List<String>, Stats> {
        val locations = linkedSetOf<String>()
        var packets = 0
        var sony = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        val buffer = ByteArray(16 * 1024)
        while (System.currentTimeMillis() < deadline) {
            socket.soTimeout = (deadline - System.currentTimeMillis()).coerceIn(1, 300).toInt()
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                packets++
                val text = String(packet.data, packet.offset, packet.length, StandardCharsets.ISO_8859_1)
                val headers = parseHeaders(text)
                val location = headers["location"].orEmpty().trim()
                if (location.isNotBlank()) locations += location
                val server = headers["server"].orEmpty()
                if (server.contains("Sony", true) || text.contains("SonyImagingDevice", true)) sony++
            } catch (_: java.net.SocketTimeoutException) {
                // Keep receiving until total timeout expires, matching the stable Sony behavior.
            }
        }
        return locations.toList() to Stats(packets, locations.size, sony)
    }

    private fun buildRequest(st: String): ByteArray = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: $SSDP_HOST:$SSDP_PORT\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: 2\r\n")
        append("ST: $st\r\n\r\n")
    }.toByteArray(StandardCharsets.UTF_8)

    private fun parseHeaders(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            val i = line.indexOf(':')
            if (i > 0) result[line.substring(0, i).trim().lowercase(Locale.US)] = line.substring(i + 1).trim()
        }
        return result
    }
}
