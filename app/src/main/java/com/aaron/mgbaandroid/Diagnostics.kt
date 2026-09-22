package com.aaron.mgbaandroid

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File

class MgbaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.start(this)
    }
}

object Diagnostics {
    private val lock = Any()
    private var generation = 0
    private var process: java.lang.Process? = null
    private var active = false
    private var hooked = false
    private fun dir(context: Context) = File(context.filesDir, "diagnostics").apply { mkdirs() }
    fun enabled(context: Context) = context.getSharedPreferences("diagnostics", 0).getBoolean("enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        synchronized(lock) {
            context.getSharedPreferences("diagnostics", 0).edit().putBoolean("enabled", enabled).apply()
            if (!enabled) {
                generation++
                active = false
                process?.destroy()
                process = null
            }
        }
        if (enabled) start(context.applicationContext)
    }

    private fun append(context: Context, text: String) {
        val current = File(dir(context), "current.txt")
        if (current.length() > 1024 * 1024) {
            val previous = File(dir(context), "previous.txt")
            previous.delete()
            current.renameTo(previous)
        }
        current.appendText(text.take(32 * 1024) + "\n")
    }

    fun record(context: Context, text: String) = synchronized(lock) {
        if (enabled(context)) runCatching { append(context, "${java.util.Date()}: $text") }
        Unit
    }

    fun start(context: Context) {
        val app = context.applicationContext
        val ticket = synchronized(lock) {
            if (!enabled(app) || active) return
            active = true
            if (!hooked) {
                hooked = true
                val previous = Thread.getDefaultUncaughtExceptionHandler()
                Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                    record(app, "Uncaught exception on ${thread.name}:\n${error.stackTraceToString()}")
                    if (previous != null) previous.uncaughtException(thread, error)
                    else { Process.killProcess(Process.myPid()); kotlin.system.exitProcess(10) }
                }
            }
            ++generation
        }
        Thread({
            var readerProcess: java.lang.Process? = null
            try {
                record(app, "Session started; device=${Build.MANUFACTURER} ${Build.MODEL}; Android=${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}; app=${app.packageManager.getPackageInfo(app.packageName, 0).versionName}")
                // Earlier Android versions do not expose historical process exit information.
                if (Build.VERSION.SDK_INT >= 30) runCatching {
                    app.getSystemService(ActivityManager::class.java)
                        .getHistoricalProcessExitReasons(app.packageName, 0, 3).forEach {
                            record(app, "Previous exit: time=${it.timestamp} reason=${it.reason} status=${it.status} description=${it.description}")
                        }
                }
                synchronized(lock) {
                    if (ticket != generation || !enabled(app)) return@Thread
                    readerProcess = ProcessBuilder("logcat", "--pid=${Process.myPid()}",
                        "-b", "main", "-b", "system", "-b", "crash", "-v", "threadtime", "-T", "1")
                        .redirectErrorStream(true).start()
                    process = readerProcess
                }
                readerProcess!!.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        synchronized(lock) {
                            if (ticket != generation || !enabled(app)) return@Thread
                            runCatching { append(app, line) }
                        }
                    }
                }
            } catch (error: Exception) {
                synchronized(lock) {
                    if (ticket == generation) record(app, "Logcat unavailable: ${error.message}; app event logging remains enabled.")
                }
            } finally {
                readerProcess?.destroy()
                synchronized(lock) {
                    if (ticket == generation) {
                        process = null
                        active = false
                    }
                }
            }
        }, "mgba-diagnostics").start()
    }

    fun snapshot(context: Context): File = synchronized(lock) {
        val folder = File(context.cacheDir, "diagnostic-share").apply { mkdirs() }
        File(folder, "mgba-diagnostics.txt").apply {
            bufferedWriter().use { writer ->
                writer.write("mGBA Android diagnostic report\nLogs may contain game filenames and paths.\n")
                for (name in listOf("previous.txt", "current.txt")) {
                    val log = File(dir(context), name)
                    if (log.exists()) writer.write(log.readText())
                }
            }
        }
    }

    fun clear(context: Context) = synchronized(lock) {
        for (name in listOf("previous.txt", "current.txt")) File(dir(context), name).delete()
        File(context.cacheDir, "diagnostic-share/mgba-diagnostics.txt").delete()
    }
}
