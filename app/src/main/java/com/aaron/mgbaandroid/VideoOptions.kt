package com.aaron.mgbaandroid

import android.content.Context

data class VideoOptions(
    val mode: Int = 0,
    val aspect: Int = 0,
    val lcd: Boolean = false,
    val fullscreen: Boolean = true
) {
    companion object {
        fun read(context: Context, game: String?): VideoOptions {
            val global = context.getSharedPreferences("emulator", Context.MODE_PRIVATE)
            val local = game?.let { context.getSharedPreferences("video_game_$it", Context.MODE_PRIVATE) }
            val prefs = if (local?.getBoolean("enabled", false) == true) local else global
            return VideoOptions(
                prefs.getInt("display_mode", 0).coerceIn(0, 2),
                prefs.getInt("display_aspect", 0).coerceIn(0, 2),
                prefs.getBoolean("display_lcd", false),
                prefs.getBoolean("display_fullscreen", true)
            )
        }
    }
}

/** Allocation-free Scale2x pixel-art expansion after the caller allocates its output buffer. */
internal object PixelScaler {
    fun scale2x(source: IntArray, width: Int, height: Int, output: IntArray) {
        require(width > 0 && height > 0 && source.size >= width * height)
        require(output.size >= width * height * 4)
        val stride = width * 2
        for (y in 0 until height) for (x in 0 until width) {
            val e = source[y * width + x]
            val b = source[maxOf(0, y - 1) * width + x]
            val d = source[y * width + maxOf(0, x - 1)]
            val f = source[y * width + minOf(width - 1, x + 1)]
            val h = source[minOf(height - 1, y + 1) * width + x]
            val index = y * 2 * stride + x * 2
            val edge = b != h && d != f
            output[index] = if (edge && d == b) d else e
            output[index + 1] = if (edge && b == f) f else e
            output[index + stride] = if (edge && d == h) d else e
            output[index + stride + 1] = if (edge && h == f) f else e
        }
    }
}
