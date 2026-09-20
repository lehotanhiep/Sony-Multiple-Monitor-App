package com.example.sonymultilive

/**
 * Converts raw Sony PTP / Camera Remote property values into the labels shown
 * on the camera UI. Keep this separate from SonyCameraSession so transport and
 * presentation-value decoding do not get mixed together.
 */
object SonyCameraValueFormatter {

    /** Sony aperture/FNumber is reported in hundredths (e.g. 280 = F2.8). */
    fun iris(raw: Long): String =
        if (raw <= 0L || raw >= 0xffffL) "--"
        else "F%.1f".format(java.util.Locale.US, raw / 100.0)

    /** Exposure Index used by the Cine EI path (Sony property 0xD022). */
    fun ei(raw: Long): String = when {
        raw <= 0L || raw == 0xffff_ffffL -> "EI AUTO"
        raw in 25L..1_000_000L -> "EI $raw"
        else -> "EI #$raw"
    }

    fun iso(raw: Long): String {
        // Sony CrISOMode occupies bits 24..27; the actual ISO is bits 0..23.
        // Example: 0x10000190 is ISO 400, not the decimal/hex wire value.
        val value = raw and 0x00ff_ffffL
        return when {
            value == 0L || value == 0x00ff_ffffL -> "ISO AUTO"
            value in 25L..1_000_000L -> "ISO $value"
            raw in 25L..1_000_000L -> "ISO $raw"
            else -> "ISO ${raw and 0x00ff_ffffL}"
        }
    }

    /** User requested the monitor to collapse Sony's detailed AF variants to AF/MF. */
    fun focus(raw: Long): String = when (raw.toInt() and 0xffff) {
        0x0001 -> "MF"
        // Standard PTP + Sony vendor AF-S/AF-C/AF-A/DMF/PF values all become AF
        // in the compact monitor UI. ZV-E10/ZV-E10M2 commonly reports 0x8004.
        else -> "AF"
    }

    /**
     * The monitor exposes Focus as the compact AF/MF switch requested by the UI.
     * Sony bodies (notably ZV-1) may advertise several AF-S/AF-C/AF-A/DMF raw
     * values under 0x500A. Showing every raw enum produces repeated "AF" rows.
     * Keep exactly one safe AF wire value plus MF. Prefer the current AF value so
     * opening the wheel does not silently change the body's detailed AF variant.
     */
    fun compactFocusValues(rawValues: List<Long>, currentRaw: Long?): List<Long> {
        if (rawValues.isEmpty()) return emptyList()
        val values = rawValues.distinct()
        val currentAf = currentRaw?.takeIf { current ->
            values.contains(current) && focus(current) == "AF"
        }
        val standardAf = values.firstOrNull { (it.toInt() and 0xffff) == 0x0002 }
        val anyAf = values.firstOrNull { focus(it) == "AF" }
        val mf = values.firstOrNull { (it.toInt() and 0xffff) == 0x0001 }

        val out = ArrayList<Long>(2)
        (currentAf ?: standardAf ?: anyAf)?.let { out.add(it) }
        mf?.let { out.add(it) }
        return out.distinct()
    }

    fun exposureCompensation(raw: Long): String {
        // 0x5010 is signed Int16 on Sony bodies and is expressed in 1/1000 EV.
        val wire = raw and 0xffffL
        val signed = if (wire >= 0x8000L) wire - 0x10000L else wire
        val ev = signed / 1000.0
        return if (kotlin.math.abs(ev) < 0.05) "0.0" else "%+.1f".format(java.util.Locale.US, ev)
    }

    /**
     * Sony CrDeviceProperty_MeteredManualLevel (0xD1B5). This is the moving
     * exposure-meter indication shown by the body in Manual exposure mode, not
     * ExposureBiasCompensation. Sony reports signed Int16 in 1/1000 EV.
     */
    fun meteredManualLevel(raw: Long): String {
        val wire = raw and 0xffffL
        val signed = if (wire >= 0x8000L) wire - 0x10000L else wire
        // Sony may use the Int16 extrema for unavailable/invalid.
        if (signed <= -32768L || signed >= 32767L) return "--"
        val ev = signed / 1000.0
        return if (kotlin.math.abs(ev) < 0.05) "0.0" else "%+.1f".format(java.util.Locale.US, ev)
    }

    fun whiteBalance(raw: Long, colorTemperature: Long? = null): String {
        val v = raw.toInt() and 0xffff
        // Sony 0x5005 values confirmed across recent Alpha/ZV bodies.
        return when (v) {
            0x0001 -> "MANUAL"
            0x0002 -> "AUTO"
            0x0003 -> "ONE PUSH"
            0x0004 -> "DAYLIGHT"
            0x0006 -> "TUNGSTEN"
            0x0007 -> "FLASH"
            0x8001 -> "FL WARM"
            0x8002 -> "FL COOL"
            0x8003 -> "FL DAY WHITE"
            0x8004 -> "FL DAYLIGHT"
            0x8010 -> "CLOUDY"
            0x8011 -> "SHADE"
            0x8012 -> {
                val kelvin = colorTemperature?.takeIf { it in 2000L..15000L }
                if (kelvin != null) "${kelvin}K" else "COLOR TEMP"
            }
            0x8030 -> "UNDERWATER AUTO"
            0x8020 -> "CUSTOM 1"
            0x8021 -> "CUSTOM 2"
            0x8022 -> "CUSTOM 3"
            else -> "WB $v"
        }
    }

