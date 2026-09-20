package com.example.sonymultilive

import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiSsid
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor

/** Owns Android LocalOnlyHotspot independently from camera protocol/session code. */
class PhoneHotspotManager(context: Context) : AutoCloseable {
    data class HotspotInfo(
        val ssid: String,
        val password: String,
        val customConfigurationApplied: Boolean
    )

    interface Callback {
        fun onStarted(info: HotspotInfo)
        fun onStopped()
        fun onFailed(reason: String)
    }

    private val wifiManager = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> mainHandler.post(command) }

    @Volatile
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    @Volatile
    var state: HotspotState = HotspotState.Stopped
        private set

    fun isRunning(): Boolean = reservation != null

    /**
     * Custom SSID/passphrase needs both the API-36 start method and the
     * SoftApConfiguration setters. Those setters arrived in the Android 16
     * 36.1 SDK, so checking only SDK_INT >= 36 is not sufficient.
     */
    fun supportsCustomConfiguration(): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        return runCatching {
            val builderClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
            builderClass.getMethod("setWifiSsid", WifiSsid::class.java)
            builderClass.getMethod("setPassphrase", String::class.java, Integer.TYPE)
            wifiManager.javaClass.methods.firstOrNull {
                it.name == "startLocalOnlyHotspotWithConfiguration" && it.parameterTypes.size == 3
            } ?: return@runCatching false
            true
        }.getOrDefault(false)
    }

    /**
     * Starts LocalOnlyHotspot.
     * Android 16 builds exposing the 36.1 configuration setters can apply the requested SSID/password.
     * Android 15/API 35 and below can only use framework-generated credentials.
     */
    fun start(config: HotspotConfig?, callback: Callback) {
        if (reservation != null) {
            val running = state
            if (running is HotspotState.Running) {
                callback.onStarted(
                    HotspotInfo(
                        ssid = running.ssid,
                        password = running.password,
                        customConfigurationApplied = false
                    )
                )
            } else {
                callback.onFailed("Hotspot is starting")
            }
            return
        }

        state = HotspotState.Starting
        try {
            if (config != null && supportsCustomConfiguration()) {
                startWithCustomConfigurationApi36(config, callback)
            } else {
                startFrameworkConfigured(callback)
            }
        } catch (security: SecurityException) {
            reservation = null
            val message = "Missing Wi-Fi/Nearby permission: ${security.message.orEmpty()}".trim()
            state = HotspotState.Error(message)
            callback.onFailed(message)
        } catch (t: Throwable) {
            reservation = null
            val message = t.message ?: t.javaClass.simpleName
            state = HotspotState.Error(message)
            callback.onFailed(message)
        }
    }

    /** Backward-compatible call: always lets Android choose SSID/password. */
    fun start(callback: Callback) = start(null, callback)

    private fun startFrameworkConfigured(callback: Callback) {
        wifiManager.startLocalOnlyHotspot(
            callbackAdapter(callback, customConfigurationApplied = false, requestedConfig = null),
            mainHandler
        )
    }

    /**
     * Kept reflective so this project can still compile with compileSdk 35 while
     * using Android 16's public startLocalOnlyHotspotWithConfiguration API at runtime.
     */
    private fun startWithCustomConfigurationApi36(
        config: HotspotConfig,
        callback: Callback
    ) {
        val validation = config.validate()
        if (validation != null) {
            state = HotspotState.Error(validation)
            callback.onFailed(validation)
            return
        }

        val builderClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
        val builder = builderClass.getConstructor().newInstance()
        val wifiSsid = WifiSsid.fromBytes(config.ssid.trim().toByteArray(Charsets.UTF_8))

        builderClass
            .getMethod("setWifiSsid", WifiSsid::class.java)
            .invoke(builder, wifiSsid)

        builderClass
            .getMethod("setPassphrase", String::class.java, Integer.TYPE)
            .invoke(builder, config.password, SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)

        val softApConfig = builderClass.getMethod("build").invoke(builder)
        val method = wifiManager.javaClass.methods.firstOrNull {
            it.name == "startLocalOnlyHotspotWithConfiguration" && it.parameterTypes.size == 3
        } ?: throw UnsupportedOperationException(
            "This Android 16 device does not expose configurable hotspot API support"
        )

        method.invoke(
            wifiManager,
            softApConfig,
            mainExecutor,
            callbackAdapter(
                callback,
                customConfigurationApplied = true,
                requestedConfig = config
            )
        )
    }

    private fun callbackAdapter(
        callback: Callback,
        customConfigurationApplied: Boolean,
        requestedConfig: HotspotConfig? = null
    ): WifiManager.LocalOnlyHotspotCallback {
        return object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(value: WifiManager.LocalOnlyHotspotReservation) {
                reservation = value
                val rawCredentials = readCredentials(value, customConfigurationApplied)
                if (rawCredentials == null || rawCredentials.ssid.isBlank()) {
                    value.close()
                    reservation = null
                    state = HotspotState.Error("Android did not return the LocalOnlyHotspot SSID")
                    callback.onFailed("Unable to read hotspot SSID/password")
                    return
                }
                val exactCustomMatch = requestedConfig != null &&
                    rawCredentials.ssid == requestedConfig.ssid.trim() &&
                    rawCredentials.password == requestedConfig.password
                val credentials = rawCredentials.copy(
                    customConfigurationApplied = customConfigurationApplied && exactCustomMatch
                )
                state = HotspotState.Running(credentials.ssid, credentials.password)
                callback.onStarted(credentials)
            }

            override fun onStopped() {
                reservation = null
                state = HotspotState.Stopped
                callback.onStopped()
            }

            override fun onFailed(reason: Int) {
                reservation = null
                val message = failureText(reason)
                state = HotspotState.Error(message)
                callback.onFailed(message)
            }
        }
    }

    fun stop() {
        val current = reservation
        reservation = null
        if (current != null) {
            runCatching { current.close() }
        }
        state = HotspotState.Stopped
    }

    override fun close() = stop()

    private fun readCredentials(
        value: WifiManager.LocalOnlyHotspotReservation,
        customConfigurationApplied: Boolean
    ): HotspotInfo? {
        return if (Build.VERSION.SDK_INT >= 30) {
            val config = value.softApConfiguration
            HotspotInfo(
                ssid = config.ssid.orEmpty(),
                password = config.passphrase.orEmpty(),
                customConfigurationApplied = customConfigurationApplied
            )
        } else {
            @Suppress("DEPRECATION")
            val config = value.wifiConfiguration ?: return null
            @Suppress("DEPRECATION")
            HotspotInfo(
                ssid = config.SSID.orEmpty().trim('"'),
                password = config.preSharedKey.orEmpty().trim('"'),
                customConfigurationApplied = false
            )
        }
    }

    private fun failureText(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "No Wi-Fi channel is available"
        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC -> "Android could not create LocalOnlyHotspot"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "Wi-Fi is in a mode incompatible with hotspot"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "Device/system policy does not allow hotspot"
        else -> "Unable to create hotspot (reason=$reason)"
    }
}
