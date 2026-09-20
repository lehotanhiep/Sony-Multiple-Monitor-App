package com.example.sonymultilive

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Repeatedly runs the same Sony SSDP + Wi-Fi/AP LAN discovery used by the SCAN button while
 * the phone hotspot is active. It is deliberately separate from SonyCameraSession: this class
 * only discovers hosts; camera transport remains Sony PTP/IP.
 */
class HotspotCameraAutoDiscovery(context: Context) : AutoCloseable {
    interface Callback {
        fun onSearching(attempt: Int)
        fun onCamera(camera: SonyCamera)
        fun onCycleFinished(attempt: Int, foundThisCycle: Int, subnet: String)
        fun onError(message: String)
    }

    private val app = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)

    fun isRunning(): Boolean = running.get()

    fun start(maxCameras: Int, callback: Callback) {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            var attempt = 0
            val knownIds = linkedSetOf<String>()
            val knownHosts = linkedSetOf<String>()
            try {
                while (running.get() && knownIds.size < maxCameras) {
                    attempt++
                    callback.onSearching(attempt)
                    var foundThisCycle = 0

                    val result = runCatching {
                        SonyPtpDiscovery(app).discover(
                            maxCameras = maxCameras,
                            ssdpTimeoutMs = 1_600,
                            connectTimeoutMs = 120
                        )
                    }.getOrElse {
                        callback.onError(it.message ?: it.javaClass.simpleName)
                        null
                    }

                    if (result != null) {
                        result.cameras.forEach { camera ->
                            if (!running.get()) return@forEach
                            if (camera.id in knownIds || camera.host in knownHosts) return@forEach
                            knownIds += camera.id
                            if (camera.host.isNotBlank()) knownHosts += camera.host
                            foundThisCycle++
                            callback.onCamera(camera)
                        }
                        callback.onCycleFinished(attempt, foundThisCycle, result.stats.subnetLabel)
                    }

                    if (!running.get() || knownIds.size >= maxCameras) break
                    // DHCP + Sony remote services can appear several seconds after association.
                    var waited = 0L
                    while (running.get() && waited < 2_500L) {
                        Thread.sleep(250L)
                        waited += 250L
                    }
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (t: Throwable) {
                if (running.get()) callback.onError(t.message ?: t.javaClass.simpleName)
            } finally {
                running.set(false)
            }
        }
    }

    fun stop() {
        running.set(false)
    }

    override fun close() {
        running.set(false)
        executor.shutdownNow()
    }
}
