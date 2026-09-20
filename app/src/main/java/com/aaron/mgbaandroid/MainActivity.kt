package com.aaron.mgbaandroid

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.io.File
import java.security.MessageDigest

class MainActivity : AppCompatActivity(), Choreographer.FrameCallback {
    private lateinit var emulatorView: EmulatorView
    private var gameToolbar: View? = null
    private var presentation: GamePresentation? = null
    private var foreground = false
    private var latestPixels: IntArray? = null
    private var latestWidth = 0
    private var latestHeight = 0
    private val displayManager by lazy { getSystemService(android.hardware.display.DisplayManager::class.java) }
    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { updateDualScreen() }
        override fun onDisplayRemoved(displayId: Int) { updateDualScreen() }
        override fun onDisplayChanged(displayId: Int) { updateDualScreen() }
    }
    private fun closePresentation() {
        val old = presentation
        presentation = null
        old?.setOnDismissListener(null)
        old?.releaseControls()
        old?.dismiss()
        secondaryTouchHeld = emptySet()
        refreshTouchControls()
        updateMenuVisibility()
        if (::emulatorView.isInitialized) {
            emulatorView.visibility = View.VISIBLE
            latestPixels?.let { emulatorView.submitFrame(it, latestWidth, latestHeight) }
        }
    }
    private fun updateDualScreen() {
        if (!::emulatorView.isInitialized) return
        val ownDisplay = window.decorView.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
        val target = if (foreground && prefs.getBoolean("dual_screen", false))
            displayManager.getDisplays(android.hardware.display.DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                .firstOrNull { it.displayId != ownDisplay && it.isValid } else null
        if (target == null) { closePresentation(); return }
        if (presentation?.display?.displayId == target.displayId && presentation?.isShowing == true) return
        closePresentation()
        val next = GamePresentation(this, target, { held -> secondaryTouchHeld = held; sendButtons() }, { showGameMenu() })
        try {
            next.show()
            presentation = next
            next.setOnDismissListener {
                if (presentation === next) {
                    next.releaseControls()
                    presentation = null
                    secondaryTouchHeld = emptySet()
                    refreshTouchControls()
                    updateMenuVisibility()
                    emulatorView.visibility = View.VISIBLE
                    latestPixels?.let { emulatorView.submitFrame(it, latestWidth, latestHeight) }
                }
            }
            refreshTouchControls()
            updateMenuVisibility()
        } catch (_: android.view.WindowManager.InvalidDisplayException) {
            closePresentation()
            toast("Second display unavailable; using the main screen")
        }
    }
    private val menuPreferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "show_game_menu") updateMenuVisibility()
        if (key == "dual_screen") updateDualScreen()
        if (key == "touch_controls") refreshTouchControls()
    }
    private fun updateMenuVisibility() {
        gameToolbar?.visibility = if (presentation == null && prefs.getBoolean("show_game_menu", true)) View.VISIBLE else View.GONE
    }
    private var touchControls: TouchControls? = null
    private var touchHeld = emptySet<Int>()
    private var secondaryTouchHeld = emptySet<Int>()
    private var stickHeld = emptySet<Int>()
    private val stickState by lazy { StickBindings.State(this) }
    private val buttonState by lazy { ButtonBindings.State(this) }
    private fun sendButtons() {
        for (id in 0..9) NativeBridge.setButton(id, id in touchHeld || id in secondaryTouchHeld || id in buttonState.held() || id in stickHeld)
    }
    private fun releaseButtons() {
        buttonState.clear()
        hotkeys.clear()
        stickHeld = emptySet()
        stickState.clear()
        touchControls?.release()
        presentation?.releaseControls()
        secondaryTouchHeld = emptySet()
        touchHeld = emptySet()
        sendButtons()
    }
    private fun refreshTouchControls() {
        touchControls?.release()
        val enabled = prefs.getBoolean("touch_controls", true)
        touchControls?.visibility = if (enabled && presentation == null) View.VISIBLE else View.GONE
        presentation?.setControlsEnabled(enabled)
    }
    private var pausedByUser = false
    private fun setPaused(paused: Boolean) {
        pausedByUser = paused
        resetAudioQueue()
        previousFrameNanos = 0L
        frameDebtNanos = 0.0
        buttonState.clear()
        touchControls?.release()
        presentation?.releaseControls()
        secondaryTouchHeld = emptySet()
        touchHeld = emptySet()
        stickHeld = emptySet()
        stickState.clear()
        sendButtons()
        toast(if (paused) "Paused" else "Playing")
    }
    private val hotkeys by lazy { Hotkeys.State(this, ::runHotkey) }
    private fun runHotkey(action: Int) {
        when (action) {
            8 -> showGameMenu()
            4 -> setPaused(!pausedByUser)
            5 -> setPaused(true)
            6 -> setPaused(false)
            0 -> runCatching {
                val bytes = NativeBridge.saveState() ?: error("Could not save state")
                stateFile(1).writeBytes(bytes)
                toast("State 1 saved")
            }.onFailure { toast("Could not save state 1") }
            1 -> AlertDialog.Builder(this).setTitle("Load state 1?")
                .setMessage("This replaces your current game progress with the saved state.")
                .setPositiveButton("Load") { _, _ ->
                    runCatching {
                        val file = stateFile(1)
                        check(file.exists() && NativeBridge.loadState(file.readBytes()))
                        resetAudioQueue()
                        previousFrameNanos = 0L
                        toast("State 1 loaded")
                    }.onFailure { toast("Could not load state 1") }
                }.setNegativeButton("Cancel", null).show()
            2 -> { fastForward = !fastForward; toast(if (fastForward) "Fast-forward on" else "Fast-forward off") }
            3 -> AlertDialog.Builder(this).setTitle("Exit mGBA?")
                .setMessage("Save game data and close the emulator task.")
                .setPositiveButton("Exit") { _, _ ->
                    persistBatterySave()
                    finishAndRemoveTask()
                }.setNegativeButton("Cancel", null).show()
            7 -> AlertDialog.Builder(this).setTitle("Close game and return to mGBA home?")
                .setPositiveButton("Return") { _, _ ->
                    persistBatterySave()
                    startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    finish()
                }.setNegativeButton("Cancel", null).show()
        }
    }
    private var running = false
    private var fastForward = false
    private var romKey: String? = null
    private var audioTrack: AudioTrack? = null
    // GBA and GB both run at approximately 59.7275 frames per second.
    private val framePeriodNanos = 1_000_000_000.0 * 280896.0 / 16777216.0
    private var previousFrameNanos = 0L
    private var frameDebtNanos = 0.0
    private val pendingAudio = java.util.ArrayDeque<ShortArray>()
    private var pendingOffset = 0
    private var queuedSamples = 0
    private var primedSamples = 0
    private var audioRate = 32768
    private var wasFastForward = false
    private val prefs by lazy { getSharedPreferences("emulator", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.data == null) {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
            return
        }
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val saveDir = File(filesDir, "saves").apply { mkdirs() }
        val systemDir = File(filesDir, "system").apply { mkdirs() }
        check(NativeBridge.initialize(systemDir.absolutePath, saveDir.absolutePath))

        emulatorView = EmulatorView(this)
        val root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        root.addView(emulatorView, FrameLayout.LayoutParams(-1, -1))
        touchControls = TouchControls(this) { held -> touchHeld = held; sendButtons() }
        root.addView(touchControls, FrameLayout.LayoutParams(-1, -1))
        refreshTouchControls()
        gameToolbar = buildToolbar()
        prefs.registerOnSharedPreferenceChangeListener(menuPreferenceListener)
        updateMenuVisibility()
        root.addView(gameToolbar, FrameLayout.LayoutParams(-2, -2).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.END })
        setContentView(root)
        displayManager.registerDisplayListener(displayListener, android.os.Handler(android.os.Looper.getMainLooper()))

        intent?.data?.let(::openRom)
    }

    private fun buildToolbar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
            alpha = 0.82f
        }
        fun button(label: String, action: () -> Unit) = MaterialButton(this).apply {
            text = label
            minWidth = 0
            setOnClickListener { action() }
        }
        bar.addView(button("Games") {
            startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        })
        bar.addView(button("Pause/Play") { setPaused(!pausedByUser) })
        bar.addView(button("State") { showStateMenu() })
        bar.addView(button("FF") { fastForward = !fastForward; toast(if (fastForward) "Fast-forward on" else "Fast-forward off") })
        bar.addView(button("Cheat") { showCheatDialog() })
        bar.addView(button("Settings") { showSettings() })
        bar.addView(button("Video") { EmulatorSettings.video(this) { emulatorView.invalidate() } })
        bar.addView(button("Input") {
            releaseButtons()
            EmulatorSettings.input(this) { releaseButtons(); refreshTouchControls() }
        })
        return android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let(::openRom)
    }

    private fun openRom(uri: Uri) {
        runCatching {
            releaseButtons()
            persistBatterySave()
            val displayName = runCatching {
                contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "game.gba"
            val loaded = contentResolver.openInputStream(uri)?.let {
                RomArchive.load(it, displayName, intent.getStringExtra(RomArchive.ENTRY_EXTRA))
            } ?: error("Could not read ROM")
            val bytes = loaded.bytes
            latestPixels = null
            pausedByUser = false
            running = false
            Choreographer.getInstance().removeFrameCallback(this)
            resetAudioQueue()
            romKey = null
            if (!NativeBridge.loadRom(bytes, loaded.name, prefs.getBoolean("skip_bios_${RomArchive.extension(loaded.name)}", false))) error("mGBA rejected the extracted ROM")
            romKey = MessageDigest.getInstance("SHA-256").digest(bytes).take(12).joinToString("") { "%02x".format(it) }
            restoreBatterySave()
            startAudio()
            running = true
            Choreographer.getInstance().removeFrameCallback(this)
            Choreographer.getInstance().postFrameCallback(this)
        }.onFailure { toast(it.message ?: "ROM loading failed") }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (pausedByUser) {
            Choreographer.getInstance().postFrameCallback(this)
            return
        }
        if (fastForward != wasFastForward) {
            resetAudioQueue()
            wasFastForward = fastForward
            previousFrameNanos = 0L
        }
        val elapsed = if (previousFrameNanos == 0L) framePeriodNanos else
            (frameTimeNanos - previousFrameNanos).toDouble().coerceIn(0.0, framePeriodNanos * 4)
        previousFrameNanos = frameTimeNanos
        frameDebtNanos = (frameDebtNanos + elapsed).coerceAtMost(framePeriodNanos * 4)
        pumpAudio()
        var latestFrame: IntArray? = null
        var steps = 0
        while (frameDebtNanos >= framePeriodNanos && steps < 4) {
            // Keep a bounded backlog if the audio device temporarily stops accepting data.
            if (!fastForward && queuedSamples >= audioRate / 5) break
            val count = if (fastForward) prefs.getInt("ff_multiplier", 3).coerceIn(2, 8) else 1
            latestFrame = NativeBridge.runFrame(count)
            val currentRate = NativeBridge.audioRate()
            if (currentRate > 0 && currentRate != audioRate) {
                android.util.Log.i("MgbaAudio", "Core audio rate changed: $audioRate -> $currentRate")
                val savedDebt = frameDebtNanos
                val savedTimestamp = previousFrameNanos
                startAudio()
                frameDebtNanos = savedDebt
                previousFrameNanos = savedTimestamp
            }
            val samples = NativeBridge.takeAudio()
            if (!fastForward && samples.isNotEmpty()) {
                pendingAudio.addLast(samples)
                queuedSamples += samples.size
                pumpAudio()
            }
            frameDebtNanos -= framePeriodNanos
            steps++
        }
        latestFrame?.let {
            latestPixels = it
            latestWidth = NativeBridge.videoWidth()
            latestHeight = NativeBridge.videoHeight()
            emulatorView.submitFrame(it, latestWidth, latestHeight)
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun pumpAudio() {
        val track = audioTrack ?: return
        while (pendingAudio.isNotEmpty()) {
            val samples = pendingAudio.peekFirst() ?: break
            val written = track.write(samples, pendingOffset, samples.size - pendingOffset, AudioTrack.WRITE_NON_BLOCKING)
            if (written < 0) {
                latestPixels = null
            pausedByUser = false
            running = false
                toast("Audio output failed ($written). Reopen the ROM.")
                return
            }
            if (written == 0) break
            pendingOffset += written
            queuedSamples -= written
            primedSamples += written
            // Start with about 50 ms queued instead of playing an empty buffer.
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING && primedSamples >= audioRate / 10) {
                track.play()
            }
            if (pendingOffset == samples.size) {
                pendingAudio.removeFirst()
                pendingOffset = 0
            }
        }
    }

    private fun resetAudioQueue() {
        audioTrack?.pause()
        audioTrack?.flush()
        pendingAudio.clear()
        pendingOffset = 0
        queuedSamples = 0
        primedSamples = 0
        previousFrameNanos = 0L
        frameDebtNanos = 0.0
    }

    private fun startAudio() {
        audioTrack?.release()
        audioTrack = null
        audioRate = NativeBridge.audioRate().takeIf { it > 0 } ?: 32768
        val minimum = AudioTrack.getMinBufferSize(audioRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(audioRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(minimum, audioRate / 10 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        resetAudioQueue()
        wasFastForward = fastForward
    }

    private fun showStateMenu() {
        if (romKey == null) return
        val labels = arrayOf("Save slot 1", "Load slot 1", "Save slot 2", "Load slot 2", "Save slot 3", "Load slot 3")
        AlertDialog.Builder(this).setTitle("Save states").setItems(labels) { _, which ->
            val slot = which / 2 + 1
            val file = stateFile(slot)
            if (which % 2 == 0) {
                NativeBridge.saveState()?.let(file::writeBytes)
                toast("State $slot saved")
            } else if (file.exists() && NativeBridge.loadState(file.readBytes())) toast("State $slot loaded")
            else toast("No state in slot $slot")
        }.show()
    }

    private fun showCheatDialog() {
        val input = EditText(this).apply { hint = "GameShark / Action Replay code" }
        AlertDialog.Builder(this).setTitle("Add cheat").setView(input)
            .setPositiveButton("Enable") { _, _ -> NativeBridge.setCheat(0, true, input.text.toString()) }
            .setNeutralButton("Clear all") { _, _ -> NativeBridge.clearCheats() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showGameMenu() {
        if (romKey == null || isFinishing) return
        releaseButtons()
        val items = arrayOf(if (pausedByUser) "Play / Resume" else "Pause", "Save states",
            if (fastForward) "Fast-forward off" else "Fast-forward on", "Video", "Input", "Settings",
            "Cheats", "Close game", "Exit App")
        AlertDialog.Builder(this).setTitle("Game menu")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> setPaused(!pausedByUser)
                    1 -> showStateMenu()
                    2 -> runHotkey(2)
                    3 -> EmulatorSettings.video(this) { emulatorView.invalidate() }
                    4 -> EmulatorSettings.input(this) { releaseButtons(); refreshTouchControls() }
                    5 -> showSettings()
                    6 -> showCheatDialog()
                    7 -> runHotkey(7)
                    8 -> runHotkey(3)
                }
            }.setNegativeButton("Back to game", null).show()
    }

    private fun showSettings() {
        EmulatorSettings.general(this)
    }

    override fun onGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) &&
            event.actionMasked == android.view.MotionEvent.ACTION_MOVE && romKey != null) {
            stickHeld = stickState.read(event)
            buttonState.motion(event)
            sendButtons()
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && romKey != null) releaseButtons()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (romKey != null && Hotkeys.isReturnKey(event.keyCode)) {
            if (hotkeys.handle(event)) return true
            return super.dispatchKeyEvent(event)
        }
        val sources = event.device?.sources ?: 0
        val controller = (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (!controller || romKey == null || !ButtonBindings.supports(event.keyCode)) return super.dispatchKeyEvent(event)
        if (hotkeys.handle(event)) return true
        buttonState.key(event)
        sendButtons()
        return true
    }

    private fun stateFile(slot: Int) = File(filesDir, "states/${romKey}_$slot.state").apply { parentFile?.mkdirs() }
    private fun saveFile() = File(filesDir, "saves/${romKey}.sav")
    private fun restoreBatterySave() { saveFile().takeIf(File::exists)?.readBytes()?.let(NativeBridge::writeSaveRam) }
    private fun persistBatterySave() { if (romKey != null) NativeBridge.readSaveRam().takeIf { it.isNotEmpty() }?.let { saveFile().writeBytes(it) } }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onPause() {
        foreground = false
        closePresentation()
        if (romKey != null) releaseButtons()
        Choreographer.getInstance().removeFrameCallback(this)
        resetAudioQueue()
        persistBatterySave()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        updateDualScreen()
        updateMenuVisibility()
        if (running) {
            refreshTouchControls()
            previousFrameNanos = 0L
            Choreographer.getInstance().removeFrameCallback(this)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    override fun onDestroy() {
        foreground = false
        displayManager.unregisterDisplayListener(displayListener)
        closePresentation()
        latestPixels = null
        prefs.unregisterOnSharedPreferenceChangeListener(menuPreferenceListener)
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        persistBatterySave()
        NativeBridge.unloadRom()
        audioTrack?.release()
        super.onDestroy()
    }
}
