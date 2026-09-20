package com.aaron.mgbaandroid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** Multi-touch controls; each pointer is evaluated independently, including sliding. */
class TouchControls(context: Context, private val changed: (Set<Int>) -> Unit) : View(context) {
    private data class Button(val label: String, val id: Int, val bounds: RectF)
    private val buttons = mutableListOf<Button>()
    private val pad = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var held = emptySet<Int>()

    init { isFocusable = false; contentDescription = "Game touch controls" }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        release()
        val unit = minOf(52f * resources.displayMetrics.density, w / 9f, h / 6f)
        val margin = unit * 0.3f
        pad.set(margin, h - margin - unit * 3, margin + unit * 3, h - margin)
        buttons.clear()
        fun add(label: String, id: Int, x: Float, y: Float, width: Float = unit) {
            buttons.add(Button(label, id, RectF(x, y, x + width, y + unit)))
        }
        add("B", 1, w - margin - unit * 2.3f, h - margin - unit * 1.3f)
        add("A", 0, w - margin - unit, h - margin - unit * 2.1f)
        add("L", 9, margin, pad.top - unit * 1.2f, unit * 1.5f)
        add("R", 8, w - margin - unit * 1.5f, pad.top - unit * 1.2f, unit * 1.5f)
        add("Select", 2, w / 2f - unit * 1.25f, h - margin - unit, unit * 1.15f)
        add("Start", 3, w / 2f + unit * 0.1f, h - margin - unit, unit * 1.15f)
    }

    private fun hits(x: Float, y: Float): Set<Int> {
        if (pad.contains(x, y)) {
            val dx = (x - pad.centerX()) / (pad.width() / 2)
            val dy = (y - pad.centerY()) / (pad.height() / 2)
            return buildSet {
                if (dx < -0.3f) add(5)
                if (dx > 0.3f) add(4)
                if (dy < -0.3f) add(6)
                if (dy > 0.3f) add(7)
            }
        }
        return buttons.filter { it.bounds.contains(x, y) }.map { it.id }.toSet()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN &&
            !pad.contains(event.x, event.y) && buttons.none { it.bounds.contains(event.x, event.y) }) return false
        val next = mutableSetOf<Int>()
        if (event.actionMasked != MotionEvent.ACTION_CANCEL && event.actionMasked != MotionEvent.ACTION_UP) {
            for (i in 0 until event.pointerCount) {
                if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && i == event.actionIndex) continue
                next.addAll(hits(event.getX(i), event.getY(i)))
            }
        }
        update(next)
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
    private fun update(next: Set<Int>) {
        if (held == next) return
        held = next.toSet()
        changed(held)
        invalidate()
    }
    fun release() = update(emptySet())
    override fun onDetachedFromWindow() { release(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        fun draw(label: String, rect: RectF, active: Boolean) {
            paint.color = if (active) Color.argb(210, 135, 83, 220) else Color.argb(135, 40, 35, 55)
            canvas.drawRoundRect(rect, 12f, 12f, paint)
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = rect.height() * 0.3f
            canvas.drawText(label, rect.centerX(), rect.centerY() - (paint.ascent() + paint.descent()) / 2, paint)
        }
        val size = pad.width() / 3
        for ((id, cell) in listOf(6 to (1 to 0), 7 to (1 to 2), 5 to (0 to 1), 4 to (2 to 1))) {
            val x = pad.left + cell.first * size
            val y = pad.top + cell.second * size
            draw(when (id) { 6 -> "↑"; 7 -> "↓"; 5 -> "←"; else -> "→" }, RectF(x, y, x + size, y + size), id in held)
        }
        buttons.forEach { draw(it.label, it.bounds, it.id in held) }
    }
}
