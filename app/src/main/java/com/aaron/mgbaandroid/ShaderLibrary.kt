package com.aaron.mgbaandroid

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class ShaderLibrary(private val context: Context) {
    private val directory get() = File(context.filesDir, "shaders").apply { mkdirs() }

    fun source(id: String): ShaderSource {
        if (id in listOf("gba-color", "lcd3x")) return ShaderSource(id, "$id.glsl",
            context.assets.open("shaders/$id.glsl").bufferedReader().use { it.readText() })
        require(id.matches(Regex("[a-f0-9]{24}"))) { "Invalid shader identifier." }
        val json = JSONObject(File(directory, "$id.json").readText())
        return ShaderSource(id, json.getString("name"), json.getString("source"))
    }

    fun sources(): List<ShaderSource> = listOf(source("gba-color"), source("lcd3x")) +
        directory.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull {
            runCatching { source(it.nameWithoutExtension) }.getOrNull()
        }.sortedBy { it.name.lowercase() }

    fun install(name: String, text: String): ShaderSource {
        ShaderFormat.validate(text)
        val id = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
        val json = JSONObject().put("name", name).put("source", text)
        // Write atomically; the live renderer never sees a partially imported file.
        val pending = File(directory, "$id.tmp")
        pending.writeText(json.toString())
        val destination = File(directory, "$id.json")
        if (!pending.renameTo(destination)) { pending.delete(); error("Could not save shader.") }
        return ShaderSource(id, name, text)
    }

    fun importFile(uri: Uri): ShaderSource {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "shader.glsl"
        require(name.endsWith(".glsl", true)) { "Choose a .glsl file. To import a .glslp preset and its dependencies, use Import folder." }
        val text = read(uri)
        require(!Regex("""(?m)^\s*#include\b""").containsMatchIn(text)) {
            "This shader needs include files. Use Import folder and select their common parent folder."
        }
        return install(name, text)
    }

    fun read(uri: Uri): String = context.contentResolver.openInputStream(uri)?.use {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            require(output.size() + count <= 1024 * 1024) { "Shader file exceeds 1 MB." }
            output.write(buffer, 0, count)
        }
        output.toString("UTF-8").removePrefix("\uFEFF")
    } ?: error("Could not read shader file.")

    fun folder(tree: Uri): Map<String, Uri> {
        val files = linkedMapOf<String, Uri>()
        var visited = 0
        fun visit(id: String, prefix: String, depth: Int) {
            require(depth <= 20 && visited++ < 20000) { "Folder is too large; select a smaller shader folder." }
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    require(visited++ < 20000) { "Folder is too large; select a smaller shader folder." }
                    val childId = cursor.getString(0)
                    val name = cursor.getString(1)
                    val path = prefix + name
                    if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) visit(childId, "$path/", depth + 1)
                    else files[path] = DocumentsContract.buildDocumentUriUsingTree(tree, childId)
                }
            } ?: error("Could not read the selected folder.")
        }
        visit(DocumentsContract.getTreeDocumentId(tree), "", 0)
        return files
    }

    fun importFromFolder(path: String, files: Map<String, Uri>): List<ShaderPass> {
        fun readPath(name: String) = read(files[name] ?: error("Missing dependency: $name. Select the folder containing the preset and all its shaders."))
        val passes = if (path.endsWith(".glslp", true)) ShaderFormat.preset(readPath(path)).map {
            it.copy(shader = ShaderFormat.resolve(path, it.shader))
        } else listOf(ShaderPass(path))
        val expanded = passes.map { ShaderFormat.expand(it.shader, ::readPath) }
        expanded.forEach(ShaderFormat::validate)
        return passes.mapIndexed { index, pass ->
            pass.copy(shader = install(pass.shader.substringAfterLast('/'), expanded[index]).id)
        }
    }

    companion object {
        const val KEY = "display_shaders"
        fun preferences(context: Context, game: String?): SharedPreferences {
            val global = context.getSharedPreferences("emulator", Context.MODE_PRIVATE)
            val local = game?.let { context.getSharedPreferences("video_game_$it", Context.MODE_PRIVATE) }
            return if (local?.getBoolean("enabled", false) == true) local else global
        }
        fun decode(value: String?): ShaderChain = runCatching {
            if (value.isNullOrBlank()) return ShaderChain()
            val json = JSONObject(value)
            val list = json.getJSONArray("passes")
            require(list.length() <= ShaderChain.MAX_PASSES)
            ShaderChain(json.optBoolean("enabled", false), (0 until list.length()).map { i ->
                val p = list.getJSONObject(i)
                val parameters = p.optJSONObject("parameters") ?: JSONObject()
                val scale = p.optDouble("scale", 1.0).toFloat()
                require(scale.isFinite() && scale in 0.25f..4f)
                val type = p.optString("scaleType", "viewport")
                require(type in listOf("source", "viewport"))
                ShaderPass(p.getString("shader"), p.optBoolean("linear", false), type, scale,
                    parameters.keys().asSequence().associateWith { parameters.getDouble(it).toFloat().also { value -> require(value.isFinite()) } })
            })
        }.getOrDefault(ShaderChain())

        fun encode(chain: ShaderChain): String = JSONObject().put("enabled", chain.enabled)
            .put("passes", JSONArray().also { list -> chain.passes.forEach { pass ->
                list.put(JSONObject().put("shader", pass.shader).put("linear", pass.linear)
                    .put("scaleType", pass.scaleType).put("scale", pass.scale.toDouble())
                    .put("parameters", JSONObject(pass.parameters)))
            } }).toString()

        fun screenshotPreset() = ShaderChain(true, listOf(
            ShaderPass("gba-color", scaleType = "source"), ShaderPass("lcd3x")
        ))
    }
}
