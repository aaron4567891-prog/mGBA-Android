package com.aaron.mgbaandroid

import android.content.Context
import android.view.KeyEvent as K
import android.view.MotionEvent as M
import androidx.appcompat.app.AlertDialog

object ButtonBindings {
    private data class Button(val label: String, val code: Int, val default: Int)
    private val buttons = listOf(
        Button("A", K.KEYCODE_BUTTON_A, 0), Button("B", K.KEYCODE_BUTTON_B, 1),
        Button("X", K.KEYCODE_BUTTON_X, -1), Button("Y", K.KEYCODE_BUTTON_Y, -1),
        Button("L / L1", K.KEYCODE_BUTTON_L1, 9), Button("R / R1", K.KEYCODE_BUTTON_R1, 8),
        Button("L2", K.KEYCODE_BUTTON_L2, -1), Button("R2", K.KEYCODE_BUTTON_R2, -1),
        Button("L3 (left stick click)", K.KEYCODE_BUTTON_THUMBL, -1),
        Button("R3 (right stick click)", K.KEYCODE_BUTTON_THUMBR, -1),
        Button("Start", K.KEYCODE_BUTTON_START, 3), Button("Select", K.KEYCODE_BUTTON_SELECT, 2),
        Button("D-pad up", K.KEYCODE_DPAD_UP, 6), Button("D-pad down", K.KEYCODE_DPAD_DOWN, 7),
        Button("D-pad left", K.KEYCODE_DPAD_LEFT, 5), Button("D-pad right", K.KEYCODE_DPAD_RIGHT, 4))
    private val targets = arrayOf("Unbound", "A", "B", "Select", "Start", "Right", "Left", "Up", "Down", "R", "L")
    private fun prefs(context: Context) = context.getSharedPreferences("emulator", Context.MODE_PRIVATE)
    fun supports(code: Int) = buttons.any { it.code == code }
    fun target(context: Context, code: Int): Int {
        val button = buttons.firstOrNull { it.code == code } ?: return -1
        val p = prefs(context)
        val fallback = if (p.getBoolean("input_swap_ab", false) && button.default in 0..1) 1 - button.default else button.default
        return p.getInt("button_binding_$code", fallback).coerceIn(-1, 9)
    }
    fun reset(context: Context) {
        val editor = prefs(context).edit()
        buttons.forEach { editor.remove("button_binding_${it.code}") }
        editor.apply()
    }
    fun show(context: Context) {
        AlertDialog.Builder(context).setTitle("Button bindings")
            .setItems(buttons.map { "${it.label}: ${targets[target(context, it.code) + 1]}" }.toTypedArray()) { _, index ->
                val button = buttons[index]
                AlertDialog.Builder(context).setTitle("Map ${button.label}")
                    .setSingleChoiceItems(targets, target(context, button.code) + 1) { dialog, choice ->
                        prefs(context).edit().putInt("button_binding_${button.code}", choice - 1).apply()
                        dialog.dismiss(); show(context)
                    }.setNegativeButton("Cancel") { _, _ -> show(context) }.show()
            }.setPositiveButton("Done", null).show()
    }

    class State(private val context: Context) {
        private val keys = mutableSetOf<Pair<Int, Int>>()
        private val axes = mutableMapOf<Int, Set<Int>>()
        fun clear() { keys.clear(); axes.clear() }
        fun key(event: K) {
            val key = event.deviceId to event.keyCode
            if (event.action == K.ACTION_DOWN) keys.add(key)
            if (event.action == K.ACTION_UP) keys.remove(key)
        }
        fun motion(event: M) {
            val codes = mutableSetOf<Int>()
            fun value(axis: Int): Float = if (event.device?.getMotionRange(axis, event.source) != null) event.getAxisValue(axis) else 0f
            val previous = axes[event.deviceId].orEmpty()
            fun press(code: Int, amount: Float) {
                if (amount > if (code in previous) 0.35f else 0.5f) codes.add(code)
            }
            press(K.KEYCODE_BUTTON_L2, maxOf(value(M.AXIS_LTRIGGER), value(M.AXIS_BRAKE)))
            press(K.KEYCODE_BUTTON_R2, maxOf(value(M.AXIS_RTRIGGER), value(M.AXIS_GAS)))
            press(K.KEYCODE_DPAD_LEFT, -value(M.AXIS_HAT_X))
            press(K.KEYCODE_DPAD_RIGHT, value(M.AXIS_HAT_X))
            press(K.KEYCODE_DPAD_UP, -value(M.AXIS_HAT_Y))
            press(K.KEYCODE_DPAD_DOWN, value(M.AXIS_HAT_Y))
            axes[event.deviceId] = codes
        }
        fun held(): Set<Int> = (keys.map { it.second } + axes.values.flatten())
            .map { target(context, it) }.filter { it >= 0 }.toSet()
    }
}
