package com.aaron.mgbaandroid

import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class BiosActivity : AppCompatActivity() {
    private val scanner = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var scanning = false
    private var scanStatus = "Folder scanning includes subfolders and ignores unrelated files. Existing BIOS selections are kept; manual selection can replace them. Use extracted BIOS files."
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scanning = true
            scanStatus = "Scanning BIOS folder…"
            render()
            scanner.execute {
                val result = runCatching { BiosFolder.scan(applicationContext, uri) }
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    scanning = false
                    result.onSuccess { scan ->
                        val imported = mutableListOf<String>()
                        val failed = mutableListOf<String>()
                        for ((system, bytes) in scan.found) {
                            val file = File(filesDir, "system/${system}_bios.bin")
                            if (file.exists()) continue
                            runCatching {
                                file.parentFile?.mkdirs()
                                val atomic = android.util.AtomicFile(file)
                                val output = atomic.startWrite()
                                try { output.write(bytes); atomic.finishWrite(output) }
                                catch (e: Exception) { atomic.failWrite(output); throw e }
                            }.onSuccess { imported.add(system.uppercase()) }.onFailure { failed.add(system.uppercase()) }
                        }
                        val missing = listOf("gba", "gbc", "gb").filter { !File(filesDir, "system/${it}_bios.bin").exists() }
                        scanStatus = "Imported: ${imported.joinToString().ifEmpty { "none" }}. Missing: ${missing.joinToString { it.uppercase() }.ifEmpty { "none" }}. Unreadable files: ${scan.unreadable}."
                        if (failed.isNotEmpty()) scanStatus += " Import failed: ${failed.joinToString()}."
                    }.onFailure { scanStatus = "Could not scan folder: ${it.message}" }
                    render()
                }
            }
        }
    }
    override fun onDestroy() {
        scanner.shutdownNow()
        super.onDestroy()
    }
    private var selected = "gba"
    private val prefs by lazy { getSharedPreferences("emulator", MODE_PRIVATE) }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        require(out.size() + n <= 16384) { "BIOS file is too large" }
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                } ?: error("Cannot read BIOS")
                val sizes = when (selected) { "gba" -> setOf(16384); "gbc" -> setOf(2304, 2048); else -> setOf(256) }
                require(bytes.size in sizes) { "Unexpected $selected BIOS size: ${bytes.size} bytes" }
                val file = File(filesDir, "system/${selected}_bios.bin")
                file.parentFile?.mkdirs()
                val atomic = android.util.AtomicFile(file)
                val output = atomic.startWrite()
                try { output.write(bytes); atomic.finishWrite(output) }
                catch (e: Exception) { atomic.failWrite(output); throw e }
            }.onFailure { Toast.makeText(this, it.message ?: "BIOS import failed", Toast.LENGTH_LONG).show() }
            render()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected = savedInstanceState?.getString("selected") ?: "gba"
        render()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selected", selected)
        super.onSaveInstanceState(outState)
    }
    private fun render() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        root.addView(TextView(this).apply { text = "BIOS settings"; textSize = 24f })
        root.addView(TextView(this).apply {
            text = "Choose your BIOS files. BIOS startup is enabled by default. Skip BIOS skips the boot sequence. Changes apply next time you open a game. Without a usable BIOS, mGBA uses its built-in fallback."
        })
        root.addView(Button(this).apply {
            text = "Scan BIOS folder"
            isEnabled = !scanning
            setOnClickListener { folderPicker.launch(null) }
        })
        root.addView(TextView(this).apply { text = scanStatus })
        for (system in listOf("gba", "gbc", "gb")) {
            val file = File(filesDir, "system/${system}_bios.bin")
            root.addView(Button(this).apply {
                text = "${system.uppercase()} BIOS: ${if (file.exists()) "Imported — replace" else "Choose file"}"
                isEnabled = !scanning
                setOnClickListener { selected = system; picker.launch(arrayOf("*/*")) }
            })
            root.addView(CheckBox(this).apply {
                text = "Skip ${system.uppercase()} BIOS"
                isChecked = prefs.getBoolean("skip_bios_$system", false)
                setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("skip_bios_$system", checked).apply() }
            })
        }
        root.addView(Button(this).apply { text = "Done"; setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
