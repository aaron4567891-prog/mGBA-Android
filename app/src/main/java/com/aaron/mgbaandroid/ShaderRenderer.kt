package com.aaron.mgbaandroid

import android.graphics.Bitmap
import android.opengl.GLES20.*
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToInt

/** All methods run on the owning GL thread. No emulation or audio runs here. */
internal class ShaderRenderer(private val source: (String) -> ShaderSource) {
    private data class Target(val texture: Int, val width: Int, val height: Int, val framebuffer: Int = 0)
    private class Program(val id: Int) {
        val uniforms = linkedMapOf<String, Int>()
        val samplers = mutableListOf<String>()
        val attributes = linkedMapOf<String, Int>()
        init {
            val count = IntArray(1); val size = IntArray(1); val type = IntArray(1)
            glGetProgramiv(id, GL_ACTIVE_UNIFORMS, count, 0)
            repeat(count[0]) { index ->
                val name = glGetActiveUniform(id, index, size, 0, type, 0)
                require(size[0] == 1) { "Uniform arrays are not supported: $name" }
                uniforms[name] = glGetUniformLocation(id, name)
                if (type[0] == GL_SAMPLER_2D) samplers.add(name)
                require(type[0] != GL_SAMPLER_CUBE) { "Cube textures are not supported." }
            }
            glGetProgramiv(id, GL_ACTIVE_ATTRIBUTES, count, 0)
            repeat(count[0]) { index ->
                val name = glGetActiveAttrib(id, index, size, 0, type, 0)
                require(name in listOf("VertexCoord", "TexCoord", "COLOR", "OrigTexCoord") || name.matches(Regex("Pass[1-6]TexCoord"))) {
                    "Unsupported shader attribute: $name"
                }
                attributes[name] = glGetAttribLocation(id, name)
            }
        }
        fun integer(name: String, value: Int) { uniforms[name]?.let { glUniform1i(it, value) } }
        fun number(name: String, value: Float) { uniforms[name]?.let { glUniform1f(it, value) } }
        fun size(name: String, width: Int, height: Int) { uniforms[name]?.let { glUniform2f(it, width.toFloat(), height.toFloat()) } }
    }

    private var programs = emptyList<Program>()
    private var sources = emptyList<ShaderSource>()
    private var shaderIds: List<String>? = null
    private var blit: Program? = null
    private var input: Target? = null
    private val targets = mutableListOf<Target>()
    private var bitmap: Bitmap? = null
    private var expanded = IntArray(0)
    private var frame = 0
    private val positions = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val coordinates = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    private val flipped = floats(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
    private val identity = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)

