package com.example.sonymultilive

fun main() {
    // ZV-1 style: several AF variants plus MF. Compact UI must show AF/MF once.
    val zv1 = listOf(0x0001L, 0x0002L, 0x8001L, 0x8002L, 0x8004L)
    val currentAf = SonyCameraValueFormatter.compactFocusValues(zv1, 0x8004L)
    check(currentAf == listOf(0x8004L, 0x0001L)) { "current AF variant must be preserved: $currentAf" }
    check(currentAf.map(SonyCameraValueFormatter::focus) == listOf("AF", "MF"))

    val currentMf = SonyCameraValueFormatter.compactFocusValues(zv1, 0x0001L)
    check(currentMf == listOf(0x0002L, 0x0001L)) { "MF -> AF should prefer standard AF raw: $currentMf" }
    check(currentMf.map(SonyCameraValueFormatter::focus) == listOf("AF", "MF"))

    val vendorOnly = listOf(0x0001L, 0x8004L, 0x8005L)
    val vendorCompact = SonyCameraValueFormatter.compactFocusValues(vendorOnly, 0x0001L)
    check(vendorCompact == listOf(0x8004L, 0x0001L))

    println("FocusCompactSelfTest PASS")
}
