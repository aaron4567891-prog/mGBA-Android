package com.aaron.mgbaandroid

import android.os.Bundle
import android.view.KeyEvent
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class ShaderActivity : AppCompatActivity() {
    private val library by lazy { ShaderLibrary(this) }
    private val preferences by lazy { ShaderLibrary.preferences(this, intent.getStringExtra("game")) }
    private val worker = Executors.newSingleThreadExecutor()
    private var chain = ShaderChain()
    private var busy = false
    private var catalog = emptyList<ShaderSource>()
    private var screen: ScrollView? = null
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work({ library.importFile(uri) }) { source ->
            catalog = library.sources()
            add(source.id)
        }
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) work({ library.folder(uri) }) { files ->
            val choices = files.keys.filter { it.endsWith(".glslp", true) || it.endsWith(".glsl", true) }.sorted()
            if (choices.isEmpty()) message("No GLSL shaders or presets found in this folder.")
            else AlertDialog.Builder(this).setTitle("Choose shader or preset")
                .setItems(choices.toTypedArray()) { _, index ->
                    work({ library.importFromFolder(choices[index], files) }) { passes ->
                        catalog = library.sources()
                        if (choices[index].endsWith(".glslp", true)) {
                            AlertDialog.Builder(this).setTitle("Use imported preset?")
                                .setMessage("Replace the current passes with ${passes.size} imported passes?")
                                .setPositiveButton("Use preset") { _, _ -> save(ShaderChain(true, passes)) }
                                .setNegativeButton("Cancel", null).show()
                        } else add(passes.single().shader)
                    }
                }.setNegativeButton("Cancel", null).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chain = ShaderLibrary.decode(preferences.getString(ShaderLibrary.KEY, null))
        catalog = library.sources()
        render()
    }

    private fun save(value: ShaderChain, rebuild: Boolean = true) {
        chain = value
        preferences.edit().putString(ShaderLibrary.KEY, ShaderLibrary.encode(value)).apply()
        if (rebuild) render()
    }
    private fun update(index: Int, pass: ShaderPass, rebuild: Boolean = true) =
        save(chain.copy(passes = chain.passes.toMutableList().also { it[index] = pass }), rebuild)
    private fun add(id: String) {
        if (chain.passes.size >= ShaderChain.MAX_PASSES) { message("Remove a pass first. Up to ${ShaderChain.MAX_PASSES} passes are supported."); return }
        save(chain.copy(enabled = true, passes = chain.passes + ShaderPass(id)))
    }
    private fun selectShader(selected: (String) -> Unit) {
        AlertDialog.Builder(this).setTitle("Choose shader")
            .setItems(catalog.map { it.name }.toTypedArray()) { _, i -> selected(catalog[i].id) }
            .setNegativeButton("Cancel", null).show()
    }
    private fun message(text: String) = AlertDialog.Builder(this).setTitle("Shaders")
        .setMessage(text).setPositiveButton("OK", null).show()

    private fun render() {
        val oldScroll = screen?.scrollY ?: 0
        val oldFocus = currentFocus?.tag as? Int
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 24) }
        val scroll = ScrollView(this).apply { addView(root) }
        screen = scroll
        setContentView(scroll)
        root.addView(TextView(this).apply {
            text = "Shaders · ${if (preferences.getBoolean("enabled", false)) "this game" else "global"}"
            textSize = 24f
        })
        var buttonIndex = 0
        fun button(text: String, action: (Button) -> Unit) { root.addView(Button(this).apply {
            tag = buttonIndex++
            this.text = text; isEnabled = !busy; setOnClickListener { action(this) }
        }) }
        button(if (busy) "Importing…" else "Shaders: ${if (chain.enabled) "On" else "Off"}") { control ->
            save(chain.copy(enabled = !chain.enabled), rebuild = false)
            control.text = "Shaders: ${if (chain.enabled) "On" else "Off"}"
        }
        button("GBA colour + LCD3x preset") { save(ShaderLibrary.screenshotPreset()) }
        root.addView(TextView(this).apply {
            text = "Passes run in order. Screen scale draws at the game viewport size. Turn shaders off to restore your normal display mode."
        })
        chain.passes.forEachIndexed { i, pass ->
            val source = catalog.find { it.id == pass.shader }
            button("Shader #$i: ${source?.name ?: "Missing shader"}") { selectShader { update(i, chain.passes[i].copy(shader = it, parameters = emptyMap())) } }
            button("#$i Filter: ${if (pass.linear) "Linear" else "Nearest"}") { control ->
                val next = chain.passes[i].let { it.copy(linear = !it.linear) }
                update(i, next, rebuild = false)
                control.text = "#$i Filter: ${if (next.linear) "Linear" else "Nearest"}"
            }
            button("#$i Scale: ${if (pass.scaleType == "viewport") "Screen" else "Source"} ${pass.scale}x") { control ->
                val labels = arrayOf("Screen 1x", "Source 1x", "Source 2x", "Source 3x", "Source 4x")
                AlertDialog.Builder(this).setTitle("Pass #$i scale").setItems(labels) { _, which ->
                    val next = chain.passes[i].copy(scaleType = if (which == 0) "viewport" else "source", scale = if (which == 0) 1f else which.toFloat())
                    update(i, next, rebuild = false)
                    control.text = "#$i Scale: ${if (next.scaleType == "viewport") "Screen" else "Source"} ${next.scale}x"
                }.setNegativeButton("Cancel", null).show()
            }
            if (!source?.parameters.isNullOrEmpty()) button("#$i Parameters") { parameters(i, source!!) }
            if (i > 0) button("Move #$i up") {
                val list = chain.passes.toMutableList(); val p = list.removeAt(i); list.add(i - 1, p); save(chain.copy(passes = list))
            }
            button("Remove shader #$i") { save(chain.copy(passes = chain.passes.filterIndexed { n, _ -> n != i })) }
        }
        button("Add shader pass") { selectShader(::add) }
        button("Import file (.glsl)") { filePicker.launch(arrayOf("*/*")) }
        button("Import folder (.glsl / .glslp)") { folderPicker.launch(null) }
        button("Compatibility and help") {
            message("Supports up to 6 combined OpenGL ES 2 compatible RetroArch GLSL passes, includes, shader parameters, nearest/linear filtering and source/viewport scaling. Folder import copies the chosen shader/preset into the app so the original folder is no longer needed.\n\nSlang (.slang/.slangp), Cg, lookup textures, temporal/feedback shaders, pass aliases, float/sRGB framebuffers and mipmaps are not supported. Get the GLSL version of a shader where available. Unsupported files show an error; rendering failures fall back to the normal picture.\n\nGBA colour uses the Slang colour profiles and Screen Darkness calculation, ported to GLSL. The included shaders are public-domain work by hunterk/Pokefan531/crashGG and Gigaherz, from the Libretro shader collections. LCD3x looks best at integer display scales of at least 3x.\n\nUse Customize this game in Video to keep a separate shader setup for each ROM.")
        }
        button("Done") { finish() }
        scroll.post {
            if (screen !== scroll) return@post
            if (oldFocus != null) root.findViewWithTag<Button>(oldFocus)?.requestFocus()
            scroll.scrollTo(0, oldScroll)
        }
    }

    private fun parameters(index: Int, source: ShaderSource) {
        val pass = chain.passes[index]
        val values = pass.parameters.toMutableMap()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
        source.parameters.forEach { p ->
            var value = (values[p.id] ?: p.initial).coerceIn(p.min, p.max)
            val label = TextView(this).apply { text = "${p.label}: $value" }
            root.addView(label)
            root.addView(SeekBar(this).apply {
                max = ((p.max - p.min) / p.step).roundToInt().coerceIn(1, 10000)
                progress = (((value - p.min) / (p.max - p.min).coerceAtLeast(0.0001f)) * max).roundToInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser) { value = p.min + (p.max - p.min) * progress / max; values[p.id] = value; label.text = "${p.label}: %.2f".format(value) }
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) {}
                    override fun onStopTrackingTouch(bar: SeekBar) {}
                })
            })
        }
        AlertDialog.Builder(this).setTitle(source.name).setView(ScrollView(this).apply { addView(root) })
            .setPositiveButton("Apply") { _, _ -> update(index, pass.copy(parameters = values), rebuild = false) }
            .setNeutralButton("Defaults") { _, _ -> update(index, pass.copy(parameters = emptyMap()), rebuild = false) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun <T> work(task: () -> T, done: (T) -> Unit) {
        busy = true; render()
        worker.execute {
            val result = runCatching(task)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy = false; render()
                result.fold(done) { message(it.message ?: "Shader import failed.") }
            }
        }
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) { finish(); return true }
        return super.onKeyUp(keyCode, event)
    }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
