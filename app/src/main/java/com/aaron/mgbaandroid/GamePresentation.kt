package com.aaron.mgbaandroid

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout

/** Touch-only companion; video and physical controller focus stay in MainActivity. */
class GamePresentation(
    context: Context,
    display: Display,
    private val changed: (Set<Int>) -> Unit,
    private val menu: () -> Unit
) : Presentation(context, display) {
    private var controls: TouchControls? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window?.setBackgroundDrawableResource(android.R.color.black)
        window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val root = FrameLayout(context)
        controls = TouchControls(context, changed)
        root.addView(controls, FrameLayout.LayoutParams(-1, -1))
        root.addView(Button(context).apply {
            text = "Menu"
            isFocusable = false
            setOnClickListener { releaseControls(); menu() }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        setContentView(root)
    }

    fun releaseControls() { controls?.release() }

    fun setControlsEnabled(enabled: Boolean) {
        releaseControls()
        controls?.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    override fun onStop() {
        releaseControls()
        super.onStop()
    }
}
