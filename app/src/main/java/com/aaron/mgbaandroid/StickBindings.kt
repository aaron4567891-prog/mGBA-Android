package com.aaron.mgbaandroid

import android.content.Context
import android.view.InputDevice
import android.view.MotionEvent
import androidx.appcompat.app.AlertDialog

object StickBindings {
    fun autoMap(context: Context) {
        val editor = prefs(context).edit().putBoolean("input_swap_ab", false)
        directions.indices.forEach { editor.remove("stick_binding_$it") }
        editor.apply()
    }
    private val directions = arrayOf("Left stick up", "Left stick down", "Left stick left", "Left stick right",
        "Right stick up", "Right stick down", "Right stick left", "Right stick right")
    private val labels = arrayOf("Unbound", "A", "B", "Select", "Start", "Right", "Left", "Up", "Down", "R", "L")
    private val defaults = intArrayOf(6, 7, 5, 4, -1, -1, -1, -1)
    private fun prefs(context: Context) = context.getSharedPreferences("emulator", Context.MODE_PRIVATE)
    private fun binding(context: Context, index: Int) = prefs(context).getInt("stick_binding_$index", defaults[index]).coerceIn(-1, 9)

    fun show(context: Context) {
        val rows = directions.indices.map { "${directions[it]}: ${labels[binding(context, it) + 1]}" }.toTypedArray()
        AlertDialog.Builder(context).setTitle("Thumbstick bindings")
            .setItems(rows) { _, index ->
                AlertDialog.Builder(context).setTitle(directions[index])
                    .setSingleChoiceItems(labels, binding(context, index) + 1) { dialog, selected ->
                        prefs(context).edit().putInt("stick_binding_$index", selected - 1).apply()
                        dialog.dismiss()
                        show(context)
                    }.setNegativeButton("Cancel") { _, _ -> show(context) }.show()
            }
            .setNeutralButton("Reset") { _, _ ->
                val editor = prefs(context).edit()
                directions.indices.forEach { editor.remove("stick_binding_$it") }
                editor.apply()
                show(context)
            }.setPositiveButton("Done", null).show()
    }

    class State(private val context: Context) {
        private val active = BooleanArray(8)
        fun clear() { active.fill(false) }
        fun read(event: MotionEvent): Set<Int> {
            val device = event.device ?: return emptySet()
            fun axis(code: Int) = event.getAxisValue(code)
            // Android controllers normally use Z/RZ; some expose RX/RY instead.
            val rightX = if (device.getMotionRange(MotionEvent.AXIS_Z, event.source) != null) MotionEvent.AXIS_Z else MotionEvent.AXIS_RX
            val rightY = if (device.getMotionRange(MotionEvent.AXIS_RZ, event.source) != null) MotionEvent.AXIS_RZ else MotionEvent.AXIS_RY
            val axes = intArrayOf(MotionEvent.AXIS_Y, MotionEvent.AXIS_Y, MotionEvent.AXIS_X, MotionEvent.AXIS_X, rightY, rightY, rightX, rightX)
            val result = mutableSetOf<Int>()
            for (i in axes.indices) {
                val range = device.getMotionRange(axes[i], event.source)
                val engage = maxOf(0.3f, range?.flat ?: 0f)
                val threshold = if (active[i]) engage * 0.7f else engage
                val value = axis(axes[i]) * if (i % 4 == 0 || i % 4 == 2) -1f else 1f
                active[i] = range != null && value > threshold
                val id = binding(context, i)
                if (active[i] && id >= 0) result.add(id)
            }
            return result
        }
    }
}
