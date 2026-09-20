package com.aaron.mgbaandroid

import android.content.Context
import androidx.appcompat.app.AlertDialog

object EmulatorSettings {
    fun video(context: Context, changed: () -> Unit = {}) {
        val prefs = context.getSharedPreferences("emulator", Context.MODE_PRIVATE)
        val labels = arrayOf("Smooth image", "Stretch to fill screen")
        val keys = arrayOf("video_smooth", "video_stretch")
        val checked = booleanArrayOf(prefs.getBoolean(keys[0], true), prefs.getBoolean(keys[1], false))
        AlertDialog.Builder(context).setTitle("Video")
            .setMultiChoiceItems(labels, checked) { _, index, enabled ->
                prefs.edit().putBoolean(keys[index], enabled).apply()
                changed()
            }.setPositiveButton("Done", null).show()
    }

    fun input(context: Context, changed: () -> Unit = {}) {
        val prefs = context.getSharedPreferences("emulator", Context.MODE_PRIVATE)
        AlertDialog.Builder(context).setTitle("Input")
            .setMultiChoiceItems(arrayOf("Swap default controller A and B", "Show touch controls"),
                booleanArrayOf(prefs.getBoolean("input_swap_ab", false), prefs.getBoolean("touch_controls", true))) { _, index, enabled ->
                prefs.edit().putBoolean(if (index == 0) "input_swap_ab" else "touch_controls", enabled).apply()
                changed()
            }
            .setNeutralButton("Controller mapping") { _, _ ->
                controllerMapping(context, changed)
            }.setPositiveButton("Done", null).show()
    }

    private fun controllerMapping(context: Context, changed: () -> Unit) {
        AlertDialog.Builder(context).setTitle("Controller mapping")
            .setItems(arrayOf("Auto-map", "Thumbstick bindings", "Button bindings", "Button guide")) { _, item ->
                when (item) {
                    0 -> { StickBindings.autoMap(context); ButtonBindings.reset(context); changed(); android.widget.Toast.makeText(context, "Standard controller mapping applied", android.widget.Toast.LENGTH_SHORT).show() }
                    1 -> StickBindings.show(context)
                    2 -> ButtonBindings.show(context)
                    else -> {
                AlertDialog.Builder(context).setTitle("Controller buttons")
                    .setMessage("Auto-map defaults:\nD-pad: directions\nA / B: A / B\nStart / Select: Start / Select\nL1 / R1: L / R\nOther buttons: unbound\n\nCustom Button bindings override the default A/B swap.\n\nUses Android's controller button labels. Left stick defaults to directions. Right stick defaults to unbound. Use Thumbstick bindings to change each direction.")
                    .setPositiveButton("Done", null).show()
                    }
                }
            }.setNegativeButton("Close", null).show()
    }

    fun general(context: Context) {
        val prefs = context.getSharedPreferences("emulator", Context.MODE_PRIVATE)
        val values = intArrayOf(2, 3, 4, 6, 8)
        AlertDialog.Builder(context).setTitle("Fast-forward speed")
            .setSingleChoiceItems(arrayOf("2×", "3×", "4×", "6×", "8×"),
                values.indexOf(prefs.getInt("ff_multiplier", 3)).coerceAtLeast(0)) { dialog, which ->
                prefs.edit().putInt("ff_multiplier", values[which]).apply()
                dialog.dismiss()
            }.setNegativeButton("Close", null).show()
    }
}