    /**
     * LOOK can represent three different Sony properties. Base Look is used by
     * the Cinema/Monitor & Control path; Creative Look and Picture Profile are
     * used by still/movie bodies depending on shooting mode.
     */
    fun look(
        propertyCode: Int,
        raw: Long,
        userLookNames: Map<Int, String> = emptyMap(),
        pictureProfileGammaRaw: Long? = null,
        customCreativeBaseRaw: Long? = null
    ): String = when (propertyCode) {
        0xD03C -> baseLook(raw, userLookNames)
        0xD0FA -> creativeLook(raw, userLookNames, customCreativeBaseRaw)
        0xD23F -> pictureProfile(raw, pictureProfileGammaRaw)
        else -> "LOOK $raw"
    }

    private fun baseLook(raw: Long, userLookNames: Map<Int, String>): String {
        val v = raw.toInt() and 0xffff
        val kind = (v ushr 8) and 0xff
        val index = v and 0xff
        return when {
            kind == 0x01 -> {
                val name = userLookNames[index]?.trim().orEmpty()
                if (name.isNotEmpty()) "User$index : $name" else "User$index : Base Look"
            }
            v == 0x0002 -> "s709 : Sony 709"
            v == 0x0003 -> "709(800%) : Rec.709 800%"
            else -> "Base Look $index"
        }
    }

    private fun creativeLook(
        raw: Long,
        userLookNames: Map<Int, String>,
        customBaseRaw: Long?
    ): String {
        val v = raw.toInt() and 0xffff
        val kind = (v ushr 8) and 0xff
        if (kind == 0x01) {
            val slot = v and 0xff
            // ZV-E10M2 custom looks are LUT-backed. If the 0x9209 cache exposes
            // the imported LUT filename, show that exact .cube basename instead
            // of describing the Creative Look base preset.
            val cubeName = userLookNames[slot]?.trim().orEmpty()
            if (cubeName.isNotEmpty()) return "User$slot : $cubeName"
            val baseName = customBaseRaw?.let(::creativeLookPresetName)
            return if (baseName != null) "User$slot : $baseName" else "User$slot : Custom Look"
        }
        return creativeLookPresetLabel(raw) ?: "Creative Look $v"
    }

    private fun creativeLookPresetLabel(raw: Long): String? {
        val v = raw.toInt() and 0xffff
        val code = when (v) {
            1 -> "ST"
            2 -> "PT"
            3 -> "NT"
            4 -> "VV"
            5 -> "VV2"
            6 -> "FL"
            7 -> "IN"
            8 -> "SH"
            9 -> "BW"
            10 -> "SE"
            11 -> "FL2"
            12 -> "FL3"
            else -> return null
        }
        return "$code : ${creativeLookPresetName(raw)}"
    }

    private fun creativeLookPresetName(raw: Long): String? = when (raw.toInt() and 0xffff) {
        1 -> "Standard"
        2 -> "Portrait"
        3 -> "Neutral"
        4 -> "Vivid"
        5 -> "Vivid 2"
        6 -> "Film"
        7 -> "Instant"
        8 -> "Soft Highkey"
        9 -> "Black & White"
        10 -> "Sepia"
        11 -> "Film 2"
        12 -> "Film 3"
        else -> null
    }

    private fun pictureProfile(raw: Long, gammaRaw: Long?): String {
        val v = raw.toInt() and 0xffff
        if (v == 0) return "PP OFF"
        val prefix = when {
            v in 1..99 -> "PP$v"
            else -> "PP$v"
        }
        val gamma = gammaRaw?.let(::pictureProfileGammaName)
        return if (gamma != null) "$prefix : $gamma" else prefix
    }

    /** Sony PictureProfile_Gamma (0xD0E1). */
    fun pictureProfileGammaName(raw: Long): String? = when (raw.toInt() and 0xffff) {
        0x0001 -> "Movie"
        0x0002 -> "Still"
        0x0003 -> "S-Cinetone"
        0x0101 -> "Cine1"
        0x0102 -> "Cine2"
        0x0103 -> "Cine3"
        0x0104 -> "Cine4"
        0x0201 -> "ITU709"
        0x0202 -> "ITU709(800%)"
        0x0302 -> "S-Log2"
        0x0303 -> "S-Log3"
        0x0401 -> "HLG"
        0x0402 -> "HLG1"
        0x0403 -> "HLG2"
        0x0404 -> "HLG3"
        else -> null
    }

}
