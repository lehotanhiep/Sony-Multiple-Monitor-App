package com.example.sonymultilive

/**
 * Validates one complete JPEG frame before it reaches BitmapFactory.
 * Sony live-view transports occasionally append padding/metadata after EOI;
 * incomplete frames are dropped instead of being rendered as visual glitches.
 */
object JpegFrameSanitizer {
    fun sanitize(bytes: ByteArray): ByteArray? {
        if (bytes.size < 4) return null
        if ((bytes[0].toInt() and 0xff) != 0xff || (bytes[1].toInt() and 0xff) != 0xd8) return null

        var i = bytes.size - 2
        while (i >= 2) {
            if ((bytes[i].toInt() and 0xff) == 0xff && (bytes[i + 1].toInt() and 0xff) == 0xd9) {
                val endExclusive = i + 2
                return if (endExclusive == bytes.size) bytes else bytes.copyOfRange(0, endExclusive)
            }
            i--
        }
        return null
    }
}
