package com.villamediana.novelo.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface EntryDao {
    @Insert
    suspend fun insert(entry: Entry): Long

    @Update
    suspend fun update(entry: Entry)

    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun get(id: Long): Entry?

    @Query("SELECT * FROM entries WHERE id = :id")
    fun observe(id: Long): Flow<Entry?>

    @Query("SELECT * FROM entries WHERE id IN (:ids)")
    suspend fun getAll(ids: List<Long>): List<Entry>

    @Query("SELECT * FROM entries ORDER BY createdAt DESC")
    suspend fun all(): List<Entry>

    @Query("SELECT * FROM entries ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Entry>>

    @Query("SELECT * FROM entries WHERE kind = 'task' AND (status IS NULL OR status != 'done') ORDER BY CASE WHEN dueAt IS NULL THEN 1 ELSE 0 END, dueAt, createdAt DESC")
    suspend fun openTasks(): List<Entry>

    @Query("SELECT * FROM entries WHERE remindAt IS NOT NULL")
    suspend fun withReminder(): List<Entry>

    @Query("SELECT * FROM entries WHERE checkInAt IS NOT NULL AND kind = 'task' AND (status IS NULL OR status != 'done')")
    suspend fun withCheckIn(): List<Entry>

    @Query("SELECT * FROM entries WHERE captureId = :captureId")
    suspend fun fromCapture(captureId: Long): List<Entry>

    /** The diaries written between [from] and [to] (a day), newest first. */
    @Query("SELECT * FROM entries WHERE kind = 'diary' AND createdAt BETWEEN :from AND :to ORDER BY createdAt DESC, id DESC")
    suspend fun diariesBetween(from: Long, to: Long): List<Entry>

    /** The tasks' categories in use. */
    @Query("SELECT DISTINCT category FROM entries WHERE kind = 'task' AND category IS NOT NULL")
    suspend fun categories(): List<String>

    /** A category renamed (or joined into another, or taken off with null), on every task that has it. */
    @Query("UPDATE entries SET category = :to WHERE kind = 'task' AND category = :from")
    suspend fun renameCategory(from: String, to: String?)

    /** The open tasks with no category yet. */
    @Query("SELECT * FROM entries WHERE kind = 'task' AND category IS NULL AND (status IS NULL OR status != 'done') ORDER BY createdAt")
    suspend fun uncategorized(): List<Entry>

    /** In another category: it leaves where it was moved by hand and goes with the ones of that one. */
    @Query("UPDATE entries SET category = :category, position = NULL WHERE id = :id")
    suspend fun setCategory(id: Long, category: String?)
}

@Dao
interface CaptureDao {
    @Insert
    suspend fun insert(capture: Capture): Long

    @Update
    suspend fun update(capture: Capture)

    @Query("SELECT * FROM captures WHERE id = :id")
    suspend fun get(id: Long): Capture?

    @Query("SELECT * FROM captures WHERE id = :id")
    fun observe(id: Long): Flow<Capture?>

    @Query("SELECT * FROM captures WHERE state != 'done' ORDER BY createdAt")
    suspend fun unfinished(): List<Capture>

    @Query("SELECT * FROM captures ORDER BY createdAt DESC LIMIT 1")
    fun observeLatest(): Flow<Capture?>

    /** The last capture that went all the way through before [id] (the conversation's previous turn). */
    // (What was sent along with something said is part of that, not a turn of its own.)
    @Query("SELECT * FROM captures WHERE state = 'done' AND id != :id AND partOf IS NULL ORDER BY id DESC LIMIT 1")
    suspend fun lastDone(id: Long): Capture?

    /** The photos sent together with capture [id], in the order she took them. */
    @Query("SELECT * FROM captures WHERE partOf = :id AND imagePath IS NOT NULL ORDER BY createdAt, id")
    suspend fun attachedTo(id: Long): List<Capture>

    /** The photos sent together with something said, and those captures (what they went with). */
    @Query("SELECT * FROM captures WHERE (partOf IS NOT NULL AND imagePath IS NOT NULL) OR id IN (SELECT partOf FROM captures WHERE partOf IS NOT NULL)")
    fun observeAttachments(): Flow<List<Capture>>

    /** The recordings whose sound was kept (a melody sung…), the oldest first. */
    @Query("SELECT * FROM captures WHERE sound = 1 AND audioPath IS NOT NULL ORDER BY createdAt, id")
    fun observeSounds(): Flow<List<Capture>>

    /** Every photo kept, the newest first (the gallery, the search). */
    @Query("SELECT * FROM captures WHERE source = 'photo' AND imagePath IS NOT NULL ORDER BY createdAt DESC, id DESC")
    fun observeAllPhotos(): Flow<List<Capture>>

    @Query("SELECT * FROM captures WHERE source = 'photo' AND imagePath IS NOT NULL ORDER BY createdAt DESC, id DESC")
    suspend fun allPhotos(): List<Capture>

    /** The photos taken between [from] and [to] (a day), in the order they were taken. */
    @Query("SELECT * FROM captures WHERE source = 'photo' AND imagePath IS NOT NULL AND createdAt BETWEEN :from AND :to ORDER BY createdAt, id")
    fun observePhotos(from: Long, to: Long): Flow<List<Capture>>

    /** Everything sent together with capture [id] (photos and files). */
    @Query("SELECT * FROM captures WHERE partOf = :id ORDER BY createdAt, id")
    suspend fun partsOf(id: Long): List<Capture>

    /** The files sent together with capture [id], in the order she picked them. */
    @Query("SELECT * FROM captures WHERE partOf = :id AND filePath IS NOT NULL ORDER BY createdAt, id")
    suspend fun filesWith(id: Long): List<Capture>

    /** Every file kept and the captures they went with (what says where each one lives), the newest first. */
    @Query("SELECT * FROM captures WHERE filePath IS NOT NULL OR id IN (SELECT partOf FROM captures WHERE partOf IS NOT NULL AND filePath IS NOT NULL) ORDER BY createdAt DESC, id DESC")
    fun observeFiles(): Flow<List<Capture>>

    @Query("DELETE FROM captures WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface GraphDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTopic(topic: Topic): Long

    @Query("SELECT * FROM entities WHERE norm = :norm")
    suspend fun topicByNorm(norm: String): Topic?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun tie(link: EntryTopic)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(link: Link)

    @Query("SELECT t.* FROM entities t JOIN entry_topics et ON et.topicId = t.id WHERE et.entryId = :entryId ORDER BY t.name COLLATE NOCASE")
    suspend fun topicsOf(entryId: Long): List<Topic>

    @Query("SELECT * FROM entities")
    suspend fun allTopics(): List<Topic>

    @Query("SELECT * FROM entry_topics")
    suspend fun allTies(): List<EntryTopic>

    @Query("SELECT * FROM links")
    suspend fun allLinks(): List<Link>

    @Query("SELECT entryId FROM entry_topics WHERE topicId = :topicId")
    suspend fun entriesOfTopic(topicId: Long): List<Long>

    @Query("SELECT * FROM entities WHERE id = :id")
    suspend fun topic(id: Long): Topic?

    @Query(
        "SELECT t.id AS id, t.name AS name, t.type AS type, COUNT(et.entryId) AS count FROM entities t " +
            "JOIN entry_topics et ON et.topicId = t.id GROUP BY t.id ORDER BY count DESC LIMIT :limit",
    )
    fun observeTopTopics(limit: Int): Flow<List<TopicCount>>

    @Query(
        "SELECT x.topicId AS a, y.topicId AS b, COUNT(*) AS weight FROM entry_topics x " +
            "JOIN entry_topics y ON x.entryId = y.entryId AND x.topicId < y.topicId GROUP BY x.topicId, y.topicId",
    )
    fun observePairs(): Flow<List<TopicPair>>

    @Query("DELETE FROM entities WHERE id NOT IN (SELECT topicId FROM entry_topics)")
    suspend fun pruneTopics()
}

@Dao
interface ProgressDao {
    @Insert
    suspend fun insert(progress: Progress): Long

    @Query("SELECT * FROM progress WHERE entryId = :entryId ORDER BY at DESC, id DESC LIMIT :limit")
    suspend fun latest(entryId: Long, limit: Int): List<Progress>
}

/** Everything at once: what a backup takes, and what an import puts back (ids kept). */
@Dao
interface BackupDao {
    @Query("SELECT * FROM entries ORDER BY id")
    suspend fun entries(): List<Entry>

    @Query("SELECT * FROM captures ORDER BY id")
    suspend fun captures(): List<Capture>

    @Query("SELECT * FROM entities ORDER BY id")
    suspend fun topics(): List<Topic>

    @Query("SELECT * FROM entry_topics")
    suspend fun ties(): List<EntryTopic>

    @Query("SELECT * FROM links")
    suspend fun links(): List<Link>

    @Query("SELECT * FROM progress ORDER BY id")
    suspend fun progress(): List<Progress>

    @Query("SELECT (SELECT COUNT(*) FROM entries) + (SELECT COUNT(*) FROM captures)")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putEntries(list: List<Entry>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCaptures(list: List<Capture>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTopics(list: List<Topic>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putTies(list: List<EntryTopic>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putLinks(list: List<Link>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putProgress(list: List<Progress>)

    @Query("DELETE FROM entry_topics")
    suspend fun clearTies()

    @Query("DELETE FROM links")
    suspend fun clearLinks()

    @Query("DELETE FROM progress")
    suspend fun clearProgress()

    @Query("DELETE FROM entries")
    suspend fun clearEntries()

    @Query("DELETE FROM entities")
    suspend fun clearTopics()

    @Query("DELETE FROM captures")
    suspend fun clearCaptures()
}

@Database(
    entities = [Entry::class, Capture::class, Topic::class, EntryTopic::class, Link::class, Progress::class],
    version = 11,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7), AutoMigration(from = 7, to = 8), AutoMigration(from = 8, to = 9), AutoMigration(from = 9, to = 10)],
)
abstract class NoveloDatabase : RoomDatabase() {
    abstract fun entries(): EntryDao
    abstract fun captures(): CaptureDao
    abstract fun graph(): GraphDao
    abstract fun progress(): ProgressDao
    abstract fun backup(): BackupDao

    companion object {
        /**
         * An item is one text: the news kept apart as "andamento" joins the item's text, in the
         * order it came. What stays in `progress` is only when the status changed.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val news = LinkedHashMap<Long, MutableList<String>>()
                db.query("SELECT entryId, text FROM progress WHERE TRIM(text) != '' ORDER BY at, id").use { c ->
                    while (c.moveToNext()) news.getOrPut(c.getLong(0)) { mutableListOf() } += c.getString(1)
                }
                for ((id, texts) in news) {
                    val body = db.query("SELECT body FROM entries WHERE id = ?", arrayOf<Any>(id)).use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: continue
                    db.execSQL("UPDATE entries SET body = ? WHERE id = ?", arrayOf<Any>(texts.fold(body) { b, t -> com.villamediana.novelo.mind.joined(b, t) }, id))
                }
                db.execSQL("UPDATE progress SET text = '' WHERE text != ''")
            }
        }

        /**
         * The tasks go together by category (1.59): the order set by hand before there were categories
         * is let go once, so each group starts arranged; what she moves from now on stays.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE entries SET position = NULL WHERE kind = 'task'")
            }
        }

        fun open(context: Context): NoveloDatabase =
            Room.databaseBuilder(context.applicationContext, NoveloDatabase::class.java, "novelo.db").addMigrations(MIGRATION_2_3, MIGRATION_10_11).build()

        fun inMemory(context: Context): NoveloDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, NoveloDatabase::class.java).allowMainThreadQueries().build()
    }
}
