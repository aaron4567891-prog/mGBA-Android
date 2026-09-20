package com.aaron.mgbaandroid

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.WindowManager

/** Video only: input focus stays with the main activity's controls and dialogs. */
class GamePresentation(context: Context, display: Display) : Presentation(context, display) {
    private var video: EmulatorView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window?.setBackgroundDrawableResource(android.R.color.black)
        window?.decorView?.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        video = EmulatorView(context)
        setContentView(video!!)
    }
    fun frame(pixels: IntArray, width: Int, height: Int) { video?.submitFrame(pixels, width, height) }
}
