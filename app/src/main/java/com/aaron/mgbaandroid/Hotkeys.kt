package com.aaron.mgbaandroid

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent as K
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

object Hotkeys {
    private val names = arrayOf("Quick save (slot 1)", "Quick load (slot 1)", "Toggle fast-forward", "Exit App", "Pause / Resume", "Pause", "Play / Resume", "Close game (mGBA home)", "Open menu")
    private val legacyCodes = intArrayOf(-1, K.KEYCODE_BUTTON_R1, K.KEYCODE_BUTTON_L1, K.KEYCODE_BUTTON_THUMBR, K.KEYCODE_BUTTON_START, K.KEYCODE_BUTTON_A, K.KEYCODE_BUTTON_B, K.KEYCODE_BUTTON_X, K.KEYCODE_BUTTON_Y)
    private fun prefs(context: Context) = context.getSharedPreferences("emulator", Context.MODE_PRIVATE)
    private fun binding(context: Context, action: Int): List<Int> {
        val p = prefs(context)
        val stored = p.getString("hotkey_custom_$action", null)
        if (stored != null) return stored.split(',').mapNotNull { it.toIntOrNull() }.distinct().take(2)
        val legacy = p.getInt("hotkey_$action", if (action < 4) action + 1 else 0).coerceIn(0, legacyCodes.lastIndex)
        return if (legacy == 0) emptyList() else listOf(K.KEYCODE_BUTTON_THUMBL, legacyCodes[legacy])
    }
    private fun label(keys: List<Int>) = if (keys.isEmpty()) "Disabled" else keys.joinToString(" + ") { K.keyCodeToString(it).removePrefix("KEYCODE_").removePrefix("BUTTON_") }
    private fun save(context: Context, action: Int, keys: List<Int>) {
        val editor = prefs(context).edit().putString("hotkey_custom_$action", keys.joinToString(","))
        if (keys.isNotEmpty()) names.indices.filter { it != action && binding(context, it).toSet() == keys.toSet() }
            .forEach { editor.putString("hotkey_custom_$it", "") }
        editor.apply()
    }
    fun show(context: Context) {
        AlertDialog.Builder(context).setTitle("Hotkeys")
            .setItems(names.indices.map { "${names[it]}: ${label(binding(context, it))}" }.toTypedArray()) { _, action -> capture(context, action) }
            .setNeutralButton("Disable all") { _, _ ->
                val editor = prefs(context).edit()
                names.indices.forEach { editor.putString("hotkey_custom_$it", "") }
                editor.apply(); show(context)
            }.setPositiveButton("Done", null).show()
    }
    private fun capture(context: Context, action: Int) {
        val captured = mutableListOf<Int>()
        val info = TextView(context).apply {
            setPadding(32, 24, 32, 24)
            text = "Press one controller button, or hold one and press a second, then choose Save.\nThe first button is reserved for this shortcut. Digital buttons only; stick directions and axis-only triggers are not supported."
        }
        val dialog = AlertDialog.Builder(context).setTitle(names[action]).setView(info)
            .setPositiveButton("Save") { _, _ -> save(context, action, captured); show(context) }
            .setNeutralButton("Disable") { _, _ -> save(context, action, emptyList()); show(context) }
            .setNegativeButton("Cancel") { _, _ -> show(context) }.create()
        dialog.setOnKeyListener { _, code, event ->
            val sources = event.device?.sources ?: 0
            val controller = (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            if (!controller || !ButtonBindings.supports(code)) false else {
                if (event.action == K.ACTION_DOWN && event.repeatCount == 0 && code !in captured && captured.size < 2) {
                    captured.add(code)
                    info.text = "Selected: ${label(captured)}\nChoose Save using the touchscreen. Cancel to try again."
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                }
                true
            }
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
    }
    class State(private val context: Context, private val run: (Int) -> Unit) {
        private val held = mutableSetOf<Pair<Int, Int>>()
        private val consumed = mutableSetOf<Pair<Int, Int>>()
        fun clear() { held.clear(); consumed.clear() }
        fun handle(event: K): Boolean {
            val key = event.deviceId to event.keyCode
            if (event.action == K.ACTION_UP) {
                held.remove(key)
                return consumed.remove(key)
            }
            if (event.action != K.ACTION_DOWN) return false
            if (event.repeatCount > 0) return key in consumed
            held.add(key)
            val bindings = names.indices.associateWith { binding(context, it) }
            val action = bindings.entries.sortedByDescending { it.value.size }.firstOrNull { (_, keys) ->
                keys.isNotEmpty() && keys.last() == event.keyCode && keys.all { (event.deviceId to it) in held }
            }?.key
            if (action != null) {
                consumed.add(key)
                run(action)
                return true
            }
            if (bindings.values.any { it.firstOrNull() == event.keyCode }) {
                consumed.add(key)
                return true
            }
            return false
        }
    }
}
