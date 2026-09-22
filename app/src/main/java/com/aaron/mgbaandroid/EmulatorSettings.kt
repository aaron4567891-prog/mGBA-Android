package com.aaron.mgbaandroid

import android.content.Context
import androidx.appcompat.app.AlertDialog

object EmulatorSettings {
    fun video(context: Context, game: String? = null, changed: () -> Unit = {}) {
        val global = context.getSharedPreferences("emulator", Context.MODE_PRIVATE)
        val local = game?.let { context.getSharedPreferences("video_game_$it", Context.MODE_PRIVATE) }
        val override = local?.getBoolean("enabled", false) == true
        val prefs = if (override) local!! else global
        val options = VideoOptions.read(context, game)
        val modes = arrayOf("Sharp HD (integer scaling)", "Smooth HD (bilinear)", "Pixel-art enhancement (Scale2x)")
        val aspects = arrayOf("Original", "Stretch", "Crop", "4:3", "16:9")
        val labels = mutableListOf(
            "Display mode: ${modes[options.mode]}",
            "Aspect ratio: ${aspects[options.aspect]}",
            "LCD effect: ${if (options.lcd) "On" else "Off"}",
            "Fullscreen: ${if (options.fullscreen) "On" else "Off"}"
        )
        if (local != null) labels.add(if (override) "Use global display settings" else "Customize this game")
        labels.add("About display modes")
        AlertDialog.Builder(context).setTitle(if (override) "Video � this game" else "Video � global")
            .setItems(labels.toTypedArray()) { _, index ->
                fun refresh() { changed(); video(context, game, changed) }
                when (index) {
                    0, 1 -> AlertDialog.Builder(context)
                        .setTitle(if (index == 0) "Display mode" else "Aspect ratio")
                        .setSingleChoiceItems(if (index == 0) modes else aspects,
                            if (index == 0) options.mode else options.aspect) { dialog, value ->
                            prefs.edit().putInt(if (index == 0) "display_mode" else "display_aspect", value).apply()
                            dialog.dismiss(); refresh()
                        }.setNegativeButton("Cancel") { _, _ -> video(context, game, changed) }.show()
                    2 -> { prefs.edit().putBoolean("display_lcd", !options.lcd).apply(); refresh() }
                    3 -> { prefs.edit().putBoolean("display_fullscreen", !options.fullscreen).apply(); refresh() }
                    4 -> if (local != null) {
                        local.edit().putBoolean("enabled", !override)
                            .putInt("display_mode", options.mode).putInt("display_aspect", options.aspect)
                            .putBoolean("display_lcd", options.lcd).putBoolean("display_fullscreen", options.fullscreen).apply()
                        refresh()
                    } else videoHelp(context)
                    else -> videoHelp(context)
                }
            }.setPositiveButton("Done", null).show()
    }

    private fun videoHelp(context: Context) {
        AlertDialog.Builder(context).setTitle("Display modes")
            .setMessage("Sharp HD keeps crisp pixels and uses whole-number scaling with Original aspect ratio when the screen is large enough. Smooth HD softens pixel edges. Scale2x smooths pixel-art outlines; it is not xBRZ or ScaleFX. LCD adds a subtle pixel grid.\n\nOriginal preserves the game shape; Stretch fills the screen with distortion; Crop fills it by cutting off edges. 4:3 and 16:9 reshape the whole picture to the chosen ratio with black bars where needed. Fullscreen hides Android system bars.\n\nGames retain their original resolution; these options do not add HD textures. Open Video during a game to enable settings just for that ROM.")
            .setPositiveButton("Done", null).show()
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
            }.setNegativeButton("Hotkeys") { _, _ -> Hotkeys.show(context) }
            .setPositiveButton("Done", null).show()
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
        AlertDialog.Builder(context).setTitle("Settings")
            .setItems(arrayOf("Fast-forward speed", "BIOS", "In-game menu", "Dual screen")) { _, which ->
                when (which) {
                    3 -> dualScreen(context)
                    2 -> menuVisibility(context)
                    0 -> fastForward(context)
                    1 -> context.startActivity(android.content.Intent(context, BiosActivity::class.java))
                }
            }.setNegativeButton("Close", null).show()
    }

    private fun dualScreen(context: Context) {
        val prefs = context.getSharedPreferences("emulator", Context.MODE_PRIVATE)
        AlertDialog.Builder(context).setTitle("Dual screen")
            .setMultiChoiceItems(arrayOf("Bottom-screen controls (game stays on top)"),
                booleanArrayOf(prefs.getBoolean("dual_screen", false))) { _, _, enabled ->
                prefs.edit().putBoolean("dual_screen", enabled).apply()
            }.setNeutralButton("Help") { _, _ ->
                AlertDialog.Builder(context).setTitle("Dual screen")
                    .setMessage("Open mGBA on the top screen. The game and physical controller input stay there; touch controls and a Menu button appear on the second display. Menu dialogs open on the game screen. Enable touch controls in Input if needed. Without a second display, controls stay on the game screen.")
                    .setPositiveButton("Done", null).show()
            }.setPositiveButton("Done", null).show()
    }

    private fun menuVisibility(context: Context) {
        val prefs = context.getSharedPreferences("emulator", Context.MODE_PRIVATE)
        AlertDialog.Builder(context).setTitle("In-game menu")
            .setMultiChoiceItems(arrayOf("Show in-game menu"),
                booleanArrayOf(prefs.getBoolean("show_game_menu", true))) { _, _, show ->
                prefs.edit().putBoolean("show_game_menu", show).apply()
            }
            .setNeutralButton("Help") { _, _ ->
                AlertDialog.Builder(context).setTitle("Hidden menu")
                    .setMessage("This hides only the top menu during games. Touch controls stay as configured. To show the menu again, open Settings from the mGBA home screen and turn Show in-game menu on.")
                    .setPositiveButton("Done", null).show()
            }.setPositiveButton("Done", null).show()
    }

    private fun fastForward(context: Context) {
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
