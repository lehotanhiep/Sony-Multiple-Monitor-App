package com.example.sonymultilive

data class CameraTelemetry(
    val recording: Boolean = false,
    val recordFrameRate: String = "--",
    val focusMode: String = "--",
    val iris: String = "--",
    val iso: String = "--",
    val shutter: String = "--",
    val exposureCompensation: String = "--",
    val evDisplayEnabled: Boolean = false,
    val whiteBalance: String = "--",
    val look: String = "--",
    val batteryPercent: Int? = null,
    val storageFreeBytes: Long? = null,
    val storageCapacityBytes: Long? = null,
    val storageLabel: String = "",
    val cardRemainingSeconds: Long? = null,
    val recordStartedElapsedMs: Long? = null,
    val recordTakeNumber: Int = 0
)
