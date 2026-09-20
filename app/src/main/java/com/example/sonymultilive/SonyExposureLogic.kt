package com.example.sonymultilive

/** Transport-free exposure-axis gate used by both tile EV and ALL CAM EV. */
object SonyExposureLogic {
    fun isIsoAuto(raw: Long?): Boolean {
        if (raw == null) return false
        val low24 = raw and 0x00ff_ffffL
        return low24 == 0L || low24 == 0x00ff_ffffL || raw == 0xffff_ffffL
    }

    private fun isAutoSentinel(raw: Long?): Boolean =
        raw == 0L || raw == 0xffffL || raw == 0xffff_ffffL

    /**
     * EV is adjustable whenever at least one exposure axis is automatic.
     * PTP ExposureProgramMode 1 is full Manual; every other published program
     * mode has at least Iris or Shutter under automatic control. Auto ISO also
     * enables EV while the body remains in full Manual.
     */
    fun canAdjustExposureCompensation(
        exposureProgramModeRaw: Long?,
        isoRaw: Long?,
        irisRaw: Long?,
        shutterRaw: Long?
    ): Boolean {
        val programMode = exposureProgramModeRaw?.toInt()?.and(0xffff)
        val programHasAutoAxis = programMode != null && programMode != 1
        return programHasAutoAxis || isIsoAuto(isoRaw) || isAutoSentinel(irisRaw) || isAutoSentinel(shutterRaw)
    }
}
