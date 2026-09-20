package com.aaron.mgbaandroid

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/** Reads archives in memory; archive paths are never written to the filesystem. */
object RomArchive {
    const val ENTRY_EXTRA = "rom_zip_entry"
    private const val MAX_ROM = 64L * 1024 * 1024
    private const val MAX_ARCHIVE = 128L * 1024 * 1024
    data class Loaded(val name: String, val bytes: ByteArray)

    fun extension(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    private fun isRom(name: String) = extension(name) in setOf("gba", "gbc", "gb") &&
        !name.startsWith("__MACOSX/") && !name.substringAfterLast('/').startsWith("._")

    private fun consume(input: InputStream, limit: Long, output: ByteArrayOutputStream? = null): Long {
        val buffer = ByteArray(16384)
        var size = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            val count = input.read(buffer)
            if (count < 0) return size
            size += count
            require(size <= limit) { "ROM or ZIP exceeds the supported size limit" }
            output?.write(buffer, 0, count)
        }
    }

    fun entries(input: InputStream): List<String> = ZipInputStream(input.buffered()).use { zip ->
        val names = ArrayList<String>()
        var total = 0L
        var count = 0
        while (true) {
            val entry = zip.nextEntry ?: break
            require(++count <= 2048) { "ZIP contains too many entries" }
            val size = consume(zip, MAX_ARCHIVE - total)
            total += size
            if (!entry.isDirectory && isRom(entry.name) && size in 1..MAX_ROM) names.add(entry.name)
            zip.closeEntry()
        }
        names
    }

    fun load(input: InputStream, name: String, selectedEntry: String? = null): Loaded =
        PushbackInputStream(input.buffered(), 4).use { stream ->
            val signature = ByteArray(4)
            var count = 0
            while (count < 4) {
                val read = stream.read(signature, count, 4 - count)
                if (read < 0) break
                count += read
            }
            stream.unread(signature, 0, count)
            val zipHeader = count == 4 && signature[0] == 0x50.toByte() && signature[1] == 0x4b.toByte() &&
                ((signature[2] == 3.toByte() && signature[3] == 4.toByte()) ||
                 (signature[2] == 5.toByte() && signature[3] == 6.toByte()))
            if (!zipHeader && extension(name) != "zip") {
                require(selectedEntry == null) { "Selected file is not a ZIP archive" }
                val output = ByteArrayOutputStream()
                require(consume(stream, MAX_ROM, output) > 0) { "ROM file is empty" }
                return@use Loaded(name, output.toByteArray())
            }
            ZipInputStream(stream).use { zip ->
                var total = 0L
                var entryCount = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++entryCount <= 2048) { "ZIP contains too many entries" }
                    if (!entry.isDirectory && isRom(entry.name) && (selectedEntry == null || entry.name == selectedEntry)) {
                        val output = ByteArrayOutputStream()
                        require(consume(zip, minOf(MAX_ROM, MAX_ARCHIVE - total), output) > 0) { "ROM inside ZIP is empty" }
                        return Loaded(entry.name.substringAfterLast('/'), output.toByteArray())
                    }
                    total += consume(zip, MAX_ARCHIVE - total)
                    zip.closeEntry()
                }
                error("No supported GBA, GBC or GB ROM found in ZIP")
            }
        }
}
