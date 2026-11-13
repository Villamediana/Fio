package com.villamediana.novelo.importer

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.IOException

/**
 * A folder she picked (Storage Access Framework, a tree): its name, and every file in it and in its
 * folders, each with its path from there ("2022-01-23/fotos/01.jpg") — read only when [Unpack] asks.
 */
object Folder {
    /** The folder's own name ("diario-2026-11-13"). */
    fun name(resolver: ContentResolver, tree: Uri): String? = runCatching {
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        resolver.query(root, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    fun pieces(resolver: ContentResolver, tree: Uri): List<Unpack.Piece> {
        val out = ArrayList<Unpack.Piece>()
        walk(resolver, tree, DocumentsContract.getTreeDocumentId(tree), "", out)
        return out
    }

    private fun walk(resolver: ContentResolver, tree: Uri, parent: String, prefix: String, out: MutableList<Unpack.Piece>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED)
        val found = ArrayList<Triple<String, String, Pair<String?, Long?>>>()
        resolver.query(children, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                found += Triple(id, name, c.getString(2) to (if (c.isNull(3)) null else c.getLong(3).takeIf { it > 0 }))
            }
        }
        for ((id, name, info) in found.sortedBy { it.second }) {
            val (mime, time) = info
            if (mime == Document.MIME_TYPE_DIR) {
                walk(resolver, tree, id, "$prefix$name/", out)
            } else {
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                out += Unpack.Piece("$prefix$name", time) { resolver.openInputStream(uri) ?: throw IOException(name) }
            }
        }
    }
}
