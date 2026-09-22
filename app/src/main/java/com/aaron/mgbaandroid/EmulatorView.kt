package com.aaron.mgbaandroid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.floor

class EmulatorView(context: Context) : View(context) {
    private val paint = Paint()
    private val gridPaint = Paint().apply { color = 0x28000000; strokeWidth = 1f }
    private val destination = RectF()
    private var bitmap: Bitmap? = null
    private var options = VideoOptions()
    private var original = IntArray(0)
    private var expanded = IntArray(0)
    private var sourceWidth = 0
    private var sourceHeight = 0

    fun configure(value: VideoOptions) {
        val modeChanged = options.mode != value.mode
        options = value
        if (modeChanged && original.isNotEmpty()) updateBitmap()
        invalidate()
    }

    fun submitFrame(pixels: IntArray, width: Int, height: Int) {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return
        if (original.size != width * height) original = IntArray(width * height)
        pixels.copyInto(original, endIndex = width * height)
        sourceWidth = width
        sourceHeight = height
        updateBitmap()
        postInvalidateOnAnimation()
    }

    private fun updateBitmap() {
        val enhance = options.mode == 2
        val factor = if (enhance) 2 else 1
        val w = sourceWidth * factor
        val h = sourceHeight * factor
        val pixels = if (enhance) {
            if (expanded.size != w * h) expanded = IntArray(w * h)
            PixelScaler.scale2x(original, sourceWidth, sourceHeight, expanded)
            expanded
        } else original
        val target = bitmap?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap = it }
        target.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val image = bitmap ?: return
        if (width == 0 || height == 0) return
        val sx = width.toFloat() / sourceWidth
        val sy = height.toFloat() / sourceHeight
        var scale = if (options.aspect == 2) maxOf(sx, sy) else minOf(sx, sy)
        if (options.mode == 0 && options.aspect == 0 && scale >= 1f) scale = floor(scale)
        val fixedRatio = when (options.aspect) {
            3 -> 4f / 3f
            4 -> 16f / 9f
            else -> null
        }
        val drawWidth = when {
            fixedRatio != null -> minOf(width.toFloat(), height * fixedRatio)
            options.aspect == 1 -> width.toFloat()
            else -> sourceWidth * scale
        }
        val drawHeight = when {
            fixedRatio != null -> drawWidth / fixedRatio
            options.aspect == 1 -> height.toFloat()
            else -> sourceHeight * scale
        }
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f
        destination.set(left, top, left + drawWidth, top + drawHeight)
        paint.isFilterBitmap = options.mode == 1
        canvas.drawBitmap(image, null, destination, paint)
        // Grid follows the original handheld pixels, not the enhanced intermediate image.
        if (options.lcd && drawWidth / sourceWidth >= 3f && drawHeight / sourceHeight >= 3f) {
            val save = canvas.save()
            canvas.clipRect(destination)
            for (x in 1 until sourceWidth) {
                val px = left + x * drawWidth / sourceWidth
                canvas.drawLine(px, top, px, top + drawHeight, gridPaint)
            }
            for (y in 1 until sourceHeight) {
                val py = top + y * drawHeight / sourceHeight
                canvas.drawLine(left, py, left + drawWidth, py, gridPaint)
            }
            canvas.restoreToCount(save)
        }
    }
}