    fun render(pixels: IntArray, width: Int, height: Int, screenWidth: Int, screenHeight: Int, options: VideoOptions) {
        if (screenWidth < 1 || screenHeight < 1 || width < 1 || height < 1) return
        require(pixels.size >= width * height)
        glDisable(GL_BLEND); glDisable(GL_DEPTH_TEST); glDisable(GL_SCISSOR_TEST)
        val identifiers = options.shaders.passes.map { it.shader }
        // Filtering and parameters are live GL state; only changed shader files
        // or pass order need new programs. Keep textures and frame history alive.
        if (shaderIds != identifiers) configure(identifiers)
        if (blit == null) blit = compile(BLIT_VERTEX, BLIT_FRAGMENT)
        val factor = if (options.mode == 2) 2 else 1
        val w = width * factor; val h = height * factor
        val data = if (factor == 2) {
            if (expanded.size != w * h) expanded = IntArray(w * h)
            PixelScaler.scale2x(pixels, width, height, expanded); expanded
        } else pixels
        if (input?.width != w || input?.height != h) {
            input?.let(::delete)
            input = texture(w, h, false)
            bitmap?.recycle()
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        bitmap!!.setPixels(data, 0, w, 0, 0, w, h)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, input!!.texture)
        GLUtils.texSubImage2D(GL_TEXTURE_2D, 0, 0, 0, bitmap!!)
        val viewport = VideoViewport.calculate(width, height, screenWidth, screenHeight, options)
        val sizes = mutableListOf<Pair<Int, Int>>()
        var previousWidth = w; var previousHeight = h
        var totalPixels = 0L
        val maxTexture = IntArray(1); glGetIntegerv(GL_MAX_TEXTURE_SIZE, maxTexture, 0)
        for (pass in options.shaders.passes) {
            val outW = ((if (pass.scaleType == "viewport") viewport.width else previousWidth) * pass.scale).roundToInt().coerceAtLeast(1)
            val outH = ((if (pass.scaleType == "viewport") viewport.height else previousHeight) * pass.scale).roundToInt().coerceAtLeast(1)
            totalPixels += outW.toLong() * outH
            require(outW <= minOf(maxTexture[0], 4096) && outH <= minOf(maxTexture[0], 4096) && totalPixels <= 16_777_216L) {
                "Shader scale uses too much GPU memory. Reduce pass scales or the number of passes."
            }
            sizes.add(outW to outH); previousWidth = outW; previousHeight = outH
        }
        if (targets.map { it.width to it.height } != sizes) {
            targets.forEach(::delete); targets.clear()
            sizes.forEach { (tw, th) -> targets.add(texture(tw, th, true)) }
        }
        var previous = input!!
        programs.forEachIndexed { index, program ->
            val target = targets[index]
            val pass = options.shaders.passes[index]
            glBindFramebuffer(GL_FRAMEBUFFER, target.framebuffer)
            glViewport(0, 0, target.width, target.height)
            glClearColor(0f, 0f, 0f, 1f); glClear(GL_COLOR_BUFFER_BIT)
            glUseProgram(program.id)
            program.uniforms["MVPMatrix"]?.let { glUniformMatrix4fv(it, 1, false, identity, 0) }
            program.integer("FrameCount", frame); program.integer("FrameDirection", 1)
            program.size("InputSize", previous.width, previous.height)
            program.size("TextureSize", previous.width, previous.height)
            program.size("OutputSize", target.width, target.height)
            program.size("OrigInputSize", w, h); program.size("OrigTextureSize", w, h)
            for (p in 0 until index) {
                program.size("Pass${p + 1}InputSize", targets[p].width, targets[p].height)
                program.size("Pass${p + 1}TextureSize", targets[p].width, targets[p].height)
            }
            sources[index].parameters.forEach { parameter ->
                program.number(parameter.id, (pass.parameters[parameter.id] ?: parameter.initial).coerceIn(parameter.min, parameter.max))
            }
            program.samplers.forEachIndexed { unit, name ->
                val tex = when (name) {
                    "Texture" -> previous
                    "OrigTexture" -> input!!
                    else -> targets[name.removePrefix("Pass").removeSuffix("Texture").toInt() - 1]
                }
                bind(tex.texture, unit, pass.linear); program.integer(name, unit)
            }
            draw(program, false)
            previous = target
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, screenWidth, screenHeight)
        glClearColor(0f, 0f, 0f, 1f); glClear(GL_COLOR_BUFFER_BIT)
        glViewport(viewport.x, viewport.y, viewport.width, viewport.height)
        val final = blit!!
        glUseProgram(final.id)
        bind(previous.texture, 0, options.mode == 1); final.integer("Texture", 0)
        final.size("GridSize", width, height)
        final.size("TargetSize", viewport.width, viewport.height)
        final.number("Grid", if (options.lcd && viewport.width >= width * 3 && viewport.height >= height * 3) 1f else 0f)
        // Bitmap row zero is uploaded at texture Y=0. Keep this convention through all
        // FBO passes and flip only at presentation, so pass count never changes orientation.
        draw(final, true)
        val error = glGetError()
        check(error == GL_NO_ERROR) { "GPU shader error 0x${error.toString(16)}" }
        frame = (frame + 1) and 0x7fffffff
    }

    private fun configure(identifiers: List<String>) {
        require(identifiers.size <= ShaderChain.MAX_PASSES)
        programs.forEach { glDeleteProgram(it.id) }; programs = emptyList()
        val loaded = identifiers.map(source)
        val compiled = mutableListOf<Program>()
        try {
            loaded.forEachIndexed { index, shader ->
                val program = compile(ShaderFormat.stage(shader.source, true), ShaderFormat.stage(shader.source, false))
                compiled.add(program)
                val known = mutableSetOf("Texture", "MVPMatrix", "InputSize", "TextureSize", "OutputSize", "FrameCount", "FrameDirection",
                    "OrigTexture", "OrigInputSize", "OrigTextureSize")
                known.addAll(shader.parameters.map { it.id })
                for (p in 1..index) known.addAll(listOf("Pass${p}Texture", "Pass${p}InputSize", "Pass${p}TextureSize"))
                val unsupported = program.uniforms.keys - known
                require(unsupported.isEmpty()) { "${shader.name}: unsupported uniforms ${unsupported.joinToString()}. Choose a sequential GLSL preset without temporal or lookup textures." }
                val units = IntArray(1); glGetIntegerv(GL_MAX_TEXTURE_IMAGE_UNITS, units, 0)
                require(program.samplers.size <= units[0]) { "Too many textures for this GPU." }
            }
        } catch (error: Exception) {
            compiled.forEach { glDeleteProgram(it.id) }
            throw error
        }
        sources = loaded; programs = compiled; shaderIds = identifiers
    }

