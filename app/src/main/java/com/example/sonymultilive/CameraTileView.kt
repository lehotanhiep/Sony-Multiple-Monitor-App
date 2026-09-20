package com.example.sonymultilive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToLong

class CameraTileView(
    context: Context,
    val camera: SonyCamera,
    private val decodeExecutor: Executor
) : FrameLayout(context) {
    private val ui = Handler(Looper.getMainLooper())
    private val latestJpeg = AtomicReference<ByteArray?>(null)
    private val decodeRunning = AtomicBoolean(false)
    private val droppedFrames = AtomicLong(0)
    private val latestBitmap = AtomicReference<Bitmap?>(null)
    private val renderPosted = AtomicBoolean(false)

    @Volatile private var targetFps = 24
    @Volatile private var decodeSampleSize = 1
    @Volatile private var renderWidth = 1024
    @Volatile private var renderHeight = 576
    @Volatile private var connectionState = "Waiting for connection..."
    @Volatile private var sourceFps = 0.0
    @Volatile private var sourceKbps = 0.0
    @Volatile private var authRequired = false
    @Volatile private var requestedProfile = LiveViewProfile.forCameraCount(1)
    @Volatile private var sourceWidth = 0
    @Volatile private var sourceHeight = 0
    @Volatile private var focusTapListener: ((Double, Double) -> Unit)? = null
    @Volatile private var settingsTapListener: (() -> Unit)? = null
    @Volatile private var settingTapListener: ((CameraSettingKey, View) -> Unit)? = null
    @Volatile private var settingScrollListener: ((CameraSettingKey, Boolean) -> Unit)? = null
    @Volatile private var autofocusTapListener: (() -> Unit)? = null
    @Volatile private var recordTapListener: (() -> Unit)? = null
    @Volatile private var playbackPreviousTapListener: (() -> Unit)? = null
    @Volatile private var playbackPlayTapListener: (() -> Unit)? = null
    @Volatile private var playbackPauseTapListener: (() -> Unit)? = null
    @Volatile private var playbackNextTapListener: (() -> Unit)? = null
    @Volatile private var playbackModeActive = false
    @Volatile private var playbackPlaying = false
    @Volatile private var playbackControlBusy = false
    @Volatile private var controlsLocked = false
    @Volatile private var falseColorMode = FalseColorPalette.OFF
    @Volatile private var telemetry = CameraTelemetry()
    @Volatile private var cameraSlotsDensity = 1
    @Volatile private var displayIndex = 1
    // REC UI is command-driven. Sony D21D/C203 telemetry is still retained for
    // diagnostics, but a missing/late confirmation must not suppress the user's
    // REC/STOP action, border blink, or elapsed timer.
    @Volatile private var recordingUiOverride: Boolean? = null
    @Volatile private var recordingUiStartedElapsedMs: Long? = null
    private var lastStatusOverlayText = ""
    private var recordBorderBright = false
    @Volatile private var osdVisible = true
    @Volatile private var histogramVisible = false
    @Volatile private var lastHistogramMs = 0L
    @Volatile private var lastFrameReceivedMs = 0L
    @Volatile private var focusClearListener: (() -> Unit)? = null
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var lastFocusTapUpMs = 0L
    private var lastFocusTapX = 0f
    private var lastFocusTapY = 0f

    private var displayStatStarted = SystemClock.elapsedRealtime()
    @Volatile private var lastRenderMs = 0L
    private var displayFrames = 0L
    @Volatile private var displayFps = 0.0

    // Low-latency 24 Hz presentation. Real camera frames are shown immediately;
    // the presentation clock holds the newest frame between source arrivals. This
    // avoids the extra source-frame delay introduced by full-frame cross-fades.
    private var currentBitmap: Bitmap? = null
    private var previousBitmap: Bitmap? = null
    private var lastSourcePresentedMs = 0L
    private var sourceIntervalEmaMs = 1000.0 / 24.0
    private var transitionStartedMs = 0L
    private var transitionDurationMs = 42L
    private var interpolationRunning = false
    private var nextInterpolationTickMs = 0.0

    private fun monitorImageView() = ImageView(context).apply {
        setBackgroundColor(Color.BLACK)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private val previousImage = monitorImageView()
    private val image = monitorImageView()

    private val panelHeader = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), 0, dp(10), 0)
        setBackgroundColor(0x33000000)
    }
    private val cameraIdOverlay = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 8.5f
        typeface = Typeface.MONOSPACE
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
    }
    private val cameraStatusOverlay = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 7.2f
        typeface = Typeface.MONOSPACE
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        maxLines = 1
        setPadding(dp(4), 0, 0, 0)
    }
    private val fpsOverlay = TextView(context).apply {
        setTextColor(Color.WHITE)
        setBackgroundColor(0x88000000.toInt())
        textSize = 8f
        typeface = Typeface.MONOSPACE
        setPadding(dp(5), dp(2), dp(5), dp(2))
        text = "LIVE -- FPS"
    }
    private fun settingField(title: String, key: CameraSettingKey?): TextView = TextView(context).apply {
        tag = key
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        textSize = 8.2f
        typeface = Typeface.DEFAULT
        gravity = Gravity.CENTER
        setPadding(dp(3), dp(2), dp(3), dp(2))
        maxLines = 2
        text = "$title\n--"
        isClickable = key != null
        isFocusable = key != null

        // Tap the telemetry value to open a compact vertical wheel exactly at
        // that setting. The wheel itself is owned by MainActivity because it
        // needs the live Sony property descriptors from the camera session.
        if (key != null) {
            setOnClickListener {
                if (!controlsLocked) settingTapListener?.invoke(key, this)
            }
        }
    }

    private val fpsField = settingField("FPS", CameraSettingKey.RECORD_FRAME_RATE)
    private val focusField = settingField("FOCUS", CameraSettingKey.FOCUS_MODE)
    private val irisField = settingField("IRIS", CameraSettingKey.IRIS)
    private val isoField = settingField("ISO", CameraSettingKey.ISO)
    private val shutterField = settingField("SHUTTER", CameraSettingKey.SHUTTER)
    private val exposureField = settingField("EV", CameraSettingKey.EXPOSURE_COMPENSATION)
    private val wbField = settingField("WB", CameraSettingKey.WHITE_BALANCE)
    private val lookField = settingField("LOOK", CameraSettingKey.LOOK)
    private val settingsBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(0xaa000000.toInt())
        listOf(fpsField, focusField, irisField, isoField, shutterField, exposureField, wbField, lookField).forEach { field ->
            addView(field, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
    }
    private val falseColorLegend = FalseColorLegendView(context).apply {
        mode = FalseColorPalette.OFF
    }
    private val histogramView = HistogramOverlayView(context).apply { visibility = View.GONE }
    private val focusMarker = View(context).apply {
        visibility = View.GONE
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(2), 0xff75dbff.toInt())
        }
    }
    private val disconnectedOverlay = TextView(context).apply {
        text = "CAMERA DISCONNECTED..."
        setTextColor(Color.WHITE)
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setBackgroundColor(0xaa000000.toInt())
        visibility = View.GONE
    }

    private fun playbackTransportButton(label: String, widthDp: Int, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 8.5f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(4), 0, dp(4), 0)
        background = GradientDrawable().apply {
            setColor(0xdd111317.toInt())
            setStroke(dp(1), 0xff8b8f94.toInt())
            cornerRadius = dp(5).toFloat()
        }
        isClickable = true
        isFocusable = true
        setOnClickListener {
            if (playbackModeActive && !playbackControlBusy) onClick()
        }
        layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(38))
    }

    private val playbackPreviousButton = playbackTransportButton("<", 42) { playbackPreviousTapListener?.invoke() }
    private val playbackPlayButton = playbackTransportButton("PLAY", 54) { playbackPlayTapListener?.invoke() }
    private val playbackPauseButton = playbackTransportButton("PAUSE", 58) { playbackPauseTapListener?.invoke() }
    private val playbackNextButton = playbackTransportButton(">", 42) { playbackNextTapListener?.invoke() }
    private val playbackControls = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(5), dp(4), dp(5), dp(4))
        background = GradientDrawable().apply {
            setColor(0xbb000000.toInt())
            setStroke(dp(1), 0xff5e6268.toInt())
            cornerRadius = dp(8).toFloat()
        }
        visibility = View.GONE
        addView(playbackPreviousButton, LinearLayout.LayoutParams(dp(42), dp(38)).apply { marginEnd = dp(4) })
        addView(playbackPlayButton, LinearLayout.LayoutParams(dp(54), dp(38)).apply { marginEnd = dp(4) })
        addView(playbackPauseButton, LinearLayout.LayoutParams(dp(58), dp(38)).apply { marginEnd = dp(4) })
        addView(playbackNextButton, LinearLayout.LayoutParams(dp(42), dp(38)))
    }


    private fun roundControl(label: String, diameter: Int, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = if (label == "REC") 8f else 17f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (label == "REC") 0xffd91625.toInt() else 0x66000000)
            setStroke(dp(if (label == "REC") 2 else 1), if (label == "REC") 0xffff6b72.toInt() else 0xffffffff.toInt())
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { if (!controlsLocked || label == "HOLD") onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(diameter), dp(diameter))
    }

    private val focusControl = roundControl("◎", 40) { autofocusTapListener?.invoke() }
    private val recordControl = roundControl("REC", 46) { recordTapListener?.invoke() }
    private val holdControl = TextView(context).apply {
        text = "HOLD  ○"
        setTextColor(Color.WHITE)
        textSize = 8f
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(8), 0, dp(8), 0)
        background = GradientDrawable().apply {
            setColor(0x66000000)
            setStroke(dp(1), 0xffbfc2c7.toInt())
            cornerRadius = dp(14).toFloat()
        }
        isClickable = true
        isFocusable = true
        setOnClickListener {
            controlsLocked = !controlsLocked
            text = if (controlsLocked) "HOLD  ●" else "HOLD  ○"
            setTextColor(if (controlsLocked) 0xffffdf54.toInt() else Color.WHITE)
        }
    }
    private val cameraControls = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(2), dp(6), dp(2))
        setBackgroundColor(0xaa000000.toInt())
        addView(focusControl, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) })
        addView(recordControl, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(8) })
        addView(holdControl, LinearLayout.LayoutParams(dp(72), dp(28)))
    }
    private val controlFooter = View(context).apply {
        setBackgroundColor(0xff050708.toInt())
    }

    private val recordingBorderTicker = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || !isRecordingForUi()) {
                recordBorderBright = false
                applyRecordingBorder(false)
                return
            }
            recordBorderBright = !recordBorderBright
            applyRecordingBorder(true)
            ui.postDelayed(this, 420L)
        }
    }

    private val disconnectWatchdog = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            val last = lastFrameReceivedMs
            if (playbackModeActive) {
                disconnectedOverlay.visibility = View.GONE
            } else if (last > 0L && SystemClock.elapsedRealtime() - last > 5000L) {
                disconnectedOverlay.visibility = View.VISIBLE
            }
            ui.postDelayed(this, 1000L)
        }
    }

    private val interpolationTicker = object : Runnable {
        override fun run() {
            val current = currentBitmap
            if (current == null || !isAttachedToWindow) {
                interpolationRunning = false
                return
            }
            val now = SystemClock.elapsedRealtime()
            // REC elapsed/status shares the 24 Hz presentation clock so the
            // counter remains visually responsive without any camera polling.
            refreshStatusOverlay()
            val prev = previousBitmap
            if (prev != null) {
                val progress = ((now - transitionStartedMs).toFloat() / transitionDurationMs.coerceAtLeast(1L).toFloat())
                    .coerceIn(0f, 1f)
                image.alpha = progress
                previousImage.alpha = 1f
                if (progress >= 1f) {
                    previousImage.setImageDrawable(null)
                    previousImage.alpha = 0f
                    if (!prev.isRecycled) prev.recycle()
                    previousBitmap = null
                }
            } else {
                image.alpha = 1f
            }

            // Count the 24 Hz presentation clock, including held frames.
            displayFrames++
            val elapsed = now - displayStatStarted
            if (elapsed >= 1000L) {
                displayFps = displayFrames * 1000.0 / elapsed
                displayFrames = 0L
                displayStatStarted = now
                refreshOverlay()
            }
            val period = 1000.0 / targetFps.coerceAtLeast(1).toDouble()
            nextInterpolationTickMs = if (nextInterpolationTickMs <= now.toDouble()) {
                now + period
            } else {
                nextInterpolationTickMs + period
            }
            val delay = (nextInterpolationTickMs - SystemClock.elapsedRealtime()).roundToLong().coerceAtLeast(1L)
            ui.postDelayed(this, delay)
        }
    }

    init {
        background = GradientDrawable().apply {
            setColor(Color.BLACK)
            setStroke(dp(1), 0xff4b4b4b.toInt())
        }
        isClickable = true
        isFocusable = true
        val headerH = dp(30)
        val footerH = dp(52)
        // Previous Sony-style UI draws controls over the live picture rather than
        // shrinking the video surface. The video engine remains unchanged.
        addView(previousImage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            bottomMargin = footerH
        })
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            bottomMargin = footerH
        })
        previousImage.alpha = 0f

        panelHeader.addView(cameraIdOverlay, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        panelHeader.addView(cameraStatusOverlay, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(panelHeader, LayoutParams(LayoutParams.MATCH_PARENT, headerH, Gravity.TOP))
        addView(falseColorLegend, LayoutParams(dp(64), LayoutParams.MATCH_PARENT, Gravity.START).apply {
            topMargin = headerH + dp(3)
            bottomMargin = footerH + dp(3)
        })
        addView(histogramView, LayoutParams(dp(126), dp(66), Gravity.END or Gravity.TOP).apply {
            topMargin = headerH + dp(8)
            marginEnd = dp(8)
        })
        // Footer is outside the image surface. REC/HOLD no longer cover Live View.
        addView(controlFooter, LayoutParams(LayoutParams.MATCH_PARENT, footerH, Gravity.BOTTOM))
        addView(settingsBar, LayoutParams(LayoutParams.MATCH_PARENT, footerH, Gravity.BOTTOM))
        addView(focusMarker, LayoutParams(dp(36), dp(36)))
        addView(disconnectedOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            topMargin = headerH
            bottomMargin = footerH
        })
        // Playback transport is intentionally not attached to the tile.
        // The global ALL CAM transport bar owns < PLAY >.

        panelHeader.isClickable = true
        panelHeader.isFocusable = true
        panelHeader.setOnClickListener { if (!controlsLocked) settingsTapListener?.invoke() }
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = event.x
                    touchDownY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val movedX = abs(event.x - touchDownX)
                    val movedY = abs(event.y - touchDownY)
                    if (movedX < dp(14) && movedY < dp(14)) {
                        val activeHeaderH = if (osdVisible) headerH else 0
                        val activeFooterH = if (osdVisible) footerH else 0
                        val videoH = (height - activeHeaderH - activeFooterH).coerceAtLeast(1)
                        if (event.y >= activeHeaderH && event.y <= height - activeFooterH) {
                            val now = event.eventTime
                            val doubleTap = lastFocusTapUpMs > 0L &&
                                now - lastFocusTapUpMs <= 360L &&
                                abs(event.x - lastFocusTapX) <= dp(42) &&
                                abs(event.y - lastFocusTapY) <= dp(42)
                            if (doubleTap) {
                                lastFocusTapUpMs = 0L
                                focusMarker.visibility = View.GONE
                                if (!controlsLocked) focusClearListener?.invoke()
                            } else {
                                lastFocusTapUpMs = now
                                lastFocusTapX = event.x
                                lastFocusTapY = event.y
                                val nx = (event.x / width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                                val ny = ((event.y - activeHeaderH) / videoH.toFloat()).coerceIn(0f, 1f)
                                showFocusMarker(event.x, event.y)
                                if (!controlsLocked) focusTapListener?.invoke(nx.toDouble(), ny.toDouble())
                            }
                        }
                    }
                }
            }
            false
        }
        refreshOverlay()
        ui.postDelayed(disconnectWatchdog, 1500L)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ui.removeCallbacks(disconnectWatchdog)
        ui.postDelayed(disconnectWatchdog, 1500L)
        if (isRecordingForUi()) updateRecordingBlink(true)
    }

    override fun onDetachedFromWindow() {
        ui.removeCallbacks(interpolationTicker)
        ui.removeCallbacks(disconnectWatchdog)
        ui.removeCallbacks(recordingBorderTicker)
        interpolationRunning = false
        super.onDetachedFromWindow()
    }

    fun setDisplayIndex(index: Int) {
        displayIndex = index.coerceAtLeast(1)
        ui.post(::refreshOverlay)
    }

    /**
     * Reduce OSD density as the monitor is split into three/four camera slots.
     * This only changes Android text/padding; camera telemetry and sessions are
     * untouched.
     */
    fun setTelemetryDensity(cameraSlots: Int) {
        cameraSlotsDensity = cameraSlots.coerceAtLeast(1)
        val fieldSize = when {
            cameraSlots >= 4 -> 6.2f
            cameraSlots >= 3 -> 6.8f
            cameraSlots == 2 -> 7.4f
            else -> 8.2f
        }
        val headerSize = when {
            cameraSlots >= 4 -> 6.6f
            cameraSlots >= 3 -> 7.2f
            cameraSlots == 2 -> 7.8f
            else -> 8.5f
        }
        val liveSize = when {
            cameraSlots >= 4 -> 6.2f
            cameraSlots >= 3 -> 6.8f
            else -> 8.0f
        }
        ui.post {
            cameraIdOverlay.textSize = headerSize
            cameraStatusOverlay.textSize = when {
                cameraSlots >= 4 -> 5.6f
                cameraSlots >= 3 -> 6.0f
                cameraSlots == 2 -> 6.5f
                else -> 7.2f
            }
            fpsOverlay.textSize = liveSize
            val horizontalPadding = if (cameraSlots >= 3) dp(1) else dp(3)
            listOf(fpsField, focusField, irisField, isoField, shutterField, exposureField, wbField, lookField).forEach { field ->
                field.textSize = fieldSize
                field.setPadding(horizontalPadding, dp(1), horizontalPadding, dp(1))
            }
            panelHeader.setPadding(if (cameraSlots >= 3) dp(5) else dp(10), 0, if (cameraSlots >= 3) dp(5) else dp(10), 0)
        }
    }

    fun setControlsLocked(locked: Boolean) {
        controlsLocked = locked
        ui.post {
            // HOLD is global now; the tile simply obeys the shared lock state.
            settingsBar.alpha = if (locked) 0.55f else 1f
            exposureField.alpha = if (locked || !telemetry.evDisplayEnabled) 0.42f else 1f
        }
    }

    fun setOsdVisible(visible: Boolean) {
        osdVisible = visible
        ui.post {
            panelHeader.visibility = if (visible) View.VISIBLE else View.GONE
            settingsBar.visibility = if (visible) View.VISIBLE else View.GONE
            // controlFooter is a separate opaque footer background. If it remains
            // visible while OSD is OFF it covers the bottom of the Live View frame.
            controlFooter.visibility = if (visible) View.VISIBLE else View.GONE
            falseColorLegend.visibility = if (visible && falseColorMode != FalseColorPalette.OFF) View.VISIBLE else View.GONE
            histogramView.visibility = if (visible && histogramVisible) View.VISIBLE else View.GONE
            val footer = if (visible) dp(52) else 0
            (previousImage.layoutParams as? LayoutParams)?.let { lp ->
                lp.bottomMargin = footer
                previousImage.layoutParams = lp
            }
            (image.layoutParams as? LayoutParams)?.let { lp ->
                lp.bottomMargin = footer
                image.layoutParams = lp
            }
            (playbackControls.layoutParams as? LayoutParams)?.let { lp ->
                lp.bottomMargin = if (visible) dp(60) else dp(8)
                playbackControls.layoutParams = lp
            }
            requestLayout()
        }
    }

    fun setOnFocusTapListener(listener: ((Double, Double) -> Unit)?) { focusTapListener = listener }
    fun setOnFocusClearListener(listener: (() -> Unit)?) { focusClearListener = listener }
    fun setOnSettingsTapListener(listener: (() -> Unit)?) { settingsTapListener = listener }
    fun setOnSettingTapListener(listener: ((CameraSettingKey, View) -> Unit)?) { settingTapListener = listener }
    fun setOnSettingScrollListener(listener: ((CameraSettingKey, Boolean) -> Unit)?) { settingScrollListener = listener }
    fun setOnAutofocusTapListener(listener: (() -> Unit)?) { autofocusTapListener = listener }
    fun setOnRecordTapListener(listener: (() -> Unit)?) { recordTapListener = listener }
    fun setOnPlaybackPreviousListener(listener: (() -> Unit)?) { playbackPreviousTapListener = listener }
    fun setOnPlaybackPlayListener(listener: (() -> Unit)?) { playbackPlayTapListener = listener }
    fun setOnPlaybackPauseListener(listener: (() -> Unit)?) { playbackPauseTapListener = listener }
    fun setOnPlaybackNextListener(listener: (() -> Unit)?) { playbackNextTapListener = listener }

    fun setPlaybackMode(active: Boolean, playing: Boolean) {
        playbackModeActive = active
        playbackPlaying = playing
        ui.post {
            playbackControls.visibility = View.GONE
            if (active) disconnectedOverlay.visibility = View.GONE
            playbackPlayButton.setTextColor(if (active && playing) 0xffffd84a.toInt() else Color.WHITE)
            playbackPauseButton.setTextColor(if (active && !playing) 0xffffd84a.toInt() else Color.WHITE)
            updatePlaybackControlEnabledState()
        }
    }

    fun setPlaybackControlBusy(busy: Boolean) {
        playbackControlBusy = busy
        ui.post { updatePlaybackControlEnabledState() }
    }

    private fun updatePlaybackControlEnabledState() {
        val enabled = playbackModeActive && !playbackControlBusy
        listOf(playbackPreviousButton, playbackPlayButton, playbackPauseButton, playbackNextButton).forEach { button ->
            button.isEnabled = enabled
            button.alpha = if (enabled) 1f else 0.5f
        }
    }

    fun setTelemetry(value: CameraTelemetry) {
        val recordingChanged = telemetry.recording != value.recording
        telemetry = value
        ui.post {
            // When there is no explicit REC/STOP command override, follow camera
            // telemetry as before. During a commanded take the local UI remains
            // authoritative even if D21D/C203 never confirms on this body.
            if (recordingUiOverride == null && recordingChanged) updateRecordingBlink(value.recording)
            exposureField.alpha = if (controlsLocked || !value.evDisplayEnabled) 0.42f else 1f
            refreshOverlay()
        }
    }

    /**
     * Apply the user's REC/STOP command immediately to the monitor UI. This does
     * not alter SonyCameraSession telemetry and therefore does not fake a camera
     * property value; it only controls the presentation state of this tile.
     */
    fun setRecordingCommandState(recording: Boolean) {
        val wasRecording = isRecordingForUi()
        recordingUiOverride = recording
        if (recording) {
            if (!wasRecording || recordingUiStartedElapsedMs == null) {
                recordingUiStartedElapsedMs = SystemClock.elapsedRealtime()
            }
        } else {
            recordingUiStartedElapsedMs = null
        }
        ui.post {
            updateRecordingBlink(recording)
            refreshOverlay()
        }
    }

    private fun isRecordingForUi(): Boolean = recordingUiOverride ?: telemetry.recording

    fun setHistogramVisible(visible: Boolean) {
        histogramVisible = visible
        ui.post { histogramView.visibility = if (visible && osdVisible) View.VISIBLE else View.GONE }
    }

    fun setFalseColorEnabled(enabled: Boolean) = setFalseColorMode(if (enabled) FalseColorPalette.SDR else FalseColorPalette.OFF)

    fun setFalseColorMode(mode: Int) {
        falseColorMode = mode.coerceIn(FalseColorPalette.OFF, FalseColorPalette.SLOG3)
        ui.post {
            falseColorLegend.mode = falseColorMode
            falseColorLegend.visibility = if (osdVisible && falseColorMode != FalseColorPalette.OFF) View.VISIBLE else View.GONE
            // The CPU false-color LUT is applied on the next decoded frame. No
            // RenderEffect/RuntimeShader is used, avoiding GPU-driver app exits.
        }
    }

    private fun showFocusMarker(x: Float, y: Float) {
        val size = dp(36)
        val footer = if (osdVisible) dp(52) else 0
        val liveBottom = (height - footer - size).coerceAtLeast(0).toFloat()
        focusMarker.x = (x - size / 2f).coerceIn(0f, (width - size).coerceAtLeast(0).toFloat())
        focusMarker.y = (y - size / 2f).coerceIn(0f, liveBottom)
        setFocusMarkerColor(0xff75dbff.toInt())
        focusMarker.visibility = View.VISIBLE
    }

    private fun setFocusMarkerColor(color: Int) {
        focusMarker.background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(2), color)
        }
    }

    fun setFocusResult(success: Boolean) {
        ui.post {
            if (focusMarker.visibility == View.VISIBLE) {
                setFocusMarkerColor(if (success) 0xff38e86f.toInt() else 0xffff5b5b.toInt())
            }
        }
    }

    fun setTargetFps(fps: Int) { targetFps = fps.coerceIn(4, 60) }
    fun setDecodeSampleSize(sampleSize: Int) { decodeSampleSize = sampleSize.coerceIn(1, 4) }
    fun setRenderResolution(width: Int, height: Int) {
        renderWidth = width.coerceAtLeast(160)
        renderHeight = height.coerceAtLeast(90)
    }
    fun setLiveViewProfile(profile: LiveViewProfile) { requestedProfile = profile; setTargetFps(profile.fps) }
    fun setState(text: String) {
        connectionState = text
        val lower = text.lowercase(Locale.US)
        val severe = lower.contains("disconnected") ||
            lower.contains("connection lost") ||
            lower.contains("failed to connect") ||
            lower.contains("no route") ||
            lower.contains("ehostunreach") ||
            lower.contains("connection refused") ||
            lower.contains("socket closed") ||
            lower.contains("stream lost") ||
            lower.contains("mất stream") ||
            lower.contains("eof")
        if (severe && !playbackModeActive) {
            ui.postDelayed({
                val noRecentFrame = lastFrameReceivedMs == 0L || SystemClock.elapsedRealtime() - lastFrameReceivedMs > 1800L
                if (!playbackModeActive && connectionState == text && noRecentFrame) disconnectedOverlay.visibility = View.VISIBLE
            }, 2200L)
        }
    }
    fun rawState(): String = connectionState
    fun friendlyState(): String = humanizeState(connectionState)
    fun hasRecentLiveFrame(maxAgeMs: Long = 5_000L): Boolean {
        val last = lastFrameReceivedMs
        return last > 0L && SystemClock.elapsedRealtime() - last <= maxAgeMs
    }
    fun isStreamDisconnected(maxAgeMs: Long = 5_000L): Boolean = !hasRecentLiveFrame(maxAgeMs)
    fun setAuthRequired(required: Boolean) { authRequired = required }

    fun setStats(stats: LiveViewStats) {
        sourceFps = stats.fps
        sourceKbps = stats.kbps
        ui.post(::refreshOverlay)
    }

    private fun refreshOverlay() {
        // Visible OSD intentionally reports only the presentation rate after
        // interpolation. Source FPS/bitrate are kept internally for diagnostics.
        val liveLabel = if (displayFps > 1.0) {
            String.format(Locale.US, "LIVE %.1f FPS", displayFps)
        } else {
            "LIVE -- FPS"
        }
        fpsOverlay.text = liveLabel
        val cameraName = camera.friendlyName.ifBlank { camera.modelName.ifBlank { "Sony Camera" } }
        cameraIdOverlay.text = "$displayIndex · $cameraName  ·  ${camera.host}  ·  $liveLabel"
        val recMark = if (isRecordingForUi()) "● " else ""
        val frame = telemetry.recordFrameRate.takeIf { it != "--" } ?: "--"
        fpsField.text = "FPS\n$recMark$frame"
        focusField.text = "FOCUS\n${telemetry.focusMode}"
        irisField.text = "IRIS\n${telemetry.iris.removePrefix("IRIS ")}"
        isoField.text = "ISO\n${telemetry.iso.removePrefix("ISO ").removePrefix("EI ")}"
        shutterField.text = "SHUTTER\n${telemetry.shutter.removePrefix("SHUTTER ")}"
        exposureField.text = "EV\n${telemetry.exposureCompensation}"
        wbField.text = "WB\n${telemetry.whiteBalance.removePrefix("WB ")}"
        lookField.text = "LOOK\n${telemetry.look.removePrefix("LOOK ")}"
        refreshStatusOverlay(force = true)
    }

    private fun refreshStatusOverlay(force: Boolean = false) {
        val t = telemetry
        val recording = isRecordingForUi()
        val startElapsed = if (recordingUiOverride == true) recordingUiStartedElapsedMs else t.recordStartedElapsedMs
        val recElapsed = if (recording) formatRecordElapsed(startElapsed) else "00:00:00"
        val battery = t.batteryPercent?.let { "BAT ${it.coerceIn(0, 100)}%" } ?: "BAT --"
        val card = t.cardRemainingSeconds?.let { "CARD ${formatCardRemaining(it)}" } ?: "CARD --"
        // Requested monitor header:
        // ZV-1 · IP · LIVE 24.0 FPS        00:01:42 · BAT 76% · CARD 2h33m
        val text = "$recElapsed  · $battery · $card"
        if (force || text != lastStatusOverlayText) {
            lastStatusOverlayText = text
            cameraStatusOverlay.text = text
            cameraStatusOverlay.setTextColor(if (recording) 0xffff858b.toInt() else Color.WHITE)
        }
    }

    private fun formatCardRemaining(seconds: Long): String {
        val totalMinutes = (seconds.coerceAtLeast(0L) + 30L) / 60L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return if (hours > 0L) String.format(Locale.US, "%dh%02dm", hours, minutes)
        else String.format(Locale.US, "%dm", minutes)
    }

    private fun formatRecordElapsed(startElapsedMs: Long?): String {
        if (startElapsedMs == null) return "00:00:00"
        val total = ((SystemClock.elapsedRealtime() - startElapsedMs).coerceAtLeast(0L) / 1000L)
        val h = total / 3600L
        val m = (total / 60L) % 60L
        val sec = total % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, sec)
    }

    private fun updateRecordingBlink(recording: Boolean) {
        ui.removeCallbacks(recordingBorderTicker)
        recordBorderBright = false
        if (recording) {
            applyRecordingBorder(true)
            ui.post(recordingBorderTicker)
        } else {
            applyRecordingBorder(false)
        }
    }

    private fun applyRecordingBorder(recording: Boolean) {
        foreground = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            val stroke = if (recording) dp(4) else dp(1)
            val color = when {
                !recording -> 0x004b4b4b
                recordBorderBright -> 0xffff2233.toInt()
                else -> 0xff6b0d15.toInt()
            }
            setStroke(stroke, color)
        }
    }

    fun renderSharedBitmapNow(bitmap: Bitmap, sourceW: Int, sourceH: Int) {
        sourceWidth = sourceW
        sourceHeight = sourceH
        ui.post { presentBitmap(bitmap) }
    }

    fun offerJpeg(jpeg: ByteArray) {
        lastFrameReceivedMs = SystemClock.elapsedRealtime()
        if (disconnectedOverlay.visibility == View.VISIBLE) ui.post { disconnectedOverlay.visibility = View.GONE }
        if (latestJpeg.getAndSet(jpeg) != null) droppedFrames.incrementAndGet()
        scheduleDecode()
    }

    private fun scheduleDecode() {
        if (!decodeRunning.compareAndSet(false, true)) return
        decodeExecutor.execute {
            try {
                while (true) {
                    val bytes = latestJpeg.getAndSet(null) ?: break
                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = if (falseColorMode == FalseColorPalette.OFF) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
                        inMutable = falseColorMode != FalseColorPalette.OFF
                        inSampleSize = decodeSampleSize
                    }
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: continue
                    sourceWidth = decoded.width * decodeSampleSize
                    sourceHeight = decoded.height * decodeSampleSize

                    // If a newer JPEG arrived while this one was being decoded, do
                    // not spend more CPU scaling/effects on a frame that would only
                    // increase monitor latency. Latest-frame-wins at every stage.
                    if (latestJpeg.get() != null) {
                        if (!decoded.isRecycled) decoded.recycle()
                        droppedFrames.incrementAndGet()
                        continue
                    }

                    var bitmap = if (decoded.width == renderWidth && decoded.height == renderHeight) decoded
                    else Bitmap.createScaledBitmap(decoded, renderWidth, renderHeight, true).also {
                        if (it !== decoded && !decoded.isRecycled) decoded.recycle()
                    }
                    if (latestJpeg.get() != null) {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        droppedFrames.incrementAndGet()
                        continue
                    }
                    if (histogramVisible) {
                        val nowHist = SystemClock.elapsedRealtime()
                        if (nowHist - lastHistogramMs >= 180L) {
                            lastHistogramMs = nowHist
                            histogramView.updateFrom(bitmap)
                        }
                    }
                    val fcMode = falseColorMode
                    if (fcMode != FalseColorPalette.OFF) {
                        val colored = FalseColorPalette.process(bitmap, fcMode)
                        if (colored !== bitmap && !bitmap.isRecycled) bitmap.recycle()
                        bitmap = colored
                    }
                    val stale = latestBitmap.getAndSet(bitmap)
                    if (stale != null && stale !== bitmap && !stale.isRecycled) stale.recycle()
                    scheduleRender()
                }
            } finally {
                decodeRunning.set(false)
                if (latestJpeg.get() != null) scheduleDecode()
            }
        }
    }

    private fun scheduleRender() {
        // LOW-LATENCY policy: never hold a newly decoded camera frame waiting for
        // the 24 Hz presentation clock. The latest real frame is posted to the UI
        // immediately; the separate 24 Hz ticker is only the display cadence/FPS
        // clock. If several decoded frames arrive before the UI runs, latestBitmap
        // already contains only the newest one.
        if (!renderPosted.compareAndSet(false, true)) return
        ui.postAtFrontOfQueue {
            try {
                val bitmap = latestBitmap.getAndSet(null) ?: return@postAtFrontOfQueue
                presentBitmap(bitmap)
            } finally {
                renderPosted.set(false)
                if (latestBitmap.get() != null) scheduleRender()
            }
        }
    }

    private fun presentBitmap(bitmap: Bitmap) {
        val now = SystemClock.elapsedRealtime()

        // A full-frame cross-fade adds almost one source-frame of visible latency:
        // the newest frame used to start at alpha=0 and only became fully visible
        // tens of milliseconds later. For monitoring we prioritize latency. Show
        // the newest real frame immediately and use the 24 Hz ticker only to hold
        // that newest frame between camera arrivals.
        val oldPrevious = previousBitmap
        val oldCurrent = currentBitmap
        currentBitmap = bitmap
        previousBitmap = null

        previousImage.setImageDrawable(null)
        previousImage.alpha = 0f
        image.setImageBitmap(bitmap)
        image.alpha = 1f

        if (oldPrevious != null && oldPrevious !== oldCurrent && oldPrevious !== bitmap && !oldPrevious.isRecycled) {
            ui.post { if (!oldPrevious.isRecycled) oldPrevious.recycle() }
        }
        if (oldCurrent != null && oldCurrent !== bitmap && !oldCurrent.isRecycled) {
            ui.post { if (!oldCurrent.isRecycled) oldCurrent.recycle() }
        }

        lastSourcePresentedMs = now
        lastRenderMs = now
        ensureInterpolationTicker()
    }

    private fun ensureInterpolationTicker() {
        if (interpolationRunning) return
        interpolationRunning = true
        nextInterpolationTickMs = SystemClock.elapsedRealtime().toDouble()
        ui.removeCallbacks(interpolationTicker)
        ui.post(interpolationTicker)
    }

    private fun frameIntervalMs(): Long = (1000.0 / targetFps.coerceAtLeast(1).toDouble()).roundToLong().coerceAtLeast(1L)

    private fun humanizeState(raw: String): String {
        val s = raw.lowercase(Locale.US)
        return when {
            s.contains("live http-fast: live") -> "LIVE VIEW HTTP-FAST: RUNNING · TARGET 24 FPS"
            s.contains("live ptp: live") || s.contains("live http: live") -> "LIVE VIEW: RUNNING · TARGET 24 FPS"
            s.contains("ptp: kết nối") || s.contains("đang thử") || s.contains("đang chờ") -> "PTP: CONNECTING..."
            s.contains("ehostunreach") || s.contains("no route to host") -> "NETWORK: CAMERA UNREACHABLE"
            s.contains("mất stream") || s.contains("stream lost") -> "LIVE VIEW: STREAM LOST"
            s.contains("lỗi") -> "CAMERA: CONNECTION ERROR"
            s.contains("đã dừng") -> "CAMERA: STOPPED"
            else -> raw
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
