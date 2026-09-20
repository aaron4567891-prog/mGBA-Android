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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.io.File
import java.security.MessageDigest

class MainActivity : AppCompatActivity(), Choreographer.FrameCallback {
    private lateinit var emulatorView: EmulatorView
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

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            openRom(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val saveDir = File(filesDir, "saves").apply { mkdirs() }
        val systemDir = File(filesDir, "system").apply { mkdirs() }
        check(NativeBridge.initialize(systemDir.absolutePath, saveDir.absolutePath))

        emulatorView = EmulatorView(this)
        val root = FrameLayout(this)
        root.addView(emulatorView, FrameLayout.LayoutParams(-1, -1))
        root.addView(buildToolbar(), FrameLayout.LayoutParams(-2, -2).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.END })
        setContentView(root)

        intent?.data?.let(::openRom) ?: picker.launch(arrayOf("application/octet-stream", "application/zip", "*/*"))
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
        bar.addView(button("Open") { picker.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) })
        bar.addView(button("State") { showStateMenu() })
        bar.addView(button("FF") { fastForward = !fastForward; toast(if (fastForward) "Fast-forward on" else "Fast-forward off") })
        bar.addView(button("Cheat") { showCheatDialog() })
        bar.addView(button("Settings") { showSettings() })
        return bar
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let(::openRom)
    }

    private fun openRom(uri: Uri) {
        runCatching {
            persistBatterySave()
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Could not read ROM")
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "game.gba"
            if (!NativeBridge.loadRom(bytes, name)) error("mGBA rejected this ROM")
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
            emulatorView.submitFrame(it, NativeBridge.videoWidth(), NativeBridge.videoHeight())
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun pumpAudio() {
        val track = audioTrack ?: return
        while (pendingAudio.isNotEmpty()) {
            val samples = pendingAudio.peekFirst() ?: break
            val written = track.write(samples, pendingOffset, samples.size - pendingOffset, AudioTrack.WRITE_NON_BLOCKING)
            if (written < 0) {
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

    private fun showSettings() {
        val speeds = arrayOf("2×", "3×", "4×", "6×", "8×")
        val values = intArrayOf(2, 3, 4, 6, 8)
        val selected = values.indexOf(prefs.getInt("ff_multiplier", 3)).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Fast-forward speed").setSingleChoiceItems(speeds, selected) { dialog, which ->
            prefs.edit().putInt("ff_multiplier", values[which]).apply(); dialog.dismiss()
        }.setNegativeButton("Close", null).show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.device?.sources?.and(InputDevice.SOURCE_GAMEPAD) == 0) return super.dispatchKeyEvent(event)
        val id = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> 0
            KeyEvent.KEYCODE_BUTTON_B -> 1
            KeyEvent.KEYCODE_BUTTON_SELECT -> 2
            KeyEvent.KEYCODE_BUTTON_START -> 3
            KeyEvent.KEYCODE_DPAD_UP -> 6
            KeyEvent.KEYCODE_DPAD_DOWN -> 7
            KeyEvent.KEYCODE_DPAD_LEFT -> 5
            KeyEvent.KEYCODE_DPAD_RIGHT -> 4
            KeyEvent.KEYCODE_BUTTON_L1 -> 9
            KeyEvent.KEYCODE_BUTTON_R1 -> 8
            else -> -1
        }
        if (id < 0) return super.dispatchKeyEvent(event)
        NativeBridge.setButton(id, event.action == KeyEvent.ACTION_DOWN)
        return true
    }

    private fun stateFile(slot: Int) = File(filesDir, "states/${romKey}_$slot.state").apply { parentFile?.mkdirs() }
    private fun saveFile() = File(filesDir, "saves/${romKey}.sav")
    private fun restoreBatterySave() { saveFile().takeIf(File::exists)?.readBytes()?.let(NativeBridge::writeSaveRam) }
    private fun persistBatterySave() { if (romKey != null) NativeBridge.readSaveRam().takeIf { it.isNotEmpty() }?.let { saveFile().writeBytes(it) } }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onPause() {
        Choreographer.getInstance().removeFrameCallback(this)
        resetAudioQueue()
        persistBatterySave()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (running) {
            previousFrameNanos = 0L
            Choreographer.getInstance().removeFrameCallback(this)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    override fun onDestroy() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        persistBatterySave()
        NativeBridge.unloadRom()
        audioTrack?.release()
        super.onDestroy()
    }
}
