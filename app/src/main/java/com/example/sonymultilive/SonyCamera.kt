package com.example.sonymultilive

/** Camera metadata used by Sony Multiple Monitor V1.0.0. */
data class SonyCamera(
    val id: String,
    val friendlyName: String,
    val modelName: String,
    val locationUrl: String,
    val host: String,
    val manufacturer: String = "",
    val macAddress: String = "",
    val function: String = "",
    val serverType: String = "",
    val firmwareVersion: String = "",
    val serialNumber: String = "",
    val serialVersion: String = "",
    val digitalImagingScpdUrl: String = "",
    val ptpRemoteControlAdvertised: Boolean = true,
    val ptpRemoteControlEnabled: Boolean = true,
    val ptpVersion: String = "",
    val ptpPairingNecessity: String = ""
) {
    companion object {
        fun manual(host: String): SonyCamera = SonyCamera(
            id = "manual:$host",
            friendlyName = "Sony camera ($host)",
            modelName = "Sony",
            locationUrl = "",
            host = host,
            ptpRemoteControlAdvertised = true,
            ptpRemoteControlEnabled = true
        )
    }
}
