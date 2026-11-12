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
    data class Saved(val entries: Int, val photos: Int, val snapshot: String?, val empty: Boolean = false, val sounds: Int = 0, val aside: String? = null, val files: Int = 0)

    data class Kept(val name: String, val at: Long)

    data class Restored(val entries: Int, val photos: Int, val sounds: Int = 0, val files: Int = 0)

    /** Where the backup goes, to show ("Documents/Novelo backup.zip"). */
    fun place(root: Shelf): String = root.name + "/" + FILE

    /** Everything to [root]. [key] tells folders apart (what was written to one says nothing about another). */
    suspend fun save(root: Shelf, key: String): Saved = lock.withLock {
        withContext(Dispatchers.IO) {
            val snap = snapshot()
            if (snap.entries.isEmpty() && snap.captures.isEmpty()) return@withContext Saved(0, 0, null, empty = true)
            val state = readState(key)
            val same = sha(json.encodeToString(Snapshot.serializer(), snap.copy(madeAt = 0)).toByteArray(Charsets.UTF_8))
            val counted = Saved(snap.entries.size, snap.photoCount, null, sounds = snap.soundCount, files = snap.files.size)
            val there = FILE in root.files()
            if (same == state.last && there) return@withContext counted
            // Made whole on the phone first, then it goes over the one in the folder.
            val work = work()
            var aside: String? = null
            try {
                // A backup already in the folder that this app didn't write (another phone, the app
                // before it was reinstalled): never written over — it stays beside, with its date.
                if (there && state.last.isEmpty()) aside = keepAside(root, work)
                val zip = File(work, "backup.zip")
                writeZip(zip, snap)
                root.copyIn(FILE, zip)
            } finally {
                work.deleteRecursively()
            }
            writeState(State(key, last = same))
            counted.copy(snapshot = FILE, aside = aside)
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
                snap.sounds.mapNotNull { (id, rel) -> byId[id]?.audioPath?.let { it to rel } } +
                snap.files.mapNotNull { (id, rel) -> byId[id]?.filePath?.let { it to rel } }
            for ((path, rel) in files) {
                val local = File(path).takeIf { it.isFile } ?: continue
                z.putNextEntry(ZipEntry(rel))
                local.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
    }

    /** Nothing in the app yet (a first start): a backup found can come back without replacing anything. */
    suspend fun empty(): Boolean = withContext(Dispatchers.IO) { db.backup().count() == 0 }

    /** The backups in [root], the newest first: the file, and the dated ones of the old folder while it is there. */
    suspend fun kept(root: Shelf): List<Kept> = withContext(Dispatchers.IO) {
        val out = ArrayList<Kept>()
        val files = root.files()
        if (FILE in files) out += Kept(FILE, runCatching { readZip(root).madeAt }.getOrDefault(0L))
        files.keys.filter { it.startsWith(ASIDE) && it.endsWith(".zip") }.forEach { n -> out += Kept(n, runCatching { readZip(root, n).madeAt }.getOrDefault(0L)) }
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
    suspend fun restore(root: Shelf, name: String, key: String? = null): Restored = lock.withLock {
        withContext(Dispatchers.IO) {
            val work = work()
            try {
                // Where its photos and sounds are: the file opened on the phone, or the old folder.
                val (snap, home) = if (name == FILE || name.startsWith(ASIDE)) {
                    unzip(root, work, name)
                    decode(File(work, INSIDE).readBytes()) to FileShelf(work)
                } else {
                    read(root, name) to (oldHome(root) ?: throw IOException(NOT_FOUND))
                }
                val r = putBack(snap, home)
                // The folder's file is now what the app has: the next backup doesn't write it again
                // (nor keeps it aside as someone else's).
                if (name == FILE && key != null) {
                    writeState(State(key, last = sha(json.encodeToString(Snapshot.serializer(), snapshot().copy(madeAt = 0)).toByteArray(Charsets.UTF_8))))
                }
                r
            } finally {
                work.deleteRecursively()
            }
        }
    }

    /** A name beside the folder's file, with the moment: "Novelo backup antes de 2026-11-12 18.30.05.zip". */
    private fun asideName(): String =
        ASIDE + java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(clock()), zone()).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss")) + ".zip"

    /**
     * What the app has now, as a backup beside the folder's file (before the folder's backup comes
     * back in its place): its name, or null when the app has nothing.
     */
    suspend fun saveAside(root: Shelf): String? = lock.withLock {
        withContext(Dispatchers.IO) {
            val snap = snapshot()
            if (snap.entries.isEmpty() && snap.captures.isEmpty()) return@withContext null
            val work = work()
            try {
                val zip = File(work, "aside.zip")
                writeZip(zip, snap)
                val name = asideName()
                root.copyIn(name, zip)
                name
            } finally {
                work.deleteRecursively()
            }
        }
    }

    /** The folder's file copied beside it ("Novelo backup antes de 2026-11-12 18.30.05.zip"); its name. */
    private fun keepAside(root: Shelf, work: File): String {
        val name = asideName()
        val copy = File(work, "aside.zip")
        (root.open(FILE) ?: throw IOException(NOT_FOUND)).use { i -> copy.outputStream().use { i.copyTo(it) } }
        root.copyIn(name, copy)
        copy.delete()
        return name
    }

    private suspend fun putBack(snap: Snapshot, home: Shelf): Restored {
        // The photos into the app first: if one fails, nothing in the app has changed.
        val made = ArrayList<File>()
        val restored = HashMap<Long, String>()
        val sounds = HashMap<Long, String>()
        val files = HashMap<Long, String>()
        val names = snap.captures.associate { it.id to it.fileName }
        fun bring(id: Long, rel: String, kind: String, into: HashMap<Long, String>) {
            val (folder, n) = home.at(rel, create = false) ?: return
            val input = folder.open(n) ?: return
            // A file comes back under its own name, in a folder of its own (as when it was attached).
            val f = if (kind == "file") File(File(dir, "files/$id-${clock()}").apply { mkdirs() }, names[id]?.replace('/', '-')?.ifBlank { null } ?: n)
            else File(dir.apply { mkdirs() }, "$kind-$id-${clock()}." + n.substringAfterLast('.', if (kind == "photo") "jpg" else "wav"))
            input.use { i -> f.outputStream().use { i.copyTo(it) } }
            made += f
            into[id] = f.path
        }
        try {
            for ((id, rel) in snap.photos) bring(id, rel, "photo", restored)
            for ((id, rel) in snap.sounds) bring(id, rel, "voice", sounds)
            for ((id, rel) in snap.files) bring(id, rel, "file", files)
        } catch (e: Throwable) {
            made.forEach(File::delete)
            throw e
        }
        val captures = snap.captures.mapNotNull { c ->
            val image = restored[c.id]
            when {
                // A file comes back with its copy; one whose copy isn't in the backup has nothing left to open.
                c.source == Capture.FILE -> files[c.id]?.let { p -> c.copy(filePath = p, tries = 0) }
                // A photo whose file isn't in the backup, still to be read: nothing to read.
                c.source == "photo" && image == null && c.state != Capture.DONE -> null
                // Something said, never heard: back with its sound, to be heard (an older backup didn't have it).
                c.source != "photo" && c.transcript == null -> sounds[c.id]?.let { a -> c.copy(audioPath = a, state = Capture.RECORDED, tries = 0, error = null) }
                // A sound kept comes back with it; any other recording is long gone.
                else -> c.copy(audioPath = sounds[c.id], sound = sounds[c.id] != null, imagePath = image, tries = 0)
            }
        }
        val b = db.backup()
        val before = b.entries().map { it.id }
        val oldFiles = b.captures().flatMap { listOfNotNull(it.imagePath, it.audioPath, it.filePath) }.toSet()
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
        val now = captures.flatMap { listOfNotNull(it.imagePath, it.audioPath, it.filePath) }.toSet()
        (oldFiles - now).forEach { File(it).delete() }
        // A file's own folder goes with it.
        File(dir, "files").listFiles()?.forEach { if (it.isDirectory && it.list().isNullOrEmpty()) it.delete() }
        runCatching { reminders.sync((before + entryIds).distinct()) }
        return Restored(snap.entries.size, restored.size, sounds.size, files.size)
    }

    /** The folder the backup was before it became one file (Novelo/ or Fio/ with Backups/, or the chosen one itself). */
    private fun oldHome(root: Shelf): Shelf? =
        if (Readable.BACKUPS in root.folders()) root
        else listOf(HOME, OLD_HOME).firstNotNullOfOrNull { n -> root.folder(n, create = false)?.takeIf { Readable.BACKUPS in it.folders() } }

    /** backup.json, straight from the file in the folder. */
    private fun readZip(root: Shelf, name: String = FILE): Snapshot {
        val input = root.open(name) ?: throw IOException(NOT_FOUND)
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
    private fun unzip(root: Shelf, into: File, name: String = FILE) {
        val input = root.open(name) ?: throw IOException(NOT_FOUND)
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
            // Something said and not yet heard goes too, with its sound (heard after an import):
            // only one whose sound is already gone stays out.
            fun hasAudio(c: Capture) = c.audioPath?.let { p -> File(p).isFile } == true
            fun hasFile(c: Capture) = c.filePath?.let { p -> File(p).isFile } == true
            val captures = b.captures().filterNot {
                if (it.source == Capture.FILE) !hasFile(it) else it.source != "photo" && it.transcript == null && !hasAudio(it)
            }
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
                sounds = captures.filter { (it.sound || (it.source != "photo" && it.transcript == null)) && hasAudio(it) }
                    .associate { it.id to Readable.soundPath(it, zone()) },
                files = captures.filter { it.source == Capture.FILE && hasFile(it) }.associate { it.id to Readable.filePath(it, zone()) },
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

        /** A backup that was in the folder before this app wrote there, kept beside it with its date. */
        const val ASIDE = "Novelo backup antes de "

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
