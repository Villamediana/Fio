package com.villamediana.novelo.importer

import com.villamediana.novelo.R
import com.villamediana.novelo.settings.t
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.time.ZoneId

/**
 * What an import left: days new in the diary, days that already had one (told some more), photos
 * new, and the span (0 when nothing had a date).
 */
data class Kept(val added: Int, val joined: Int, val from: Long, val to: Long, val photos: Int = 0) {
    val days: Int get() = added + joined
}

/**
 * Ajustes › Backup › Importar diário: a diary exported by another app as a .zip or a folder of one
 * folder a day ([DayFolders]). It is opened and read, she sees what came out (how many entries and
 * photos, a few as they will be), confirms, and the entries go into the diary (one a day: a day that
 * already has one is told some more), the photos into the gallery on their days, and a backup goes
 * right after. It runs on the app's scope: the sheet can close while it goes.
 */
class DiaryImport(
    private val scope: CoroutineScope,
    /** What came out into Novelo: the entries into the diary, the photos. */
    private val keep: suspend (Reading) -> Kept,
    /** The backup right after (false: no folder, or it went wrong). */
    private val backup: suspend () -> Boolean,
    /** What went wrong with the backup, when it did. */
    private val backupProblem: suspend () -> String? = { null },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    /** Where the photos wait until she imports (emptied when the import ends or is let go). */
    private val stage: () -> java.io.File? = { null },
) {
    sealed interface State {
        data object Idle : State
        data class Opening(val name: String) : State
        /** Read: waiting for her. */
        data class Ready(val name: String, val reading: Reading) : State
        data class Failed(val name: String?, val message: String) : State
        /** [count] entries and [photos] going in (then the backup). */
        data class Saving(val count: Int, val backingUp: Boolean = false, val photos: Int = 0) : State
        /** [backup]: true done, false not (see [problem]), null: no folder. */
        data class Done(val kept: Kept, val backup: Boolean?, val problem: String? = null) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var job: Job? = null
    private var name: String = ""

    /** A .zip picked: opened and read. [read] gives its bytes (on IO). */
    fun open(name: String, read: () -> InputStream?) =
        start(name) { stage -> Unpack.files(name, read() ?: throw IOException(t(R.string.import_not_zip)), stage) }

    /** A folder picked: every file in it and in its folders ([list], on IO). */
    fun openFolder(name: String, list: () -> List<Unpack.Piece>) =
        start(name) { stage -> Unpack.pieces(list(), stage) }

    private fun start(name: String, unpack: (java.io.File?) -> Unpacked) {
        if (_state.value is State.Saving) return
        job?.cancel()
        this.name = name
        job = scope.launch {
            _state.value = State.Opening(name)
            val reading = try {
                withContext(Dispatchers.IO) {
                    clearStage()
                    val opened = unpack(stage())
                    DayFolders.read(opened.texts, opened.others, zone())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TooBig) {
                _state.value = State.Failed(name, t(R.string.import_too_big))
                return@launch
            } catch (e: Exception) {
                _state.value = State.Failed(name, e.message ?: t(R.string.import_nothing))
                return@launch
            }
            _state.value = if (reading.isEmpty) State.Failed(name, t(R.string.import_nothing)) else State.Ready(name, reading)
        }
    }

    /** She confirmed: the entries and photos go in, then the backup. */
    fun confirm() {
        val ready = _state.value as? State.Ready ?: return
        if (ready.reading.isEmpty) return
        job?.cancel()
        job = scope.launch {
            val going = State.Saving(ready.reading.found.size, photos = ready.reading.photos.size)
            _state.value = going
            val kept = try {
                withContext(Dispatchers.IO) { keep(ready.reading) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = State.Failed(name, e.message ?: e.javaClass.simpleName)
                return@launch
            }
            withContext(Dispatchers.IO) { clearStage() }
            _state.value = going.copy(backingUp = true)
            val went = runCatching { backup() }.getOrDefault(false)
            val problem = if (went) null else backupProblem()
            _state.value = State.Done(kept, if (went) true else if (problem == null) null else false, problem)
        }
    }

    /** Back to nothing (the sheet closed on a result, or she gave up). An import being saved goes on. */
    fun close() {
        if (_state.value is State.Saving) return
        job?.cancel()
        _state.value = State.Idle
        scope.launch(Dispatchers.IO) { clearStage() }
    }

    /** The photos set aside by an import that ended or was let go. */
    private fun clearStage() {
        stage()?.listFiles()?.forEach { it.deleteRecursively() }
    }
}

/** A file over [Unpack.MAX_BYTES]. */
class TooBig : IOException()

/** What she picked, opened: the text files, and everything else — photos set aside. */
data class Unpacked(val texts: List<TextFile>, val others: List<OtherFile> = emptyList()) {
    val photos: Int get() = others.count { it.isPhoto }
}

/**
 * What she picked → the files in it. A .zip is copied aside and read by its index (the list at its
 * end: a .zip written as it streams, without the sizes before each file, reads as well), a .zip
 * inside it too; a folder, file by file with its folders. The text files are read (up to
 * [MAX_BYTES] of text in all), the photos copied aside to the stage (named by their content, so the
 * same photo is one file) with the date the camera wrote inside, anything else listed only by name
 * and size.
 */
object Unpack {
    const val MAX_BYTES = 40L * 1024 * 1024
    private const val PEEK = 8192
    val PHOTOS = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif")

    /** A file in what she picked: its path inside (with its folders), its own date when known, and how to read it. */
    class Piece(val path: String, val time: Long? = null, val open: () -> InputStream)

    /** What was read so far: the texts, the rest, and how much text. */
    private class Taken {
        val texts = ArrayList<TextFile>()
        val others = ArrayList<OtherFile>()
        var text = 0L
        fun done() = Unpacked(texts.sortedBy { it.name }, others.sortedBy { it.name })
    }

    /** One file picked: a .zip (by its first bytes) or a text. */
    fun files(name: String, input: InputStream, stage: java.io.File? = null): Unpacked {
        val buffered = java.io.BufferedInputStream(input, 64 * 1024)
        return buffered.use { inp ->
            inp.mark(4)
            val magic = ByteArray(4)
            val n = inp.readNBytesCompat(magic)
            inp.reset()
            if (n == 4 && isZip(magic)) {
                val taken = Taken()
                zip(inp, "", stage, taken)
                taken.done()
            } else {
                Unpacked(listOfNotNull(decode(capped(inp))?.let { TextFile(name, it) }))
            }
        }
    }

    /** A folder picked: each of its files ([pieces], with their paths). */
    fun pieces(pieces: List<Piece>, stage: java.io.File? = null): Unpacked {
        val taken = Taken()
        pieces.forEach { take(it, stage, taken) }
        return taken.done()
    }

    private fun isZip(b: ByteArray) = b.size >= 4 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte() && b[2] == 0x03.toByte() && b[3] == 0x04.toByte()

    /** A .zip ([input]): copied aside, then each file by its index; [prefix]: the path of the .zip it was inside. */
    private fun zip(input: InputStream, prefix: String, stage: java.io.File?, taken: Taken) {
        stage?.mkdirs()
        val copy = java.io.File.createTempFile("import", ".zip", stage)
        try {
            copy.outputStream().use { input.copyTo(it, 64 * 1024) }
            java.util.zip.ZipFile(copy).use { z ->
                for (e in z.entries().asSequence().filter { !it.isDirectory }.sortedBy { it.name }) {
                    take(Piece(prefix + e.name, e.time.takeIf { it > 0 }) { z.getInputStream(e) }, stage, taken)
                }
            }
        } finally {
            copy.delete()
        }
    }

    private fun take(p: Piece, stage: java.io.File?, taken: Taken) {
        val path = p.path.trimStart('/')
        val base = path.substringAfterLast('/')
        if (path.startsWith("__MACOSX") || "/__MACOSX/" in path || base.startsWith(".")) return
        val ext = base.substringAfterLast('.', "").lowercase()
        p.open().use { input ->
            when {
                ext in PHOTOS && stage != null -> taken.others += photo(input, path, stage)
                ext == "zip" -> zip(input, "$path/", stage, taken)
                else -> {
                    val head = ByteArray(PEEK)
                    val got = input.readNBytesCompat(head)
                    val start = head.copyOf(got)
                    if (looksBinary(start)) {
                        taken.others += OtherFile(path, got + drain(input))
                        return
                    }
                    val data = java.io.ByteArrayOutputStream().apply { write(start) }
                    copyCapped(input, data, MAX_BYTES - taken.text - got)
                    taken.text += data.size()
                    if (taken.text > MAX_BYTES) throw TooBig()
                    val bytes = data.toByteArray()
                    decode(bytes)?.let { taken.texts += TextFile(path, it, p.time) } ?: run { taken.others += OtherFile(path, bytes.size.toLong()) }
                }
            }
        }
    }

    /** A photo copied aside, named by what's in it; the date the camera wrote inside, when it did. */
    private fun photo(input: InputStream, path: String, stage: java.io.File): OtherFile {
        stage.mkdirs()
        val tmp = java.io.File.createTempFile("photo", ".part", stage)
        val digest = java.security.MessageDigest.getInstance("SHA-1")
        var size = 0L
        tmp.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (input.read(buf).also { n = it } > 0) {
                digest.update(buf, 0, n)
                out.write(buf, 0, n)
                size += n
            }
        }
        val ext = path.substringAfterLast('.').lowercase()
        val file = java.io.File(stage, digest.digest().joinToString("") { "%02x".format(it) } + "." + ext)
        if (file.exists()) tmp.delete() else if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
        val exif = runCatching { androidx.exifinterface.media.ExifInterface(file) }.getOrNull()
        val taken = exif?.let { x ->
            listOf(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL, androidx.exifinterface.media.ExifInterface.TAG_DATETIME_DIGITIZED)
                .firstNotNullOfOrNull { x.getAttribute(it)?.trim()?.takeIf { v -> v.isNotEmpty() && !v.startsWith("0000") } }
        }
        val offset = exif?.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
        return OtherFile(path, size, file, taken, offset)
    }

    /** NUL bytes at the start, and no UTF-16 mark: not text. */
    private fun looksBinary(b: ByteArray): Boolean {
        if (b.size >= 2 && ((b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) || (b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()))) return false
        return b.any { it == 0.toByte() }
    }

    private fun InputStream.readNBytesCompat(into: ByteArray): Int {
        var n = 0
        while (n < into.size) {
            val r = read(into, n, into.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    private fun drain(input: InputStream): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        var n: Int
        while (input.read(buf).also { n = it } > 0) total += n
        return total
    }

    private fun copyCapped(input: InputStream, out: java.io.ByteArrayOutputStream, room: Long) {
        val buf = ByteArray(64 * 1024)
        var n: Int
        var left = room
        while (input.read(buf).also { n = it } > 0) {
            out.write(buf, 0, n)
            left -= n
            if (left < 0) throw TooBig()
        }
    }

    private fun capped(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        copyCapped(input, out, MAX_BYTES)
        return out.toByteArray()
    }

    /** Bytes → text, when they are text: UTF-8 (or UTF-16 with its mark; old Windows text as cp1252). Null: not text. */
    fun decode(b: ByteArray): String? {
        if (b.isEmpty()) return null
        val text = when {
            b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> String(b, 3, b.size - 3, Charsets.UTF_8)
            b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16LE)
            b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16BE)
            else -> {
                val head = b.copyOf(minOf(b.size, 8192))
                if (head.any { it == 0.toByte() }) return null
                val utf8 = String(b, Charsets.UTF_8)
                if (utf8.contains('�')) String(b, Charset.forName("windows-1252")) else utf8
            }
        }
        // Control characters other than tabs and line breaks: a binary file that happened to decode.
        val odd = text.take(4096).count { it < ' ' && it != '\n' && it != '\r' && it != '\t' }
        return if (odd > 8) null else text
    }
}
