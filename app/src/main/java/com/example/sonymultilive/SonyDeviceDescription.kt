package com.example.sonymultilive

import android.net.Network
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** DeviceDescription parser reduced to the Sony Imaging Edge/PTP discovery path. */
object SonyDeviceDescription {
    private data class Service(val type: String?, val scpd: String?)
    private data class Parsed(
        var friendlyName: String = "",
        var modelName: String = "",
        var udn: String = "",
        var manufacturer: String = "",
        var serverType: String = "",
        var firmware: String = "",
        var serial: String = "",
        var serialVersion: String = "",
        var mac: String = "",
        var function: String = "",
        var ptpSupport: String = "",
        var ptpVersions: String = "",
        var ptpPairing: String = "",
        val services: MutableList<Service> = mutableListOf()
    )

    fun fetch(locationUrl: String, network: Network? = null): SonyCamera? {
        val xml = httpGet(locationUrl, network) ?: return null
        val p = Parsed()
        parseXml(xml, p)

        // Follow SCPD after DD only to collect Sony PTP metadata.
        var digitalScpd = ""
        p.services.forEach { service ->
            val scpd = service.scpd ?: return@forEach
            val resolved = resolve(locationUrl, scpd)
            if (service.type.orEmpty().contains("DigitalImaging", true)) digitalScpd = resolved
            httpGet(resolved, network)?.let { parseXml(it, p) }
        }

        val host = runCatching { URL(locationUrl).host }.getOrDefault("")
        if (host.isBlank()) return null
        val looksSony = p.manufacturer.contains("Sony", true) ||
            p.modelName.startsWith("ZV", true) || p.serverType.contains("Sony", true) ||
            p.ptpSupport.isNotBlank() || digitalScpd.isNotBlank()
        if (!looksSony) return null

        val enabled = p.ptpSupport.equals("Enable", true) || p.ptpSupport.equals("Enabled", true) ||
            p.ptpSupport.equals("On", true) || p.ptpSupport == "1"
        return SonyCamera(
            id = p.udn.removePrefix("uuid:").ifBlank { "sony-ptp:$host" },
            friendlyName = p.friendlyName.ifBlank { p.modelName.ifBlank { "Sony Camera" } },
            modelName = p.modelName.ifBlank { "Sony" },
            locationUrl = locationUrl,
            host = host,
            manufacturer = p.manufacturer,
            macAddress = p.mac,
            function = p.function,
            serverType = p.serverType,
            firmwareVersion = p.firmware,
            serialNumber = p.serial,
            serialVersion = p.serialVersion,
            digitalImagingScpdUrl = digitalScpd,
            ptpRemoteControlAdvertised = p.ptpSupport.isNotBlank() || p.ptpVersions.isNotBlank() || p.ptpPairing.isNotBlank() || digitalScpd.isNotBlank(),
            ptpRemoteControlEnabled = enabled,
            ptpVersion = p.ptpVersions,
            ptpPairingNecessity = p.ptpPairing
        )
    }

    fun fetchByHost(host: String, network: Network? = null): SonyCamera? {
        val candidates = listOf(
            "http://$host:64321/dd.xml",
            "http://$host:64321/DigitalImagingDesc.xml",
            "http://$host:64321/DmsDesc.xml"
        )
        for (url in candidates) fetch(url, network)?.let { return it }
        return null
    }

    private fun parseXml(xml: String, out: Parsed) {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(xml.reader())
        }
        var event = parser.eventType
        var currentTag: String? = null
        var inService = false
        var serviceType: String? = null
        var serviceScpd: String? = null
        fun local(raw: String?) = raw.orEmpty().substringAfter(':')

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    currentTag = local(parser.name)
                    if (currentTag.equals("service", true)) {
                        inService = true
                        serviceType = null
                        serviceScpd = null
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotEmpty()) when (currentTag?.lowercase(Locale.US)) {
                        "manufacturer" -> out.manufacturer = text
                        "friendlyname" -> out.friendlyName = text
                        "modelname", "x_modelname" -> out.modelName = text
                        "udn" -> out.udn = text
                        "x_servertype", "x_serverversion", "x_digitalimaging_version" -> out.serverType = text
                        "x_firmwareversion" -> out.firmware = text
                        "serialnumber", "x_serialnumber" -> out.serial = text
                        "x_serialversion" -> out.serialVersion = text
                        "x_macaddress" -> out.mac = text
                        "x_function" -> out.function = text
                        "x_ptp_remotecontrolsupport" -> out.ptpSupport = text
                        "x_ptp_versions" -> out.ptpVersions = text
                        "x_ptp_pairingnecessity" -> out.ptpPairing = text
                        "servicetype" -> if (inService) serviceType = text
                        "scpdurl" -> if (inService) serviceScpd = text
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (local(parser.name).equals("service", true) && inService) {
                        out.services += Service(serviceType, serviceScpd)
                        inService = false
                    }
                    currentTag = null
                }
            }
            event = parser.next()
        }
    }

    private fun httpGet(url: String, network: Network?): String? {
        val connection = runCatching {
            val u = URL(url)
            (network?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
        }.getOrNull() ?: return null
        return try {
            connection.connectTimeout = 2_600
            connection.readTimeout = 3_200
            connection.requestMethod = "GET"
            connection.useCaches = false
            connection.setRequestProperty("Connection", "close")
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun resolve(base: String, value: String): String =
        runCatching { URL(URL(base), value).toString() }.getOrDefault(value)
}
