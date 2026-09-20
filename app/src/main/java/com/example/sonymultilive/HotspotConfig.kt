package com.example.sonymultilive

/** User-selected hotspot credentials. */
data class HotspotConfig(
    val ssid: String,
    val password: String
) {
    fun validate(): String? {
        val cleanSsid = ssid.trim()
        if (cleanSsid.isEmpty()) return "Hotspot name cannot be empty"
        if (cleanSsid.toByteArray(Charsets.UTF_8).size > 32) {
            return "Hotspot name must be at most 32 UTF-8 bytes"
        }
        if (password.length !in 8..63) {
            return "WPA2 password must be 8 to 63 characters"
        }
        if (password.any { it.code !in 32..126 }) {
            return "Password must use printable ASCII characters"
        }
        return null
    }
}
