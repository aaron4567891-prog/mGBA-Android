package com.aaron.mgbaandroid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

class EmulatorView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var bitmap: Bitmap? = null

    fun submitFrame(pixels: IntArray, width: Int, height: Int) {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return
        val target = bitmap?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap = it }
        target.setPixels(pixels, 0, width, 0, 0, width, height)
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val image = bitmap ?: return
        val scale = minOf(width.toFloat() / image.width, height.toFloat() / image.height)
        val drawWidth = image.width * scale
        val drawHeight = image.height * scale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f
        canvas.drawBitmap(image, null, android.graphics.RectF(left, top, left + drawWidth, top + drawHeight), paint)
    }
}
