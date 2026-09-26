package com.aaron.mgbaandroid

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/** Battery-save storage with an optional user-selected SAF folder. */
object SaveStorage {
    const val PREF_TREE_URI = "save_tree_uri"

    private fun prefs(context: Context) = context.getSharedPreferences("emulator", Context.MODE_PRIVATE)
    private fun internalFile(context: Context, romKey: String) =
        File(context.filesDir, "saves/$romKey.sav")

    fun treeUri(context: Context): Uri? = prefs(context).getString(PREF_TREE_URI, null)?.let(Uri::parse)

    fun locationLabel(context: Context): String = treeUri(context)?.toString() ?: "App-private storage"

    fun setTreeUri(context: Context, uri: Uri) {
        prefs(context).edit().putString(PREF_TREE_URI, uri.toString()).apply()
    }

    fun useAppPrivateStorage(context: Context) {
        prefs(context).edit().remove(PREF_TREE_URI).apply()
    }

    fun read(context: Context, romKey: String): ByteArray? {
        val external = treeUri(context)?.let { findFile(context, it, "$romKey.sav") }
        return external?.let { context.contentResolver.openInputStream(it)?.use { stream -> stream.readBytes() } }
            ?: internalFile(context, romKey).takeIf(File::exists)?.readBytes()
    }

    fun write(context: Context, romKey: String, bytes: ByteArray) {
        val file = internalFile(context, romKey)
        val target = treeUri(context)?.let { findFile(context, it, file.name) }
        if (treeUri(context) != null) {
            val uri = target ?: createFile(context, treeUri(context)!!, file.name)
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: error("Could not open the selected save folder")
        } else {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
    }

    fun migrateInternalSaves(context: Context) {
        if (treeUri(context) == null) return
        File(context.filesDir, "saves").listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("sav", ignoreCase = true) }
            .forEach { file ->
                runCatching { write(context, file.nameWithoutExtension, file.readBytes()) }
            }
    }

    private fun findFile(context: Context, tree: Uri, name: String): Uri? {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        resolver.query(children, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                }
            }
        }
        return null
    }

    private fun createFile(context: Context, tree: Uri, name: String): Uri {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return DocumentsContract.createDocument(context.contentResolver, parent, "application/octet-stream", name)
            ?: error("Could not create a save file in the selected folder")
    }
}
