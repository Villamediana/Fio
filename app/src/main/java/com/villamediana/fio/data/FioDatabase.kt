package com.villamediana.fio.data

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

@Database(
    entities = [Entry::class, Capture::class, Topic::class, EntryTopic::class, Link::class, Progress::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class FioDatabase : RoomDatabase() {
    abstract fun entries(): EntryDao
    abstract fun captures(): CaptureDao
    abstract fun graph(): GraphDao
    abstract fun progress(): ProgressDao

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
                    db.execSQL("UPDATE entries SET body = ? WHERE id = ?", arrayOf<Any>(texts.fold(body) { b, t -> com.villamediana.fio.mind.joined(b, t) }, id))
                }
                db.execSQL("UPDATE progress SET text = '' WHERE text != ''")
            }
        }

        fun open(context: Context): FioDatabase =
            Room.databaseBuilder(context.applicationContext, FioDatabase::class.java, "fio.db").addMigrations(MIGRATION_2_3).build()

        fun inMemory(context: Context): FioDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, FioDatabase::class.java).allowMainThreadQueries().build()
    }
}
