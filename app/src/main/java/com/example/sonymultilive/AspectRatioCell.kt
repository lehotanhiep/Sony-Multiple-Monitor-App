package com.example.sonymultilive

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.min

/**
 * Equal-size grid cell that centers its first child inside a strict 16:9 box.
 * The cell itself still fills the row/column weight, so 2x2 mode stays perfectly equal.
 */
class AspectRatioCell(context: Context) : FrameLayout(context) {
    private val ratio = 16f / 9f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentW = MeasureSpec.getSize(widthMeasureSpec)
        val parentH = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(parentW, parentH)

        if (childCount == 0) return
        val childWByHeight = (parentH * ratio).toInt()
        val childW = min(parentW, childWByHeight)
        val childH = (childW / ratio).toInt()
        val wSpec = MeasureSpec.makeMeasureSpec(childW, MeasureSpec.EXACTLY)
        val hSpec = MeasureSpec.makeMeasureSpec(childH, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) {
            getChildAt(i).measure(wSpec, hSpec)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val cellW = right - left
        val cellH = bottom - top
        for (i in 0 until childCount) {
            val child: View = getChildAt(i)
            val cw = child.measuredWidth
            val ch = child.measuredHeight
            val cl = (cellW - cw) / 2
            val ct = (cellH - ch) / 2
            child.layout(cl, ct, cl + cw, ct + ch)
        }
    }

    override fun generateDefaultLayoutParams(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
}
