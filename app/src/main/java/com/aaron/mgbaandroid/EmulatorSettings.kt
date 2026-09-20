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

    fun input(context: Context) {
        val prefs = context.getSharedPreferences("emulator", Context.MODE_PRIVATE)
        AlertDialog.Builder(context).setTitle("Input")
            .setMultiChoiceItems(arrayOf("Swap controller A and B"),
                booleanArrayOf(prefs.getBoolean("input_swap_ab", false))) { _, _, enabled ->
                prefs.edit().putBoolean("input_swap_ab", enabled).apply()
            }
            .setNeutralButton("Button guide") { _, _ ->
                AlertDialog.Builder(context).setTitle("Controller buttons")
                    .setMessage("D-pad: directions\nA / B: A / B (or swapped)\nStart: Start\nSelect: Select\nL1 / R1: L / R\n\nUses Android's controller button labels. Stick mapping is not included yet.")
                    .setPositiveButton("Done", null).show()
            }.setPositiveButton("Done", null).show()
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
