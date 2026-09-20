package com.aaron.mgbaandroid

import android.content.Context
import android.view.KeyEvent as K
import androidx.appcompat.app.AlertDialog

object Hotkeys {
    private val names = arrayOf("Quick save (slot 1)", "Quick load (slot 1)", "Toggle fast-forward", "Return to library")
    private val labels = arrayOf("Disabled", "L3 + R1", "L3 + L1", "L3 + R3", "L3 + Start", "L3 + A", "L3 + B", "L3 + X", "L3 + Y")
    private val codes = intArrayOf(-1, K.KEYCODE_BUTTON_R1, K.KEYCODE_BUTTON_L1, K.KEYCODE_BUTTON_THUMBR,
        K.KEYCODE_BUTTON_START, K.KEYCODE_BUTTON_A, K.KEYCODE_BUTTON_B, K.KEYCODE_BUTTON_X, K.KEYCODE_BUTTON_Y)
    private fun prefs(context: Context) = context.getSharedPreferences("emulator", Context.MODE_PRIVATE)
    private fun choice(context: Context, action: Int) = prefs(context).getInt("hotkey_$action", action + 1).coerceIn(0, codes.lastIndex)
    fun show(context: Context) {
        AlertDialog.Builder(context).setTitle("Hotkeys — hold L3 first")
            .setItems(names.indices.map { "${names[it]}: ${labels[choice(context, it)]}" }.toTypedArray()) { _, action ->
                AlertDialog.Builder(context).setTitle(names[action])
                    .setSingleChoiceItems(labels, choice(context, action)) { dialog, selected ->
                        val editor = prefs(context).edit().putInt("hotkey_$action", selected)
                        // One shortcut performs one action; move duplicate assignments.
                        if (selected != 0) names.indices.filter { it != action && choice(context, it) == selected }
                            .forEach { editor.putInt("hotkey_$it", 0) }
                        editor.apply(); dialog.dismiss(); show(context)
                    }.setNegativeButton("Cancel") { _, _ -> show(context) }.show()
            }.setNeutralButton("Disable all") { _, _ ->
                val editor = prefs(context).edit()
                names.indices.forEach { editor.putInt("hotkey_$it", 0) }
                editor.apply(); show(context)
            }.setPositiveButton("Done", null).show()
    }

    class State(private val context: Context, private val run: (Int) -> Unit) {
        private val modifiers = mutableSetOf<Int>()
        private val consumed = mutableSetOf<Pair<Int, Int>>()
        fun clear() { modifiers.clear(); consumed.clear() }
        fun handle(event: K): Boolean {
            if (names.indices.none { choice(context, it) != 0 }) return false
            if (event.keyCode == K.KEYCODE_BUTTON_THUMBL) {
                if (event.action == K.ACTION_DOWN) modifiers.add(event.deviceId)
                if (event.action == K.ACTION_UP) modifiers.remove(event.deviceId)
                return true
            }
            val key = event.deviceId to event.keyCode
            if (key in consumed) {
                if (event.action == K.ACTION_UP) consumed.remove(key)
                return true
            }
            if (event.deviceId !in modifiers || event.action != K.ACTION_DOWN || event.repeatCount != 0) return false
            val action = names.indices.firstOrNull { codes[choice(context, it)] == event.keyCode } ?: return false
            consumed.add(key)
            run(action)
            return true
        }
    }
}
