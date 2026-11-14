package com.villamediana.novelo.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * A medicine she (or someone of hers: a child, a pet) takes — its own tab, not a task: what it is,
 * how much each time, when, from when until when, and the reminder that comes with each time.
 */
@Serializable
@Entity(tableName = "meds", indices = [Index("person")])
data class Med(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** How strong one of it is, as the box says ("500 mg", "200 mg/ml"). */
    val strength: String? = null,
    /** How much of it each time ("½ comprimido", "10 gotas"). */
    val dose: String? = null,
    /** What that comes to ("250 mg"): worked out from the two, or said by the model. */
    val amount: String? = null,
    /** Who takes it ("Cooper", "Aurora"); null: she does. */
    val person: String? = null,
    /** The times of the day, JSON ["08:00", "20:00"]; none: only when needed (no reminder). */
    val times: String = "[]",
    /** "De 8 em 8 horas": the times are the first one and every this many hours after it. */
    val everyHours: Int? = null,
    /** The days of the week it is taken, JSON [1..7] (1 = Monday); none: every day. */
    val days: String = "[]",
    /** From when (the times before it don't count). */
    val startsAt: Long,
    /** Until when (the end of its last day); null: no end, taken for good. */
    val endsAt: Long? = null,
    /** How to take it ("com comida", "em jejum"). */
    val notes: String = "",
    /** A photo of it (a capture): the box, the prescription. */
    val photoId: Long? = null,
    /** What she said or photographed that made it. */
    val captureId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** One time of a medicine taken (or given): [at] is the time it was for, [takenAt] when it was marked. */
@Serializable
@Entity(
    tableName = "med_taken",
    primaryKeys = ["medId", "at"],
    foreignKeys = [ForeignKey(entity = Med::class, parentColumns = ["id"], childColumns = ["medId"], onDelete = ForeignKey.CASCADE)],
)
data class MedTaken(val medId: Long, val at: Long, val takenAt: Long)

@Dao
interface MedDao {
    @Insert
    suspend fun insert(med: Med): Long

    @Update
    suspend fun update(med: Med)

    @Query("DELETE FROM meds WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM meds WHERE id = :id")
    suspend fun get(id: Long): Med?

    @Query("SELECT * FROM meds WHERE id = :id")
    fun observe(id: Long): Flow<Med?>

    @Query("SELECT * FROM meds ORDER BY createdAt")
    suspend fun all(): List<Med>

    @Query("SELECT * FROM meds ORDER BY createdAt")
    fun observeAll(): Flow<List<Med>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun take(taken: MedTaken)

    @Query("DELETE FROM med_taken WHERE medId = :medId AND at = :at")
    suspend fun untake(medId: Long, at: Long)

    @Query("SELECT * FROM med_taken WHERE at BETWEEN :from AND :to")
    suspend fun takenBetween(from: Long, to: Long): List<MedTaken>

    @Query("SELECT * FROM med_taken WHERE at BETWEEN :from AND :to")
    fun observeTaken(from: Long, to: Long): Flow<List<MedTaken>>

    // A backup: everything, and everything back.
    @Query("SELECT * FROM med_taken ORDER BY at")
    suspend fun allTaken(): List<MedTaken>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(list: List<Med>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putTaken(list: List<MedTaken>)

    @Query("DELETE FROM med_taken")
    suspend fun clearTaken()

    @Query("DELETE FROM meds")
    suspend fun clear()
}
