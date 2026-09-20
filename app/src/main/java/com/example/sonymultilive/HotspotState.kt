package com.example.sonymultilive

sealed class HotspotState {
    object Stopped : HotspotState()
    object Starting : HotspotState()
    data class Running(val ssid: String, val password: String) : HotspotState()
    data class Error(val reason: String) : HotspotState()
}
