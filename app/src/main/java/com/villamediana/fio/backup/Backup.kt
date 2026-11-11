package com.villamediana.fio.backup

import androidx.room.withTransaction
import com.villamediana.fio.data.Capture
import com.villamediana.fio.data.FioDatabase
import com.villamediana.fio.mind.ReminderSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.ZoneId

/**
 * The backup: everything in Fio to a folder she chose, laid out to be read (see [Readable]) and,
 * in Backups/, a dated snapshot an import puts back as it was. A snapshot is written only when
 * something changed, and the last [KEEP] stay, so an older day can come back too. The photos go
 * once (the same file is never copied twice) and never leave the folder: older snapshots point to
 * them. An app with nothing in it writes nothing — a fresh install never covers a good backup.
 */
class Backup(
    private val db: FioDatabase,
    /** Where the app keeps the photos (filesDir/captures). */
    private val dir: File,
    /** What the last run wrote, so an unchanged file isn't written again. */
    private val stateFile: File,
    private val reminders: ReminderSync,
    private val version: String,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()

    /** What went: how many items and photos, the snapshot written (null: nothing changed); [empty]: nothing in the app to keep. */
    data class Saved(val entries: Int, val photos: Int, val snapshot: String?, val empty: Boolean = false)

    data class Kept(val name: String, val at: Long)

    data class Restored(val entries: Int, val photos: Int)

    /** The Fio folder inside the one she chose: that one itself when it already is (named Fio, or holding Backups/). */
    fun home(root: Shelf, create: Boolean): Shelf? =
        if (root.name.equals(HOME, ignoreCase = true) || Readable.BACKUPS in root.folders()) root else root.folder(HOME, create)

    /** Where the backup goes, to show ("Documents/Fio"). */
    fun place(root: Shelf): String =
        if (root.name.equals(HOME, ignoreCase = true) || Readable.BACKUPS in root.folders()) root.name else root.name + "/" + HOME

    /** Everything to [root]. [key] tells folders apart (what was written to one says nothing about another). */
    suspend fun save(root: Shelf, key: String): Saved = lock.withLock { saveLocked(root, key) }

    private suspend fun saveLocked(root: Shelf, key: String): Saved = withContext(Dispatchers.IO) {
        val snap = snapshot()
        if (snap.entries.isEmpty() && snap.captures.isEmpty()) return@withContext Saved(0, 0, null, empty = true)
        val home = home(root, create = true) ?: throw IOException("Não consegui criar a pasta do backup.")
        val state = readState(key)
        val byId = snap.captures.associateBy { it.id }

        // The photos first: the snapshot points to them.
        for ((id, rel) in snap.photos) {
            val local = byId[id]?.imagePath?.let(::File)?.takeIf { it.isFile } ?: continue
            val (folder, name) = home.at(rel, create = true) ?: continue
            if (folder.files()[name] != local.length()) folder.copyIn(name, local)
        }
        // The gallery of the phone leaves the backup's photos alone (no copies of her photos in it).
        if (snap.photos.isNotEmpty()) home.folder(Readable.PHOTOS, create = true)?.let { if (NOMEDIA !in it.files()) it.write(NOMEDIA, ByteArray(0)) }

        // The readable side: written when it changed, gone when its item is gone.
        val wanted = Readable.files(snap, zone())
        val hashes = HashMap<String, String>()
        for ((path, text) in wanted) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val h = sha(bytes)
            val (folder, name) = home.at(path, create = true) ?: continue
            if (state.hashes[path] != h || name !in folder.files()) folder.write(name, bytes)
            hashes[path] = h
        }
        for (top in Readable.MIRRORED) home.folder(top, create = false)?.let { sweep(it, top, wanted.keys) }

        // The snapshot, when something changed since the last one.
        val backups = home.folder(Readable.BACKUPS, create = true) ?: throw IOException("Não consegui criar a pasta Backups.")
        val sameAsLast = sha(json.encodeToString(Snapshot.serializer(), snap.copy(madeAt = 0)).toByteArray(Charsets.UTF_8))
        val hasOne = backups.files().keys.any(Readable::isSnapshot)
        var wrote: String? = null
        if (sameAsLast != state.last || !hasOne) {
            val name = Readable.snapshotName(snap.madeAt, zone())
            backups.write(name, json.encodeToString(Snapshot.serializer(), snap).toByteArray(Charsets.UTF_8))
            wrote = name
            backups.files().keys.filter(Readable::isSnapshot).sorted().dropLast(KEEP).forEach(backups::delete)
        }
        writeState(State(key, hashes, sameAsLast))
        Saved(snap.entries.size, snap.photoCount, wrote)
    }

    /** The snapshots in [root], the newest first. */
    suspend fun kept(root: Shelf): List<Kept> = withContext(Dispatchers.IO) {
        val backups = home(root, create = false)?.folder(Readable.BACKUPS, create = false) ?: return@withContext emptyList()
        backups.files().keys.mapNotNull { n -> Readable.snapshotTime(n, zone())?.let { Kept(n, it) } }.sortedByDescending { it.name }
    }

    /** A snapshot as it is in the folder. */
    suspend fun read(root: Shelf, name: String): Snapshot = withContext(Dispatchers.IO) {
        val backups = home(root, create = false)?.folder(Readable.BACKUPS, create = false) ?: throw IOException(NOT_FOUND)
        val text = (backups.open(name) ?: throw IOException(NOT_FOUND)).use { it.readBytes().toString(Charsets.UTF_8) }
        val snap = runCatching { json.decodeFromString(Snapshot.serializer(), text) }.getOrElse { throw IOException("Este backup não abriu (arquivo estragado).") }
        if (snap.format > Snapshot.FORMAT) throw IOException("Este backup é de uma versão mais nova do Fio. Atualize o app antes.")
        snap
    }

    /**
     * Puts the snapshot [name] back in place of what is in the app now — which, when there is
     * anything, first becomes a backup itself in the same folder, so an import can be undone.
     */
    suspend fun restore(root: Shelf, key: String, name: String): Restored = lock.withLock {
        withContext(Dispatchers.IO) {
            val snap = read(root, name)
            val home = home(root, create = false) ?: throw IOException(NOT_FOUND)
            if (db.backup().count() > 0) saveLocked(root, key)

            // The photos into the app first: if one fails, nothing in the app has changed.
            val made = ArrayList<File>()
            val restored = HashMap<Long, String>()
            try {
                for ((id, rel) in snap.photos) {
                    val (folder, n) = home.at(rel, create = false) ?: continue
                    val input = folder.open(n) ?: continue
                    val f = File(dir.apply { mkdirs() }, "photo-$id-${clock()}." + n.substringAfterLast('.', "jpg"))
                    input.use { i -> f.outputStream().use { i.copyTo(it) } }
                    made += f
                    restored[id] = f.path
                }
            } catch (e: Throwable) {
                made.forEach(File::delete)
                throw e
            }
            val captures = snap.captures.mapNotNull { c ->
                val image = restored[c.id]
                when {
                    // A photo whose file isn't in the folder, still to be read: nothing to read.
                    c.source == "photo" && image == null && c.state != Capture.DONE -> null
                    // Something said, never heard: its sound isn't in the backup.
                    c.source != "photo" && c.transcript == null -> null
                    else -> c.copy(audioPath = null, imagePath = image, tries = 0)
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
            // What was replaced is in the backup made above: its files leave the app.
            val now = captures.flatMap { listOfNotNull(it.imagePath) }.toSet()
            (oldFiles - now).forEach { File(it).delete() }
            runCatching { reminders.sync((before + entryIds).distinct()) }
            Restored(snap.entries.size, restored.size)
        }
    }

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
            )
        }
    }

    /** Markdown files under [folder] ([path] from the Fio folder) whose item is gone. */
    private fun sweep(folder: Shelf, path: String, wanted: Set<String>) {
        folder.files().keys.filter { it.endsWith(".md") && "$path/$it" !in wanted }.forEach(folder::delete)
        folder.folders().forEach { sub -> folder.folder(sub, create = false)?.let { sweep(it, "$path/$sub", wanted) } }
    }

    @Serializable
    private data class State(val key: String = "", val hashes: Map<String, String> = emptyMap(), val last: String = "")

    private fun readState(key: String): State =
        runCatching { json.decodeFromString(State.serializer(), stateFile.readText()) }.getOrNull()?.takeIf { it.key == key } ?: State(key)

    private fun writeState(s: State) {
        runCatching { stateFile.writeText(json.encodeToString(State.serializer(), s)) }
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        /** Snapshots kept in Backups/ (a month of days, or seven months of weeks). */
        const val KEEP = 30

        const val HOME = "Fio"
        private const val NOMEDIA = ".nomedia"
        private const val NOT_FOUND = "Não achei backup nesta pasta."

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}
