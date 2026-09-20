package com.example.sonymultilive

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.content.Context
import kotlin.math.roundToInt

/**
 * Crash-safe false-color implementation inspired by the Monitor & Control
 * false-color scale. It intentionally avoids RuntimeShader/AGSL because some
 * phone GPU drivers were observed to terminate the app when that effect was
 * enabled. False color is applied on the decode worker, never the UI thread.
 */
object FalseColorPalette {
    const val OFF = 0
    const val SDR = 1
    const val SLOG3 = 2

    private const val DEEP_BLUE = 0xff111fd4.toInt()
    private const val LIGHT_BLUE = 0xffb9deee.toInt()
    private const val GREEN = 0xff66ef45.toInt()
    private const val MAGENTA = 0xffe86ee5.toInt()
    private const val CYAN = 0xff66e4e8.toInt()
    private const val YELLOW = 0xfffff35a.toInt()
    private const val RED = 0xffef4034.toInt()

    fun process(source: Bitmap, mode: Int): Bitmap {
        if (mode == OFF) return source
        return try {
            val width = source.width
            val height = source.height
            val pixels = IntArray(width * height)
            source.getPixels(pixels, 0, width, 0, 0, width, height)
            for (i in pixels.indices) {
                val c = pixels[i]
                val r = (c ushr 16) and 0xff
                val g = (c ushr 8) and 0xff
                val b = c and 0xff
                // Rec.709 luma. False-color mapping follows the same visual bands
                // exposed by Monitor & Control: blue shadows, green mid band,
                // magenta reference band, cyan/yellow highlights and red clipping.
                val luma = ((54 * r + 183 * g + 19 * b) ushr 8).coerceIn(0, 255)
                val ire = if (mode == SLOG3) slog3DisplayIre(luma) else sdrIre(luma)
                pixels[i] = colorForIre(ire, luma)
            }
            if (source.isMutable && source.config == Bitmap.Config.ARGB_8888) {
                source.setPixels(pixels, 0, width, 0, 0, width, height)
                source
            } else {
                Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            }
        } catch (_: Throwable) {
            // False color is monitor assistance only. A bad bitmap/driver/memory
            // condition must never terminate live view. Fall back to the source.
            source
        }
    }

    private fun sdrIre(luma: Int): Double = luma / 255.0 * 116.0 - 7.0

    /**
     * Display-oriented S-Log3 approximation. This is not a LUT conversion of the
     * camera file; it shifts the monitor-assist false-color zones so middle gray
     * and highlight bands are useful on a log-looking live feed.
     */
    private fun slog3DisplayIre(luma: Int): Double {
        val x = luma / 255.0
        val lifted = ((x - 0.06) / 0.86).coerceIn(0.0, 1.0)
        return lifted * 116.0 - 7.0
    }

    fun colorForIre(ire: Double, originalLuma: Int = 128): Int {
        val gray = originalLuma.coerceIn(0, 255)
        val neutral = Color.rgb(gray, gray, gray)
        return when {
            ire < 0.0 -> Color.rgb(16, 16, 16)
            ire < 10.0 -> DEEP_BLUE
            ire < 20.0 -> LIGHT_BLUE
            ire < 40.0 -> neutral
            ire < 50.0 -> GREEN
            ire < 56.0 -> neutral
            ire < 61.0 -> MAGENTA
            ire < 80.0 -> neutral
            ire < 84.0 -> CYAN
            ire < 100.0 -> YELLOW
            else -> RED
        }
    }
}

class FalseColorLegendView(context: Context) : View(context) {
    var mode: Int = FalseColorPalette.OFF
        set(value) {
            field = value
            visibility = if (value == FalseColorPalette.OFF) GONE else VISIBLE
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 9f * resources.displayMetrics.scaledDensity
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.RIGHT
        setShadowLayer(2f, 0f, 0f, Color.BLACK)
    }
    private val labels = intArrayOf(109, 100, 90, 80, 70, 60, 50, 40, 30, 20, 10, 0, -7)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mode == FalseColorPalette.OFF || width <= 0 || height <= 0) return
        paint.color = 0xaa000000.toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        val labelW = width * 0.56f
        val barLeft = labelW + 3f
        val barRight = width - 3f
        val topPad = 4f
        val bottomPad = 4f
        val usable = (height - topPad - bottomPad).coerceAtLeast(1f)

        fun yFor(ire: Double): Float {
            val t = ((109.0 - ire) / 116.0).coerceIn(0.0, 1.0)
            return topPad + (usable * t).toFloat()
        }

        for (ire in 0 until 116) {
            val actual = 109.0 - ire
            val y1 = yFor(actual)
            val y2 = yFor(actual - 1.0) + 1f
            paint.color = FalseColorPalette.colorForIre(actual, (((actual + 7.0) / 116.0) * 255.0).roundToInt())
            canvas.drawRect(barLeft, y1, barRight, y2, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.WHITE
        canvas.drawRect(barLeft, topPad, barRight, topPad + usable, paint)
        paint.style = Paint.Style.FILL

        // Keep ticks at their exact IRE locations, but separate text labels when
        // a 2x2 tile is too short for adjacent values such as 109 and 100. This
        // preserves the scale while preventing numbers from drawing over each other.
        val fm = textPaint.fontMetrics
        val textHeight = (fm.descent - fm.ascent).coerceAtLeast(1f)
        val minCenter = topPad + textHeight * 0.5f
        val maxCenter = topPad + usable - textHeight * 0.5f
        val preferredGap = textPaint.fontSpacing + 2f * resources.displayMetrics.density
        val fitGap = if (labels.size > 1) {
            ((maxCenter - minCenter) / (labels.size - 1)).coerceAtLeast(1f)
        } else preferredGap
        val gap = kotlin.math.min(preferredGap, fitGap)
        val centers = FloatArray(labels.size)
        for (i in labels.indices) {
            val desired = yFor(labels[i].toDouble()).coerceIn(minCenter, maxCenter)
            centers[i] = if (i == 0) desired else kotlin.math.max(desired, centers[i - 1] + gap)
        }
        if (centers.isNotEmpty() && centers.last() > maxCenter) {
            centers[centers.lastIndex] = maxCenter
            for (i in centers.lastIndex - 1 downTo 0) {
                centers[i] = kotlin.math.min(centers[i], centers[i + 1] - gap)
            }
        }
        if (centers.isNotEmpty() && centers[0] < minCenter) {
            val shift = minCenter - centers[0]
            for (i in centers.indices) centers[i] += shift
        }

        val textRight = labelW - 9f
        for (i in labels.indices) {
            val value = labels[i]
            val tickY = yFor(value.toDouble())
            val labelY = centers[i]
            paint.color = Color.WHITE
            canvas.drawRect(labelW - 5f, tickY - 1f, labelW, tickY + 1f, paint)
            // A short leader keeps the adjusted label visually tied to its real IRE tick.
            canvas.drawLine(textRight + 2f, labelY, labelW - 5f, tickY, paint)
            val baseline = labelY - (fm.ascent + fm.descent) * 0.5f
            canvas.drawText(value.toString(), textRight, baseline, textPaint)
        }
    }
}
