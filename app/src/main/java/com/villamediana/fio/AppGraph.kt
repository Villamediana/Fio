package com.villamediana.fio

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.villamediana.fio.backup.Backup
import com.villamediana.fio.backup.Backups
import com.villamediana.fio.mind.PhotoDate
import com.villamediana.fio.ai.Api
import com.villamediana.fio.audio.AndroidMicHolder
import com.villamediana.fio.audio.Recorder
import com.villamediana.fio.data.FioDatabase
import com.villamediana.fio.data.Library
import com.villamediana.fio.local.LocalEngine
import com.villamediana.fio.local.LocalModel
import com.villamediana.fio.mind.Hearing
import com.villamediana.fio.mind.Keeper
import com.villamediana.fio.mind.Mind
import com.villamediana.fio.mind.MlKitPhotoReader
import com.villamediana.fio.mind.Thinking
import com.villamediana.fio.remind.Reminders
import com.villamediana.fio.settings.Settings
import com.villamediana.fio.settings.Source
import com.villamediana.fio.update.Updates
import com.villamediana.fio.update.createUpdates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import java.io.File
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

/** Everything the app is made of, built once (lazily) for the whole process. */
class AppGraph(context: Context) {
    val appContext: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .build()
    }

    val db: FioDatabase by lazy { FioDatabase.open(appContext) }
    val settings: Settings by lazy { Settings(appContext, scope) }
    val api: Api by lazy { Api(http) }
    val engine: LocalEngine by lazy { LocalEngine(java.io.File(appContext.cacheDir, "model")) }
    /** On the phone: LFM3-Omni hears and sees; when the phone thinks too, d1 decides and LFM3-1.7B writes. */
    val localModel: LocalModel by lazy { LocalModel(appContext, scope, settings.now.map { it.think == Source.LOCAL }) }
    val recorder: Recorder by lazy { Recorder() }
    val reminders: Reminders by lazy { Reminders(appContext, db) }
    val library: Library by lazy { Library(db, reminders) }

    private val captures: File by lazy { File(appContext.filesDir, "captures").apply { mkdirs() } }

    val mind: Mind by lazy {
        Mind(
            scope = scope,
            db = db,
            recorder = recorder,
            hearing = Hearing(engine, localModel),
            thinking = Thinking(settings, api, engine, localModel),
            keeper = Keeper(db),
            reminders = reminders,
            photos = MlKitPhotoReader(appContext),
            mic = AndroidMicHolder(appContext),
            dir = captures,
        )
    }

    private val photoCount = AtomicInteger()

    /** Where the camera (or the gallery) puts a photo before it goes to [mind]. */
    fun newPhotoFile(ext: String = "jpg"): File = File(captures, "photo-${System.currentTimeMillis()}-${photoCount.incrementAndGet()}.$ext")

    /**
     * Photos picked in the phone's gallery, as many as she chose: copied in (the gallery keeps its
     * own), each dated when it was taken — the camera's date in the file, else the gallery's — and
     * sent to [mind] in the order picked, like photos taken here.
     */
    fun fromGallery(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val resolver = appContext.contentResolver
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val picked = uris.mapNotNull { uri ->
                val ext = when (runCatching { resolver.getType(uri) }.getOrNull()) {
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    "image/heic", "image/heif" -> "heic"
                    else -> "jpg"
                }
                val file = newPhotoFile(ext)
                val copied = runCatching {
                    (resolver.openInputStream(uri) ?: error("no stream")).use { input -> file.outputStream().use { input.copyTo(it) } }
                }.isSuccess
                if (!copied || file.length() == 0L) {
                    file.delete()
                    return@mapNotNull null
                }
                val taken = PhotoDate.of(file, zone) ?: galleryDate(uri)
                file to (taken?.takeIf { it > 0 }?.coerceAtMost(now) ?: now)
            }
            // They wait on the main screen: sent as they are, or with what she says next.
            mind.stage(picked)
        }
    }

    /** The date the gallery has for a photo (MediaStore's date taken), when it says. */
    private fun galleryDate(uri: Uri): Long? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    /** The backup to the folder she chose (Ajustes › Backup), and the import back. */
    val backups: Backups by lazy {
        Backups(
            context = appContext,
            core = Backup(db, captures, File(appContext.filesDir, "backup-state.json"), reminders, BuildConfig.VERSION_NAME),
            settings = settings,
            mind = mind,
            scope = scope,
            joinDays = { library.joinDayDiaries() },
        )
    }

    /** The app updating itself from the GitHub releases (Ajustes › Sobre, the update sheet). */
    val updates: Updates by lazy { createUpdates(appContext, http, scope) }
}
