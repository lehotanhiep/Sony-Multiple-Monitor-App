package com.example.sonymultilive

import android.content.Context
import java.net.URL

/**
 * Discovery for the Sony PTP/IP PTP transport.
 *
 * Camera-Wi-Fi path:
 *   SSDP -> LOCATION -> Sony DeviceDescription -> PTP/IP 15740
 *
 * Wi-Fi Access Point / router / phone-hotspot path:
 *   SSDP first, then local /24 scan for Sony DD 64321 and PTP/IP 15740.
 *
 * No legacy LiveView/Scalar/SSH/D278 transport is selected here.
 */
class SonyPtpDiscovery(context: Context) {
    data class Stats(
        val ssdpPackets: Int,
        val ssdpLocations: Int,
        val sonyServerPackets: Int,
        val subnetLabel: String,
        val probedHosts: Int,
        val serviceHosts: Int,
        val openPortSummary: String
    )

    data class Result(val cameras: List<SonyCamera>, val stats: Stats)

    private val app = context.applicationContext

    fun discover(maxCameras: Int = 8, ssdpTimeoutMs: Long = 2_800, connectTimeoutMs: Int = 150): Result {
        val ssdp = SsdpDiscovery(app)
        val locations = runCatching { ssdp.discover(ssdpTimeoutMs) }.getOrDefault(emptyList())
        val cameras = linkedMapOf<String, SonyCamera>()
        val hosts = linkedSetOf<String>()

        fun accept(camera: SonyCamera?) {
            if (camera == null || camera.host.isBlank() || camera.host in hosts || cameras.size >= maxCameras) return
            hosts += camera.host
            cameras[camera.id.ifBlank { camera.host }] = camera
        }

        // Fast path: normal SSDP on camera Wi-Fi or multicast-friendly AP.
        locations.forEach { location ->
            if (cameras.size >= maxCameras) return@forEach
            val host = runCatching { URL(location).host }.getOrDefault("")
            val network = SonyNetworkRoute.findWifiNetworkForHost(app, host, 250)
            accept(runCatching { SonyDeviceDescription.fetch(location, network) }.getOrNull())
        }

        // Direct-camera fallback used by ZV-1.
        if ("192.168.122.1" !in hosts && cameras.size < maxCameras) {
            val host = "192.168.122.1"
            val network = SonyNetworkRoute.findWifiNetworkForHost(app, host, 200)
            val tetherRoute = if (network == null) TetheringNetworkRoute.findForHost(app, host) else null
            // Only probe the fixed ZV-1 address when a local interface actually
            // owns the 192.168.122.0 subnet. Otherwise an AP-mode scan should
            // start immediately instead of waiting on an unrelated address.
            if (network != null || tetherRoute != null) {
                accept(runCatching { SonyDeviceDescription.fetchByHost(host, network) }.getOrNull())
            }
        }

        // AP/router/tethering fallback. This is discovery only; the resulting
        // session still connects with SonyPtpIpClient from the stable Sony transport.
        val ap = if (cameras.size < maxCameras) {
            runCatching {
                SonyAccessPointDiscovery.scan(
                    context = app,
                    maxCameras = maxCameras - cameras.size,
                    connectTimeoutMs = connectTimeoutMs
                )
            }.getOrElse {
                SonyAccessPointDiscovery.Result(emptyList(), "unknown", 0, 0, emptyList())
            }
        } else {
            SonyAccessPointDiscovery.Result(emptyList(), "not scanned", 0, 0, emptyList())
        }
        ap.cameras.forEach(::accept)

        val addresses = LocalNetworkAddress.findAll(app)
        val localLabel = addresses.joinToString(" · ") { a ->
            "${a.ip.hostAddress}/${a.prefixLength}@${a.interfaceName}"
        }.ifBlank { "unknown" }
        val subnetLabel = when {
            ap.subnetLabel == "not scanned" -> localLabel
            ap.subnetLabel.isBlank() || ap.subnetLabel == "unknown" -> localLabel
            else -> ap.subnetLabel
        }

        val ds = ssdp.lastStats
        val openSummary = linkedSetOf<String>()
        cameras.values.forEach { camera ->
            openSummary += "${camera.host}: PTP/IP :15740"
        }
        ap.probes.take(16).forEach { probe ->
            val ports = buildList {
                if (probe.ddOpen) add(":64321 DD")
                if (probe.ptpOpen) add(":15740 PTP")
            }
            if (ports.isNotEmpty()) openSummary += "${probe.host}: ${ports.joinToString(", ")}"
        }

        return Result(
            cameras = cameras.values.toList(),
            stats = Stats(
                ssdpPackets = ds.packets,
                ssdpLocations = ds.locations,
                sonyServerPackets = ds.sonyServerPackets,
                subnetLabel = subnetLabel,
                probedHosts = locations.size + ap.probedHosts,
                serviceHosts = (hosts + ap.probes.map { it.host }).size,
                openPortSummary = openSummary.joinToString("\n")
            )
        )
    }
}
