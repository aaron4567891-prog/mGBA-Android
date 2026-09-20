package com.aaron.mgbaandroid

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors

class HomeActivity : AppCompatActivity() {
    private data class Rom(val uri: Uri, val name: String, val system: String)
    private val worker = Executors.newSingleThreadExecutor()
    private val covers = Executors.newFixedThreadPool(2)
    private val prefs by lazy { getSharedPreferences("library", MODE_PRIVATE) }
    private lateinit var status: TextView
    private lateinit var grid: GridView
    private var games = emptyList<Rom>()
    private var generation = 0
    private var section = "gba"
    private fun folderKey() = "folder_$section"
    private fun sectionName() = when (section) { "gbc" -> "Game Boy Color"; "gb" -> "Game Boy"; else -> "GBA" }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                prefs.edit().putString(folderKey(), uri.toString()).apply()
                refresh()
            } catch (_: SecurityException) {
                status.text = "Folder permission was not granted. Please choose the folder again."
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        root.addView(TextView(this).apply { text = "mGBA Android"; textSize = 28f })
        section = prefs.getString("section", "gba").takeIf { it in listOf("gba", "gbc", "gb") } ?: "gba"
        val sections = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        for ((code, label) in listOf("gba" to "GBA", "gbc" to "Game Boy Color", "gb" to "Game Boy")) {
            sections.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                isChecked = section == code
                setOnClickListener {
                    section = code
                    prefs.edit().putString("section", code).apply()
                    refresh()
                }
            })
        }
        root.addView(sections)
        val actions = LinearLayout(this)
        actions.addView(MaterialButton(this).apply {
            text = "Add ROM folder"
            setOnClickListener { folderPicker.launch(prefs.getString(folderKey(), null)?.let(Uri::parse)) }
        })
        actions.addView(MaterialButton(this).apply { text = "Refresh"; setOnClickListener { refresh() } })
        root.addView(actions)
        status = TextView(this).apply { setPadding(0, dp(8), 0, dp(8)) }
        root.addView(status)
        grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(170)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(12)
            verticalSpacing = dp(12)
            isFocusable = true
            setOnItemClickListener { _, _, position, _ ->
                games.getOrNull(position)?.let { rom ->
                    startActivity(Intent(this@HomeActivity, MainActivity::class.java)
                        .setData(rom.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
            }
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(this).apply {
            text = "Box art: Libretro thumbnails · downloaded automatically and cached"
            textSize = 11f
        })
        setContentView(root)
        refresh()
    }

    private fun refresh() {
        val selected = prefs.getString(folderKey(), null)
        val ticket = ++generation
        val selectedSection = section
        games = emptyList()
        grid.adapter = RomAdapter()
        if (selected == null) {
            status.text = "Add your ${sectionName()} ROM folder. Subfolders are included."
            return
        }
        status.text = "Scanning ROM folder…"
        worker.execute {
            val result = runCatching { scan(Uri.parse(selected), selectedSection) }
            runOnUiThread {
                if (isDestroyed || ticket != generation) return@runOnUiThread
                result.onSuccess {
                    games = it
                    grid.adapter = RomAdapter()
                    status.text = if (it.isEmpty()) "No ${sectionName()} games found. Use extracted .$selectedSection files." else "${sectionName()} · ${it.size} games"
                }.onFailure {
                    status.text = "Could not read the ROM folder. Choose it again to restore access."
                }
            }
        }
    }

    private fun scan(tree: Uri, selectedSection: String): List<Rom> {
        val queue = java.util.ArrayDeque<String>()
        val visited = HashSet<String>()
        val found = ArrayList<Rom>()
        queue.add(DocumentsContract.getTreeDocumentId(tree))
        while (queue.isNotEmpty() && !Thread.currentThread().isInterrupted) {
            val id = queue.removeFirst()
            if (!visited.add(id)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
            val cursor = contentResolver.query(children, columns, null, null, null)
                ?: error("Folder is unavailable")
            cursor.use {
                while (it.moveToNext()) {
                    val child = it.getString(0)
                    val name = it.getString(1) ?: continue
                    if (it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        queue.add(child)
                    } else {
                        if (name.substringAfterLast('.', "").lowercase(Locale.ROOT) != selectedSection) continue
                        val system = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
                            "gba" -> "Nintendo_-_Game_Boy_Advance"
                            "gb" -> "Nintendo_-_Game_Boy"
                            "gbc" -> "Nintendo_-_Game_Boy_Color"
                            else -> continue
                        }
                        found.add(Rom(DocumentsContract.buildDocumentUriUsingTree(tree, child), name.substringBeforeLast('.'), system))
                    }
                }
            }
        }
        return found.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    private inner class RomAdapter : BaseAdapter() {
        override fun getCount() = games.size
        override fun getItem(position: Int) = games[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val card = (convertView as? LinearLayout) ?: LinearLayout(this@HomeActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                addView(ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(-1, dp(145)))
                addView(TextView(context).apply { gravity = Gravity.CENTER; maxLines = 2; textSize = 15f }, LinearLayout.LayoutParams(-1, dp(48)))
                val value = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
                setBackgroundResource(value.resourceId)
            }
            val rom = games[position]
            val image = card.getChildAt(0) as ImageView
            (card.getChildAt(1) as TextView).text = rom.name
            image.setImageResource(android.R.drawable.ic_menu_gallery)
            image.contentDescription = "${rom.name} cover"
            image.tag = rom.uri.toString()
            val ticket = generation
            covers.execute {
                val bitmap = runCatching { cover(rom) }.getOrNull()
                runOnUiThread {
                    if (!isDestroyed && ticket == generation && image.tag == rom.uri.toString() && bitmap != null) image.setImageBitmap(bitmap)
                }
            }
            return card
        }
    }

    private fun cover(rom: Rom): android.graphics.Bitmap? {
        val key = MessageDigest.getInstance("SHA-256").digest("${rom.system}/${rom.name}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val cached = File(cacheDir, "covers/$key.png")
        if (cached.exists()) return BitmapFactory.decodeFile(cached.path)
        val base = rom.name.replace(Regex("\\s*\\([^)]*\\)|\\s*\\[[^]]*\\]"), "").trim()
        val candidates = listOf(rom.name, "$base (USA)", "$base (USA, Europe)", "$base (Europe)", base).distinct()
        for (name in candidates) {
            if (Thread.currentThread().isInterrupted) return null
            val safeName = name.replace(Regex("[&*/:`<>?\\\\|]"), "_")
            val url = URL("https://raw.githubusercontent.com/libretro-thumbnails/${rom.system}/master/Named_Boxarts/${Uri.encode(safeName)}.png")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 4000
                connection.readTimeout = 4000
                if (connection.responseCode != 200) continue
                val bytes = connection.inputStream.use { it.readBytes() }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                cached.parentFile?.mkdirs()
                cached.writeBytes(bytes)
                return bitmap
            } finally { connection.disconnect() }
        }
        return null
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onDestroy() {
        generation++
        worker.shutdownNow()
        covers.shutdownNow()
        super.onDestroy()
    }
}
