package com.example.sonymultilive

import java.nio.ByteBuffer
import java.nio.ByteOrder

private fun l16(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
private fun l32(v: Long) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()
private fun arrayU16(values: IntArray): ByteArray {
    val b = ByteBuffer.allocate(4 + values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    b.putInt(values.size)
    values.forEach { b.putShort(it.toShort()) }
    return b.array()
}

fun main() {
    // One D103 UInt16Array descriptor. Default ST, current FL.
    val body = l16(0xD103) + l16(0x4004) + byteArrayOf(0, 1) +
        arrayU16(intArrayOf(1)) + arrayU16(intArrayOf(6)) + byteArrayOf(0)
    val data = l32(1) + l32(0) + body
    val client = SonyPtpIpClient("127.0.0.1", initiatorGuid = ByteArray(16), initiatorName = "test")
    val ctrl = SonyPtpLiveViewController(client) {}
    val method = SonyPtpLiveViewController::class.java.getDeclaredMethod("parseExtPropDataset", ByteArray::class.java)
    method.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val parsed = method.invoke(ctrl, data) as Map<Int, SonyPtpLiveViewController.ExtPropSnapshot>
    check(parsed[0xD103]?.currentValue == 6L) { "D103 current FL not parsed: ${parsed[0xD103]}" }

    val lutNames = mapOf(1 to "Andy_Cine_01.cube")
    check(
        SonyCameraValueFormatter.look(
            propertyCode = 0xD0FA,
            raw = 0x0101,
            userLookNames = lutNames,
            customCreativeBaseRaw = 6L
        ) == "User1 : Andy_Cine_01.cube"
    )
    check(SonyCameraValueFormatter.look(0xD03C, 0x0101, lutNames) == "User1 : Andy_Cine_01.cube")
    check(SonyCameraValueFormatter.look(0xD0FA, 1L) == "ST : Standard")
    check(
        SonyCameraValueFormatter.look(
            propertyCode = 0xD23F,
            raw = 11L,
            pictureProfileGammaRaw = 0x0303L
        ) == "PP11 : S-Log3"
    )
    check(
        SonyCameraValueFormatter.look(
            propertyCode = 0xD23F,
            raw = 11L,
            pictureProfileGammaRaw = 0x0003L
        ) == "PP11 : S-Cinetone"
    )
    println("LOOK NAME SELF-TEST PASS: User1 : Andy_Cine_01.cube / PP11 : live Gamma")
}
