package com.example.sonymultilive

import android.content.Context

/** Persists the preferred hotspot name/password independently from hotspot runtime state. */
class HotspotConfigStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "phone_hotspot_config",
        Context.MODE_PRIVATE
    )

    fun load(): HotspotConfig {
        return HotspotConfig(
            ssid = prefs.getString(KEY_SSID, DEFAULT_SSID) ?: DEFAULT_SSID,
            password = prefs.getString(KEY_PASSWORD, DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD
        )
    }

    fun save(config: HotspotConfig) {
        prefs.edit()
            .putString(KEY_SSID, config.ssid.trim())
            .putString(KEY_PASSWORD, config.password)
            .apply()
    }

    companion object {
        private const val KEY_SSID = "ssid"
        private const val KEY_PASSWORD = "password"
        private const val DEFAULT_SSID = "SonyMultipleMonitor"
        private const val DEFAULT_PASSWORD = "SonyLive24"
    }
}