    private fun compile(vertex: String, fragment: String): Program {
        fun stage(type: Int, text: String): Int {
            val shader = glCreateShader(type)
            glShaderSource(shader, text); glCompileShader(shader)
            val status = IntArray(1); glGetShaderiv(shader, GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val message = glGetShaderInfoLog(shader); glDeleteShader(shader)
                error("Shader compilation failed: ${message.take(1800)}")
            }
            return shader
        }
        val vs = stage(GL_VERTEX_SHADER, vertex)
        var fs = 0; var id = 0
        try {
            fs = stage(GL_FRAGMENT_SHADER, fragment)
            id = glCreateProgram()
            glAttachShader(id, vs); glAttachShader(id, fs); glLinkProgram(id)
            val status = IntArray(1); glGetProgramiv(id, GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "Shader link failed: ${glGetProgramInfoLog(id).take(1800)}" }
            return Program(id)
        } catch (error: Exception) {
            if (id != 0) glDeleteProgram(id)
            throw error
        } finally { glDeleteShader(vs); if (fs != 0) glDeleteShader(fs) }
    }

    private fun texture(width: Int, height: Int, framebuffer: Boolean): Target {
        val ids = IntArray(1); glGenTextures(1, ids, 0)
        val id = ids[0]
        bind(id, 0, false)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
        var fbo = 0
        if (framebuffer) {
            glGenFramebuffers(1, ids, 0); fbo = ids[0]
            glBindFramebuffer(GL_FRAMEBUFFER, fbo)
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, id, 0)
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                delete(Target(id, width, height, fbo)); error("Could not allocate a shader framebuffer. Reduce the pass scale.")
            }
        }
        return Target(id, width, height, fbo)
    }
    private fun bind(texture: Int, unit: Int, linear: Boolean) {
        glActiveTexture(GL_TEXTURE0 + unit); glBindTexture(GL_TEXTURE_2D, texture)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, if (linear) GL_LINEAR else GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, if (linear) GL_LINEAR else GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    }
    private fun draw(program: Program, flip: Boolean) {
        program.attributes.forEach { (name, location) ->
            if (name == "COLOR") { glDisableVertexAttribArray(location); glVertexAttrib4f(location, 1f, 1f, 1f, 1f) }
            else {
                val buffer = if (name == "VertexCoord") positions else if (flip) flipped else coordinates
                buffer.position(0); glEnableVertexAttribArray(location)
                glVertexAttribPointer(location, 2, GL_FLOAT, false, 0, buffer)
            }
        }
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        program.attributes.values.forEach { glDisableVertexAttribArray(it) }
    }
    private fun delete(target: Target) {
        glDeleteTextures(1, intArrayOf(target.texture), 0)
        if (target.framebuffer != 0) glDeleteFramebuffers(1, intArrayOf(target.framebuffer), 0)
    }
    fun release() {
        programs.forEach { glDeleteProgram(it.id) }; programs = emptyList()
        blit?.let { glDeleteProgram(it.id) }; blit = null
        input?.let(::delete); input = null
        targets.forEach(::delete); targets.clear()
        bitmap?.recycle(); bitmap = null; shaderIds = null
    }
    companion object {
        private fun floats(vararg values: Float) = ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
        private const val BLIT_VERTEX = "attribute vec4 VertexCoord; attribute vec2 TexCoord; varying vec2 uv; void main(){ gl_Position=VertexCoord; uv=TexCoord; }"
        private const val BLIT_FRAGMENT = """
            precision mediump float;
            uniform sampler2D Texture;
            uniform vec2 GridSize, TargetSize;
            uniform float Grid;
            varying vec2 uv;
            void main(){
                vec3 c=texture2D(Texture,uv).rgb;
                vec2 pos=fract(uv*GridSize);
                vec2 edge=GridSize/TargetSize;
                float line=max(1.0-step(edge.x,pos.x),1.0-step(edge.y,pos.y));
                gl_FragColor=vec4(c*(1.0-Grid*line*0.157),1.0);
            }
        """
    }
}

internal data class VideoViewport(val x: Int, val y: Int, val width: Int, val height: Int) {
    companion object {
        fun calculate(sourceWidth: Int, sourceHeight: Int, width: Int, height: Int, options: VideoOptions): VideoViewport {
            val sx = width.toFloat() / sourceWidth; val sy = height.toFloat() / sourceHeight
            var scale = if (options.aspect == 2) maxOf(sx, sy) else minOf(sx, sy)
            if (options.mode == 0 && options.aspect == 0 && scale >= 1f) scale = floor(scale)
            val ratio = when (options.aspect) { 3 -> 4f/3f; 4 -> 16f/9f; else -> null }
            val dw = when { ratio != null -> minOf(width.toFloat(), height * ratio); options.aspect == 1 -> width.toFloat(); else -> sourceWidth * scale }
            val dh = when { ratio != null -> dw / ratio; options.aspect == 1 -> height.toFloat(); else -> sourceHeight * scale }
            val w = dw.roundToInt().coerceAtLeast(1); val h = dh.roundToInt().coerceAtLeast(1)
            return VideoViewport((width - w) / 2, (height - h) / 2, w, h)
        }
    }
}
