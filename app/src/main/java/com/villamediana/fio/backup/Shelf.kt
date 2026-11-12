package com.villamediana.fio.backup

import com.villamediana.fio.R
import com.villamediana.fio.settings.t
import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * A folder the backup writes to: the one she chose (a tree from the system picker, [TreeShelf])
 * or a plain directory ([FileShelf], the tests). What is in it is listed once and kept up to date
 * as the backup writes, so a run with hundreds of files asks the provider only once per folder.
 */
interface Shelf {
    val name: String

    /** The files in it: name → size in bytes. */
    fun files(): Map<String, Long>

    fun folders(): Set<String>

    /** A folder inside, made when [create] and it isn't there; null when it isn't and can't be. */
    fun folder(name: String, create: Boolean): Shelf?

    /** Writes [name] whole (made, or replaced when it is there). */
    fun write(name: String, bytes: ByteArray)

    /** Copies [from] in as [name]. */
    fun copyIn(name: String, from: File)

    fun open(name: String): InputStream?

    fun delete(name: String)
}

/** A path ("Fotos/2026/a.jpg") from [this]: the folder it is in (made when [create]) and the file's name. */
fun Shelf.at(path: String, create: Boolean): Pair<Shelf, String>? {
    val parts = path.split('/').filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    var dir: Shelf = this
    for (p in parts.dropLast(1)) dir = dir.folder(p, create) ?: return null
    return dir to parts.last()
}

/** A folder on disk. */
class FileShelf(private val dir: File) : Shelf {
    override val name: String get() = dir.name

    override fun files(): Map<String, Long> = dir.listFiles()?.filter { it.isFile }?.associate { it.name to it.length() }.orEmpty()

    override fun folders(): Set<String> = dir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.toSet().orEmpty()

    override fun folder(name: String, create: Boolean): Shelf? {
        val f = File(dir, name)
        if (!f.isDirectory && (!create || !f.mkdirs())) return null
        return FileShelf(f)
    }

    override fun write(name: String, bytes: ByteArray) {
        File(dir, name).writeBytes(bytes)
    }

    override fun copyIn(name: String, from: File) {
        from.copyTo(File(dir, name), overwrite = true)
    }

    override fun open(name: String): InputStream? = File(dir, name).takeIf { it.isFile }?.inputStream()

    override fun delete(name: String) {
        File(dir, name).let { if (it.isDirectory) it.deleteRecursively() else it.delete() }
    }
}

/**
 * A folder chosen in the system picker (Storage Access Framework), straight on DocumentsContract:
 * one query lists a folder (a DocumentFile would ask again for every name).
 */
class TreeShelf private constructor(
    private val resolver: ContentResolver,
    private val tree: Uri,
    private val docId: String,
    override val name: String,
) : Shelf {
    private class Child(val id: String, val dir: Boolean, var size: Long)

    private var children: MutableMap<String, Child>? = null

    private fun kids(): MutableMap<String, Child> = children ?: load().also { children = it }

    private fun load(): MutableMap<String, Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val out = HashMap<String, Child>()
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val cursor = try {
            resolver.query(uri, cols, null, null, null)
        } catch (e: SecurityException) {
            throw IOException(LOST, e)
        } catch (e: IllegalArgumentException) {
            throw IOException(LOST, e)
        } ?: throw IOException(LOST)
        cursor.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(1) ?: continue
                out[n] = Child(c.getString(0), c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR, if (c.isNull(3)) 0L else c.getLong(3))
            }
        }
        return out
    }

    private fun uri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    override fun files(): Map<String, Long> = kids().filterValues { !it.dir }.mapValues { it.value.size }

    override fun folders(): Set<String> = kids().filterValues { it.dir }.keys

    override fun folder(name: String, create: Boolean): Shelf? {
        val k = kids()
        k[name]?.let { c -> return if (c.dir) TreeShelf(resolver, tree, c.id, name) else null }
        if (!create) return null
        val made = DocumentsContract.createDocument(resolver, uri(docId), DocumentsContract.Document.MIME_TYPE_DIR, name) ?: throw IOException(t(R.string.shelf_cant_make_folder, name))
        val id = DocumentsContract.getDocumentId(made)
        k[name] = Child(id, true, 0)
        return TreeShelf(resolver, tree, id, name)
    }

    /** The document for [name], made when it isn't there (as octet-stream, so no provider adds an extension of its own). */
    private fun doc(name: String): Pair<Uri, Child> {
        val k = kids()
        k[name]?.let { c -> if (!c.dir) return uri(c.id) to c }
        val made = DocumentsContract.createDocument(resolver, uri(docId), "application/octet-stream", name) ?: throw IOException(t(R.string.shelf_cant_make, name))
        val c = Child(DocumentsContract.getDocumentId(made), false, 0)
        k[name] = c
        return made to c
    }

    override fun write(name: String, bytes: ByteArray) {
        val (u, c) = doc(name)
        // "wt": write over and cut what was longer (plain "w" doesn't cut on some Android versions).
        (resolver.openOutputStream(u, "wt") ?: throw IOException(t(R.string.shelf_cant_write, name))).use { it.write(bytes) }
        c.size = bytes.size.toLong()
    }

    override fun copyIn(name: String, from: File) {
        val (u, c) = doc(name)
        (resolver.openOutputStream(u, "wt") ?: throw IOException(t(R.string.shelf_cant_write, name))).use { out -> from.inputStream().use { it.copyTo(out) } }
        c.size = from.length()
    }

    override fun open(name: String): InputStream? {
        val c = kids()[name]?.takeIf { !it.dir } ?: return null
        return try {
            resolver.openInputStream(uri(c.id))
        } catch (_: FileNotFoundException) {
            null
        }
    }

    override fun delete(name: String) {
        val c = kids().remove(name) ?: return
        runCatching { DocumentsContract.deleteDocument(resolver, uri(c.id)) }
    }

    companion object {
        val LOST: String get() = t(R.string.shelf_lost)

        /** The folder she chose, from the tree uri the picker gave. */
        fun root(resolver: ContentResolver, tree: Uri): TreeShelf {
            val id = DocumentsContract.getTreeDocumentId(tree)
            val name = runCatching {
                resolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, id), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: id.substringAfterLast(':').substringAfterLast('/').ifBlank { "pasta" }
            return TreeShelf(resolver, tree, id, name)
        }
    }
}
