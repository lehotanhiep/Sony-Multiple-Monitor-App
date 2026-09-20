package com.example.sonymultilive

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Sony Multiple Monitor wrapper around the exact Sony ZV-1 Sony PTP/IP Live View stack.
 *
 * There is intentionally no ScalarWebAPI, HTTP LiveView, SSH tunnel, SDK-2.x,
 * legacy packet parser, D278 path, or alternate PTP controller in this class.
 * Sony Multiple Monitor owns lifecycle/UI; all camera transport is Sony PTP/IP.
 */
class SonyCameraSession(
    context: Context,
    val camera: SonyCamera,
    private val executor: Executor,
    private val onJpeg: (ByteArray) -> Unit,
    private val onState: (String) -> Unit,
    private val onStats: (LiveViewStats) -> Unit,
    private val onTelemetry: (CameraTelemetry) -> Unit = {},
    private val onNeedsAuth: () -> Unit = {}
) : AutoCloseable {
    private val app = context.applicationContext
    private val stopped = AtomicBoolean(true)
    private val generation = AtomicInteger(0)
    private val settingsRefreshQueued = AtomicBoolean(false)
    private val lastSettingsRefreshMs = AtomicLong(0L)
    private val c203EventCount = AtomicLong(0L)
    private val c203AppliedCount = AtomicLong(0L)
    private val c203BulkRefreshCount = AtomicLong(0L)
    private val c203LastProperty = AtomicInteger(0)
    private val c203LastEventMs = AtomicLong(0L)
    private val pendingPropertyReads = ConcurrentLinkedQueue<Int>()
    private val queuedPropertyCodes = ConcurrentHashMap.newKeySet<Int>()
    private val failedAutoSyncCodes = ConcurrentHashMap<Int, AtomicInteger>()
    private val lastAutoPropertyReadMs = AtomicLong(0L)
    private val lastD21dHeartbeatQueueMs = AtomicLong(0L)
    private val lastEvHeartbeatQueueMs = AtomicLong(0L)
    private val lastC203BulkSnapshotMs = AtomicLong(0L)
    private val urgentPropertyCode = AtomicInteger(0)
    private val propertyUpdatedAtMs = ConcurrentHashMap<Int, Long>()

    @Volatile private var client: SonyPtpIpClient? = null
    @Volatile private var controller: SonyPtpLiveViewController? = null
    @Volatile private var liveViewProfile = LiveViewProfile.forCameraCount(1)
    @Volatile private var sourceHighRequested = false
    @Volatile private var movieRecording = false
    @Volatile private var pendingMovieRecordingRequest: Boolean? = null
    @Volatile private var pendingMovieRecordingRequestAtMs: Long = 0L
    @Volatile private var pendingRecordTakeNumber: Int = 0
    @Volatile private var recordStartedElapsedMs: Long? = null
    @Volatile private var recordTakeNumber: Int = 0
    @Volatile private var telemetry = CameraTelemetry()
    @Volatile private var propertyCache: Map<Int, SonyPtpLiveViewController.ExtPropSnapshot> = emptyMap()
    @Volatile private var playbackRequested = false
    @Volatile private var moviePlaybackPlaying = false
    @Volatile private var playbackExitOnReconnect = false
    @Volatile private var playbackExitCallback: ((Boolean) -> Unit)? = null

    fun start() = startPtpSession()

    fun retryDirectPtp() {
        // Once the user intentionally sends the camera to Playback, loss of the
        // FFFFC002 stream is expected. Do not let the reconnect watchdog undo the
        // mode change by reopening Remote Live View.
        if (playbackRequested) return
        startPtpSession()
    }

    fun isPlaybackRequested(): Boolean = playbackRequested
    fun isMoviePlaybackPlaying(): Boolean = moviePlaybackPlaying

    /** Explicit user reconnect exits the intentional Playback hold and restores Live View. */
    fun resumeLiveView() {
        playbackRequested = false
        moviePlaybackPlaying = false
        playbackExitOnReconnect = false
        playbackExitCallback = null
        startPtpSession()
    }

    private fun startPtpSession() {
        val myGeneration = generation.incrementAndGet()
        stopped.set(false)
        pendingPropertyReads.clear()
        queuedPropertyCodes.clear()
        failedAutoSyncCodes.clear()
        lastAutoPropertyReadMs.set(0L)
        lastD21dHeartbeatQueueMs.set(0L)
        lastEvHeartbeatQueueMs.set(0L)
        lastC203BulkSnapshotMs.set(0L)
        urgentPropertyCode.set(0)
        propertyUpdatedAtMs.clear()
        c203EventCount.set(0L)
        c203AppliedCount.set(0L)
        c203BulkRefreshCount.set(0L)
        c203LastProperty.set(0)
        c203LastEventMs.set(0L)
        closeTransport()
        executor.execute {
            if (!isCurrent(myGeneration)) return@execute
            try {
                publish("SONY PTP/IP · WAIT PTP/IP 15740")
                val network = SonyNetworkRoute.findWifiNetworkForHost(app, camera.host, 900)
                val tetherRoute = if (network == null) TetheringNetworkRoute.findForHost(app, camera.host) else null
                val bindSocket: (Socket) -> Unit = { socket ->
                    if (network != null) network.bindSocket(socket) else tetherRoute?.bind(socket)
                }
                if (network != null) {
                    publish("SONY PTP/IP · WIFI/AP ROUTE · ${SonyNetworkRoute.describe(app, camera.host, network)}")
                } else if (tetherRoute != null) {
                    publish("SONY PTP/IP · TETHER/AP ROUTE · ${tetherRoute.label}")
                }
                if (!waitForPtpPort(camera.host, bindSocket, myGeneration)) {
                    if (isCurrent(myGeneration)) publish("CAMERA DISCONNECTED · PTP 15740 CLOSED")
                    return@execute
                }

                val ptp = SonyPtpIpClient(
                    host = camera.host,
                    initiatorGuid = loadOrCreateImagingEdgeGuid(),
                    initiatorName = Build.MODEL.ifBlank { "Android" },
                    socketBinder = bindSocket,
                    onEvent = { event -> handlePtpEvent(event, myGeneration) }
                )
                client = ptp

                publish("SONY PTP/IP · INIT COMMAND / EVENT")
                ptp.connect(5000)
                if (!isCurrent(myGeneration)) return@execute

                val ctrl = SonyPtpLiveViewController(ptp) { msg ->
                    if (isCurrent(myGeneration) && (
                            msg.startsWith("OpenSession") ||
                            msg.startsWith("SDIO_Connect") ||
                            msg.startsWith("0x9209 readiness") ||
                            msg.startsWith("GetObjectInfo")
                        )) {
                        publish("SONY PTP/IP · $msg")
                    }
                }
                controller = ctrl

                publish("SONY PTP/IP · SONY SDIO HANDSHAKE")
                ctrl.initialize()
                if (!isCurrent(myGeneration)) return@execute

                // Reuse the property table already captured by the existing 10x 0x9209
                // warm-up. The only optional transaction before FFFFC002 prime is the
                // user-selected D26A source-quality write below.
                applySonyPropertySnapshot(ctrl.cachedExtDevicePropInfo())

                // If the user pressed PLAYBACK a second time after the camera had
                // already torn down the old live-view transport, reconnect only far
                // enough to emulate the physical Playback key again. That key is the
                // requested exit action; after it is accepted continue into normal
                // Remote Live View startup.
                if (playbackExitOnReconnect) {
                    publish("PLAYBACK · EXIT KEY")
                    val exited = ctrl.pressPlaybackModeButton()
                    if (!exited) throw IOException("camera rejected Playback exit key")
                    playbackExitOnReconnect = false
                    playbackRequested = false
                    moviePlaybackPlaying = false
                    playbackExitCallback?.invoke(true)
                    playbackExitCallback = null
                    Thread.sleep(160L)
                }

                // RES LOW/HIGH changes the SOURCE JPEG generated by the camera. The
                // quality write is done only on this fresh PTP session, before FFFFC002
                // is primed. Never write D26A while GetObject is already streaming.
                val sourceHigh = sourceHighRequested
                publish(if (sourceHigh) "SONY PTP/IP · SOURCE HIGH · D26A=2" else "SONY PTP/IP · SOURCE LOW · D26A=1")
                val sourceApplied = ctrl.applyLiveViewSourceQuality(high = sourceHigh)
                if (!sourceApplied) {
                    publish("SONY PTP/IP · SOURCE QUALITY UNSUPPORTED · CAMERA NATIVE")
                }
                if (!isCurrent(myGeneration)) return@execute

                publish(if (sourceHigh) "SONY PTP/IP · PRIME FFFFC002 · SOURCE HIGH" else "SONY PTP/IP · PRIME FFFFC002 · SOURCE LOW")
                ctrl.primeLiveViewObject()
                if (!isCurrent(myGeneration)) return@execute

                publish("SONY PTP/IP · LIVE · FAST PULL · LOW LATENCY")
                onTelemetry(telemetry.copy(recording = movieRecording))
                streamFrames(ctrl, myGeneration)
            } catch (t: Throwable) {
                if (isCurrent(myGeneration)) {
                    if (playbackExitOnReconnect) {
                        playbackExitOnReconnect = false
                        playbackExitCallback?.invoke(false)
                        playbackExitCallback = null
                        // Keep the logical Playback hold, but discard this failed
                        // transport. A second PLAYBACK press can then reopen PTP cleanly.
                        closeTransport()
                        publish("PLAYBACK EXIT ERROR · ${t.message ?: t.javaClass.simpleName}")
                    } else if (playbackRequested) {
                        publish("PLAYBACK · CAMERA MODE")
                    } else {
                        publish("CAMERA DISCONNECTED · SONY PTP/IP · ${t.javaClass.simpleName}: ${t.message ?: "transport error"}")
                    }
                }
            } finally {
                // Keep the PTP command/event sockets alive while intentionally in
                // Playback. PLAY / PAUSE and the second PLAYBACK press then use the
                // same camera session. A failed reconnect-based exit is not preserved.
                if (generation.get() == myGeneration && !(playbackRequested && !playbackExitOnReconnect)) {
                    closeTransport()
                }
            }
        }
    }

    private fun streamFrames(ctrl: SonyPtpLiveViewController, myGeneration: Int) {
        // Keep the camera-read loop on a high-priority worker. Decode/render already run
        // on separate executors, so this thread can return to GetObject(FFFFC002) as soon
        // as the previous PTP transaction completes.
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }

        var frames = 0L
        var statFrames = 0
        var statBytes = 0L
        var statStart = System.nanoTime()
        var consecutiveErrors = 0

        while (isCurrent(myGeneration)) {
            try {
                val jpeg = ctrl.getLiveViewJpegFast()
                consecutiveErrors = 0
                frames++
                statFrames++
                statBytes += jpeg.size

                // Non-blocking handoff: CameraTileView keeps only the newest pending JPEG,
                // so decoding can never stall the camera transport loop.
                onJpeg(jpeg)
                if (frames == 1L) publish("SONY PTP/IP · LIVE · FIRST FRAME · FAST PULL")

                // Event-driven setting sync only. There is no timer/bulk 0x9209 polling.
                // If the camera reports a property change on the event socket, read at most
                // one changed property between complete JPEG transactions. D21D is treated
                // as a Sony heartbeat; REC confirmation is prioritized and EV telemetry is
                // rate-limited so GetObject(FFFFC002) remains the dominant transaction.
                maybeSyncOneChangedProperty(ctrl, myGeneration)

                val now = System.nanoTime()
                val elapsed = (now - statStart) / 1_000_000_000.0
                if (elapsed >= 1.0) {
                    val fps = statFrames / elapsed
                    val kbps = statBytes * 8.0 / 1000.0 / elapsed
                    onStats(LiveViewStats(fps, kbps))
                    // Do not publish a per-second source-FPS string to the UI. The visible
                    // OSD reports only the post-interpolation presentation rate.
                    statFrames = 0
                    statBytes = 0L
                    statStart = now
                }
            } catch (t: Throwable) {
                // Entering Playback intentionally makes FFFFC002 unavailable. Stop
                // pulling immediately but preserve the command transport so PLAY,
                // PAUSE and the second PLAYBACK press remain usable.
                if (playbackRequested) return
                consecutiveErrors++
                if (consecutiveErrors >= 8) throw t
                // Short recovery delay only for actual transport/PTP errors. Normal
                // DeviceBusy/AccessDenied pacing is handled inside getLiveViewJpegFast().
                Thread.sleep(20)
            }
        }
    }

    /**
     * Sony vendor event 0xC203 is PropertyChanged, but several Sony bodies also
     * emit C203(D21D) periodically as a status heartbeat while remote live view is
     * active. Treat D21D accordingly: it is not proof that recording changed, it
     * is only a safe trigger to re-read the current MovieRecordingState.
     *
     * The same heartbeat is used to sample the EV source at a low rate:
     *   - an automatic exposure axis -> 0x5010 Exposure Compensation
     *   - full Manual               -> 0xD1B5 Metered Manual Level
     *
     * Dynamic REC/EV values use a throttled 0x9209 refresh only after C203; no reconnect is performed.
     */
    private fun handlePtpEvent(event: SonyPtpIpClient.PtpEvent, myGeneration: Int) {
        if (!isCurrent(myGeneration)) return
        if (event.code != 0xC203 && event.code != 0x4006) return
        val code = event.params.firstOrNull() ?: return
        val now = SystemClock.elapsedRealtime()
        if (event.code == 0xC203) c203EventCount.incrementAndGet()
        c203LastProperty.set(code)
        c203LastEventMs.set(now)

        if (code == SonyPtpLiveViewController.PROP_MOVIE_RECORDING_STATE) {
            // D21D can be a high-rate Sony heartbeat. While a REC/STOP request is
            // pending, make the status read urgent so the UI is confirmed by the
            // camera rather than by the button press. Outside a pending request,
            // sample at most once per second so an on-camera REC press is still seen.
            val pending = pendingMovieRecordingRequest != null
            val last = lastD21dHeartbeatQueueMs.get()
            if (pending || now - last >= 1_000L) {
                lastD21dHeartbeatQueueMs.set(now)
                if (pending) {
                    urgentPropertyCode.set(SonyPtpLiveViewController.PROP_MOVIE_RECORDING_STATE)
                } else {
                    queueChangedProperty(SonyPtpLiveViewController.PROP_MOVIE_RECORDING_STATE)
                }
            }

            // Use the existing Sony heartbeat as the clock for EV telemetry instead
            // of adding a timer. One EV read every ~900 ms is enough for the monitor
            // while keeping FFFFC002 traffic dominant on the command socket.
            val evLast = lastEvHeartbeatQueueMs.get()
            if (now - evLast >= 900L) {
                lastEvHeartbeatQueueMs.set(now)
                val evCode = if (isExposureCompensationAdjustable()) {
                    0x5010
                } else {
                    SonyPtpLiveViewController.PROP_METERED_MANUAL_LEVEL
                }
                queueChangedProperty(evCode)
            }
            return
        }

        if (!isAutoSyncProperty(code)) return

        // D1B5 can fire often as the meter moves. do not discard it;
        // just throttle it to the same low-rate EV telemetry cadence.
        if (code == SonyPtpLiveViewController.PROP_METERED_MANUAL_LEVEL) {
            val last = lastEvHeartbeatQueueMs.get()
            if (now - last < 900L) return
            lastEvHeartbeatQueueMs.set(now)
        }
        queueChangedProperty(code)
    }

    private fun queueChangedProperty(code: Int) {
        val dynamic = code == SonyPtpLiveViewController.PROP_MOVIE_RECORDING_STATE ||
            code == SonyPtpLiveViewController.PROP_METERED_MANUAL_LEVEL ||
            code == 0x5010
        // D21D/D1B5/5010 may be omitted from a body's initial 0x9209 descriptor
        // table even though direct reads are supported. Never discard their C203
        // events just because the warm-up cache lacks an entry.
        if (!dynamic && !propertyCache.containsKey(code)) return
        val failures = failedAutoSyncCodes[code]?.get() ?: 0
        // Dynamic status must never become permanently disabled because one Sony
        // read opcode is temporarily rejected while the body is busy.
        if (!dynamic && failures >= 3) return
        if (queuedPropertyCodes.add(code)) pendingPropertyReads.offer(code)
    }

    private fun maybeSyncOneChangedProperty(ctrl: SonyPtpLiveViewController, myGeneration: Int) {
        if (!isCurrent(myGeneration)) return
        val now = SystemClock.elapsedRealtime()
        // A pending REC/STOP confirmation gets priority over ordinary setting sync.
        val urgent = urgentPropertyCode.getAndSet(0)
        val code = if (urgent != 0) {
            queuedPropertyCodes.remove(urgent)
            pendingPropertyReads.remove(urgent)
            urgent
        } else {
            if (now - lastAutoPropertyReadMs.get() < 250L) return
            val next = pendingPropertyReads.poll() ?: return
            queuedPropertyCodes.remove(next)
            next
        }
        lastAutoPropertyReadMs.set(now)

        val dynamicStatus = code == SonyPtpLiveViewController.PROP_MOVIE_RECORDING_STATE ||
            code == SonyPtpLiveViewController.PROP_METERED_MANUAL_LEVEL || code == 0x5010
        if (dynamicStatus) {
            // On the tested ZV-1 / ZV-E10M2 pair, C203 is reliable but an isolated
            // Sony 0x9204 read can remain stale while FFFFC002 Live View is active.
            // Sony/libgphoto2 refreshes the 0x9209 property dataset after C203, so do
            // the same here, event-triggered and throttled (never periodic/reconnect).
            val minGapMs = if (pendingMovieRecordingRequest != null) 180L else 700L
            val previousBulk = lastC203BulkSnapshotMs.get()
            if (now - previousBulk < minGapMs) return
            lastC203BulkSnapshotMs.set(now)
            try {
                val fresh = ctrl.refreshExtDevicePropInfo()
                if (fresh.isNullOrEmpty()) {
                    failedAutoSyncCodes.computeIfAbsent(code) { AtomicInteger(0) }.incrementAndGet()
                    return
                }
                applySonyPropertySnapshot(fresh)
                failedAutoSyncCodes.remove(code)
                c203BulkRefreshCount.incrementAndGet()
                c203AppliedCount.incrementAndGet()
            } catch (_: Throwable) {
                failedAutoSyncCodes.computeIfAbsent(code) { AtomicInteger(0) }.incrementAndGet()
            }
            return
        }

        val snapshot = propertyCache[code] ?: syntheticDynamicSnapshot(code) ?: return
        try {
            val value = ctrl.readCachedNumericProperty(code) ?: run {
                failedAutoSyncCodes.computeIfAbsent(code) { AtomicInteger(0) }.incrementAndGet()
                return
            }
            failedAutoSyncCodes.remove(code)
            val updated = snapshot.copy(currentValue = value)
            propertyCache = propertyCache.toMutableMap().apply { put(code, updated) }
            applySonyPropertySnapshot(mapOf(code to updated))
            c203AppliedCount.incrementAndGet()
        } catch (_: Throwable) {
            // A failed telemetry read must never terminate the Live View generation.
            failedAutoSyncCodes.computeIfAbsent(code) { AtomicInteger(0) }.incrementAndGet()
        }
    }

    private fun syntheticDynamicSnapshot(code: Int): SonyPtpLiveViewController.ExtPropSnapshot? = when (code) {
        SonyPtpLiveViewController.PROP_MOVIE_RECORDING_STATE ->
            SonyPtpLiveViewController.ExtPropSnapshot(code = code, dataType = 0x0002) // UINT8
        SonyPtpLiveViewController.PROP_METERED_MANUAL_LEVEL,
        0x5010 -> SonyPtpLiveViewController.ExtPropSnapshot(code = code, dataType = 0x0003) // INT16
        else -> null
    }

    private fun isAutoSyncProperty(code: Int): Boolean = when (code) {
        0x5005, 0x5007, 0x500A, 0x500D, 0x500E, 0x500F, 0x5010,
        0xD00C, 0xD016, 0xD017, 0xD022, 0xD023, 0xD03C, 0xD0E1, 0xD0FA,
        0xD20D, 0xD20F, 0xD21D, 0xD21E, 0xD226, 0xD23F, 0xD286,
        SonyPtpLiveViewController.PROP_METERED_MANUAL_LEVEL,
        SonyPtpLiveViewController.PROP_BATTERY_REMAIN,
        SonyPtpLiveViewController.PROP_TOTAL_BATTERY_REMAIN,
        SonyPtpLiveViewController.PROP_MEDIA_SLOT1_REMAINING_TIME,
        SonyPtpLiveViewController.PROP_MEDIA_SLOT2_REMAINING_TIME -> true
        else -> false
    }

    private fun waitForPtpPort(host: String, socketBinder: (Socket) -> Unit, myGeneration: Int): Boolean {
        repeat(12) {
            if (!isCurrent(myGeneration)) return false
            val open = runCatching {
                Socket().use { socket ->
                    socketBinder(socket)
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(host, 15740), 450)
                }
                true
            }.getOrDefault(false)
            if (open) return true
            Thread.sleep(500)
        }
        return false
    }

    private fun loadOrCreateImagingEdgeGuid(): ByteArray {
        val prefs = app.getSharedPreferences("imaging_edge_probe", Context.MODE_PRIVATE)
        val raw = prefs.getString("ptp_uuid", null)
        val uuid = runCatching { raw?.let(UUID::fromString) }.getOrNull() ?: UUID.randomUUID().also {
            prefs.edit().putString("ptp_uuid", it.toString()).apply()
        }
        return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()
    }

    private fun isCurrent(myGeneration: Int): Boolean =
        !stopped.get() && generation.get() == myGeneration

    private fun publish(text: String) = onState(text)

    private fun closeTransport() {
        val ctrl = controller
        controller = null
        client = null
        propertyCache = emptyMap()
        runCatching { ctrl?.close() }
    }

    override fun close() {
        stopped.set(true)
        generation.incrementAndGet()
        closeTransport()
    }

    // ---- Camera information + settings over the SAME Sony PTP/IP session ----

    fun setLiveViewProfile(profile: LiveViewProfile) {
        liveViewProfile = profile
        sourceHighRequested = profile.modernJpegQuality == LiveViewProfile.QUALITY_HIGH
    }
    fun currentLiveViewProfile(): LiveViewProfile = liveViewProfile

    fun cameraPtpInfoSummary(): String {
        val info = controller?.deviceInfo
        val propCount = propertyCache.size
        if (info == null) return if (propCount > 0) "PTP properties=$propCount" else ""
        return buildString {
            if (info.manufacturer.isNotBlank()) append(info.manufacturer)
            if (info.model.isNotBlank()) { if (isNotEmpty()) append(" · "); append(info.model) }
            if (info.deviceVersion.isNotBlank()) append(" · FW ").append(info.deviceVersion)
            if (info.serialNumber.isNotBlank()) append(" · S/N ").append(info.serialNumber)
            append(" · props=").append(propCount)
        }
    }

    fun setLowResolutionMode(low: Boolean) {
        val newHigh = !low
        if (sourceHighRequested == newHigh) return
        sourceHighRequested = newHigh
        liveViewProfile = liveViewProfile.copy(
            width = if (low) 640 else 1024,
            height = if (low) 360 else 576,
            modernJpegQuality = if (low) LiveViewProfile.QUALITY_LOW else LiveViewProfile.QUALITY_HIGH,
            label = if (low) "SOURCE LOW 640-class" else "SOURCE HIGH 1024-class"
        )

        // A quality switch is a transport restart by design: stop the old generation,
        // let its GetObject loop exit/close, then establish a clean PTP session and
        // apply D26A before the new FFFFC002 prime.
        if (!stopped.get()) {
            publish(if (low) "RES LOW · RECONNECTING SOURCE" else "RES HIGH · RECONNECTING SOURCE")
            startPtpSession()
        }
    }
    fun isMovieRecording(): Boolean = movieRecording

    fun enterPlaybackMode(onResult: (Boolean) -> Unit = {}) {
        if (playbackRequested) {
            onResult(true)
            return
        }
        // Set this before the command so the reconnect coordinator knows that a
        // disappearing Live View is intentional, not a network fault.
        playbackRequested = true
        moviePlaybackPlaying = false
        executor.execute {
            val ctrl = controller
            if (ctrl == null) {
                playbackRequested = false
                publish("PLAYBACK · PTP NOT READY")
                onResult(false)
                return@execute
            }
            try {
                val ok = ctrl.enterPlaybackMode()
                if (!ok) throw IOException("camera does not accept Playback button")
                publish("PLAYBACK · COMMAND SENT")
                onResult(true)
            } catch (t: Throwable) {
                playbackRequested = false
                moviePlaybackPlaying = false
                publish("PLAYBACK ERROR · ${t.message ?: t.javaClass.simpleName}")
                onResult(false)
            }
        }
    }

    /** Pressing PLAYBACK again leaves Playback and restores Remote Live View. */
    fun exitPlaybackMode(onResult: (Boolean) -> Unit = {}) {
        if (!playbackRequested) {
            onResult(true)
            return
        }
        executor.execute {
            val ctrl = controller
            if (ctrl != null) {
                try {
                    val ok = ctrl.pressPlaybackModeButton()
                    if (ok) {
                        playbackRequested = false
                        moviePlaybackPlaying = false
                        publish("PLAYBACK · EXIT · RECONNECT LIVE")
                        onResult(true)
                        startPtpSession()
                        return@execute
                    }
                } catch (_: Throwable) {
                    // Fall through to a clean reconnect and issue the same physical
                    // Playback key on the newly opened PTP command session.
                }
            }

            playbackExitOnReconnect = true
            playbackExitCallback = onResult
            publish("PLAYBACK · EXIT · REOPEN PTP")
            startPtpSession()
        }
    }

    fun playbackPlay(onResult: (Boolean) -> Unit = {}) {
        if (!playbackRequested) {
            onResult(false)
            return
        }
        executor.execute {
            val ctrl = controller
            if (ctrl == null) {
                publish("PLAY · PTP NOT READY")
                onResult(false)
                return@execute
            }
            try {
                val ok = ctrl.moviePlaybackPlay()
                if (ok) moviePlaybackPlaying = true
                publish(if (ok) "PLAYBACK · PLAY" else "PLAYBACK · PLAY UNSUPPORTED")
                onResult(ok)
            } catch (t: Throwable) {
                publish("PLAY ERROR · ${t.message ?: t.javaClass.simpleName}")
                onResult(false)
            }
        }
    }

    fun playbackPause(onResult: (Boolean) -> Unit = {}) {
        if (!playbackRequested) {
            onResult(false)
            return
        }
        executor.execute {
            val ctrl = controller
            if (ctrl == null) {
                publish("PAUSE · PTP NOT READY")
                onResult(false)
                return@execute
            }
            try {
                val ok = ctrl.moviePlaybackPause()
                if (ok) moviePlaybackPlaying = false
                publish(if (ok) "PLAYBACK · PAUSE" else "PLAYBACK · PAUSE UNSUPPORTED")
                onResult(ok)
            } catch (t: Throwable) {
                publish("PAUSE ERROR · ${t.message ?: t.javaClass.simpleName}")
                onResult(false)
            }
        }
    }

    fun playbackPrevious(onResult: (Boolean) -> Unit = {}) {
        if (!playbackRequested) {
            onResult(false)
            return
        }
        executor.execute {
            val ctrl = controller
            if (ctrl == null) {
                publish("PREVIOUS · PTP NOT READY")
                onResult(false)
                return@execute
            }
            try {
                val ok = ctrl.moviePlaybackPrevious()
                if (ok) moviePlaybackPlaying = false
                publish(if (ok) "PLAYBACK · PREVIOUS FILE" else "PLAYBACK · PREVIOUS UNSUPPORTED")
                onResult(ok)
            } catch (t: Throwable) {
                publish("PREVIOUS ERROR · ${t.message ?: t.javaClass.simpleName}")
                onResult(false)
            }
        }
    }

    fun playbackNext(onResult: (Boolean) -> Unit = {}) {
        if (!playbackRequested) {
            onResult(false)
            return
        }
        executor.execute {
            val ctrl = controller
            if (ctrl == null) {
                publish("NEXT · PTP NOT READY")
                onResult(false)
                return@execute
            }
            try {
                val ok = ctrl.moviePlaybackNext()
                if (ok) moviePlaybackPlaying = false
                publish(if (ok) "PLAYBACK · NEXT FILE" else "PLAYBACK · NEXT UNSUPPORTED")
                onResult(ok)
            } catch (t: Throwable) {
                publish("NEXT ERROR · ${t.message ?: t.javaClass.simpleName}")
                onResult(false)
            }
        }
    }

    fun pulseAutofocus() {
        runPtpControl("AF") { it.pulseAutofocus() }
    }

    fun setTouchFocus(normalizedX: Double, normalizedY: Double, onResult: (Boolean) -> Unit = {}) {
        executor.execute {
            val ctrl = controller
            if (ctrl == null) {
                onResult(false)
                publish("TOUCH AF · PTP NOT READY")
                return@execute
            }
            try {
                val ok = ctrl.setTouchFocus(normalizedX, normalizedY)
                onResult(ok)
                if (!ok) throw IOException("camera rejected touch focus")
                publish("SONY PTP/IP · TOUCH AF OK")
            } catch (t: Throwable) {
                onResult(false)
                publish("TOUCH AF ERROR · ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    fun cancelTouchFocus(onResult: (Boolean) -> Unit = {}) {
        executor.execute {
            val ctrl = controller
            if (ctrl == null) {
                onResult(false)
                publish("TOUCH AF CANCEL · PTP NOT READY")
                return@execute
            }
            try {
                val ok = ctrl.cancelTouchFocus()
                onResult(ok)
                if (!ok) throw IOException("camera rejected touch focus cancel")
                publish("SONY PTP/IP · TOUCH AF CANCEL OK")
            } catch (t: Throwable) {
                onResult(false)
                publish("TOUCH AF CANCEL ERROR · ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    fun setMovieRecording(recording: Boolean) {
        val take = if (recording) (recordTakeNumber + 1).coerceAtLeast(1) else recordTakeNumber
        setMovieRecordingAt(recording, SystemClock.elapsedRealtimeNanos(), take)
    }

    /**
     * Barrier-scheduled REC used by REC ALL. Every camera worker waits for the
     * same monotonic target before pressing Sony D2C8, minimizing multi-camera
     * start skew without changing the working Live View transport.
     */
    fun setMovieRecordingAt(recording: Boolean, targetElapsedNs: Long, takeNumber: Int) {
        // movieRecording is camera-confirmed state only. Do not optimistically
        // enter REC because Sony can accept the button press yet reject recording
        // (for example when no memory card is present).
        if (recording == movieRecording && pendingMovieRecordingRequest == null) return
        val nowMs = SystemClock.elapsedRealtime()
        if (pendingMovieRecordingRequest == recording && nowMs - pendingMovieRecordingRequestAtMs < 2_500L) return
        executor.execute {
            val ctrl = controller
            if (ctrl == null) {
                publish(if (recording) "REC · PTP NOT READY" else "STOP · PTP NOT READY")
                return@execute
            }
            try {
                waitUntilElapsedNs(targetElapsedNs)
                // Mark pending before sending D2C8 so a fast C203(D21D) arriving on
                // the event socket cannot race ahead of REC confirmation setup.
                pendingMovieRecordingRequest = recording
                pendingMovieRecordingRequestAtMs = SystemClock.elapsedRealtime()
                if (recording) pendingRecordTakeNumber = takeNumber.coerceAtLeast(1)
                val ok = ctrl.setMovieRecording(recording)
                if (!ok) throw IOException("camera rejected D2C8 ${if (recording) "REC" else "STOP"}")
                publish(if (recording) "REC COMMAND SENT" else "STOP COMMAND SENT")
            } catch (t: Throwable) {
                pendingMovieRecordingRequest = null
                pendingMovieRecordingRequestAtMs = 0L
                if (recording) pendingRecordTakeNumber = 0
                publish("${if (recording) "REC" else "STOP"} ERROR · ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun waitUntilElapsedNs(target: Long) {
        while (true) {
            val remain = target - SystemClock.elapsedRealtimeNanos()
            if (remain <= 0L) return
            when {
                remain > 8_000_000L -> Thread.sleep(((remain - 4_000_000L) / 1_000_000L).coerceAtLeast(1L))
                remain > 1_000_000L -> Thread.sleep(1L)
                else -> Thread.yield()
            }
        }
    }

    fun toggleMovieRecord() = setMovieRecording(!movieRecording)

    fun setIso(text: String) {
        val trimmed = text.trim()
        val selected = selectSettingSnapshot(CameraSettingKey.ISO)
        if (selected == null) {
            publish("ISO: camera did not publish an ISO property")
            return
        }
        val values = selected.second.enumValues
        val raw = if (trimmed.equals("AUTO", ignoreCase = true)) {
            values.firstOrNull { (it and 0x00ff_ffffL) == 0L || (it and 0x00ff_ffffL) == 0x00ff_ffffL }
                ?: 0x00ff_ffffL
        } else {
            val iso = trimmed.toLongOrNull() ?: throw IllegalArgumentException("ISO must be a number or AUTO")
            values.firstOrNull { (it and 0x00ff_ffffL) == iso } ?: iso
        }
        setCameraSetting(CameraSettingKey.ISO, raw)
    }

    fun setExposureCompensationLabel(label: String) {
        if (!isExposureCompensationAdjustable()) {
            publish("EV LOCKED · ISO / IRIS / SHUTTER ARE MANUAL")
            return
        }
        val text = label.trim().removePrefix("EV ").replace("+", "")
        val ev = text.toDoubleOrNull() ?: return
        val milli = (ev.coerceIn(-2.0, 2.0) * 1000.0).toLong()
        val raw = milli and 0xffffL
        setCameraSetting(CameraSettingKey.EXPOSURE_COMPENSATION, raw)
    }

    /** Apply one Sony 0x9209 table to the compact OSD and setting cache. */
    private fun applySonyPropertySnapshot(props: Map<Int, SonyPtpLiveViewController.ExtPropSnapshot>) {
        if (props.isEmpty()) return
        propertyCache = propertyCache.toMutableMap().apply { putAll(props) }
        val stamp = SystemClock.elapsedRealtime()
        props.keys.forEach { propertyUpdatedAtMs[it] = stamp }
        val cache = propertyCache
        fun n(code: Int): Long? = cache[code]?.currentValue
        fun latestCode(vararg codes: Int): Int? {
            var best: Int? = null
            var bestStamp = Long.MIN_VALUE
            for (code in codes) {
                if (cache[code]?.currentValue == null) continue
                val ts = propertyUpdatedAtMs[code] ?: 0L
                if (best == null || ts > bestStamp) {
                    best = code
                    bestStamp = ts
                }
            }
            return best
        }

        val focusRaw = n(0x500A)
        val focusMode = focusRaw?.let(SonyCameraValueFormatter::focus) ?: telemetry.focusMode
        val fRaw = n(0x5007)
        val iris = fRaw?.takeIf { it > 0 }?.let(SonyCameraValueFormatter::iris) ?: telemetry.iris
        val isoCode = latestCode(
            SonyPtpLiveViewController.PROP_ISO_ABSOLUTE,
            SonyPtpLiveViewController.PROP_ISO,
            0xD023, 0x500F
        )
        val isoRaw = isoCode?.let(::n)
        val eiRaw = n(0xD022)
        val iso = isoRaw?.let(SonyCameraValueFormatter::iso)
            ?: eiRaw?.let(SonyCameraValueFormatter::ei)
            ?: telemetry.iso
        val shutterRaw = latestCode(0xD20D, 0xD017, 0xD016, 0x500D)?.let(::n)
        val shutter = shutterRaw?.let(::formatSonyShutter) ?: telemetry.shutter
        val meteredRaw = n(SonyPtpLiveViewController.PROP_METERED_MANUAL_LEVEL)
        val compensationRaw = n(0x5010)
        val meteredLabel = meteredRaw?.let(SonyCameraValueFormatter::meteredManualLevel)
            ?.takeIf { it != "--" }
        val compensationLabel = compensationRaw?.let(SonyCameraValueFormatter::exposureCompensation)
        // EV is a control only while the exposure system has at least one automatic
        // axis. In full Manual it becomes a read-only D1B5 meter display. C203-driven
        // telemetry refreshes the active EV source at a throttled rate.
        val exposureCompensation = if (isExposureCompensationAdjustable(cache)) {
            compensationLabel ?: telemetry.exposureCompensation
        } else {
            meteredLabel ?: compensationLabel ?: telemetry.exposureCompensation
        }
        val wbRaw = latestCode(0x5005, 0xD00C)?.let(::n)
        val colorTemperatureRaw = n(0xD20F)
        val wb = wbRaw?.let { SonyCameraValueFormatter.whiteBalance(it, colorTemperatureRaw) } ?: telemetry.whiteBalance
        val lookCode = latestCode(0xD03C, 0xD0FA, 0xD23F)
        val lookRaw = lookCode?.let(::n)
        val look = if (lookCode != null && lookRaw != null) {
            SonyCameraValueFormatter.look(
                propertyCode = lookCode,
                raw = lookRaw,
                userLookNames = cameraUserLookNames(),
                pictureProfileGammaRaw = propertyCache[0xD0E1]?.currentValue,
                customCreativeBaseRaw = propertyCache[0xD103]?.currentValue
            )
        } else telemetry.look
        val frameRateRaw = n(0xD286)
        val frameRate = frameRateRaw?.let(::formatRecordFrameRate) ?: telemetry.recordFrameRate
        val recording = n(SonyPtpLiveViewController.PROP_MOVIE_RECORDING_STATE)?.let { it != 0L } ?: telemetry.recording

        // Sony status is already present in the same 0x9209 table captured by
        // Reuse the startup Sony PTP warm-up so BAT/CARD can populate without an extra query.
        val batteryPercent = latestCode(
            SonyPtpLiveViewController.PROP_BATTERY_REMAIN,
            SonyPtpLiveViewController.PROP_TOTAL_BATTERY_REMAIN
        )?.let(::n)?.takeIf { it in 0L..100L }?.toInt() ?: telemetry.batteryPercent
        val cardRemainingSeconds = latestCode(
            SonyPtpLiveViewController.PROP_MEDIA_SLOT1_REMAINING_TIME,
            SonyPtpLiveViewController.PROP_MEDIA_SLOT2_REMAINING_TIME
        )?.let(::n)?.takeIf { it in 0L..0xffff_fffeL } ?: telemetry.cardRemainingSeconds

        val previousRecording = movieRecording
        if (recording && !previousRecording) {
            // Start the UI REC clock only after the camera itself reports D21D=REC.
            recordStartedElapsedMs = SystemClock.elapsedRealtime()
            if (pendingRecordTakeNumber > 0) recordTakeNumber = pendingRecordTakeNumber
        } else if (!recording) {
            recordStartedElapsedMs = null
        }
        if (pendingMovieRecordingRequest == recording) {
            pendingMovieRecordingRequest = null
            pendingMovieRecordingRequestAtMs = 0L
            pendingRecordTakeNumber = 0
        }

        telemetry = telemetry.copy(
            focusMode = focusMode,
            iris = iris,
            iso = iso,
            shutter = shutter,
            exposureCompensation = exposureCompensation,
            evDisplayEnabled = isExposureCompensationAdjustable(cache),
            whiteBalance = wb,
            look = look,
            recordFrameRate = frameRate,
            recording = recording,
            batteryPercent = batteryPercent,
            cardRemainingSeconds = cardRemainingSeconds,
            recordStartedElapsedMs = recordStartedElapsedMs,
            recordTakeNumber = recordTakeNumber
        )
        movieRecording = recording
        onTelemetry(telemetry)
    }

    private fun applyCameraStatus(status: SonyPtpLiveViewController.CameraStatusSnapshot) {
        val primary = status.storage
            .filter { it.maxCapacityBytes > 0L || it.freeSpaceBytes > 0L }
            .maxByOrNull { it.maxCapacityBytes }
            ?: status.storage.firstOrNull()
        telemetry = telemetry.copy(
            batteryPercent = status.batteryPercent ?: telemetry.batteryPercent,
            cardRemainingSeconds = status.remainingRecordSeconds ?: telemetry.cardRemainingSeconds,
            storageFreeBytes = primary?.freeSpaceBytes ?: telemetry.storageFreeBytes,
            storageCapacityBytes = primary?.maxCapacityBytes ?: telemetry.storageCapacityBytes,
            storageLabel = primary?.volumeLabel?.ifBlank { primary.description } ?: telemetry.storageLabel
        )
        onTelemetry(telemetry)
    }

    private fun settingCodes(key: CameraSettingKey): IntArray = when (key) {
        CameraSettingKey.FOCUS_MODE -> intArrayOf(0x500A)
        CameraSettingKey.IRIS -> intArrayOf(0x5007)
        CameraSettingKey.ISO -> intArrayOf(
            SonyPtpLiveViewController.PROP_ISO_ABSOLUTE,
            SonyPtpLiveViewController.PROP_ISO,
            0xD023, 0x500F, 0xD022
        )
        CameraSettingKey.SHUTTER -> intArrayOf(0xD20D, 0xD017, 0xD016, 0x500D)
        CameraSettingKey.EXPOSURE_COMPENSATION -> intArrayOf(0x5010)
        CameraSettingKey.WHITE_BALANCE -> intArrayOf(0x5005, 0xD00C)
        CameraSettingKey.LOOK -> intArrayOf(0xD0FA, 0xD03C, 0xD23F)
        CameraSettingKey.RECORD_FRAME_RATE -> intArrayOf(0xD286)
    }

    private fun isStepSetting(key: CameraSettingKey): Boolean =
        key == CameraSettingKey.IRIS || key == CameraSettingKey.ISO || key == CameraSettingKey.SHUTTER

    /** Convert Sony Int16 EV wire value to signed 1/1000 EV. */
    private fun signedEvMilli(raw: Long): Long {
        val wire = raw and 0xffffL
        return if (wire >= 0x8000L) wire - 0x10000L else wire
    }

    private fun evWithinUiRange(raw: Long): Boolean = signedEvMilli(raw) in -2000L..2000L

    private fun selectSettingSnapshot(
        key: CameraSettingKey
    ): Pair<Int, SonyPtpLiveViewController.ExtPropSnapshot>? {
        val codes = settingCodes(key)
        val candidates = ArrayList<Pair<Int, SonyPtpLiveViewController.ExtPropSnapshot>>()
        for (code in codes) {
            val snapshot: SonyPtpLiveViewController.ExtPropSnapshot = propertyCache[code] ?: continue
            candidates.add(Pair(code, snapshot))
        }
        if (candidates.isEmpty()) return null

        fun freshness(pair: Pair<Int, SonyPtpLiveViewController.ExtPropSnapshot>): Long {
            return propertyUpdatedAtMs[pair.first] ?: 0L
        }

        val controllable = ArrayList<Pair<Int, SonyPtpLiveViewController.ExtPropSnapshot>>()
        for (pair in candidates) {
            val snapshot: SonyPtpLiveViewController.ExtPropSnapshot = pair.second
            val hasChoices = snapshot.enumValues.isNotEmpty() ||
                (snapshot.rangeMin != null && snapshot.rangeMax != null)
            if (snapshot.writable && hasChoices) {
                controllable.add(pair)
            }
        }

        val source: List<Pair<Int, SonyPtpLiveViewController.ExtPropSnapshot>> =
            if (controllable.isNotEmpty()) controllable else candidates

        var best: Pair<Int, SonyPtpLiveViewController.ExtPropSnapshot>? = null
        var bestFreshness = Long.MIN_VALUE
        for (pair in source) {
            val value = freshness(pair)
            if (best == null || value > bestFreshness) {
                best = pair
                bestFreshness = value
            }
        }
        return best
    }

    fun isExposureCompensationAdjustable(): Boolean = isExposureCompensationAdjustable(propertyCache)

    private fun isExposureCompensationAdjustable(
        cache: Map<Int, SonyPtpLiveViewController.ExtPropSnapshot>
    ): Boolean {
        fun latestValue(vararg codes: Int): Long? {
            var bestValue: Long? = null
            var bestStamp = Long.MIN_VALUE
            for (code in codes) {
                val value = cache[code]?.currentValue ?: continue
                val stamp = propertyUpdatedAtMs[code] ?: 0L
                if (bestValue == null || stamp > bestStamp) {
                    bestValue = value
                    bestStamp = stamp
                }
            }
            return bestValue
        }

        // ISO has multiple Sony aliases. Always use the alias updated most recently
        // by C203 instead of a stale warm-up alias, otherwise AUTO ISO can look Manual.
        val isoRaw = latestValue(
            SonyPtpLiveViewController.PROP_ISO_ABSOLUTE,
            SonyPtpLiveViewController.PROP_ISO,
            0xD023, 0x500F, 0xD022
        )
        val irisRaw = latestValue(0x5007)
        val shutterRaw = latestValue(0xD20D, 0xD017, 0xD016, 0x500D)
        val programModeRaw = latestValue(0x500E)
        return SonyExposureLogic.canAdjustExposureCompensation(
            exposureProgramModeRaw = programModeRaw,
            isoRaw = isoRaw,
            irisRaw = irisRaw,
            shutterRaw = shutterRaw
        )
    }

    private fun cameraUserLookNames(): Map<Int, String> {
        // Prefer the actual imported LUT filename. Sony bodies may expose the
        // filename through a string descriptor in the same 0x9209 cache while
        // D103 only describes the Creative Look base. Keep the basename and the
        // .cube extension exactly as the camera reports it.
        val explicitCube = linkedMapOf<Int, String>()
        val cubeFallback = ArrayList<Pair<Int, String>>()
        for ((code, snapshot) in propertyCache.entries.sortedBy { it.key }) {
            val value = snapshot.currentString?.trim().orEmpty()
            if (value.isEmpty()) continue
            val normalized = value.replace("\\", "/").substringAfterLast('/').trim()
            if (!normalized.endsWith(".cube", ignoreCase = true)) continue

            val explicitMatch = Regex("(?i)user\\s*([1-9][0-9]*)\\s*[:_-]?\\s*(.*\\.cube)").find(normalized)
            if (explicitMatch != null) {
                val index = explicitMatch.groupValues[1].toIntOrNull()
                val fileName = explicitMatch.groupValues[2].trim().ifEmpty { normalized }
                if (index != null) explicitCube[index] = fileName
            } else {
                cubeFallback.add(Pair(code, normalized))
            }
        }

        var index = 1
        for ((_, fileName) in cubeFallback) {
            while (explicitCube.containsKey(index)) index++
            explicitCube[index] = fileName
            index++
        }
        return explicitCube
    }

    fun whiteBalanceUiState(): WhiteBalanceUiState? {
        val selected = selectSettingSnapshot(CameraSettingKey.WHITE_BALANCE) ?: return null
        val mode = selected.second
        val modeValues = mode.enumValues.ifEmpty { listOfNotNull(mode.currentValue) }
        val autoRaw = modeValues.firstOrNull { (it.toInt() and 0xffff) == 0x0002 }
        val customModeRaw = modeValues.firstOrNull { (it.toInt() and 0xffff) == 0x8012 }
        val presets = ArrayList<CameraSettingOption>()
        for (raw in modeValues) {
            val v = raw.toInt() and 0xffff
            if (v == 0x0002 || v == 0x8012) continue
            presets.add(CameraSettingOption(raw, SonyCameraValueFormatter.whiteBalance(raw, null)))
        }

        val temp = propertyCache[0xD20F]
        val temperatures = ArrayList<CameraSettingOption>()
        if (temp != null) {
            val values = ArrayList<Long>()
            if (temp.enumValues.isNotEmpty()) {
                values.addAll(temp.enumValues)
            } else if (temp.rangeMin != null && temp.rangeMax != null && temp.rangeStep != null && temp.rangeStep != 0L) {
                var value = temp.rangeMin
                var count = 0
                while (value <= temp.rangeMax && count < 300) {
                    values.add(value)
                    value += temp.rangeStep
                    count++
                }
            } else if (temp.currentValue != null) {
                values.add(temp.currentValue)
            }
            for (value in values.distinct()) {
                if (value in 1500L..20000L) temperatures.add(CameraSettingOption(value, "${value}K"))
            }
        }
        return WhiteBalanceUiState(
            currentModeRaw = mode.currentValue,
            currentModeLabel = mode.currentValue?.let { SonyCameraValueFormatter.whiteBalance(it, temp?.currentValue) } ?: "--",
            currentTemperature = temp?.currentValue,
            autoOption = autoRaw?.let { CameraSettingOption(it, "AUTO") },
            customTemperatures = temperatures,
            presetOptions = presets,
            modeWritable = mode.writable,
            temperatureWritable = temp?.writable == true && customModeRaw != null
        )
    }

    fun setWhiteBalanceAuto() {
        val raw = whiteBalanceUiState()?.autoOption?.rawValue ?: return
        setCameraSetting(CameraSettingKey.WHITE_BALANCE, raw)
    }

    fun setWhiteBalancePreset(rawValue: Long) {
        setCameraSetting(CameraSettingKey.WHITE_BALANCE, rawValue)
    }

    fun setWhiteBalanceTemperature(kelvin: Long) {
        val selected = selectSettingSnapshot(CameraSettingKey.WHITE_BALANCE) ?: return
        val modeSnapshot = selected.second
        val customRaw = modeSnapshot.enumValues.firstOrNull { (it.toInt() and 0xffff) == 0x8012 } ?: return
        val tempSnapshot = propertyCache[0xD20F] ?: return
        runPtpControl("WB ${kelvin}K") { ctrl ->
            if (modeSnapshot.currentValue != customRaw) {
                if (!ctrl.setExtDevicePropValue(modeSnapshot, customRaw)) throw IOException("camera rejected WB Color Temperature")
                updateSettingCacheValue(selected.first, modeSnapshot, customRaw)
                // ZV-1 and ZV-E10M2 do not commit the Color Temperature mode at
                // exactly the same speed. Writing D20F immediately after leaving
                // AWB can make the first cross-body ALL CAM write land 100 K apart.
                // Let the mode settle before applying the requested Kelvin value.
                Thread.sleep(220L)
            }
            if (!ctrl.setExtDevicePropValue(tempSnapshot, kelvin)) throw IOException("camera rejected ${kelvin}K")
            updateSettingCacheValue(0xD20F, tempSnapshot, kelvin)
        }
    }

    fun currentTelemetry(): CameraTelemetry = telemetry

    fun cameraSettingDescriptors(): List<CameraSettingDescriptor> {
        if (propertyCache.isEmpty()) return emptyList()
        val out = ArrayList<CameraSettingDescriptor>()

        fun formatterFor(key: CameraSettingKey): (Long) -> String = when (key) {
            CameraSettingKey.FOCUS_MODE -> SonyCameraValueFormatter::focus
            CameraSettingKey.IRIS -> SonyCameraValueFormatter::iris
            CameraSettingKey.ISO -> {
                val selected = selectSettingSnapshot(key)
                if (selected != null && selected.first == 0xD022) SonyCameraValueFormatter::ei else SonyCameraValueFormatter::iso
            }
            CameraSettingKey.SHUTTER -> ::formatSonyShutter
            CameraSettingKey.EXPOSURE_COMPENSATION -> SonyCameraValueFormatter::exposureCompensation
            CameraSettingKey.WHITE_BALANCE -> { raw -> SonyCameraValueFormatter.whiteBalance(raw, propertyCache[0xD20F]?.currentValue) }
            CameraSettingKey.LOOK -> { raw ->
                val code = selectSettingSnapshot(CameraSettingKey.LOOK)?.first ?: 0
                SonyCameraValueFormatter.look(
                    propertyCode = code,
                    raw = raw,
                    userLookNames = cameraUserLookNames(),
                    pictureProfileGammaRaw = propertyCache[0xD0E1]?.currentValue,
                    customCreativeBaseRaw = propertyCache[0xD103]?.currentValue
                )
            }
            CameraSettingKey.RECORD_FRAME_RATE -> ::formatRecordFrameRate
        }

        fun titleFor(key: CameraSettingKey): String = when (key) {
            CameraSettingKey.FOCUS_MODE -> "FOCUS"
            CameraSettingKey.IRIS -> "IRIS"
            CameraSettingKey.ISO -> "EI / ISO"
            CameraSettingKey.SHUTTER -> "SHUTTER"
            CameraSettingKey.EXPOSURE_COMPENSATION -> "EV"
            CameraSettingKey.WHITE_BALANCE -> "WB"
            CameraSettingKey.LOOK -> "LOOK"
            CameraSettingKey.RECORD_FRAME_RATE -> "REC FRAME"
        }

        for (key in CameraSettingKey.values()) {
            val selected = selectSettingSnapshot(key) ?: continue
            val snapshot = selected.second
            val formatter = formatterFor(key)
            val rawOptions = ArrayList<Long>()
            if (snapshot.enumValues.isNotEmpty()) {
                rawOptions.addAll(snapshot.enumValues)
            } else {
                val minValue = snapshot.rangeMin
                val maxValue = snapshot.rangeMax
                val stepValue = snapshot.rangeStep
                if (minValue != null && maxValue != null && stepValue != null && stepValue != 0L) {
                    var value = minValue
                    var count = 0
                    while (value <= maxValue && count < 200) {
                        rawOptions.add(value)
                        value += stepValue
                        count++
                    }
                }
            }
            if (key == CameraSettingKey.EXPOSURE_COMPENSATION) {
                val filtered = ArrayList<Long>()
                for (value in rawOptions) {
                    if (evWithinUiRange(value)) filtered.add(value)
                }
                rawOptions.clear()
                rawOptions.addAll(filtered)
            }
            if (key == CameraSettingKey.FOCUS_MODE) {
                // ZV-1 commonly exposes several detailed AF variants through
                // 0x500A. The compact monitor intentionally presents only AF/MF,
                // so collapse duplicate AF labels before building the wheel.
                val compact = SonyCameraValueFormatter.compactFocusValues(rawOptions, snapshot.currentValue)
                rawOptions.clear()
                rawOptions.addAll(compact)
            }
            val options = ArrayList<CameraSettingOption>()
            val seen = HashSet<Long>()
            for (value in rawOptions) if (seen.add(value)) options.add(CameraSettingOption(value, formatter(value)))
            val current = snapshot.currentValue
            val liveLabel = when (key) {
                CameraSettingKey.FOCUS_MODE -> telemetry.focusMode
                CameraSettingKey.IRIS -> telemetry.iris
                CameraSettingKey.ISO -> telemetry.iso
                CameraSettingKey.SHUTTER -> telemetry.shutter
                CameraSettingKey.EXPOSURE_COMPENSATION -> telemetry.exposureCompensation
                CameraSettingKey.WHITE_BALANCE -> telemetry.whiteBalance
                CameraSettingKey.LOOK -> telemetry.look
                CameraSettingKey.RECORD_FRAME_RATE -> telemetry.recordFrameRate
            }
            out.add(
                CameraSettingDescriptor(
                    key = key,
                    title = titleFor(key),
                    currentLabel = liveLabel.takeIf { it.isNotBlank() && it != "--" } ?: current?.let(formatter) ?: "--",
                    currentRawValue = current,
                    options = options,
                    writable = if (key == CameraSettingKey.EXPOSURE_COMPENSATION) {
                        // EV is writable only while at least one exposure axis is Auto.
                        // Sony's get/set flag is inconsistent across bodies, so the
                        // exposure-state gate is authoritative once 0x5010 is present.
                        isExposureCompensationAdjustable()
                    } else {
                        snapshot.writable || isStepSetting(key)
                    }
                )
            )
        }
        return out
    }

    fun setCameraSetting(key: CameraSettingKey, rawValue: Long) {
        if (key == CameraSettingKey.EXPOSURE_COMPENSATION && !isExposureCompensationAdjustable()) {
            publish("EV LOCKED · ISO / IRIS / SHUTTER ARE MANUAL")
            return
        }
        val selected = selectSettingSnapshot(key) ?: run {
            publish("${key.name}: camera did not publish this property")
            return
        }
        val code = selected.first
        val snapshot = selected.second
        val targetRaw = if (key == CameraSettingKey.EXPOSURE_COMPENSATION) {
            val signed = signedEvMilli(rawValue).coerceIn(-2000L, 2000L)
            // Preserve the camera's own enum encoding for signed Int16 values.
            snapshot.enumValues.firstOrNull { signedEvMilli(it) == signed }
                ?: (signed and 0xffffL)
        } else rawValue
        runPtpControl(key.name) { ctrl ->
            var ok = ctrl.setExtDevicePropValue(snapshot, targetRaw)
            if (!ok && isStepSetting(key) && snapshot.currentValue != null && snapshot.enumValues.isNotEmpty()) {
                val currentIndex = snapshot.enumValues.indexOf(snapshot.currentValue)
                val targetIndex = snapshot.enumValues.indexOf(targetRaw)
                if (currentIndex >= 0 && targetIndex >= 0 && currentIndex != targetIndex) {
                    val up = targetIndex > currentIndex
                    val steps = kotlin.math.abs(targetIndex - currentIndex).coerceAtMost(64)
                    ok = true
                    var i = 0
                    while (i < steps) {
                        if (!ctrl.stepExtDeviceProperty(code, up)) {
                            ok = false
                            break
                        }
                        i++
                    }
                }
            }
            if (!ok) throw IOException("camera rejected property 0x${code.toString(16)}")
            updateSettingCacheValue(code, snapshot, targetRaw)
        }
    }

    fun stepCameraSetting(key: CameraSettingKey, up: Boolean) {
        if (!isStepSetting(key)) return
        val selected = selectSettingSnapshot(key) ?: run {
            publish("${key.name}: camera did not publish this property")
            return
        }
        val code = selected.first
        val snapshot = selected.second
        runPtpControl("${key.name} ${if (up) "+" else "-"}") { ctrl ->
            if (!ctrl.stepExtDeviceProperty(code, up)) throw IOException("camera rejected ControlDevice 0x${code.toString(16)}")
            val values = snapshot.enumValues
            val current = snapshot.currentValue
            if (current != null && values.isNotEmpty()) {
                val index = values.indexOf(current)
                if (index >= 0) {
                    val nextIndex = (index + if (up) 1 else -1).coerceIn(0, values.lastIndex)
                    updateSettingCacheValue(code, snapshot, values[nextIndex])
                }
            }
        }
    }

    private fun updateSettingCacheValue(
        code: Int,
        snapshot: SonyPtpLiveViewController.ExtPropSnapshot,
        rawValue: Long
    ) {
        val updated = snapshot.copy(currentValue = rawValue)
        propertyCache = propertyCache.toMutableMap().apply { put(code, updated) }
        applySonyPropertySnapshot(mapOf(code to updated))
    }

    fun refreshCameraSettingCache() {
        // Retained as a compatibility entry point for older UI code. Deliberately
        // does not reconnect. Sony 0xC203 events drive synchronization; dynamic
        // REC/EV status may use a throttled 0x9209 snapshot after an event.
        lastSettingsRefreshMs.set(SystemClock.elapsedRealtime())
        publish("SETTING SYNC · C203 EVENT MODE · NO RECONNECT")
    }

    fun eventSyncSummary(): String {
        val events = c203EventCount.get()
        val applied = c203AppliedCount.get()
        val bulk = c203BulkRefreshCount.get()
        val code = c203LastProperty.get()
        val age = c203LastEventMs.get().let { if (it <= 0L) -1L else SystemClock.elapsedRealtime() - it }
        val codeText = if (code == 0) "--" else "0x" + code.toString(16).uppercase(Locale.US)
        val ageText = if (age < 0L) "no event yet" else "${age}ms ago"
        val recRaw = propertyCache[SonyPtpLiveViewController.PROP_MOVIE_RECORDING_STATE]?.currentValue
        val evCode = if (isExposureCompensationAdjustable()) 0x5010 else SonyPtpLiveViewController.PROP_METERED_MANUAL_LEVEL
        val evRaw = propertyCache[evCode]?.currentValue
        val recText = recRaw?.toString() ?: "--"
        val evText = evRaw?.let { "0x" + (it and 0xffffL).toString(16).uppercase(Locale.US) } ?: "--"
        return "C203 active · events=$events · applied=$applied · 9209=$bulk · last=$codeText · REC=$recText · EV=$evText · $ageText"
    }

    private fun runPtpControl(label: String, block: (SonyPtpLiveViewController) -> Unit) {
        executor.execute {
            val ctrl = controller
            if (ctrl == null) {
                publish("$label · PTP NOT READY")
                return@execute
            }
            try {
                block(ctrl)
                publish("SONY PTP/IP · $label OK")
            } catch (t: Throwable) {
                publish("$label ERROR · ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun formatRecordFrameRate(raw: Long): String = when (raw.toInt()) {
        1 -> "120p"
        2 -> "100p"
        3 -> "60p"
        4 -> "50p"
        5 -> "30p"
        6 -> "25p"
        7 -> "24p"
        8 -> "23.98p"
        9 -> "29.97p"
        10 -> "59.94p"
        else -> "#$raw"
    }

    private fun formatSonyShutter(raw: Long): String {
        if (raw <= 0L || raw == 0xffffffffL) return "--"
        val numerator = ((raw ushr 16) and 0xffff).toInt()
        val denominator = (raw and 0xffff).toInt()
        return when {
            numerator == 1 && denominator > 0 -> "1/$denominator"
            denominator == 10 && numerator > 0 -> {
                val seconds = numerator / 10.0
                if (seconds < 1.0) "1/${kotlin.math.round(1.0 / seconds).toInt()}" else "%.1fs".format(Locale.US, seconds)
            }
            else -> "0x${raw.toString(16).uppercase()}"
        }
    }

}
