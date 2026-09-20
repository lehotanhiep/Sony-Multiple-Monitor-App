import com.example.sonymultilive.SonyExposureLogic

fun main() {
    check(!SonyExposureLogic.canAdjustExposureCompensation(1, 100, 180, (1L shl 16) or 50L))
    check(SonyExposureLogic.canAdjustExposureCompensation(1, 0x00ff_ffffL, 180, (1L shl 16) or 50L))
    check(SonyExposureLogic.canAdjustExposureCompensation(3, 100, 180, (1L shl 16) or 50L)) // A: shutter auto
    check(SonyExposureLogic.canAdjustExposureCompensation(4, 100, 180, (1L shl 16) or 50L)) // S: iris auto
    check(SonyExposureLogic.canAdjustExposureCompensation(1, 100, 0, (1L shl 16) or 50L))
    check(SonyExposureLogic.canAdjustExposureCompensation(1, 100, 180, 0))
    println("EV AUTO GATE SELF-TEST PASS")
}
