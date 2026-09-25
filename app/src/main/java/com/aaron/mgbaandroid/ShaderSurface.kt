package com.aaron.mgbaandroid

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.EGL14
import android.opengl.EGLExt
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** A single latest-frame mailbox: a slow shader cannot queue or replay old frames. */
internal class ShaderSurface(context: Context, private val failed: (VideoOptions, String) -> Unit) : GLSurfaceView(context), GLSurfaceView.Renderer {
    private val lock = Any()
    private var latest = IntArray(0)
    private var pixels = IntArray(0)
    private var frameWidth = 0
    private var frameHeight = 0
    private var presentationNanos = 0L
    private var supportsPresentationTime = false
    private var options = VideoOptions()
    private var renderer: ShaderRenderer? = null
    private var generation = 0
    private var failedGeneration: Int? = null
    private var screenWidth = 0
    private var screenHeight = 0

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 0, 0, 0)
        preserveEGLContextOnPause = true
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
        isFocusable = false
    }
    fun configure(value: VideoOptions) {
        synchronized(lock) {
            if (options != value) { options = value; generation++ }
        }
        requestRender()
    }
    fun submitFrame(data: IntArray, width: Int, height: Int, frameTimeNanos: Long = 0L) {
        val refreshRate = display?.refreshRate?.takeIf { it.isFinite() && it > 0f } ?: 60f
        synchronized(lock) {
            if (latest.size != width * height) latest = IntArray(width * height)
            data.copyInto(latest, endIndex = latest.size)
            frameWidth = width; frameHeight = height
            // Give the renderer a consistent two-display-tick presentation
            // deadline. Without it, a short GL frame can appear one tick early
            // and make the next one appear late, despite full-speed emulation.
            presentationNanos = if (frameTimeNanos > 0L) frameTimeNanos + (2_000_000_000.0 / refreshRate).toLong() else 0L
        }
        requestRender()
    }
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Old GL names belong to the lost context; do not delete them in the new one.
        renderer = ShaderRenderer(ShaderLibrary(context)::source)
        supportsPresentationTime = EGL14.eglQueryString(EGL14.eglGetCurrentDisplay(), EGL14.EGL_EXTENSIONS)
            ?.split(' ')?.contains("EGL_ANDROID_presentation_time") == true
        synchronized(lock) { failedGeneration = null }
    }
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenWidth = width; screenHeight = height
    }
    override fun onDrawFrame(gl: GL10?) {
        val width: Int; val height: Int; val settings: VideoOptions; val ticket: Int; val presentation: Long
        synchronized(lock) {
            if (failedGeneration == generation) return
            ticket = generation
            width = frameWidth; height = frameHeight; settings = options
            presentation = presentationNanos
            if (pixels.size != latest.size) pixels = IntArray(latest.size)
            latest.copyInto(pixels)
        }
        if (width == 0) {
            GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }
        try {
            renderer?.render(pixels, width, height, screenWidth, screenHeight, settings)
            if (supportsPresentationTime) {
                EGLExt.eglPresentationTimeANDROID(EGL14.eglGetCurrentDisplay(),
                    EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW), presentation)
            }
        } catch (error: Exception) {
            synchronized(lock) { failedGeneration = ticket }
            renderer?.release()
            val detail = error.message ?: "This shader cannot run on this device."
            // A newer configuration may already be queued while this frame fails.
            // Ignore the old error, so it cannot tear down a now-valid surface.
            post {
                if (synchronized(lock) { generation == ticket && failedGeneration == ticket }) failed(settings, detail)
            }
        }
    }
}
