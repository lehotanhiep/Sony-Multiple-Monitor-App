package com.example.sonymultilive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.max

/** Smooth, lightweight luma histogram for monitor assistance. */
class HistogramOverlayView(context: Context) : View(context) {
    private val binCount = 128
    private val bins = FloatArray(binCount)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x44ffffff
        style = Paint.Style.FILL
    }
    private val bg = Paint().apply { color = 0x77000000 }

    fun updateFrom(bitmap: Bitmap) {
        if (bitmap.width <= 0 || bitmap.height <= 0 || bitmap.isRecycled) return
        val raw = FloatArray(binCount)
        val stepX = (bitmap.width / 128).coerceAtLeast(2)
        val stepY = (bitmap.height / 72).coerceAtLeast(2)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val c = bitmap.getPixel(x, y)
                val luma = ((Color.red(c) * 54 + Color.green(c) * 183 + Color.blue(c) * 19) shr 8).coerceIn(0, 255)
                raw[(luma * (binCount - 1)) / 255] += 1f
                x += stepX
            }
            y += stepY
        }

        // Spatial smoothing removes jagged one-bin spikes.
        val spatial = FloatArray(binCount)
        for (i in 0 until binCount) {
            var sum = 0f
            var weight = 0f
            for (d in -2..2) {
                val j = i + d
                if (j !in 0 until binCount) continue
                val w = when (kotlin.math.abs(d)) { 0 -> 4f; 1 -> 2f; else -> 1f }
                sum += raw[j] * w
                weight += w
            }
            spatial[i] = if (weight > 0f) sum / weight else 0f
        }

        // Temporal EMA keeps the graph stable while retaining responsiveness.
        synchronized(bins) {
            for (i in bins.indices) bins[i] = bins[i] * 0.68f + spatial[i] * 0.32f
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val local = synchronized(bins) { bins.copyOf() }
        val peak = local.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        val baseY = height.toFloat() - 3f
        val usableH = max(1f, height.toFloat() - 7f)
        val step = if (local.size > 1) width.toFloat() / (local.size - 1).toFloat() else width.toFloat()
        val path = Path()
        val fillPath = Path()
        var prevX = 0f
        var prevY = baseY
        for (i in local.indices) {
            val x = i * step
            val y = baseY - (local[i] / peak) * usableH
            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, baseY)
                fillPath.lineTo(x, y)
            } else {
                val midX = (prevX + x) * 0.5f
                val midY = (prevY + y) * 0.5f
                path.quadTo(prevX, prevY, midX, midY)
                fillPath.quadTo(prevX, prevY, midX, midY)
            }
            prevX = x
            prevY = y
        }
        path.lineTo(prevX, prevY)
        fillPath.lineTo(prevX, prevY)
        fillPath.lineTo(prevX, baseY)
        fillPath.close()
        canvas.drawPath(fillPath, fill)
        canvas.drawPath(path, paint)
    }
}
