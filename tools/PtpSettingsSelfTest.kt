package com.example.sonymultilive

import java.nio.ByteBuffer
import java.nio.ByteOrder

private fun le16(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
private fun le32(v: Long) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()

private fun propU16(code: Int, current: Int, values: IntArray, secondList: Boolean): ByteArray {
    val out = ArrayList<Byte>()
    fun add(bytes: ByteArray) { bytes.forEach(out::add) }
    add(le16(code)); add(le16(0x0004)); out += 0; out += 1
    add(le16(current)); add(le16(current)); out += 2
    add(le16(values.size)); values.forEach { add(le16(it)) }
    if (secondList) {
        add(le16(values.size)); values.forEach { add(le16(it)) }
    }
    return out.toByteArray()
}

private fun propU32(code: Int, current: Long, values: LongArray, secondList: Boolean): ByteArray {
    val out = ArrayList<Byte>()
    fun add(bytes: ByteArray) { bytes.forEach(out::add) }
    add(le16(code)); add(le16(0x0006)); out += 0; out += 1
    add(le32(current)); add(le32(current)); out += 2
    add(le16(values.size)); values.forEach { add(le32(it)) }
    if (secondList) {
        add(le16(values.size)); values.forEach { add(le32(it)) }
    }
    return out.toByteArray()
}

fun main() {
    val props = listOf(
        // Classic ZV-1 layout: one enum list only.
        propU16(0x5007, 280, intArrayOf(180, 200, 280, 400, 560), secondList = false),
        // Newer Sony layout: a second enum list follows the first one.
        propU32(0xD21E, 800, longArrayOf(100, 200, 400, 800, 1600), secondList = true),
        // Status properties after enum descriptors prove that offsets stay aligned.
        propU16(SonyPtpLiveViewController.PROP_BATTERY_REMAIN, 76, intArrayOf(), secondList = false)
    )
    val body = props.fold(ByteArray(0)) { a, b -> a + b }
    val data = le32(props.size.toLong()) + le32(0) + body

    val client = SonyPtpIpClient("127.0.0.1", initiatorGuid = ByteArray(16), initiatorName = "test")
    val ctrl = SonyPtpLiveViewController(client) {}
    val method = SonyPtpLiveViewController::class.java.getDeclaredMethod("parseExtPropDataset", ByteArray::class.java)
    method.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val parsed = method.invoke(ctrl, data) as Map<Int, SonyPtpLiveViewController.ExtPropSnapshot>

    check(parsed[0x5007]?.currentValue == 280L)
    check(parsed[0x5007]?.enumValues == listOf(180L, 200L, 280L, 400L, 560L))
    check(parsed[0xD21E]?.currentValue == 800L)
    check(parsed[0xD21E]?.enumValues?.last() == 1600L)
    check(parsed[SonyPtpLiveViewController.PROP_BATTERY_REMAIN]?.currentValue == 76L)

    val legacyMethod = SonyPtpLiveViewController::class.java.getDeclaredMethod("parseAllSonyProperties", ByteArray::class.java)
    legacyMethod.isAccessible = true
    val legacyParsed = legacyMethod.invoke(ctrl, data) as Collection<*>
    check(legacyParsed.size == 3) { "legacy parser size=${legacyParsed.size}" }
    println("PTP SETTINGS SELF-TEST PASS: single-list + optional second-list + trailing BAT property")
}
