package com.aaron.mgbaandroid

/** The portable subset of RetroArch's combined GLES-compatible GLSL format. */
data class ShaderParameter(val id: String, val label: String, val initial: Float,
    val min: Float, val max: Float, val step: Float)

data class ShaderPass(
    val shader: String,
    val linear: Boolean = false,
    val scaleType: String = "viewport",
    val scale: Float = 1f,
    val parameters: Map<String, Float> = emptyMap()
)

data class ShaderChain(val enabled: Boolean = false, val passes: List<ShaderPass> = emptyList()) {
    companion object { const val MAX_PASSES = 6 }
}

data class ShaderSource(val id: String, val name: String, val source: String) {
    // Source is immutable. Parsing it in the draw loop adds allocations and
    // occasional GC pauses even when neither the shader nor its controls changed.
    val parameters: List<ShaderParameter> by lazy { ShaderFormat.parameters(source) }
}

object ShaderFormat {
    private val parameter = Regex("""^\s*#pragma\s+parameter\s+(\w+)\s+"([^"]+)"\s+([-+\d.eE]+)\s+([-+\d.eE]+)\s+([-+\d.eE]+)\s+([-+\d.eE]+)""", RegexOption.MULTILINE)

    fun parameters(source: String): List<ShaderParameter> = parameter.findAll(source).map {
        val g = it.groupValues
        val values = (3..6).map { i -> g[i].toFloat() }
        require(values.all(Float::isFinite) && values[1] <= values[2] && values[3] > 0) {
            "Invalid shader parameter: ${g[1]}"
        }
        ShaderParameter(g[1], g[2], values[0].coerceIn(values[1], values[2]), values[1], values[2], values[3])
    }.toList().also {
        require(it.size <= 64 && it.map { p -> p.id }.distinct().size == it.size) {
            "Shaders must have at most 64 uniquely named parameters."
        }
    }

    fun validate(source: String) {
        require(source.length <= 1024 * 1024) { "Shader is too large (limit 1 MB)." }
        require(source.contains("VERTEX") && source.contains("FRAGMENT")) {
            "Choose a combined RetroArch GLSL shader with VERTEX and FRAGMENT stages."
        }
        val version = Regex("""(?m)^\s*#version\s+(\d+)""").find(source)?.groupValues?.get(1)?.toInt()
        require(version == null || version in listOf(100, 110, 120)) {
            "This shader requires a newer GLSL profile. Choose its OpenGL ES 2 compatible version."
        }
        require(!Regex("""\b(?:Prev\d*|Feedback)Texture\b""").containsMatchIn(source)) {
            "Shaders that require previous-frame or feedback textures are not supported."
        }
        parameters(source)
    }

    fun stage(source: String, vertex: Boolean): String {
        validate(source)
        val body = source.replace(Regex("""(?m)^\s*#version[^\r\n]*"""), "")
            .replace(Regex("""(?m)^\s*#pragma\s+parameter[^\r\n]*"""), "")
        return "#version 100\n#define ${if (vertex) "VERTEX" else "FRAGMENT"}\n#define PARAMETER_UNIFORM\n" + body
    }

    /** Resolve includes relative to their containing file, bounded to the selected folder. */
    fun resolve(base: String, relative: String): String {
        val path = relative.replace('\\', '/')
        require(!path.startsWith('/') && !path.contains(':')) { "Absolute shader paths are not supported." }
        val parts = mutableListOf<String>()
        for (part in (base.substringBeforeLast('/', "") + "/" + path).split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> { require(parts.isNotEmpty()) { "Shader dependency is outside the selected folder." }; parts.removeAt(parts.lastIndex) }
                else -> parts.add(part)
            }
        }
        return parts.joinToString("/")
    }

    fun expand(path: String, read: (String) -> String, stack: Set<String> = emptySet()): String {
        require(path !in stack && stack.size < 16) { "Circular or overly deep shader include: $path" }
        val source = read(path)
        val result = StringBuilder()
        fun appendBounded(text: String) {
            require(result.length + text.length <= 1024 * 1024) { "Expanded shader exceeds 1 MB." }
            result.append(text)
        }
        var offset = 0
        Regex("""(?m)^\s*#include\s+["<]([^">]+)[">][^\r\n]*""").findAll(source).forEach {
            appendBounded(source.substring(offset, it.range.first))
            appendBounded(expand(resolve(path, it.groupValues[1]), read, stack + path))
            offset = it.range.last + 1
        }
        appendBounded(source.substring(offset))
        return result.toString()
    }

    fun preset(text: String): List<ShaderPass> {
        require(!Regex("""(?m)^\s*#reference\b""").containsMatchIn(text)) { "Referenced presets are not supported; choose a complete .glslp preset." }
        val entries = linkedMapOf<String, String>()
        text.lineSequence().forEach { line ->
            val match = Regex("""^\s*([A-Za-z_][A-Za-z_0-9]*)\s*=\s*(?:"([^"]*)"|([^#]*))""").find(line)
            if (match != null) entries[match.groupValues[1]] = (match.groups[2]?.value ?: match.groupValues[3]).trim()
        }
        val count = entries["shaders"]?.toIntOrNull() ?: error("Preset has no shader pass count.")
        require(count in 1..ShaderChain.MAX_PASSES) { "Choose a preset with 1–${ShaderChain.MAX_PASSES} passes." }
        require(entries["textures"].isNullOrBlank()) { "Lookup-texture presets are not supported yet. Choose a preset without external textures." }
        require(entries["feedback_pass"] == null) { "Feedback presets are not supported." }
        val parameterIds = entries["parameters"]?.split(';')?.filter(String::isNotBlank).orEmpty()
        val values = parameterIds.associateWith { entries[it]?.toFloatOrNull()?.takeIf(Float::isFinite) ?: error("Invalid parameter: $it") }
        return (0 until count).map { i ->
            require(listOf("float_framebuffer$i", "srgb_framebuffer$i", "mipmap_input$i").none { entries[it] in listOf("true", "1") }) {
                "This preset requires float/sRGB framebuffers or mipmaps, which are not supported."
            }
            require(entries["alias$i"].isNullOrBlank()) { "Named pass aliases are not supported in imported presets." }
            val type = entries["scale_type$i"] ?: entries["scale_type_x$i"] ?: if (i == count - 1) "viewport" else "source"
            val typeY = entries["scale_type_y$i"] ?: type
            require(type in listOf("source", "viewport") && typeY == type) { "Only matching source or viewport X/Y scaling is supported." }
            val scale = (entries["scale$i"] ?: entries["scale_x$i"] ?: "1").toFloatOrNull() ?: error("Invalid scale.")
            val scaleY = entries["scale_y$i"]?.let { it.toFloatOrNull() ?: error("Invalid Y scale.") } ?: scale
            require(scale.isFinite() && scale in 0.25f..4f && scaleY == scale) { "Use matching X/Y scales between 0.25x and 4x." }
            val shader = entries["shader$i"] ?: error("Missing shader$i.")
            require(shader.endsWith(".glsl", true)) { "Use .glsl/.glslp files. Slang and Cg need a different renderer." }
            val linear = entries["filter_linear$i"] ?: "false"
            require(linear in listOf("false", "true", "0", "1")) { "Invalid filter for pass $i." }
            ShaderPass(shader, linear in listOf("true", "1"), type, scale, values)
        }
    }
}
