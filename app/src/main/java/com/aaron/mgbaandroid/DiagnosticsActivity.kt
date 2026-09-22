package com.aaron.mgbaandroid

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider

class DiagnosticsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        layout.addView(TextView(this).apply {
            text = "Diagnostic logging\nEnable this, reproduce the problem, then return here to share the log. Logs survive restarting the app and may include ROM names or paths. Nothing is uploaded automatically. About 2 MB of recent logs are kept. Native crash details depend on Android; not every crash produces a full trace."
        })
        layout.addView(Switch(this).apply {
            text = "Enable diagnostic logging"
            isChecked = Diagnostics.enabled(this@DiagnosticsActivity)
            setOnCheckedChangeListener { _, enabled -> Diagnostics.setEnabled(this@DiagnosticsActivity, enabled) }
        })
        fun button(label: String, action: () -> Unit) {
            layout.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        button("Share log") {
            runCatching {
                val file = Diagnostics.snapshot(this)
                val uri = FileProvider.getUriForFile(this, "${packageName}.diagnostic-files", file)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = android.content.ClipData.newRawUri("Diagnostic log", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, "Share mGBA log"))
            }.onFailure { Toast.makeText(this, "Could not share log.", Toast.LENGTH_LONG).show() }
        }
        button("Save log as...") {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "mgba-diagnostics.txt")
            }, 20)
        }
        button("Clear logs") {
            Diagnostics.clear(this)
            Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
        }
        button("Back") { finish() }
        setContentView(android.widget.ScrollView(this).apply { addView(layout) })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 20 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            val saved = runCatching {
                val report = Diagnostics.snapshot(this)
                val output = contentResolver.openOutputStream(uri, "wt")
                    ?: error("Could not open destination")
                output.use { destination -> report.inputStream().use { it.copyTo(destination) } }
            }.isSuccess
            runOnUiThread {
                if (!isDestroyed) Toast.makeText(this,
                    if (saved) "Log saved" else "Could not save log. Try another folder.",
                    Toast.LENGTH_LONG).show()
            }
        }.start()
    }
}
