package com.aaron.mgbaandroid

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/** Battery-save storage with an optional user-selected SAF folder. */
object SaveStorage {
    const val PREF_TREE_URI = "save_tree_uri"
    val DATA_FOLDERS = listOf("saves", "saves/gba", "saves/gb", "saves/gbc", "states", "system", "shaders", "cheats", "cheats/gba", "cheats/gb", "cheats/gbc")

    private fun prefs(context: Context) = context.getSharedPreferences("emulator", Context.MODE_PRIVATE)
    private fun internalFile(context: Context, romKey: String) =
        File(context.filesDir, "saves/$romKey.sav")

    fun treeUri(context: Context): Uri? = prefs(context).getString(PREF_TREE_URI, null)?.let(Uri::parse)

    fun locationLabel(context: Context): String = treeUri(context)?.toString() ?: "App-private storage"

    fun setTreeUri(context: Context, uri: Uri) {
        prefs(context).edit().putString(PREF_TREE_URI, uri.toString()).apply()
    }

    fun ensureDataFolders(context: Context) {
        val tree = treeUri(context) ?: error("No save folder selected")
        DATA_FOLDERS.forEach { name -> ensureDirectory(context, tree, name) }
    }

    fun useAppPrivateStorage(context: Context) {
        prefs(context).edit().remove(PREF_TREE_URI).apply()
    }

    fun read(context: Context, romKey: String, system: String = "gba"): ByteArray? {
        val external = treeUri(context)?.let { tree ->
            val folder = findFile(context, tree, "saves")?.let { findFile(context, tree, it, system) }
            folder?.let { findFile(context, tree, it, "$romKey.sav") }
                ?: findFile(context, tree, "$romKey.sav")
        }
        return external?.let { context.contentResolver.openInputStream(it)?.use { stream -> stream.readBytes() } }
            ?: internalFile(context, romKey).takeIf(File::exists)?.readBytes()
    }

    fun write(context: Context, romKey: String, bytes: ByteArray, system: String = "gba") {
        val file = internalFile(context, romKey)
        val target = treeUri(context)?.let { tree ->
            findFile(context, tree, "saves")?.let { findFile(context, tree, it, system) }
                ?.let { findFile(context, tree, it, file.name) }
        }
        if (treeUri(context) != null) {
            val tree = treeUri(context)!!
            val saves = findFile(context, tree, "saves") ?: ensureDirectory(context, tree, "saves")
            val folder = findFile(context, tree, saves, system) ?: ensureDirectory(context, tree, saves, system)
            val uri = target ?: createFile(context, tree, folder, file.name)
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
                // Never replace a save that already exists in the user-selected folder.
                // The user can import/replace it explicitly from the in-game menu.
                runCatching {
                    if (treeUri(context)?.let { findFile(context, it, file.name) } == null) {
                        write(context, file.nameWithoutExtension, file.readBytes(), "gba")
                    }
                }
            }
    }

    private fun findFile(context: Context, tree: Uri, name: String): Uri? {
        return findFile(context, tree, DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)), name)
    }

    private fun findFile(context: Context, tree: Uri, parent: Uri, name: String): Uri? {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
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

    private fun createFile(context: Context, tree: Uri, parent: Uri, name: String): Uri {
        return DocumentsContract.createDocument(context.contentResolver, parent, "application/octet-stream", name)
            ?: error("Could not create a save file in the selected folder")
    }

    private fun ensureDirectory(context: Context, tree: Uri, name: String): Uri {
        findFile(context, tree, name)?.let { return it }
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return ensureDirectory(context, tree, parent, name)
    }

    private fun ensureDirectory(context: Context, tree: Uri, parent: Uri, name: String): Uri {
        findFile(context, tree, parent, name)?.let { return it }
        return DocumentsContract.createDocument(
            context.contentResolver,
            parent,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name,
        ) ?: error("Could not create the $name folder")
    }
}
