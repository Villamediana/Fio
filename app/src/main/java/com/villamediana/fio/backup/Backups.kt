package com.villamediana.fio.backup

import com.villamediana.fio.R
import com.villamediana.fio.settings.tn
import com.villamediana.fio.settings.t
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.villamediana.fio.FioApplication
import com.villamediana.fio.mind.Mind
import com.villamediana.fio.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The backup as the app uses it: the folder she chose in the system picker (the permission kept
 * across restarts), the automatic one on WorkManager, the last result in Ajustes, the import.
 */
class Backups(
    private val context: Context,
    private val core: Backup,
    private val settings: Settings,
    private val mind: Mind,
    private val scope: CoroutineScope,
    /** One diary a day, after an import too (an older backup may have days with two). */
    private val joinDays: suspend () -> Unit = {},
) {
    private val _busy = MutableStateFlow<String?>(null)
    /** "Fazendo o backup…" / "Importando…" while it goes; null when it doesn't. */
    val busy: StateFlow<String?> = _busy

    private val resolver get() = context.contentResolver

    private fun root(uri: String): Shelf = TreeShelf.root(resolver, Uri.parse(uri))

    /** The folder chosen in the picker: kept, the old one let go, the automatic backup set, and a backup right away. */
    fun choose(uri: Uri): Job = scope.launch {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val old = settings.current().backupFolder
        runCatching { resolver.takePersistableUriPermission(uri, flags) }
        if (old.isNotBlank() && old != uri.toString()) runCatching { resolver.releasePersistableUriPermission(Uri.parse(old), flags) }
        val place = runCatching { core.place(root(uri.toString())) }.getOrDefault("")
        settings.setBackupFolder(uri.toString(), place)
        BackupWorker.schedule(context, settings.current(), replace = true)
        save()
    }

    fun now(): Job = scope.launch { save() }

    /** One backup, its result kept for Ajustes. False when there is no folder or it went wrong. */
    suspend fun save(): Boolean {
        val folder = settings.current().backupFolder.takeIf { it.isNotBlank() } ?: return false
        _busy.value = SAVING
        return try {
            val root = root(folder)
            val r = core.save(root, key = folder)
            // Where it is now (a folder chosen before the backup became one file showed another place).
            core.place(root).let { if (it != settings.current().backupPlace) settings.setBackupPlace(it) }
            settings.setBackupDone(System.currentTimeMillis(), if (r.empty) t(R.string.backup_nothing_yet) else note(r.entries, r.photos, r.sounds))
            true
        } catch (e: Throwable) {
            settings.setBackupFailed(message(e))
            false
        } finally {
            _busy.value = null
        }
    }

    /** The backups in the folder, the newest first. */
    suspend fun kept(): List<Backup.Kept> {
        val folder = settings.current().backupFolder.takeIf { it.isNotBlank() } ?: return emptyList()
        return core.kept(root(folder))
    }

    suspend fun read(name: String): Snapshot = core.read(root(settings.current().backupFolder), name)

    /** Puts the backup [name] back in place of everything. */
    suspend fun restore(name: String): Result<Backup.Restored> {
        val folder = settings.current().backupFolder.takeIf { it.isNotBlank() } ?: return Result.failure(IOException(t(R.string.backup_choose_folder)))
        if (!mind.idle) return Result.failure(IOException(t(R.string.backup_wait_busy)))
        _busy.value = IMPORTING
        val r = try {
            Result.success(core.restore(root(folder), name))
        } catch (e: Throwable) {
            Result.failure(IOException(message(e), e))
        } finally {
            _busy.value = null
        }
        if (r.isSuccess) {
            runCatching { joinDays() }
            mind.restored()
            // The file follows what came back.
            save()
        }
        return r
    }

    private fun message(e: Throwable): String = when (e) {
        is SecurityException -> TreeShelf.LOST
        else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }

    companion object {
        const val SAVING = "Fazendo o backup…"
        const val IMPORTING = "Importando…"

        fun note(entries: Int, photos: Int, sounds: Int = 0): String = buildString {
            append(tn(R.plurals.items, entries))
            if (photos > 0) append(" · ").append(tn(R.plurals.photos, photos))
            if (sounds > 0) append(" · ").append(tn(R.plurals.sounds, sounds))
        }
    }
}

/** The automatic backup: once a day, with battery and room enough. */
class BackupWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        FioApplication.graph.backups.save()
        return Result.success()
    }

    companion object {
        private const val NAME = "backup"

        /**
         * In step with Ajustes: on when there is a folder, off when there isn't. [replace] when she
         * changed it (the next one counts from now); otherwise a schedule already there stays.
         */
        fun schedule(context: Context, s: com.villamediana.fio.settings.FioSettings, replace: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (s.backupFolder.isBlank()) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val days = 1L
            val request = PeriodicWorkRequestBuilder<BackupWorker>(days, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
                .setInitialDelay(days, TimeUnit.DAYS)
                .build()
            wm.enqueueUniquePeriodicWork(NAME, if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
