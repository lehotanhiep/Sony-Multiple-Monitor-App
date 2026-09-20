package com.example.sonymultilive

/**
 * Requested live-view profile. The UI target remains 24 fps.
 * Profile object is shared by render and transport. LOW/HIGH can request a
 * camera-side live-view source change when the active Sony transport supports it.
 */
data class LiveViewProfile(
    val activeCameraCount: Int,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 24,
    val legacySizeHint: String = "CAMERA",
    val modernResolutionCode: Int = 0,
    val modernJpegQuality: Int = 0,
    val label: String = "CAMERA NATIVE @ 24fps"
) {
    companion object {
        const val RES_1280_X_720 = 6
        const val RES_1920_X_1080 = 7
        const val QUALITY_LOW = 0
        const val QUALITY_HIGH = 1

        fun forCameraCount(count: Int): LiveViewProfile = LiveViewProfile(
            activeCameraCount = count.coerceAtLeast(1),
            fps = 24,
            label = "CAMERA NATIVE @ 24fps"
        )
    }
}
