package com.aaron.mgbaandroid

import android.opengl.EGL14.*
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.io.File
import android.net.Uri
import org.json.JSONObject
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ShaderGpuTest {
    private lateinit var display: EGLDisplay
    private lateinit var context: EGLContext
    private lateinit var surface: EGLSurface
    private lateinit var renderer: ShaderRenderer
    private val library get() = ShaderLibrary(InstrumentationRegistry.getInstrumentation().targetContext)

    @Before fun createContext() {
        display = eglGetDisplay(EGL_DEFAULT_DISPLAY)
        assertTrue(eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
        assertTrue(eglChooseConfig(display, intArrayOf(EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE),0,configs,0,1,count,0))
        assertTrue(count[0] > 0)
        context = eglCreateContext(display, configs[0], EGL_NO_CONTEXT, intArrayOf(EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE),0)
        surface = eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL_WIDTH,720,EGL_HEIGHT,480,EGL_NONE),0)
        assertTrue(eglMakeCurrent(display, surface, surface, context))
        renderer = ShaderRenderer { id -> if (id == "identity") ShaderSource(id, id, IDENTITY) else library.source(id) }
    }
    @After fun destroyContext() {
        renderer.release()
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)
        eglDestroySurface(display, surface); eglDestroyContext(display, context); eglTerminate(display)
    }
    private fun pixel(x: Int, y: Int): Int {
        val bytes = ByteBuffer.allocateDirect(4)
        glReadPixels(x,y,1,1,GL_RGBA,GL_UNSIGNED_BYTE,bytes)
        assertEquals(GL_NO_ERROR, glGetError())
        return ((bytes[0].toInt() and 255) shl 16) or ((bytes[1].toInt() and 255) shl 8) or (bytes[2].toInt() and 255)
    }
    @Test fun orientationAndChannelsSurviveZeroOneTwoAndThreePasses() {
        val input = intArrayOf(0xffff0000.toInt(),0xff00ff00.toInt(),0xff0000ff.toInt(),0xffffffff.toInt())
        for (passes in 0..3) {
            renderer.render(input,2,2,720,480, VideoOptions(aspect=1,
                shaders=ShaderChain(true,List(passes) { ShaderPass("identity",scaleType="source") })))
            assertEquals("top-left pass $passes",0xff0000,pixel(80,400))
            assertEquals("top-right pass $passes",0x00ff00,pixel(640,400))
            assertEquals("bottom-left pass $passes",0x0000ff,pixel(80,80))
            assertEquals("bottom-right pass $passes",0xffffff,pixel(640,80))
        }
    }
    @Test fun builtinPresetCompilesAndParametersChangeThePicture() {
        val input = IntArray(240*160) { 0xffd09070.toInt() }
        val preset = ShaderLibrary.screenshotPreset()
        renderer.render(input,240,160,720,480,VideoOptions(shaders=preset))
        val initial = pixel(361,241)
        assertNotEquals(0, initial)
        assertNotEquals(0xd09070, initial)
        val changed = preset.copy(passes=preset.passes.map {
            if (it.shader == "gba-color") it.copy(parameters=mapOf("darken_screen" to 2.2f)) else it
        })
        renderer.render(input,240,160,720,480,VideoOptions(shaders=changed))
        assertNotEquals(initial, pixel(361,241))
        renderer.release()
        renderer.render(input,240,160,720,480,VideoOptions(shaders=preset))
        assertEquals(initial,pixel(361,241))
    }
    @Test fun excessiveScaleAndUnsupportedUniformsFailWithUsefulErrors() {
        val input = IntArray(240*160) { -1 }
        val error = runCatching { renderer.render(input,240,160,720,480,VideoOptions(shaders=ShaderChain(true,
            List(3) { ShaderPass("identity",scale=4f,scaleType="source") }))) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("scale",true))
        val bad = ShaderRenderer { ShaderSource("bad","bad",IDENTITY.replace("texture2D(Texture", "texture2D(PrevTexture").replace("uniform sampler2D Texture;","uniform sampler2D PrevTexture;")) }
        try {
            assertTrue(runCatching { bad.render(input,240,160,720,480,VideoOptions(shaders=ShaderChain(true,listOf(ShaderPass("bad"))))) }.isFailure)
        } finally { bad.release() }
    }
    @Test fun settingsRoundTripKeepsOrderAndPerGameIsolation() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val local = app.getSharedPreferences("video_game_shader_test",0)
        try {
            local.edit().putBoolean("enabled",true).putString(ShaderLibrary.KEY,ShaderLibrary.encode(ShaderLibrary.screenshotPreset())).commit()
            assertEquals(ShaderLibrary.screenshotPreset(),VideoOptions.read(app,"shader_test").shaders)
            local.edit().putBoolean("enabled",false).commit()
            assertEquals(VideoOptions.read(app,null),VideoOptions.read(app,"shader_test"))
            assertEquals(ShaderChain(),ShaderLibrary.decode("invalid json"))
        } finally { local.edit().clear().commit() }
    }
    @Test fun importedPresetWithIncludesSurvivesDeletingOriginalFiles() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(app.cacheDir,"shader-import-test").apply { mkdirs() }
        val before = library.sources().map { it.id }.toSet()
        val files = listOf(File(folder,"preset.glslp"),File(folder,"effect.glsl"),File(folder,"common.inc"))
        try {
            files[0].writeText("shaders=1\nshader0=effect.glsl\nscale_type0=source\nscale0=1\n")
            files[1].writeText("#include \"common.inc\"\n" + IDENTITY)
            files[2].writeText("// Imported shader test include\n")
            val imported = library.importFromFolder("preset.glslp",files.associate { it.name to Uri.fromFile(it) })
            files.forEach { it.delete() }
            assertFalse(library.source(imported.single().shader).source.contains("#include"))
            renderer.render(IntArray(240*160){0xff336699.toInt()},240,160,720,480,VideoOptions(shaders=ShaderChain(true,imported)))
            assertEquals(0x336699,pixel(360,240))
            assertEquals(imported,ShaderLibrary.decode(ShaderLibrary.encode(ShaderChain(true,imported))).passes)
        } finally {
            files.forEach { it.delete() }; folder.delete()
            library.sources().filter { it.id !in before }.forEach { File(app.filesDir,"shaders/${it.id}.json").delete() }
        }
    }
    @Test fun shaderCompilerFailureIsReported() {
        val broken = ShaderRenderer { ShaderSource("bad","bad",IDENTITY.replace("gl_FragColor=", "invalid syntax gl_FragColor=")) }
        try {
            val error = runCatching { broken.render(IntArray(4){-1},2,2,720,480,
                VideoOptions(shaders=ShaderChain(true,listOf(ShaderPass("bad"))))) }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("compilation failed"))
        } finally { broken.release() }
    }
    @Test fun gbaColourMatchesSlangProfilesDarknessAndDefaults() {
        val parameters = library.source("gba-color").parameters
        assertEquals(ShaderParameter("profile", "Color Profile (1=sRGB, 2=DCI, 3=Rec2020)", 1f, 1f, 3f, 1f), parameters[0])
        assertEquals(ShaderParameter("darken_screen", "Screen Darkness", 0f, 0f, 2.2f, 0.1f), parameters[1])
        // Golden values generated independently from the pinned Slang source, not
        // the GLSL port. Primaries detect matrix transposes and clamp/gamma order.
        val fixture = InstrumentationRegistry.getInstrumentation().context.assets
            .open("gba-color-slang-reference.json").bufferedReader().use { JSONObject(it.readText()) }
        val colors = fixture.getJSONArray("colors")
        val input = IntArray(colors.length()) { colors.getInt(it) or 0xff000000.toInt() }
        val cases = fixture.getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val values = mapOf("profile" to case.getDouble("profile").toFloat(),
                "darken_screen" to case.getDouble("darkness").toFloat())
            renderer.render(input,input.size,1,720,480,VideoOptions(shaders=ShaderChain(true,
                listOf(ShaderPass("gba-color",scaleType="source",parameters=values)))))
            val expected = case.getJSONArray("expected")
            for (i in input.indices) {
                val actual = pixel(((i + 0.5f) * 720 / input.size).toInt(),240)
                for (shift in listOf(16,8,0)) assertTrue("profile/darkness=$values colour=$i channel=$shift expected=${expected.getInt(i).toString(16)} actual=${actual.toString(16)}",
                    abs(((expected.getInt(i) shr shift) and 255) - ((actual shr shift) and 255)) <= 1)
            }
        }
    }
    @Test fun filterChangesApplyWithoutReloadingShaders() {
        var sourceLoads = 0
        val live = ShaderRenderer { id -> sourceLoads++; ShaderSource(id,id,IDENTITY) }
        val input = intArrayOf(0xffff0000.toInt(),0xff0000ff.toInt())
        val nearest = VideoOptions(shaders=ShaderChain(true,listOf(ShaderPass("identity"))))
        try {
            live.render(input,2,1,720,480,nearest)
            val sharp = pixel(340,240)
            assertEquals(0xff0000,sharp)
            val linear = nearest.copy(shaders=nearest.shaders.copy(passes=listOf(ShaderPass("identity",linear=true))))
            live.render(input,2,1,720,480,linear)
            val smooth = pixel(340,240)
            assertTrue((smooth and 255) in 1..254)
            assertTrue(((smooth shr 16) and 255) in 1..254)
            live.render(input,2,1,720,480,nearest)
            assertEquals(sharp,pixel(340,240))
            assertEquals("Filter changes should reuse the loaded programs",1,sourceLoads)
        } finally { live.release() }
    }
    companion object {
        private const val IDENTITY = """
            #ifdef VERTEX
            attribute vec4 VertexCoord; attribute vec2 TexCoord; varying vec2 uv;
            void main(){gl_Position=VertexCoord;uv=TexCoord;}
            #elif defined(FRAGMENT)
            precision mediump float; varying vec2 uv; uniform sampler2D Texture;
            void main(){gl_FragColor=texture2D(Texture,uv);}
            #endif
        """
    }
}
