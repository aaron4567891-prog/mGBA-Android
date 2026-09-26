package com.aaron.mgbaandroid

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SaveSettingsActivity : AppCompatActivity() {
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            SaveStorage.setTreeUri(this, uri)
            SaveStorage.ensureDataFolders(this)
            SaveStorage.migrateInternalSaves(this)
            Toast.makeText(this, "mGBA data folders created", Toast.LENGTH_SHORT).show()
        }.onFailure { Toast.makeText(this, it.message ?: "Could not select save folder", Toast.LENGTH_LONG).show() }
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    private fun render() {
        val selected = SaveStorage.treeUri(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        root.addView(TextView(this).apply { text = "Save files"; textSize = 24f })
        root.addView(TextView(this).apply {
            text = if (selected == null) {
                "Saves currently use app-private storage. Choose a folder to create saves, states, system, shaders, and cheats folders that are accessible to other apps."
            } else {
                "The selected folder contains saves, states, system, shaders, and cheats folders. Existing app-private saves were copied there when you selected it."
            }
            setPadding(0, 12, 0, 12)
        })
        root.addView(Button(this).apply {
            text = if (selected == null) "Choose mGBA data folder" else "Change mGBA data folder"
            setOnClickListener { folderPicker.launch(selected) }
        })
        root.addView(Button(this).apply {
            text = "Locate save files"
            isEnabled = selected != null
            setOnClickListener {
                val tree = SaveStorage.treeUri(this@SaveSettingsActivity) ?: return@setOnClickListener
                startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra("android.provider.extra.INITIAL_URI", tree)
                })
            }
        })
        if (selected != null) root.addView(Button(this).apply {
            text = "Use app-private storage instead"
            setOnClickListener {
                AlertDialog.Builder(this@SaveSettingsActivity)
                    .setTitle("Use app-private storage?")
                    .setMessage("New saves will no longer be written to the selected folder. Existing external saves will remain there.")
                    .setPositiveButton("Use app storage") { _, _ -> SaveStorage.useAppPrivateStorage(this@SaveSettingsActivity); render() }
                    .setNegativeButton("Cancel", null).show()
            }
        })
        root.addView(Button(this).apply { text = "Done"; setOnClickListener { finish() } })
        setContentView(root)
    }
}
