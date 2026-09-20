package com.aaron.mgbaandroid

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as D
import java.util.zip.CRC32

object BiosFolder {
    // GB/CGB fingerprints match the BIOS whitelist in the bundled mGBA core.
    fun identify(bytes: ByteArray): String? {
        val crc = CRC32().apply { update(bytes) }.value
        if (bytes.size == 256 && crc in setOf(0xC2F5CC97L, 0x59C8598EL, 0xE6920754L)) return "gb"
        if (bytes.size in setOf(2048, 2304) && crc in setOf(0x41884E46L, 0xE8EF5318L, 0xE95DC95DL, 0xFFD6B0F1L, 0x570337EAL)) return "gbc"
        // Match the native GBA BIOS detector's ARM interrupt-vector checks.
        if (bytes.size == 16384 && (0..6).all { bytes[it * 4 + 3] == 0xEA.toByte() && bytes[it * 4 + 2] == 0.toByte() }) return "gba"
        return null
    }
    data class Result(val found: Map<String, ByteArray>, val unreadable: Int, val names: Map<String, String>)
    fun scan(context: Context, tree: Uri): Result {
        val found = linkedMapOf<String, ByteArray>()
        val names = linkedMapOf<String, String>()
        val queue = java.util.ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        var errors = 0
        queue.add(D.getTreeDocumentId(tree))
        while (queue.isNotEmpty() && found.size < 3) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            val id = queue.removeFirst()
            if (!visited.add(id)) continue
            val children = D.buildChildDocumentsUriUsingTree(tree, id)
            val cursor = context.contentResolver.query(children, arrayOf(D.Document.COLUMN_DOCUMENT_ID,
                D.Document.COLUMN_MIME_TYPE, D.Document.COLUMN_SIZE, D.Document.COLUMN_DISPLAY_NAME), null, null, null)
            if (cursor == null) { errors++; continue }
            cursor.use {
                while (it.moveToNext()) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    val child = it.getString(0)
                    if (it.getString(1) == D.Document.MIME_TYPE_DIR) { queue.add(child); continue }
                    if (!it.isNull(2) && it.getLong(2) !in setOf(256L, 2048L, 2304L, 16384L)) continue
                    runCatching {
                        val uri = D.buildDocumentUriUsingTree(tree, child)
                        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                            val out = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(4096)
                            while (out.size() <= 16384) {
                                val n = input.read(buffer, 0, minOf(buffer.size, 16385 - out.size()))
                                if (n < 0) break
                                out.write(buffer, 0, n)
                            }
                            out.toByteArray()
                        } ?: error("Cannot read file")
                        identify(bytes)?.let { system ->
                            if (system !in found) {
                                found[system] = bytes
                                names[system] = it.getString(3)?.takeIf { name -> name.isNotBlank() }
                                    ?: "${system}_bios.bin"
                            }
                        }
                    }.onFailure { errors++ }
                }
            }
        }
        return Result(found, errors, names)
    }
}
