package com.aaron.mgbaandroid

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShaderUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun withShaderScreen(test: (ActivityScenario<ShaderActivity>) -> Unit) {
        val app = instrumentation.targetContext
        val preferences = app.getSharedPreferences("video_game_shader_ui_test",0)
        preferences.edit().clear().putBoolean("enabled",true)
            .putString(ShaderLibrary.KEY,ShaderLibrary.encode(ShaderLibrary.screenshotPreset())).commit()
        try {
            val intent = Intent(app,ShaderActivity::class.java).putExtra("game","shader_ui_test").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<ShaderActivity>(intent).use { scenario ->
                instrumentation.waitForIdleSync()
                test(scenario)
            }
        } finally { preferences.edit().clear().commit() }
    }

    @Test fun repeatedFilterChangesPreserveScreenFocusAndScroll() = withShaderScreen { scenario ->
        lateinit var screen: ScrollView
        lateinit var filter: Button
        var scrollPosition = 0
        scenario.onActivity { activity ->
            screen = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ScrollView
            filter = descendants(screen).filterIsInstance<Button>().first { it.text.toString() == "#1 Filter: Nearest" }
            assertTrue(filter.requestFocusFromTouch())
        }
        instrumentation.waitForIdleSync()
        scenario.onActivity { scrollPosition = screen.scrollY }
        repeat(4) { step ->
            scenario.onActivity {
                assertTrue(filter.performClick())
                assertEquals("#1 Filter: ${if (step % 2 == 0) "Linear" else "Nearest"}",filter.text.toString())
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertSame(screen,activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
                assertSame(filter,activity.currentFocus)
                assertEquals(scrollPosition,screen.scrollY)
                val settings = VideoOptions.read(activity,"shader_ui_test").shaders
                assertEquals(step % 2 == 0,settings.passes[1].linear)
                assertEquals(ShaderLibrary.screenshotPreset().passes[0],settings.passes[0])
            }
        }
    }

    @Test fun filterChangesReuseTheGameSurfaceAcrossResume() = withShaderScreen { scenario ->
        lateinit var view: EmulatorView
        lateinit var surface: ShaderSurface
        val options = VideoOptions(shaders=ShaderLibrary.screenshotPreset())
        scenario.onActivity { activity ->
            view = EmulatorView(activity)
            activity.setContentView(view)
            view.submitFrame(IntArray(240*160) { 0xff336699.toInt() },240,160)
            view.configure(options)
            surface = (0 until view.childCount).map { view.getChildAt(it) }.filterIsInstance<ShaderSurface>().single()
        }
        instrumentation.waitForIdleSync()
        scenario.onActivity {
            view.pause()
            val changed = options.copy(shaders=options.shaders.copy(passes=options.shaders.passes.map { it.copy(linear=true) }))
            view.configure(changed)
            view.resume()
            assertSame(surface,(0 until view.childCount).map { view.getChildAt(it) }.filterIsInstance<ShaderSurface>().single())
            view.configure(options)
            assertSame(surface,(0 until view.childCount).map { view.getChildAt(it) }.filterIsInstance<ShaderSurface>().single())
            view.configure(options.copy(shaders=options.shaders.copy(enabled=false)))
            assertFalse((0 until view.childCount).map { view.getChildAt(it) }.any { it is ShaderSurface })
        }
    }
}
