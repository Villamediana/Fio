package com.villamediana.novelo.backup

import com.villamediana.novelo.R
import com.villamediana.novelo.settings.t
import androidx.room.withTransaction
import com.villamediana.novelo.data.Capture
import com.villamediana.novelo.data.NoveloDatabase
import com.villamediana.novelo.mind.ReminderSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.ZoneId
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The backup: one file, "Novelo backup.zip", in the folder she chose, replaced at each backup.
 * Everything is in it: backup.json (what an import puts back as it was, ids kept), the photos, the
 * sounds kept, and the diary, notes and tasks as Markdown to read (see [Readable]). It is written
 * only when something changed. An app with nothing in it writes nothing — a fresh install never
 * covers a good backup.
 *
 * Up to 1.17.0 the backup was a tree of folders (Novelo/ or Fio/, with Backups/): its dated
 * snapshots are still listed and can still be imported while that folder is there; nothing is
 * written to it anymore.
 */
class Backup(
    private val db: NoveloDatabase,
    /** Where the app keeps the photos and the sounds kept (filesDir/captures). */
    private val dir: File,
    /** What the last run wrote, so an unchanged file isn't written again. */
    private val stateFile: File,
    private val reminders: ReminderSync,
    private val version: String,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()

    /** What went: how many items and photos, the file written (null: nothing changed); [empty]: nothing in the app to keep. */
    data class Saved(val entries: Int, val photos: Int, val snapshot: String?, val empty: Boolean = false, val sounds: Int = 0)

    data class Kept(val name: String, val at: Long)

    data class Restored(val entries: Int, val photos: Int, val sounds: Int = 0)

    /** Where the backup goes, to show ("Documents/Novelo backup.zip"). */
    fun place(root: Shelf): String = root.name + "/" + FILE

    /** Everything to [root]. [key] tells folders apart (what was written to one says nothing about another). */
    suspend fun save(root: Shelf, key: String): Saved = lock.withLock {
        withContext(Dispatchers.IO) {
            val snap = snapshot()
            if (snap.entries.isEmpty() && snap.captures.isEmpty()) return@withContext Saved(0, 0, null, empty = true)
            val state = readState(key)
            val same = sha(json.encodeToString(Snapshot.serializer(), snap.copy(madeAt = 0)).toByteArray(Charsets.UTF_8))
            val counted = Saved(snap.entries.size, snap.photoCount, null, sounds = snap.soundCount)
            if (same == state.last && FILE in root.files()) return@withContext counted
            // Made whole on the phone first, then it goes over the one in the folder.
            val work = work()
            try {
                val zip = File(work, "backup.zip")
                writeZip(zip, snap)
                root.copyIn(FILE, zip)
            } finally {
                work.deleteRecursively()
            }
            writeState(State(key, last = same))
            counted.copy(snapshot = FILE)
        }
    }

    private fun writeZip(to: File, snap: Snapshot) {
        val byId = snap.captures.associateBy { it.id }
        ZipOutputStream(BufferedOutputStream(to.outputStream())).use { z ->
            // Photos and sounds hardly shrink: the quickest packing is enough.
            z.setLevel(Deflater.BEST_SPEED)
            fun put(name: String, bytes: ByteArray) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
            put(INSIDE, json.encodeToString(Snapshot.serializer(), snap).toByteArray(Charsets.UTF_8))
            Readable.files(snap, zone()).toSortedMap().forEach { (path, text) -> put(path, text.toByteArray(Charsets.UTF_8)) }
            val files = snap.photos.mapNotNull { (id, rel) -> byId[id]?.imagePath?.let { it to rel } } +
                snap.sounds.mapNotNull { (id, rel) -> byId[id]?.audioPath?.let { it to rel } }
            for ((path, rel) in files) {
                val local = File(path).takeIf { it.isFile } ?: continue
                z.putNextEntry(ZipEntry(rel))
                local.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
    }

    /** The backups in [root], the newest first: the file, and the dated ones of the old folder while it is there. */
    suspend fun kept(root: Shelf): List<Kept> = withContext(Dispatchers.IO) {
        val out = ArrayList<Kept>()
        if (FILE in root.files()) out += Kept(FILE, runCatching { readZip(root).madeAt }.getOrDefault(0L))
        oldHome(root)?.folder(Readable.BACKUPS, create = false)?.let { b ->
            b.files().keys.mapNotNullTo(out) { n -> Readable.snapshotTime(n, zone())?.let { Kept(n, it) } }
        }
        out.sortedByDescending { it.at }
    }

    /** A backup as it is in the folder. */
    suspend fun read(root: Shelf, name: String): Snapshot = withContext(Dispatchers.IO) {
        if (name == FILE) return@withContext readZip(root)
        val backups = oldHome(root)?.folder(Readable.BACKUPS, create = false) ?: throw IOException(NOT_FOUND)
        decode((backups.open(name) ?: throw IOException(NOT_FOUND)).use { it.readBytes() })
    }

    /** Puts the backup [name] back in place of what is in the app now. */
    suspend fun restore(root: Shelf, name: String): Restored = lock.withLock {
        withContext(Dispatchers.IO) {
            val work = work()
            try {
                // Where its photos and sounds are: the file opened on the phone, or the old folder.
                val (snap, home) = if (name == FILE) {
                    unzip(root, work)
                    decode(File(work, INSIDE).readBytes()) to FileShelf(work)
                } else {
                    read(root, name) to (oldHome(root) ?: throw IOException(NOT_FOUND))
                }
                putBack(snap, home)
            } finally {
                work.deleteRecursively()
            }
        }
    }

    private suspend fun putBack(snap: Snapshot, home: Shelf): Restored {
        // The photos into the app first: if one fails, nothing in the app has changed.
        val made = ArrayList<File>()
        val restored = HashMap<Long, String>()
        val sounds = HashMap<Long, String>()
        fun bring(id: Long, rel: String, kind: String, into: HashMap<Long, String>) {
            val (folder, n) = home.at(rel, create = false) ?: return
            val input = folder.open(n) ?: return
            val f = File(dir.apply { mkdirs() }, "$kind-$id-${clock()}." + n.substringAfterLast('.', if (kind == "photo") "jpg" else "wav"))
            input.use { i -> f.outputStream().use { i.copyTo(it) } }
            made += f
            into[id] = f.path
        }
        try {
            for ((id, rel) in snap.photos) bring(id, rel, "photo", restored)
            for ((id, rel) in snap.sounds) bring(id, rel, "voice", sounds)
        } catch (e: Throwable) {
            made.forEach(File::delete)
            throw e
        }
        val captures = snap.captures.mapNotNull { c ->
            val image = restored[c.id]
            when {
                // A photo whose file isn't in the backup, still to be read: nothing to read.
                c.source == "photo" && image == null && c.state != Capture.DONE -> null
                // Something said, never heard: its sound isn't in the backup.
                c.source != "photo" && c.transcript == null -> null
                // A sound kept comes back with it; any other recording is long gone.
                else -> c.copy(audioPath = sounds[c.id], sound = sounds[c.id] != null, imagePath = image, tries = 0)
            }
        }
        val b = db.backup()
        val before = b.entries().map { it.id }
        val oldFiles = b.captures().flatMap { listOfNotNull(it.imagePath, it.audioPath) }.toSet()
        val entryIds = snap.entries.map { it.id }.toSet()
        val topicIds = snap.topics.map { it.id }.toSet()
        db.withTransaction {
            b.clearTies(); b.clearLinks(); b.clearProgress()
            b.clearEntries(); b.clearTopics(); b.clearCaptures()
            b.putEntries(snap.entries)
            b.putTopics(snap.topics)
            b.putTies(snap.ties.filter { it.entryId in entryIds && it.topicId in topicIds })
            b.putLinks(snap.links.filter { it.a in entryIds && it.b in entryIds })
            b.putProgress(snap.progress.filter { it.entryId in entryIds })
            b.putCaptures(captures)
        }
        // What was replaced leaves the app with its files.
        val now = captures.flatMap { listOfNotNull(it.imagePath, it.audioPath) }.toSet()
        (oldFiles - now).forEach { File(it).delete() }
        runCatching { reminders.sync((before + entryIds).distinct()) }
        return Restored(snap.entries.size, restored.size, sounds.size)
    }

    /** The folder the backup was before it became one file (Novelo/ or Fio/ with Backups/, or the chosen one itself). */
    private fun oldHome(root: Shelf): Shelf? =
        if (Readable.BACKUPS in root.folders()) root
        else listOf(HOME, OLD_HOME).firstNotNullOfOrNull { n -> root.folder(n, create = false)?.takeIf { Readable.BACKUPS in it.folders() } }

    /** backup.json, straight from the file in the folder. */
    private fun readZip(root: Shelf): Snapshot {
        val input = root.open(FILE) ?: throw IOException(NOT_FOUND)
        val bytes = try {
            ZipInputStream(BufferedInputStream(input)).use { z ->
                var found: ByteArray? = null
                while (found == null) {
                    val e = z.nextEntry ?: break
                    if (e.name == INSIDE) found = z.readBytes()
                }
                found
            }
        } catch (e: ZipException) {
            throw IOException(BROKEN, e)
        } ?: throw IOException(BROKEN)
        return decode(bytes)
    }

    /** The file opened into [into] (nothing goes outside it, whatever the names inside say). */
    private fun unzip(root: Shelf, into: File) {
        val input = root.open(FILE) ?: throw IOException(NOT_FOUND)
        val base = into.canonicalFile
        try {
            ZipInputStream(BufferedInputStream(input)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    val f = File(base, e.name).canonicalFile
                    if (!f.path.startsWith(base.path + File.separator)) continue
                    f.parentFile?.mkdirs()
                    f.outputStream().use { z.copyTo(it) }
                }
            }
        } catch (e: ZipException) {
            throw IOException(BROKEN, e)
        }
        if (!File(into, INSIDE).isFile) throw IOException(BROKEN)
    }

    private fun decode(bytes: ByteArray): Snapshot {
        val snap = runCatching { json.decodeFromString(Snapshot.serializer(), bytes.toString(Charsets.UTF_8)) }.getOrElse { throw IOException(BROKEN) }
        if (snap.format > Snapshot.FORMAT) throw IOException(t(R.string.backup_newer))
        return snap
    }

    /** A clean folder on the phone for one save or import. */
    private fun work(): File = File(stateFile.parentFile, "backup-work").apply { deleteRecursively(); mkdirs() }

    private suspend fun snapshot(): Snapshot {
        val b = db.backup()
        return db.withTransaction {
            // Something said and not yet heard is only a sound on the phone: it doesn't travel.
            val captures = b.captures().filterNot { it.source != "photo" && it.transcript == null }
            Snapshot(
                app = version,
                madeAt = clock(),
                entries = b.entries(),
                captures = captures,
                topics = b.topics(),
                ties = b.ties(),
                links = b.links(),
                progress = b.progress(),
                photos = captures.filter { it.source == "photo" && it.imagePath?.let { p -> File(p).isFile } == true }
                    .associate { it.id to Readable.photoPath(it, zone()) },
                sounds = captures.filter { it.sound && it.audioPath?.let { p -> File(p).isFile } == true }
                    .associate { it.id to Readable.soundPath(it, zone()) },
            )
        }
    }

    @Serializable
    private data class State(val key: String = "", val last: String = "")

    private fun readState(key: String): State =
        runCatching { json.decodeFromString(State.serializer(), stateFile.readText()) }.getOrNull()?.takeIf { it.key == key } ?: State(key)

    private fun writeState(s: State) {
        runCatching { stateFile.writeText(json.encodeToString(State.serializer(), s)) }
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        /** The backup: one file in the chosen folder. */
        const val FILE = "Novelo backup.zip"

        /** Inside it, what an import reads. */
        const val INSIDE = "backup.json"

        /** The old backup folder's names (up to 1.17.0). */
        const val HOME = "Novelo"
        const val OLD_HOME = "Fio"
        private val NOT_FOUND: String get() = t(R.string.backup_not_found)
        private val BROKEN: String get() = t(R.string.backup_broken)

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}
