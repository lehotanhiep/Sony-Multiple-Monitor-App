package com.example.sonymultilive

enum class CameraSettingKey {
    FOCUS_MODE,
    IRIS,
    ISO,
    SHUTTER,
    EXPOSURE_COMPENSATION,
    WHITE_BALANCE,
    LOOK,
    RECORD_FRAME_RATE
}

data class CameraSettingOption(
    val rawValue: Long,
    val label: String
)

data class CameraSettingDescriptor(
    val key: CameraSettingKey,
    val title: String,
    val currentLabel: String,
    val currentRawValue: Long?,
    val options: List<CameraSettingOption>,
    val writable: Boolean
)

data class WhiteBalanceUiState(
    val currentModeRaw: Long?,
    val currentModeLabel: String,
    val currentTemperature: Long?,
    val autoOption: CameraSettingOption?,
    val customTemperatures: List<CameraSettingOption>,
    val presetOptions: List<CameraSettingOption>,
    val modeWritable: Boolean,
    val temperatureWritable: Boolean
)
