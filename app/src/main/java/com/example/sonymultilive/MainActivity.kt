package com.example.sonymultilive

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.content.Intent
import android.provider.Settings
import android.os.Build
import android.content.pm.PackageManager
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.BaseAdapter
import android.widget.PopupWindow
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private val networkExecutor = Executors.newCachedThreadPool()
    private val decodeExecutor = Executors.newFixedThreadPool(
        min(8, max(4, Runtime.getRuntime().availableProcessors()))
    )
    private val sessions = ConcurrentHashMap<String, SonyCameraSession>()
    private val tiles = LinkedHashMap<String, CameraTileView>()
    private lateinit var toolButton: Button
    private lateinit var layoutButton: Button
    private lateinit var scanButton: Button
    private lateinit var addIpButton: Button
    private lateinit var hotspotButton: Button
    private lateinit var resolutionButton: Button
    private lateinit var hotspotManager: PhoneHotspotManager
    private lateinit var hotspotConfigStore: HotspotConfigStore
    private lateinit var hotspotAutoDiscovery: HotspotCameraAutoDiscovery
    @Volatile private var connectionMode = ConnectionMode.CAMERA_WIFI
    @Volatile private var waitingForSystemHotspotReturn = false
    @Volatile private var systemHotspotMode = false
    private lateinit var grid: LinearLayout
    private lateinit var topControls: View
    private lateinit var bottomControls: View
    @Volatile private var scanning = false
    @Volatile private var maxCameras = 4
    @Volatile private var falseColorMode = 0
    @Volatile private var controlsHidden = false
    private var swipeStartY = 0f
    @Volatile private var renderWidth = 1024
    @Volatile private var renderHeight = 576
    @Volatile private var gridLayoutMode = GridLayoutMode.TWO_BY_TWO
    private lateinit var globalPlaybackButton: TextView
    private lateinit var globalPreviousButton: TextView
    private lateinit var globalNextButton: TextView
    private lateinit var globalRecordButton: TextView
    private lateinit var globalHoldButton: TextView
    private lateinit var osdButton: Button
    private lateinit var falseColorButton: Button
    private lateinit var histogramButton: Button
    private val globalSettingButtons = LinkedHashMap<CameraSettingKey, TextView>()
    @Volatile private var osdVisible = true
    @Volatile private var globalHold = false
    @Volatile private var globalRecordRequested = false
    @Volatile private var globalRecordCommandKnown = false
    @Volatile private var globalRecordTakeCounter = 0
    @Volatile private var playbackToggleBusy = false
    @Volatile private var histogramVisible = false
    private val slotAssignments = arrayOfNulls<String>(4)
    private lateinit var reconnectCoordinator: CameraReconnectCoordinator
    @Volatile private var reconnectScanning = false
    @Volatile private var lastReconnectAttemptMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.keepScreenOn = true
        enterSystemFullscreen()
        hotspotManager = PhoneHotspotManager(this)
        hotspotConfigStore = HotspotConfigStore(this)
        hotspotAutoDiscovery = HotspotCameraAutoDiscovery(this)
        reconnectCoordinator = CameraReconnectCoordinator(
            this,
            onNetworkChanged = { onCameraNetworkChanged() },
            onPeriodicCheck = { checkCameraReconnects() }
        )
        setContentView(buildUi())
        // Startup monitor view is always the four-frame 2x2 canvas. Once cameras
        // are discovered, automatic layout follows the connected camera count.
        applyAutomaticLayoutForCameraCount(0)
        rebuildGrid()
        scanButton.setOnClickListener { scan() }
        toolButton.setOnClickListener { showToolMenu(toolButton) }
        layoutButton.setOnClickListener { showLayoutMenu(layoutButton) }
        osdButton.setOnClickListener { setOsdVisible(!osdVisible) }
        falseColorButton.setOnClickListener { toggleFalseColor() }
        histogramButton.setOnClickListener { setHistogramVisible(!histogramVisible) }
        resolutionButton.setOnClickListener {
            if (renderWidth == 1024) {
                renderWidth = 640
                renderHeight = 360
            } else {
                renderWidth = 1024
                renderHeight = 576
            }
            updateResolutionUi()
        }
        // Auto-discover and connect as soon as the UI is ready.
        window.decorView.postDelayed({ scan() }, 350L)
        reconnectCoordinator.start()
        // No periodic PTP reconnect. Settings are synchronized from Sony 0xC203
        // PropertyChanged events while the existing Live View session stays open.
    }

    override fun onResume() {
        super.onResume()
        if (waitingForSystemHotspotReturn) {
            waitingForSystemHotspotReturn = false
            systemHotspotMode = true
            connectionMode = ConnectionMode.PHONE_HOTSPOT
            updateHotspotButton()
            window.decorView.postDelayed({
                startHotspotAutoDiscovery()
            }, 900L)
        }
    }

    override fun onDestroy() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        if (::reconnectCoordinator.isInitialized) reconnectCoordinator.close()
        if (::hotspotAutoDiscovery.isInitialized) hotspotAutoDiscovery.close()
        if (::hotspotManager.isInitialized) hotspotManager.close()
        networkExecutor.shutdownNow()
        decodeExecutor.shutdownNow()
        SonyNetworkRoute.clearProcessBinding(this)
        super.onDestroy()
    }

    private fun buildUi(): View {
        val baseLeft = dp(2)
        val baseTop = dp(1)
        val baseRight = dp(2)
        val baseBottom = dp(1)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(baseLeft, baseTop, baseRight, baseBottom)
            setOnApplyWindowInsetsListener { view, insets ->
                val left: Int
                val top: Int
                val right: Int
                val bottom: Int
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(
                        android.view.WindowInsets.Type.statusBars() or
                            android.view.WindowInsets.Type.navigationBars() or
                            android.view.WindowInsets.Type.displayCutout()
                    )
                    left = bars.left
                    top = bars.top
                    right = bars.right
                    bottom = bars.bottom
                } else {
                    left = insets.systemWindowInsetLeft
                    top = insets.systemWindowInsetTop
                    right = insets.systemWindowInsetRight
                    bottom = insets.systemWindowInsetBottom
                }
                view.setPadding(
                    baseLeft + left,
                    baseTop + top,
                    baseRight + right,
                    baseBottom + bottom
                )
                insets
            }
        }

        fun sonyTopText(label: String, size: Float = 10f, bold: Boolean = false) = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = size
            gravity = Gravity.CENTER
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setPadding(dp(6), 0, dp(6), 0)
        }

        // Keep backing buttons alive because the connection engine and TOOL menu
        // still dispatch through them. Only TOOL is presented in the Sony-style header.
        scanButton = Button(this).apply { text = "SCAN" }
        addIpButton = Button(this).apply { text = "+IP" }
        hotspotButton = Button(this).apply { text = "HOTSPOT" }
        resolutionButton = Button(this).apply { text = "RES HIGH 1024" }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xaa000000.toInt())
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }

        val clock = sonyTopText("--:--", 10f, true)
        fun updateClock() {
            val now = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
            clock.text = now
        }
        updateClock()
        clock.post(object : Runnable {
            override fun run() {
                updateClock()
                clock.postDelayed(this, 30_000L)
            }
        })
        topBar.addView(clock, LinearLayout.LayoutParams(dp(54), LinearLayout.LayoutParams.MATCH_PARENT))

        toolButton = Button(this).apply {
            text = "≡  TOOL ▼"
            textSize = 10f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            minWidth = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dp(8), 0, dp(8), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(0, Color.TRANSPARENT)
            }
        }
        topBar.addView(toolButton, LinearLayout.LayoutParams(dp(92), dp(34)))

        falseColorButton = Button(this).apply {
            text = "FALSE COLOR"
            textSize = 8f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            minWidth = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dp(5), 0, dp(5), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x22000000)
                setStroke(dp(1), 0xff55585d.toInt())
                cornerRadius = dp(3).toFloat()
            }
        }
        topBar.addView(falseColorButton, LinearLayout.LayoutParams(dp(82), dp(30)).apply { marginEnd = dp(5) })

        histogramButton = Button(this).apply {
            text = "HIST"
            textSize = 8f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            minWidth = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dp(5), 0, dp(5), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x22000000)
                setStroke(dp(1), 0xff55585d.toInt())
                cornerRadius = dp(3).toFloat()
            }
        }
        topBar.addView(histogramButton, LinearLayout.LayoutParams(dp(54), dp(30)).apply { marginEnd = dp(5) })

        topBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        osdButton = Button(this).apply {
            text = "OSD ON"
            textSize = 9f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            minWidth = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dp(8), 0, dp(8), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x22000000)
                setStroke(dp(1), 0xff55585d.toInt())
                cornerRadius = dp(3).toFloat()
            }
        }
        topBar.addView(osdButton, LinearLayout.LayoutParams(dp(72), dp(30)).apply {
            marginEnd = dp(6)
        })
        resolutionButton.apply {
            text = if (renderWidth == 1024) "RES HIGH" else "RES LOW"
            textSize = 8f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            minWidth = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dp(6), 0, dp(6), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x22000000)
                setStroke(dp(1), 0xff55585d.toInt())
                cornerRadius = dp(3).toFloat()
            }
        }
        topBar.addView(resolutionButton, LinearLayout.LayoutParams(dp(72), dp(30)).apply {
            marginEnd = dp(6)
        })
        layoutButton = Button(this).apply {
            text = gridLayoutMode.buttonLabel()
            textSize = 9f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            minWidth = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dp(8), 0, dp(8), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x22000000)
                setStroke(dp(1), 0xff55585d.toInt())
                cornerRadius = dp(3).toFloat()
            }
        }
        topBar.addView(layoutButton, LinearLayout.LayoutParams(dp(124), dp(30)).apply {
            marginEnd = dp(8)
        })
        topBar.addView(sonyTopText("100%", 9f, true).apply {
            setTextColor(0xff67e56f.toInt())
        }, LinearLayout.LayoutParams(dp(48), dp(34)))

        topControls = topBar
        root.addView(topBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))

        grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
            weightSum = 2f
            setBackgroundColor(Color.BLACK)
        }
        root.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Global camera settings + transport live in one footer. Settings here are
        // intentionally broadcast to every connected camera; per-tile touch focus
        // and the existing tile setting wheel remain available for camera-specific work.
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(3), dp(8), dp(3))
            setBackgroundColor(0xff050708.toInt())
        }

        val allCamLabel = TextView(this).apply {
            text = "ALL CAM"
            setTextColor(0xff54e5d0.toInt())
            textSize = 8f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(4), 0)
        }
        bottomBar.addView(allCamLabel, LinearLayout.LayoutParams(dp(48), dp(42)).apply { marginEnd = dp(3) })

        val globalSettingsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val globalSettingsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = false
            addView(globalSettingsRow, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }

        val globalKeys = listOf(
            CameraSettingKey.RECORD_FRAME_RATE,
            CameraSettingKey.FOCUS_MODE,
            CameraSettingKey.IRIS,
            CameraSettingKey.ISO,
            CameraSettingKey.SHUTTER,
            CameraSettingKey.EXPOSURE_COMPENSATION,
            CameraSettingKey.WHITE_BALANCE,
            CameraSettingKey.LOOK
        )
        globalKeys.forEach { key ->
            val chip = TextView(this).apply {
                text = "${globalSettingShortTitle(key)}\n--"
                setTextColor(Color.WHITE)
                textSize = 8f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(dp(5), 0, dp(5), 0)
                isClickable = true
                isFocusable = true
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0x22000000)
                    setStroke(dp(1), 0xff4b4f54.toInt())
                    cornerRadius = dp(3).toFloat()
                }
                setOnClickListener { showGlobalSettingWheel(this, key) }
            }
            globalSettingButtons[key] = chip
            val width = when (key) {
                CameraSettingKey.SHUTTER -> dp(82)
                CameraSettingKey.WHITE_BALANCE -> dp(74)
                CameraSettingKey.RECORD_FRAME_RATE -> dp(68)
                else -> dp(64)
            }
            globalSettingsRow.addView(chip, LinearLayout.LayoutParams(width, dp(42)).apply { marginEnd = dp(3) })
        }
        bottomBar.addView(globalSettingsScroll, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })

        val transportBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        globalPlaybackButton = TextView(this).apply {
            text = "PLAYBACK"
            setTextColor(Color.WHITE)
            textSize = 8f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x44000000)
                setStroke(dp(1), 0xff777b80.toInt())
                cornerRadius = dp(3).toFloat()
            }
            setOnClickListener { togglePlaybackAllCameras() }
        }
        globalPreviousButton = TextView(this).apply {
            text = "<"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x44000000)
                setStroke(dp(1), 0xff777b80.toInt())
                cornerRadius = dp(5).toFloat()
            }
            setOnClickListener { previousAllPlaybackCameras() }
        }
        globalNextButton = TextView(this).apply {
            text = ">"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x44000000)
                setStroke(dp(1), 0xff777b80.toInt())
                cornerRadius = dp(5).toFloat()
            }
            setOnClickListener { nextAllPlaybackCameras() }
        }
        globalRecordButton = TextView(this).apply {
            text = "REC"
            setTextColor(Color.WHITE)
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xffd91625.toInt())
                setStroke(dp(2), 0xffff6b72.toInt())
            }
            setOnClickListener {
                if (sessions.values.any { it.isPlaybackRequested() }) togglePlayPauseAllPlaybackCameras()
                else toggleAllCameraRecording()
            }
        }
        globalHoldButton = TextView(this).apply {
            text = "HOLD  ○"
            setTextColor(Color.WHITE)
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x66000000)
                setStroke(dp(1), 0xffbfc2c7.toInt())
                cornerRadius = dp(15).toFloat()
            }
            setOnClickListener { setGlobalHold(!globalHold) }
        }
        transportBar.addView(globalPlaybackButton, LinearLayout.LayoutParams(dp(68), dp(34)).apply { marginEnd = dp(5) })
        transportBar.addView(globalPreviousButton, LinearLayout.LayoutParams(dp(38), dp(34)).apply { marginEnd = dp(4) })
        transportBar.addView(globalRecordButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(8) })
        transportBar.addView(globalNextButton, LinearLayout.LayoutParams(dp(38), dp(34)).apply { marginEnd = dp(4) })
        transportBar.addView(globalHoldButton, LinearLayout.LayoutParams(dp(82), dp(30)))
        bottomBar.addView(transportBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(50)))

        bottomControls = bottomBar
        root.addView(bottomBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
        return root
    }

    private fun showLayoutMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        GridLayoutMode.values().forEachIndexed { order, mode ->
            popup.menu.add(0, mode.menuItemId, order, mode.menuLabel)
        }
        popup.setOnMenuItemClickListener { item ->
            val next = GridLayoutMode.fromMenuItemId(item.itemId)
                ?: return@setOnMenuItemClickListener false
            gridLayoutMode = next
            layoutButton.text = next.buttonLabel()
            rebuildGrid()
            true
        }
        popup.show()
    }

    private fun showToolMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "SCAN · Wi-Fi/AP + PTP")
        popup.menu.add(0, 4, 1, "RECONNECT CAMERAS")
        val assignMenu = popup.menu.addSubMenu("ASSIGN CAMERAS TO SLOTS")
        val slotNames = arrayOf("Slot 1 · TOP LEFT", "Slot 2 · TOP RIGHT", "Slot 3 · BOTTOM LEFT", "Slot 4 · BOTTOM RIGHT")
        for (slot in 0 until 4) {
            val slotMenu = assignMenu.addSubMenu(slotNames[slot])
            val automatic = slotAssignments[slot] == null
            slotMenu.add(0, 2000 + slot * 100, 0, if (automatic) "✓ Automatic" else "Automatic")
            tiles.values.forEachIndexed { index, tile ->
                val label = tile.camera.friendlyName.ifBlank { tile.camera.modelName.ifBlank { tile.camera.host } }
                val assigned = slotAssignments[slot] == tile.camera.id
                slotMenu.add(0, 2001 + slot * 100 + index, index + 1, if (assigned) "✓ $label · ${tile.camera.host}" else "$label · ${tile.camera.host}")
            }
        }
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId in 2000..2399) {
                val slot = (item.itemId - 2000) / 100
                val offset = (item.itemId - 2000) % 100
                if (slot in 0..3) {
                    if (offset == 0) clearSlotAssignment(slot)
                    else {
                        val tileList: List<CameraTileView> = tiles.values.toList()
                        val tileIndex: Int = offset - 1
                        if (tileIndex >= 0 && tileIndex < tileList.size) {
                            val selectedTile: CameraTileView = tileList[tileIndex]
                            val selectedCameraId: String = selectedTile.camera.id
                            assignCameraToSlot(slot, selectedCameraId)
                        }
                    }
                    return@setOnMenuItemClickListener true
                }
            }
            when (item.itemId) {
                1 -> { scan(); true }
                4 -> {
                    if (sessions.isEmpty()) {
                        scan()
                    } else {
                        // Manual reconnect is also the explicit way to leave an
                        // intentional Playback hold and restore Remote Live View.
                        sessions.values.forEach { it.resumeLiveView() }
                        refreshPlaybackTransportUi()
                        Toast.makeText(this, "Reconnecting cameras", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }


    /**
     * PLAYBACK is a true toggle. First press emulates the physical Playback key
     * on all connected cameras; the second press emulates the same key again to
     * leave Playback, then restores Remote Live View.
     */
    private fun togglePlaybackAllCameras() {
        if (sessions.isEmpty()) {
            Toast.makeText(this, "No cameras connected", Toast.LENGTH_SHORT).show()
            return
        }

        val exiting = sessions.values.any { it.isPlaybackRequested() }
        val targets = if (exiting) {
            sessions.values.filter { it.isPlaybackRequested() }
        } else {
            sessions.values.toList()
        }
        if (targets.isEmpty()) {
            refreshPlaybackTransportUi()
            return
        }

        globalPlaybackButton.isEnabled = false
        globalPlaybackButton.alpha = 0.55f
        var remaining = targets.size
        var accepted = 0
        targets.forEach { session ->
            val callback: (Boolean) -> Unit = { ok ->
                runOnUiThread {
                    if (ok) accepted++
                    remaining--
                    if (remaining <= 0) {
                        globalPlaybackButton.isEnabled = true
                        globalPlaybackButton.alpha = 1f
                        refreshPlaybackTransportUi()
                        Toast.makeText(
                            this,
                            if (exiting) "Exit Playback $accepted/${targets.size} camera"
                            else "Playback $accepted/${targets.size} camera",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            if (exiting) session.exitPlaybackMode(callback) else session.enterPlaybackMode(callback)
        }
    }

    /**
     * ALL CAM playback transport. The center PLAY button is a single toggle:
     * stopped -> PLAY, playing -> PAUSE. ZV-E10M2 maps both actions to the
     * working physical CENTER/ENTER path inside SonyCameraSession.
     */
    private fun togglePlayPauseAllPlaybackCameras() {
        val targets = sessions.values.filter { it.isPlaybackRequested() }
        if (targets.isEmpty() || playbackToggleBusy) return

        // Keep the PLAY button visually static. ZV-E10M2 uses the same physical
        // CENTER/ENTER key for play and pause, while the per-session state only
        // decides which semantic helper to call on other Sony bodies.
        playbackToggleBusy = true
        var remaining = targets.size
        var accepted = 0
        targets.forEach { session ->
            val callback: (Boolean) -> Unit = { ok ->
                runOnUiThread {
                    if (ok) accepted++
                    remaining--
                    if (remaining <= 0) {
                        playbackToggleBusy = false
                        if (accepted == 0) {
                            Toast.makeText(this, "Play/Pause not supported by camera", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            if (session.isMoviePlaybackPlaying()) session.playbackPause(callback)
            else session.playbackPlay(callback)
        }
    }

    private fun previousAllPlaybackCameras() = navigateAllPlaybackCameras(previous = true)

    private fun nextAllPlaybackCameras() = navigateAllPlaybackCameras(previous = false)

    private fun navigateAllPlaybackCameras(previous: Boolean) {
        val targets = sessions.values.filter { it.isPlaybackRequested() }
        if (targets.isEmpty()) return

        val button = if (previous) globalPreviousButton else globalNextButton
        val unsupportedMessage = if (previous) {
            "Previous file not supported by camera"
        } else {
            "Next file not supported by camera"
        }
        button.isEnabled = false
        button.alpha = 0.55f

        var remaining = targets.size
        var accepted = 0
        targets.forEach { session ->
            val callback: (Boolean) -> Unit = { ok ->
                runOnUiThread {
                    if (ok) accepted++
                    remaining--
                    if (remaining <= 0) {
                        button.isEnabled = true
                        button.alpha = 1f
                        refreshPlaybackTransportUi()
                        if (accepted == 0) {
                            Toast.makeText(this, unsupportedMessage, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            if (previous) session.playbackPrevious(callback) else session.playbackNext(callback)
        }
    }

    private fun refreshPlaybackTransportUi() {
        if (!::globalPlaybackButton.isInitialized) return
        val active = sessions.values.any { it.isPlaybackRequested() }

        globalPlaybackButton.text = if (active) "LIVE VIEW" else "PLAYBACK"
        globalPlaybackButton.setTextColor(if (active) 0xffffd84a.toInt() else Color.WHITE)
        globalPreviousButton.visibility = if (active) View.VISIBLE else View.GONE
        globalNextButton.visibility = if (active) View.VISIBLE else View.GONE
        globalHoldButton.visibility = if (active) View.GONE else View.VISIBLE

        if (active) {
            // Playback transport is intentionally command-driven only. The center
            // control always looks exactly the same: <  PLAY  >. Each tap toggles
            // play/pause on the camera without changing text, color, or shape.
            globalRecordButton.text = "PLAY"
            globalRecordButton.setTextColor(Color.WHITE)
            globalRecordButton.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x44000000)
                setStroke(dp(1), 0xff777b80.toInt())
                cornerRadius = dp(5).toFloat()
            }
            globalRecordButton.layoutParams = LinearLayout.LayoutParams(dp(58), dp(34)).apply { marginEnd = dp(4) }
        } else {
            playbackToggleBusy = false
            // Leave Playback -> restore normal REC/STOP and HOLD transport.
            globalRecordButton.setTextColor(Color.WHITE)
            globalRecordButton.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(8) }
            updateGlobalRecordUi()
        }

        // Tiles retain Playback state only to suppress disconnect warnings; all
        // playback transport controls live in the ALL CAM bar.
        tiles.forEach { (cameraId, tile) ->
            val tileActive = sessions[cameraId]?.isPlaybackRequested() == true
            tile.setPlaybackMode(tileActive, false)
        }
    }

    private fun toggleFalseColor() {
        falseColorMode = if (falseColorMode == FalseColorPalette.OFF) FalseColorPalette.SDR else FalseColorPalette.OFF
        tiles.values.forEach { it.setFalseColorMode(falseColorMode) }
        if (::falseColorButton.isInitialized) {
            val on = falseColorMode != FalseColorPalette.OFF
            falseColorButton.text = if (on) "FALSE ON" else "FALSE COLOR"
            falseColorButton.setTextColor(if (on) 0xffffd84a.toInt() else Color.WHITE)
        }
    }

    private fun setHistogramVisible(visible: Boolean) {
        histogramVisible = visible
        tiles.values.forEach { it.setHistogramVisible(visible) }
        if (::histogramButton.isInitialized) {
            histogramButton.text = if (visible) "HIST ON" else "HIST"
            histogramButton.setTextColor(if (visible) 0xff54e5d0.toInt() else Color.WHITE)
        }
    }

    private fun assignCameraToSlot(slot: Int, cameraId: String) {
        if (slot !in 0..3 || !tiles.containsKey(cameraId)) return
        val existingSlot = slotAssignments.indexOf(cameraId)
        val displaced = slotAssignments[slot]
        if (existingSlot >= 0 && existingSlot != slot) {
            // Swap instead of compacting. Every position is a physical monitor slot:
            // 1 2
            // 3 4
            slotAssignments[existingSlot] = displaced
        }
        slotAssignments[slot] = cameraId
        gridLayoutMode = GridLayoutMode.TWO_BY_TWO
        if (::layoutButton.isInitialized) layoutButton.text = gridLayoutMode.buttonLabel()
        rebuildGrid()
        Toast.makeText(this, "Camera assigned to fixed slot ${slot + 1}", Toast.LENGTH_SHORT).show()
    }

    private fun clearSlotAssignment(slot: Int) {
        if (slot !in 0..3) return
        slotAssignments[slot] = null
        if (slotAssignments.all { it == null }) {
            applyAutomaticLayoutForCameraCount(tiles.size)
        }
        rebuildGrid()
    }

    /**
     * Resolve the four physical monitor positions without compacting holes. A
     * camera assigned to slot 4 must stay bottom-right even if slots 1-3 are empty.
     */
    private fun tilesForFixedSlots(): List<CameraTileView?> {
        val result = arrayOfNulls<CameraTileView>(4)
        val used = HashSet<String>()

        for (slot in 0 until 4) {
            val id = slotAssignments[slot] ?: continue
            val tile = tiles[id] ?: continue
            if (used.add(id)) result[slot] = tile
        }

        // Cameras without an explicit valid assignment fill only truly empty slots.
        // Their placement is stable once addCamera() writes slotAssignments.
        for ((id, tile) in tiles) {
            if (!used.add(id)) continue
            val free = result.indexOfFirst { it == null }
            if (free >= 0) result[free] = tile
        }
        return result.toList()
    }

    private fun togglePhoneHotspot() {
        // Monitor & Control style Tethering (Wi-Fi): use Android's real Portable
        // Hotspot/Internet Sharing network. LocalOnlyHotspot is intentionally not
        // used here because it is a different SoftAP product and Android may not
        // expose it/rout it the same way as tethering.
        if (hotspotManager.isRunning()) hotspotManager.stop()
        showHotspotConfigurationDialog()
    }

    /**
     * The app owns exactly one hotspot profile. Editing this dialog replaces the
     * previous SSID/password; there is no profile list and no multi-hotspot state.
     */
    private fun showHotspotConfigurationDialog() {
        val current = hotspotConfigStore.load()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val note = TextView(this).apply {
            text = "Tethering (Wi-Fi), similar to Sony Monitor & Control, uses Android Portable Hotspot. " +
                "Save the SSID/password below, then Android Hotspot settings will open so you can apply them. " +
                "When you return, SCAN checks the actual tethering subnet, including 10.x.x.x ranges."
            setTextColor(0xff444444.toInt())
            textSize = 13f
            setPadding(0, 0, 0, dp(10))
        }
        val ssidInput = EditText(this).apply {
            hint = "Hotspot name (SSID)"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(current.ssid)
            selectAll()
        }
        val passwordInput = EditText(this).apply {
            hint = "Password (8-63 characters)"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(current.password)
        }
        container.addView(note)
        container.addView(ssidInput)
        container.addView(passwordInput)

        val positiveText = "SAVE & OPEN TETHERING"

        val dialog = AlertDialog.Builder(this)
            .setTitle("Hotspot")
            .setView(container)
            .setPositiveButton(positiveText, null)
            .setNegativeButton("CANCEL", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val config = HotspotConfig(
                    ssid = ssidInput.text.toString().trim(),
                    password = passwordInput.text.toString()
                )
                val error = config.validate()
                if (error != null) {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                // Saving overwrites the one and only hotspot profile.
                hotspotConfigStore.save(config)
                dialog.dismiss()

                // Always use the system Portable Hotspot for MC-style
                // Tethering (Wi-Fi). Third-party apps cannot reliably rewrite
                // tethering SSID/password on all Android builds.
                showSystemHotspotApplyDialog(config)
            }
        }
        dialog.show()
    }

    private fun showSystemHotspotApplyDialog(config: HotspotConfig) {
        AlertDialog.Builder(this)
            .setTitle("Tethering (Wi-Fi)")
            .setMessage(
                "SSID: ${config.ssid}\n\n" +
                    "Password: ${config.password}\n\n" +
                    "Set these exact values in Android Portable Hotspot and enable it. " +
                    "Then set the camera to Tethering (Wi-Fi)/Access Point Set and join this network. " +
                    "When you return, the app scans all tethering interfaces/subnets, including 10.x.x.x."
            )
            .setPositiveButton("OPEN SETTINGS") { _, _ -> openSystemHotspotSettings() }
            .setNegativeButton("CLOSE", null)
            .show()
    }

    private fun openSystemHotspotSettings() {
        waitingForSystemHotspotReturn = true
        val tetherIntent = Intent("android.settings.TETHER_SETTINGS")
        val fallbackIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
        runCatching { startActivity(tetherIntent) }
            .recoverCatching { startActivity(fallbackIntent) }
            .onFailure {
                Toast.makeText(this, "Unable to open Hotspot settings on this device", Toast.LENGTH_LONG).show()
            }
    }

    private fun startPhoneHotspot(config: HotspotConfig = hotspotConfigStore.load()) {
        hotspotButton.isEnabled = false
        hotspotButton.text = "START..."
        hotspotManager.start(config, object : PhoneHotspotManager.Callback {
            override fun onStarted(info: PhoneHotspotManager.HotspotInfo) {
                val saved = hotspotConfigStore.load()
                val exactMatch = info.customConfigurationApplied &&
                    info.ssid == saved.ssid && info.password == saved.password
                if (!exactMatch) {
                    // Never keep a framework-generated AndroidShare_xxxx hotspot when
                    // the user requested a specific SSID/password.
                    hotspotManager.stop()
                    systemHotspotMode = false
                    connectionMode = ConnectionMode.CAMERA_WIFI
                    updateHotspotButton()
                    showCustomHotspotUnavailableDialog(saved, info)
                    return
                }
                systemHotspotMode = false
                connectionMode = ConnectionMode.PHONE_HOTSPOT
                updateHotspotButton()
                startHotspotAutoDiscovery()
                showHotspotReadyDialog(info)
            }

            override fun onStopped() {
                hotspotAutoDiscovery.stop()
                connectionMode = ConnectionMode.CAMERA_WIFI
                updateHotspotButton()
            }

            override fun onFailed(reason: String) {
                connectionMode = ConnectionMode.CAMERA_WIFI
                updateHotspotButton()
            }
        })
    }

    private fun updateHotspotButton() {
        runOnUiThread {
            hotspotButton.isEnabled = true
            hotspotButton.text = when {
                hotspotManager.isRunning() -> "HOT ON"
                systemHotspotMode -> "TETHER ON"
                else -> "TETHER"
            }
        }
    }

    private fun showHotspotReadyDialog(info: PhoneHotspotManager.HotspotInfo) {
        val passwordText = if (info.password.isBlank()) "(no password)" else info.password
        val saved = hotspotConfigStore.load()
        val exactMatch = info.ssid == saved.ssid && info.password == saved.password
        val modeText = if (exactMatch && info.customConfigurationApplied) {
            "Android applied the requested SSID/password."
        } else {
            "Android returned hotspot credentials that differ from the saved configuration. Check system Hotspot settings."
        }
        AlertDialog.Builder(this)
            .setTitle("Phone Hotspot Ready")
            .setMessage(
                "SSID: ${info.ssid}\n\nPassword: $passwordText\n\n$modeText\n\n" +
                    "On the Sony camera: Network/Wi-Fi -> Access Point Set -> select the SSID above. " +
                    "After the camera receives an IP address, the app discovers and connects automatically. SCAN CAMERA is only a manual retry."
            )
            .setPositiveButton("SCAN CAMERA") { _, _ -> scan() }
            .setNeutralButton("EDIT SSID/PASSWORD") { _, _ ->
                hotspotAutoDiscovery.stop()
                hotspotManager.stop()
                connectionMode = ConnectionMode.CAMERA_WIFI
                updateHotspotButton()
                showHotspotConfigurationDialog()
            }
            .setNegativeButton("STOP HOTSPOT") { _, _ ->
                hotspotAutoDiscovery.stop()
                hotspotManager.stop()
                connectionMode = ConnectionMode.CAMERA_WIFI
                updateHotspotButton()
            }
            .show()
    }

    private fun showCustomHotspotUnavailableDialog(
        requested: HotspotConfig,
        actual: PhoneHotspotManager.HotspotInfo
    ) {
        AlertDialog.Builder(this)
            .setTitle("Android did not apply the SSID/password")
            .setMessage(
                "Requested by app:\nSSID: ${requested.ssid}\nPassword: ${requested.password}\n\n" +
                    "Android created: ${actual.ssid}. That generated hotspot has been stopped.\n\n" +
                    "Use system Hotspot settings to apply the requested SSID/password. When you return, camera discovery starts automatically."
            )
            .setPositiveButton("OPEN SYSTEM HOTSPOT") { _, _ -> openSystemHotspotSettings() }
            .setNegativeButton("CLOSE", null)
            .show()
    }

    private fun startHotspotAutoDiscovery() {
        if (hotspotAutoDiscovery.isRunning()) return
        hotspotAutoDiscovery.start(maxCameras, object : HotspotCameraAutoDiscovery.Callback {
            override fun onSearching(attempt: Int) = Unit

            override fun onCamera(camera: SonyCamera) {
                runOnUiThread { addCamera(camera) }
            }

            override fun onCycleFinished(attempt: Int, foundThisCycle: Int, subnet: String) = Unit

            override fun onError(message: String) = Unit
        })
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != HotspotPermissionHelper.REQUEST_CODE) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (granted) {
            startPhoneHotspot(hotspotConfigStore.load())
        } else {
            Toast.makeText(this, "Wi-Fi/Nearby permission is required for Phone Hotspot", Toast.LENGTH_LONG).show()
        }
    }

    private fun scan() {
        if (scanning) return
        scanning = true
        scanButton.isEnabled = false

        sessions.values.forEach { it.close() }
        sessions.clear()
        tiles.clear()
        // A fresh scan starts in Automatic assignment mode. While discovery is
        // running, keep the requested startup four-frame monitor visible.
        slotAssignments.fill(null)
        applyAutomaticLayoutForCameraCount(0)
        rebuildGrid()
        refreshPlaybackTransportUi()

        networkExecutor.execute {
            try {
                val result = SonyPtpDiscovery(this).discover(maxCameras = maxCameras)
                var usable = 0
                result.cameras.forEach { camera ->
                    if (!scanning || usable >= maxCameras) return@forEach
                    usable++
                    runOnUiThread { addCamera(camera) }
                }

            } catch (_: Throwable) {
            } finally {
                scanning = false
                runOnUiThread { scanButton.isEnabled = true }
            }
        }
    }

    private fun showAddIpDialog() {
        val input = EditText(this).apply {
            hint = "Camera IP, e.g. 192.168.1.50"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Add Sony camera by IP")
            .setMessage("The app will try Sony unicast SSDP and Sony DD XML, then connect only by PTP/IP :15740.")
            .setView(input)
            .setPositiveButton("CONNECT") { _, _ ->
                val host = normalizeHost(input.text.toString())
                if (host.isBlank()) {
                    Toast.makeText(this, "Invalid IP/hostname", Toast.LENGTH_SHORT).show()
                } else {
                    addCameraByIp(host)
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun addCameraByIp(host: String) {
        networkExecutor.execute {
            val wifi = SonyNetworkRoute.findWifiNetworkForHost(this, host)
            val directDiscovery = SsdpDiscovery(this)
            val directLocations = runCatching { directDiscovery.discoverHost(host) }.getOrDefault(emptyList())
            var detectedCamera: SonyCamera? = null
            for (location in directLocations) {
                val candidate = runCatching { SonyDeviceDescription.fetch(location, wifi) }.getOrNull()
                if (candidate != null) {
                    detectedCamera = candidate
                    break
                }
            }
            if (detectedCamera == null) {
                detectedCamera = runCatching { SonyDeviceDescription.fetchByHost(host, wifi) }.getOrNull()
            }
            val camera = detectedCamera ?: SonyCamera.manual(host)
            runOnUiThread { addCamera(camera) }
        }
    }

    private fun normalizeHost(raw: String): String {
        var v = raw.trim()
        v = v.removePrefix("http://").removePrefix("https://")
        v = v.substringBefore('/').substringBefore(':').trim()
        return v.takeIf { it.matches(Regex("[A-Za-z0-9._-]+")) }.orEmpty()
    }

    private fun addCamera(camera: SonyCamera, preferredSlot: Int? = null) {
        if (sessions.containsKey(camera.id) || tiles.containsKey(camera.id)) return
        if (tiles.size >= maxCameras) return

        val tile = CameraTileView(this, camera, decodeExecutor).also {
            tiles[camera.id] = it
            it.setFalseColorMode(falseColorMode)
            it.setHistogramVisible(histogramVisible)
            // Automatic is the default. Only a reconnect of a camera that was
            // explicitly assigned to a physical slot restores that manual slot.
            val requested = preferredSlot?.takeIf { it in slotAssignments.indices }
            if (requested != null) slotAssignments[requested] = camera.id
        }
        applyAutomaticLayoutForCameraCount(tiles.size)
        rebuildGrid()

        val session = SonyCameraSession(
            context = this,
            camera = camera,
            executor = networkExecutor,
            onJpeg = { jpeg -> tile.offerJpeg(jpeg) },
            onState = { raw -> tile.setState(raw) },
            onStats = { stats -> tile.setStats(stats) },
            onTelemetry = { telemetry ->
                tile.setTelemetry(telemetry)
                window.decorView.post {
                    updateGlobalRecordUi()
                    updateGlobalSettingUi()
                }
            },
            onNeedsAuth = { tile.setAuthRequired(true) }
        )
        val profile = currentProfile(tiles.size.coerceAtLeast(1))
        tile.setLiveViewProfile(profile)
        tile.setRenderResolution(renderWidth, renderHeight)
        tile.setOnFocusTapListener { x, y -> session.setTouchFocus(x, y) { ok -> tile.setFocusResult(ok) } }
        // Double-tap clears the local marker immediately and sends Sony's dedicated
        // Cancel Remote Touch Operation command (D2E5). No AF half-press is used.
        tile.setOnFocusClearListener { session.cancelTouchFocus() }
        // Touch focus remains per-camera; the old round AF button is removed.
        tile.setOnSettingsTapListener { showCameraControls(camera, session, tile) }
        tile.setOnSettingTapListener { key, anchor -> showSettingWheel(anchor, session, key) }
        tile.setOnSettingScrollListener(null)
        tile.setOnLongClickListener {
            showCameraControls(camera, session, tile)
            true
        }
        session.setLiveViewProfile(profile)
        sessions[camera.id] = session
        tile.setControlsLocked(globalHold)
        tile.setOsdVisible(osdVisible)
        tile.setPlaybackMode(false, false)
        session.start()
        updateGlobalRecordUi()
        updateGlobalSettingUi()
        window.decorView.postDelayed({ updateGlobalSettingUi() }, 900L)
        rebuildGrid()
    }

    private fun onCameraNetworkChanged() {
        // Clear cached route from the previous SSID and give Android a moment to publish
        // LinkProperties for the new DIRECT-* / normal Wi-Fi network.
        SonyNetworkRoute.clearProcessBinding(this)
        window.decorView.postDelayed({
            reconnectDisconnectedSessions(forceDiscovery = true)
        }, 900L)
    }

    private fun checkCameraReconnects() {
        if (isFinishing || isDestroyed) return
        val anyDisconnected = tiles.any { (id, tile) ->
            sessions[id]?.isPlaybackRequested() != true && tile.isStreamDisconnected(5_000L)
        }
        if (anyDisconnected) reconnectDisconnectedSessions(forceDiscovery = false)
    }

    private fun reconnectDisconnectedSessions(forceDiscovery: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        // First retry the existing session. This is fast and preserves the slot.
        tiles.forEach { (id, tile) ->
            val session = sessions[id]
            if (session?.isPlaybackRequested() == true) return@forEach
            if (tile.isStreamDisconnected(4_500L)) {
                session?.retryDirectPtp()
            }
        }

        // Then run discovery to handle DHCP/IP/SSID changes. Rate-limit the expensive /24 scan.
        if (!forceDiscovery && now - lastReconnectAttemptMs < 7_000L) return
        if (reconnectScanning || scanning) return
        lastReconnectAttemptMs = now
        reconnectScanning = true
        networkExecutor.execute {
            try {
                val result = SonyPtpDiscovery(this).discover(
                    maxCameras = maxCameras,
                    ssdpTimeoutMs = 1_100L,
                    connectTimeoutMs = 110
                )
                result.cameras.forEach { discovered ->
                    runOnUiThread { reconcileDiscoveredCamera(discovered) }
                }
            } catch (_: Throwable) {
                // Keep the current tiles visible; the next periodic check retries.
            } finally {
                reconnectScanning = false
            }
        }
    }

    private fun reconcileDiscoveredCamera(discovered: SonyCamera) {
        val sameIdTile = tiles[discovered.id]
        if (sameIdTile != null) {
            if (sessions[discovered.id]?.isPlaybackRequested() == true) return
            if (sameIdTile.camera.host == discovered.host) {
                if (sameIdTile.isStreamDisconnected(4_500L)) sessions[discovered.id]?.retryDirectPtp()
                return
            }
            replaceDisconnectedCamera(discovered.id, discovered)
            return
        }

        // If DHCP changed the ID representation, match a disconnected tile by model/name.
        val match = tiles.entries.firstOrNull { (_, tile) ->
            tile.isStreamDisconnected(4_500L) && camerasLikelySame(tile.camera, discovered)
        }
        if (match != null) {
            replaceDisconnectedCamera(match.key, discovered)
        } else if (tiles.size < maxCameras) {
            addCamera(discovered)
        }
    }

    private fun camerasLikelySame(a: SonyCamera, b: SonyCamera): Boolean {
        if (a.serialNumber.isNotBlank() && b.serialNumber.isNotBlank()) return a.serialNumber == b.serialNumber
        val am = a.modelName.trim().uppercase()
        val bm = b.modelName.trim().uppercase()
        if (am.isNotBlank() && bm.isNotBlank() && am == bm) return true
        val af = a.friendlyName.trim().uppercase()
        val bf = b.friendlyName.trim().uppercase()
        return af.isNotBlank() && bf.isNotBlank() && af == bf
    }

    private fun replaceDisconnectedCamera(oldId: String, discovered: SonyCamera) {
        val oldTile = tiles[oldId] ?: return
        if (!oldTile.isStreamDisconnected(4_500L)) return
        val slot = slotAssignments.indexOf(oldId)
        sessions.remove(oldId)?.close()
        tiles.remove(oldId)
        if (slot >= 0) slotAssignments[slot] = null
        addCamera(discovered, preferredSlot = slot.takeIf { it >= 0 })
        rebuildGrid()
    }

    private fun toggleAllCameraRecording() {
        if (sessions.isEmpty()) {
            Toast.makeText(this, "No cameras connected", Toast.LENGTH_SHORT).show()
            return
        }

        // REC/STOP is intentionally command-driven. Some Sony bodies keep D21D
        // stale while Remote Live View is active, even though D2C8 REC/STOP works.
        // The user's action therefore owns the monitor state; camera confirmation
        // remains diagnostic only and never blocks STOP or the red REC indication.
        val cameraReportsRecording = sessions.values.any { it.isMovieRecording() }
        val currentIntent = if (globalRecordCommandKnown) globalRecordRequested else cameraReportsRecording
        val shouldRecord = !currentIntent
        globalRecordCommandKnown = true
        globalRecordRequested = shouldRecord

        // Update every tile immediately so REC means REC on the monitor: border
        // blink and elapsed counter start on the button press, not on D21D/C203.
        tiles.values.forEach { it.setRecordingCommandState(shouldRecord) }

        // Give every camera worker the same future monotonic barrier. Each PTP
        // session waits locally, then presses Sony REC as close to this instant
        // as its current GetObject transaction allows.
        val targetElapsedNs = SystemClock.elapsedRealtimeNanos() + 350_000_000L
        if (shouldRecord) {
            globalRecordTakeCounter = (globalRecordTakeCounter + 1).coerceAtLeast(1)
        }
        val take = globalRecordTakeCounter.coerceAtLeast(1)
        sessions.values.forEach { it.setMovieRecordingAt(shouldRecord, targetElapsedNs, take) }
        updateGlobalRecordUi()

        Toast.makeText(
            this,
            if (shouldRecord) String.format(java.util.Locale.US, "REC · R%02d", take) else "STOP",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateGlobalRecordUi() {
        if (!::globalRecordButton.isInitialized) return
        if (sessions.values.any { it.isPlaybackRequested() }) {
            if (::globalPlaybackButton.isInitialized) refreshPlaybackTransportUi()
            return
        }
        val cameraReportsRecording = sessions.values.any { it.isMovieRecording() }
        val active = if (globalRecordCommandKnown) globalRecordRequested else cameraReportsRecording

        globalRecordButton.isEnabled = true
        globalRecordButton.alpha = 1f
        globalRecordButton.text = if (active) "STOP" else "REC"
        globalRecordButton.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(if (active) 0xffa20f18.toInt() else 0xffd91625.toInt())
            setStroke(dp(2), if (active) 0xffff8a8f.toInt() else 0xffff6b72.toInt())
        }
    }

    private fun setGlobalHold(locked: Boolean) {
        globalHold = locked
        tiles.values.forEach { it.setControlsLocked(locked) }
        if (::globalHoldButton.isInitialized) {
            globalHoldButton.text = if (locked) "HOLD  ●" else "HOLD  ○"
            globalHoldButton.setTextColor(if (locked) 0xffffdf54.toInt() else Color.WHITE)
        }
        updateGlobalSettingUi()
    }

    private fun showCameraControls(camera: SonyCamera, session: SonyCameraSession, tile: CameraTileView) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }

        panel.addView(TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xff333333.toInt())
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(8), dp(10), dp(8))
            text = "STATUS\n${tile.friendlyState()}"
        })

        panel.addView(TextView(this).apply {
            setTextColor(0xffb8bcc2.toInt())
            textSize = 12f
            setPadding(0, dp(9), 0, dp(4))
            text = buildString {
                append(camera.modelName.ifBlank { camera.friendlyName }).append(" · ").append(camera.host)
                if (camera.macAddress.isNotBlank()) append(" · MAC ").append(camera.macAddress)
                if (camera.serialVersion.isNotBlank()) append("\nSerialVersion=").append(camera.serialVersion)
                if (camera.serverType.isNotBlank()) append("\n").append(camera.serverType)
                append("\nProtocol=Sony PTP/IP only · PTP :15740")
                if (camera.ptpVersion.isNotBlank()) append(" v").append(camera.ptpVersion)
                session.cameraPtpInfoSummary().takeIf { it.isNotBlank() }?.let { append("\nPTP DeviceInfo: ").append(it) }
                if (session.isPlaybackRequested()) append("\nOperating target: PLAYBACK")
            }
        })

        val cachedSettings = session.cameraSettingDescriptors()
        panel.addView(TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(10), 0, dp(4))
            text = "CAMERA SETTINGS"
        })
        panel.addView(TextView(this).apply {
            setTextColor(0xffb8bcc2.toInt())
            textSize = 11f
            setPadding(0, 0, 0, dp(5))
            text = if (cachedSettings.isEmpty()) {
                "Camera setting cache is not ready. Live View remains prioritized."
            } else {
                cachedSettings.joinToString("\n") { descriptor ->
                    "${descriptor.title}: ${descriptor.currentLabel}"
                }
            }
        })

        AlertDialog.Builder(this)
            .setTitle(camera.friendlyName)
            .setView(panel)
            .create()
            .apply {
                setCanceledOnTouchOutside(true)
                show()
            }
    }



    private fun globalSettingShortTitle(key: CameraSettingKey): String = when (key) {
        CameraSettingKey.FOCUS_MODE -> "FOCUS"
        CameraSettingKey.IRIS -> "IRIS"
        CameraSettingKey.ISO -> "ISO"
        CameraSettingKey.SHUTTER -> "SHUTTER"
        CameraSettingKey.EXPOSURE_COMPENSATION -> "EV"
        CameraSettingKey.WHITE_BALANCE -> "WB"
        CameraSettingKey.LOOK -> "LOOK"
        CameraSettingKey.RECORD_FRAME_RATE -> "FPS"
    }

    private fun globalSettingDisplayValue(key: CameraSettingKey, raw: String): String = when (key) {
        CameraSettingKey.ISO -> raw.removePrefix("ISO ").removePrefix("EI ")
        CameraSettingKey.IRIS -> raw.removePrefix("IRIS ")
        CameraSettingKey.SHUTTER -> raw.removePrefix("SHUTTER ")
        CameraSettingKey.WHITE_BALANCE -> raw.removePrefix("WB ")
        CameraSettingKey.LOOK -> raw.removePrefix("LOOK ")
        else -> raw
    }

    /**
     * Footer setting values are cache-only. This function never asks a camera for
     * a fresh property while Live View is streaming, so it cannot disturb FFFFC002.
     */
    private fun updateGlobalSettingUi() {
        if (globalSettingButtons.isEmpty()) return
        globalSettingButtons.forEach { (key, view) ->
            val descriptors = sessions.values.mapNotNull { session ->
                session.cameraSettingDescriptors().firstOrNull { it.key == key }
            }
            val rawValues = if (key == CameraSettingKey.EXPOSURE_COMPENSATION) {
                // In Manual exposure the monitor EV is Sony D1B5 MeteredManualLevel,
                // not the writable 0x5010 exposure-bias value. Mirror the tile EV here.
                sessions.values.map { it.currentTelemetry().exposureCompensation }
            } else {
                descriptors.map { it.currentLabel }
            }
            val values = rawValues
                .filter { it.isNotBlank() && it != "--" }
                .map { globalSettingDisplayValue(key, it) }
                .distinct()
            val value = when {
                values.isEmpty() -> "--"
                values.size == 1 -> values.first()
                else -> "MIX"
            }
            view.text = "${globalSettingShortTitle(key)}\n$value"
            if (key == CameraSettingKey.EXPOSURE_COMPENSATION) {
                val active = sessions.values.any { it.isExposureCompensationAdjustable() }
                view.isEnabled = active && !globalHold
                view.isClickable = active && !globalHold
                view.isFocusable = active && !globalHold
                view.alpha = if (globalHold || !active) 0.42f else 1f
            } else {
                val writable = descriptors.any { it.writable }
                view.isEnabled = writable && !globalHold
                view.alpha = when {
                    globalHold -> 0.42f
                    writable -> 1f
                    else -> 0.42f
                }
            }
        }
    }

    private fun showGlobalSettingWheel(anchor: View, key: CameraSettingKey) {
        if (globalHold) return
        if (sessions.isEmpty()) {
            Toast.makeText(this, "No cameras connected", Toast.LENGTH_SHORT).show()
            return
        }
        if (key == CameraSettingKey.WHITE_BALANCE) {
            showGlobalWhiteBalanceWheel(anchor, null)
            return
        }

        val descriptors = sessions.values.mapNotNull { session ->
            session.cameraSettingDescriptors().firstOrNull { it.key == key }?.let { session to it }
        }
        val reference = descriptors.firstOrNull { it.second.writable } ?: run {
            Toast.makeText(this, "${globalSettingShortTitle(key)}: not writable on connected cameras", Toast.LENGTH_SHORT).show()
            return
        }
        val descriptor = reference.second
        val commonRaw = descriptors.mapNotNull { it.second.currentRawValue }.distinct().singleOrNull()
        val commonLabel = descriptors.map { it.second.currentLabel }.filter { it.isNotBlank() && it != "--" }.distinct().singleOrNull()
        val entries = ArrayList<WheelEntry>()

        if (descriptor.options.isNotEmpty()) {
            descriptor.options.forEach { option ->
                entries.add(
                    WheelEntry(
                        label = option.label,
                        selected = if (key == CameraSettingKey.FOCUS_MODE) {
                            commonLabel != null && option.label == commonLabel
                        } else {
                            commonRaw != null && option.rawValue == commonRaw
                        },
                        action = { applyGlobalSetting(key, option.rawValue, option.label) }
                    )
                )
            }
        } else if (key == CameraSettingKey.IRIS || key == CameraSettingKey.ISO || key == CameraSettingKey.SHUTTER) {
            entries.add(WheelEntry("▲", false) { stepGlobalSetting(key, true) })
            entries.add(WheelEntry(if (commonRaw != null) globalSettingDisplayValue(key, descriptor.currentLabel) else "MIX", true) { })
            entries.add(WheelEntry("▼", false) { stepGlobalSetting(key, false) })
        }

        if (entries.isEmpty()) {
            Toast.makeText(this, "${descriptor.title}: no common value list", Toast.LENGTH_SHORT).show()
            return
        }
        showWheelPopup(anchor, entries, key)
    }

    private fun applyGlobalSetting(key: CameraSettingKey, rawValue: Long, label: String) {
        var targeted = 0
        sessions.values.forEach { session ->
            val descriptor = session.cameraSettingDescriptors().firstOrNull { it.key == key } ?: return@forEach
            if (!descriptor.writable) return@forEach
            when (key) {
                CameraSettingKey.ISO -> {
                    // ISO wire values are not portable between Sony bodies. Resolve
                    // the displayed ISO independently against each camera's own enum.
                    val logicalIso = globalSettingDisplayValue(CameraSettingKey.ISO, label)
                    session.setIso(logicalIso)
                    targeted++
                }
                CameraSettingKey.EXPOSURE_COMPENSATION -> {
                    if (!session.isExposureCompensationAdjustable()) return@forEach
                    val target = descriptor.options.firstOrNull { it.label == label }
                        ?: descriptor.options.firstOrNull {
                            globalSettingDisplayValue(key, it.label) == globalSettingDisplayValue(key, label)
                        }
                        ?: return@forEach
                    session.setCameraSetting(key, target.rawValue)
                    targeted++
                }
                CameraSettingKey.FOCUS_MODE -> {
                    // AF raw values differ across Sony bodies. Resolve the compact
                    // AF/MF label against each camera's own 0x500A enum.
                    val target = descriptor.options.firstOrNull { it.label == label } ?: return@forEach
                    session.setCameraSetting(key, target.rawValue)
                    targeted++
                }
                else -> {
                    // For enum properties, only send a raw value that the target body itself
                    // advertised. This makes mixed Sony bodies safe under one global control.
                    if (descriptor.options.isNotEmpty() && descriptor.options.none { it.rawValue == rawValue }) return@forEach
                    session.setCameraSetting(key, rawValue)
                    targeted++
                }
            }
        }
        if (targeted == 0) {
            Toast.makeText(this, "$label: no compatible cameras", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "$label → $targeted camera${if (targeted == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
        window.decorView.postDelayed({ updateGlobalSettingUi() }, 300L)
        window.decorView.postDelayed({ updateGlobalSettingUi() }, 900L)
    }

    private fun stepGlobalSetting(key: CameraSettingKey, up: Boolean) {
        var targeted = 0
        sessions.values.forEach { session ->
            val descriptor = session.cameraSettingDescriptors().firstOrNull { it.key == key } ?: return@forEach
            if (!descriptor.writable) return@forEach
            session.stepCameraSetting(key, up)
            targeted++
        }
        if (targeted > 0) {
            window.decorView.postDelayed({ updateGlobalSettingUi() }, 300L)
            window.decorView.postDelayed({ updateGlobalSettingUi() }, 900L)
        }
    }

    private fun showGlobalWhiteBalanceWheel(anchor: View, section: String?) {
        val states = sessions.values.mapNotNull { session -> session.whiteBalanceUiState()?.let { session to it } }
        val reference = states.firstOrNull { it.second.modeWritable } ?: run {
            Toast.makeText(this, "WB: not writable on connected cameras", Toast.LENGTH_SHORT).show()
            return
        }
        val state = reference.second
        val modes = states.mapNotNull { it.second.currentModeRaw?.toInt()?.and(0xffff) }.distinct()
        val commonMode = modes.singleOrNull()

        when (section) {
            "CUSTOM" -> {
                val entries = state.customTemperatures.map { option ->
                    WheelEntry(
                        label = option.label,
                        selected = commonMode == 0x8012 && states.mapNotNull { it.second.currentTemperature }.distinct().singleOrNull() == option.rawValue,
                        action = { applyGlobalWhiteBalanceTemperature(option.rawValue, option.label) }
                    )
                }
                if (entries.isNotEmpty()) showWheelPopup(anchor, entries, CameraSettingKey.WHITE_BALANCE)
            }
            "PRESET" -> {
                val entries = state.presetOptions.map { option ->
                    WheelEntry(
                        label = option.label,
                        selected = states.mapNotNull { it.second.currentModeRaw }.distinct().singleOrNull() == option.rawValue,
                        action = { applyGlobalWhiteBalancePreset(option.rawValue, option.label) }
                    )
                }
                if (entries.isNotEmpty()) showWheelPopup(anchor, entries, CameraSettingKey.WHITE_BALANCE)
            }
            else -> {
                val entries = listOf(
                    WheelEntry("AUTO", commonMode == 0x0002) { applyGlobalWhiteBalanceAuto() },
                    WheelEntry("CUSTOM", commonMode == 0x8012) { showGlobalWhiteBalanceWheel(anchor, "CUSTOM") },
                    WheelEntry("PRESET", commonMode != null && commonMode != 0x0002 && commonMode != 0x8012) { showGlobalWhiteBalanceWheel(anchor, "PRESET") }
                )
                showWheelPopup(anchor, entries, CameraSettingKey.WHITE_BALANCE)
            }
        }
    }

    private fun applyGlobalWhiteBalanceAuto() {
        var targeted = 0
        sessions.values.forEach { session ->
            val state = session.whiteBalanceUiState() ?: return@forEach
            if (!state.modeWritable) return@forEach
            session.setWhiteBalanceAuto()
            targeted++
        }
        if (targeted > 0) Toast.makeText(this, "WB AUTO → $targeted camera${if (targeted == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
        window.decorView.postDelayed({ updateGlobalSettingUi() }, 500L)
    }

    private fun applyGlobalWhiteBalancePreset(rawValue: Long, label: String) {
        var targeted = 0
        sessions.values.forEach { session ->
            val state = session.whiteBalanceUiState() ?: return@forEach
            if (!state.modeWritable || state.presetOptions.none { it.rawValue == rawValue }) return@forEach
            session.setWhiteBalancePreset(rawValue)
            targeted++
        }
        if (targeted > 0) Toast.makeText(this, "$label → $targeted camera${if (targeted == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
        window.decorView.postDelayed({ updateGlobalSettingUi() }, 500L)
    }

    private fun applyGlobalWhiteBalanceTemperature(kelvin: Long, label: String) {
        var targeted = 0
        sessions.values.forEach { session ->
            val state = session.whiteBalanceUiState() ?: return@forEach
            if (!state.modeWritable || !state.temperatureWritable) return@forEach
            if (state.customTemperatures.isNotEmpty() && state.customTemperatures.none { it.rawValue == kelvin }) return@forEach
            session.setWhiteBalanceTemperature(kelvin)
            targeted++
        }
        if (targeted > 0) Toast.makeText(this, "$label → $targeted camera${if (targeted == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
        window.decorView.postDelayed({ updateGlobalSettingUi() }, 500L)
    }

    private data class WheelEntry(
        val label: String,
        val selected: Boolean = false,
        val action: () -> Unit
    )

    private fun showSettingWheel(
        anchor: View,
        session: SonyCameraSession,
        key: CameraSettingKey
    ) {
        if (globalHold) return
        if (key == CameraSettingKey.WHITE_BALANCE) {
            showWhiteBalanceWheel(anchor, session, null)
            return
        }

        val descriptor = session.cameraSettingDescriptors().firstOrNull { it.key == key }
        if (descriptor == null || !descriptor.writable) {
            Toast.makeText(this, "${key.name}: camera does not expose a writable setting", Toast.LENGTH_SHORT).show()
            return
        }

        val entries = ArrayList<WheelEntry>()
        if (descriptor.options.isNotEmpty()) {
            descriptor.options.forEach { option ->
                entries.add(
                    WheelEntry(
                        label = option.label,
                        selected = option.rawValue == descriptor.currentRawValue ||
                            (key == CameraSettingKey.FOCUS_MODE && option.label == descriptor.currentLabel),
                        action = { session.setCameraSetting(key, option.rawValue) }
                    )
                )
            }
        } else if (key == CameraSettingKey.IRIS || key == CameraSettingKey.ISO || key == CameraSettingKey.SHUTTER) {
            // Some Sony bodies expose these as step-only controls. Keep the same
            // compact wheel interaction instead of falling back to a dialog.
            entries.add(WheelEntry("▲", false) { session.stepCameraSetting(key, true) })
            entries.add(WheelEntry(descriptor.currentLabel, true) { })
            entries.add(WheelEntry("▼", false) { session.stepCameraSetting(key, false) })
        }

        if (entries.isEmpty()) {
            Toast.makeText(this, "${descriptor.title}: camera does not expose a value list", Toast.LENGTH_SHORT).show()
            return
        }
        showWheelPopup(anchor, entries, key)
    }

    private fun showWhiteBalanceWheel(
        anchor: View,
        session: SonyCameraSession,
        section: String?
    ) {
        val state = session.whiteBalanceUiState() ?: run {
            Toast.makeText(this, "WB: camera does not expose this property", Toast.LENGTH_SHORT).show()
            return
        }
        if (!state.modeWritable) return

        val mode = state.currentModeRaw?.toInt()?.and(0xffff)
        when (section) {
            "CUSTOM" -> {
                val entries = state.customTemperatures.map { option ->
                    WheelEntry(
                        label = option.label,
                        selected = mode == 0x8012 && option.rawValue == state.currentTemperature,
                        action = { session.setWhiteBalanceTemperature(option.rawValue) }
                    )
                }
                if (entries.isNotEmpty()) showWheelPopup(anchor, entries, CameraSettingKey.WHITE_BALANCE)
            }
            "PRESET" -> {
                val entries = state.presetOptions.map { option ->
                    WheelEntry(
                        label = option.label,
                        selected = option.rawValue == state.currentModeRaw,
                        action = { session.setWhiteBalancePreset(option.rawValue) }
                    )
                }
                if (entries.isNotEmpty()) showWheelPopup(anchor, entries, CameraSettingKey.WHITE_BALANCE)
            }
            else -> {
                val entries = listOf(
                    WheelEntry("AUTO", mode == 0x0002) { session.setWhiteBalanceAuto() },
                    WheelEntry("CUSTOM", mode == 0x8012) { showWhiteBalanceWheel(anchor, session, "CUSTOM") },
                    WheelEntry("PRESET", mode != 0x0002 && mode != 0x8012) { showWhiteBalanceWheel(anchor, session, "PRESET") }
                )
                showWheelPopup(anchor, entries, CameraSettingKey.WHITE_BALANCE)
            }
        }
    }

    private fun showWheelPopup(
        anchor: View,
        entries: List<WheelEntry>,
        key: CameraSettingKey
    ) {
        if (entries.isEmpty()) return

        val rowHeight = dp(34)
        val visibleRows = min(7, entries.size.coerceAtLeast(3))
        val popupHeight = rowHeight * visibleRows
        val popupWidth = when (key) {
            CameraSettingKey.LOOK -> dp(230)
            CameraSettingKey.WHITE_BALANCE -> dp(170)
            CameraSettingKey.SHUTTER -> dp(118)
            else -> dp(108)
        }

        val listView = ListView(this).apply {
            dividerHeight = 0
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xee08090b.toInt())
                setStroke(dp(1), 0xff676b70.toInt())
                cornerRadius = dp(3).toFloat()
            }
        }

        var selectedIndex = entries.indexOfFirst { it.selected }
        if (selectedIndex < 0) selectedIndex = 0

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = entries.size
            override fun getItem(position: Int): Any = entries[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val entry = entries[position]
                val text = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                    gravity = Gravity.CENTER
                    textSize = 9.5f
                    typeface = Typeface.DEFAULT
                    setPadding(dp(6), 0, dp(6), 0)
                    maxLines = 1
                }
                text.text = entry.label
                if (position == selectedIndex) {
                    text.setTextColor(Color.BLACK)
                    text.setBackgroundColor(0xffd2d3d4.toInt())
                    text.typeface = Typeface.DEFAULT_BOLD
                } else {
                    text.setTextColor(Color.WHITE)
                    text.setBackgroundColor(Color.TRANSPARENT)
                    text.typeface = Typeface.DEFAULT
                }
                text.layoutParams = android.widget.AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    rowHeight
                )
                return text
            }
        }
        listView.adapter = adapter

        val popup = PopupWindow(listView, popupWidth, popupHeight, true).apply {
            isOutsideTouchable = true
            isTouchable = true
            elevation = dp(7).toFloat()
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            selectedIndex = position
            popup.dismiss()
            entries[position].action()
        }

        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val metrics = resources.displayMetrics
        val anchorCenterX = location[0] + anchor.width / 2
        val anchorCenterY = location[1] + anchor.height / 2
        val x = (anchorCenterX - popupWidth / 2).coerceIn(dp(4), metrics.widthPixels - popupWidth - dp(4))
        val y = (anchorCenterY - popupHeight / 2).coerceIn(dp(4), metrics.heightPixels - popupHeight - dp(4))
        popup.showAtLocation(window.decorView, Gravity.TOP or Gravity.START, x, y)
        listView.post {
            listView.setSelectionFromTop(selectedIndex, popupHeight / 2 - rowHeight / 2)
        }
    }

    private fun setOsdVisible(visible: Boolean) {
        osdVisible = visible
        if (::osdButton.isInitialized) {
            osdButton.text = if (visible) "OSD ON" else "OSD OFF"
            osdButton.setTextColor(if (visible) Color.WHITE else 0xff8b8f94.toInt())
        }
        tiles.values.forEach { it.setOsdVisible(visible) }
    }

    private fun applyAutomaticLayoutForCameraCount(count: Int) {
        // No camera yet: show the requested four-frame startup canvas. Once one or
        // more cameras are discovered/connected, compact automatically to the
        // matching monitor layout.
        gridLayoutMode = when (count.coerceIn(0, 4)) {
            1 -> GridLayoutMode.ONE
            2 -> GridLayoutMode.TWO
            3 -> GridLayoutMode.TWO_PLUS_ONE
            else -> GridLayoutMode.TWO_BY_TWO
        }
        if (::layoutButton.isInitialized) layoutButton.text = gridLayoutMode.buttonLabel()
    }

    private fun rebuildGrid() {
        grid.removeAllViews()

        val connectedCount = min(maxCameras, tiles.size)
        val profile = currentProfile(connectedCount.coerceAtLeast(1))
        val fixedSlots = tilesForFixedSlots()

        // Slot number is the physical grid position, never the compacted camera
        // index. This keeps OSD identity stable through assignment and reconnect.
        fixedSlots.forEachIndexed { slot, tile ->
            if (tile != null) {
                tile.setDisplayIndex(slot + 1)
                tile.setTelemetryDensity(gridLayoutMode.cameraSlots)
                tile.setLiveViewProfile(profile)
                tile.setDecodeSampleSize(1)
                tile.setRenderResolution(renderWidth, renderHeight)
            }
        }
        sessions.values.forEach { it.setLiveViewProfile(profile) }

        fun cameraCell(slot: Int, tile: CameraTileView?): View {
            if (tile != null) {
                (tile.parent as? ViewGroup)?.removeView(tile)
                return AspectRatioCell(this).apply {
                    setBackgroundColor(0xff08090b.toInt())
                    addView(tile)
                }
            }

            // Empty slots remain visible and numbered; they are not removed or
            // shifted left/up when another slot is empty.
            return FrameLayout(this).apply {
                setBackgroundColor(0xff050607.toInt())
                addView(TextView(this@MainActivity).apply {
                    text = "${slot + 1}"
                    setTextColor(0xff42464b.toInt())
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
        }

        fun addFixedRow(slotIndices: IntArray, weight: Float = 1f) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = slotIndices.size.toFloat()
                gravity = Gravity.CENTER
            }
            slotIndices.forEach { slot ->
                row.addView(
                    cameraCell(slot, fixedSlots.getOrNull(slot)),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                        setMargins(dp(1), dp(1), dp(1), dp(1))
                    }
                )
            }
            grid.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, weight))
        }

        when (gridLayoutMode) {
            GridLayoutMode.ONE -> {
                grid.weightSum = 1f
                addFixedRow(intArrayOf(0))
            }
            GridLayoutMode.TWO -> {
                grid.weightSum = 1f
                addFixedRow(intArrayOf(0, 1))
            }
            GridLayoutMode.TWO_PLUS_ONE -> {
                grid.weightSum = 2f
                addFixedRow(intArrayOf(0, 1))
                addFixedRow(intArrayOf(2))
            }
            GridLayoutMode.TWO_BY_TWO -> {
                grid.weightSum = 2f
                addFixedRow(intArrayOf(0, 1))
                addFixedRow(intArrayOf(2, 3))
            }
        }
    }

    private fun updateResolutionUi() {
        val low = renderWidth == 640
        resolutionButton.text = if (low) "RES LOW" else "RES HIGH"
        tiles.values.forEach { it.setRenderResolution(renderWidth, renderHeight) }
        // Change the source signal at the camera as well as the local render size.
        sessions.values.forEach { it.setLowResolutionMode(low) }
        rebuildGrid()
        Toast.makeText(
            this,
            if (low) "Camera Live View source: LOW" else "Camera Live View source: HIGH",
            Toast.LENGTH_SHORT
        ).show()
    }

    private enum class GridLayoutMode(
        val cameraSlots: Int,
        val menuItemId: Int,
        val menuLabel: String
    ) {
        ONE(1, 101, "x1 : 1 Camera"),
        TWO(2, 102, "x2 : 2 Camera"),
        TWO_PLUS_ONE(3, 103, "2+1 : 3 Camera"),
        TWO_BY_TWO(4, 104, "2x2 : 4 Camera");

        fun buttonLabel(): String = "$menuLabel ▼"

        companion object {
            fun fromMenuItemId(itemId: Int): GridLayoutMode? =
                values().firstOrNull { it.menuItemId == itemId }
        }
    }

    private fun currentProfile(count: Int): LiveViewProfile = if (renderWidth == 640) {
        LiveViewProfile(
            activeCameraCount = count, width = 640, height = 360, fps = 24,
            legacySizeHint = "M",
            modernJpegQuality = LiveViewProfile.QUALITY_LOW,
            label = "LOW 640-class"
        )
    } else {
        LiveViewProfile(
            activeCameraCount = count, width = 1024, height = 576, fps = 24,
            legacySizeHint = "L",
            modernJpegQuality = LiveViewProfile.QUALITY_HIGH,
            label = "SOURCE HIGH 1024-class"
        )
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> swipeStartY = ev.rawY
            MotionEvent.ACTION_UP -> {
                val dy = ev.rawY - swipeStartY
                if (dy < -dp(70)) setControlsHidden(true)
                else if (dy > dp(70)) setControlsHidden(false)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun setControlsHidden(hidden: Boolean) {
        if (controlsHidden == hidden) return
        controlsHidden = hidden
        topControls.visibility = if (hidden) View.GONE else View.VISIBLE
        bottomControls.visibility = if (hidden) View.GONE else View.VISIBLE
        // System bars stay hidden in both modes; swiping only toggles our monitor controls.
        enterSystemFullscreen()
    }

    private fun enterSystemFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars()
                )
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterSystemFullscreen()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

}
