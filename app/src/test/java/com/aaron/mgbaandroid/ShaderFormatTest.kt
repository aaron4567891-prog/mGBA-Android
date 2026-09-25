package com.aaron.mgbaandroid

import org.junit.Assert.*
import org.junit.Test

class ShaderFormatTest {
    private fun rejects(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }

    @Test fun importsOrderedPassesAndParameters() {
        val passes = ShaderFormat.preset("""
            shaders = "2"
            shader0 = "color/gba-color.glsl"
            filter_linear0 = false
            scale_type0 = source
            scale0 = 1.0
            shader1 = lcd3x.glsl
            filter_linear1 = true
            scale_type1 = viewport
            parameters = "darken_screen;brighten_lcd"
            darken_screen = 0.75
            brighten_lcd = 4.0
        """.trimIndent())
        assertEquals(2, passes.size)
        assertEquals("color/gba-color.glsl", passes[0].shader)
        assertEquals("source", passes[0].scaleType)
        assertFalse(passes[0].linear)
        assertTrue(passes[1].linear)
        assertEquals(0.75f, passes[0].parameters["darken_screen"])
        assertEquals("viewport", passes[1].scaleType)
    }

    @Test fun unsafeAndUnsupportedPresetFeaturesAreRejected() {
        val base = "shaders=1\nshader0=effect.glsl\n"
        for (extra in listOf("textures=lookup", "feedback_pass=0", "alias0=custom", "float_framebuffer0=true",
            "srgb_framebuffer0=true", "mipmap_input0=true", "scale0=5", "scale0=NaN", "scale_type0=absolute",
            "scale_x0=1\nscale_y0=2", "scale_y0=abc", "shader0=effect.slang", "shaders=7")) {
            assertTrue(extra, runCatching { ShaderFormat.preset(base + extra) }.isFailure)
        }
    }

    @Test fun includesAreRelativeAndCannotEscapeOrRecurse() {
        val files = mapOf("effects/test.glsl" to "#include \"../common.glsl\"\nbody", "common.glsl" to "shared")
        assertEquals("shared\nbody", ShaderFormat.expand("effects/test.glsl", files::getValue))
        rejects { ShaderFormat.resolve("test.glsl", "../outside") }
        rejects { ShaderFormat.resolve("test.glsl", "C:\\outside") }
        rejects { ShaderFormat.resolve("test.glsl", "/outside") }
        rejects { ShaderFormat.expand("cycle.glsl", { "#include \"cycle.glsl\"" }) }
        rejects { ShaderFormat.expand("large.glsl", { path ->
            if (path == "large.glsl") "#include \"part\"\n#include \"part\"" else "x".repeat(600000)
        }) }
    }

    @Test fun expandsParametersForGlesStages() {
        val text = """
            #version 120
            #pragma parameter strength "Strength" 1.0 0.0 2.0 0.25
            #ifdef VERTEX
            void main() {}
            #elif defined(FRAGMENT)
            void main() {}
            #endif
        """.trimIndent()
        val parameter = ShaderFormat.parameters(text).single()
        assertEquals("Strength", parameter.label)
        assertEquals(0.25f, parameter.step)
        val fragment = ShaderFormat.stage(text, false)
        assertTrue(fragment.startsWith("#version 100\n#define FRAGMENT"))
        assertFalse(fragment.contains("#version 120"))
        assertFalse(fragment.contains("#pragma parameter"))
        rejects { ShaderFormat.validate(text + "\nuniform sampler2D PrevTexture;") }
    }
}
