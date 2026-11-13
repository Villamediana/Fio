package com.villamediana.novelo

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.villamediana.novelo.backup.Backup
import com.villamediana.novelo.backup.Backups
import com.villamediana.novelo.mind.PhotoDate
import com.villamediana.novelo.ai.Api
import com.villamediana.novelo.audio.AndroidMicHolder
import com.villamediana.novelo.audio.Recorder
import com.villamediana.novelo.data.NoveloDatabase
import com.villamediana.novelo.data.Draft
import com.villamediana.novelo.data.Library
import com.villamediana.novelo.data.ToGallery
import com.villamediana.novelo.local.LocalEngine
import com.villamediana.novelo.local.LocalModel
import com.villamediana.novelo.mind.Hearing
import com.villamediana.novelo.mind.Keeper
import com.villamediana.novelo.mind.Meaning
import com.villamediana.novelo.mind.Mind
import com.villamediana.novelo.mind.MlKitPhotoReader
import com.villamediana.novelo.mind.Thinking
import com.villamediana.novelo.mind.WhenOnline
import com.villamediana.novelo.ai.NoveloModels
import com.villamediana.novelo.remind.Reminders
import com.villamediana.novelo.settings.Settings
import com.villamediana.novelo.update.Updates
import com.villamediana.novelo.update.createUpdates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    val db: NoveloDatabase by lazy { NoveloDatabase.open(appContext) }
    val settings: Settings by lazy { Settings(appContext, scope) }
    val api: Api by lazy { Api(http) }
    val engine: LocalEngine by lazy { LocalEngine(java.io.File(appContext.cacheDir, "model")) }
    /** On the phone: LFM3-Omni hears (when she chose the Novelo's ears) and looks at photos and files. */
    val localModel: LocalModel by lazy {
        LocalModel(appContext, scope).also { model ->
            // What waited for the download goes as soon as it is here.
            scope.launch { model.state.collect { if (it == LocalModel.State.Ready) mind.retryPending() } }
        }
    }
    /** The Novelo's own intelligence: free models online, picked and swapped by themselves. */
    val novelo: NoveloModels by lazy { NoveloModels(http, appContext.getSharedPreferences("novelo-models", Context.MODE_PRIVATE)) }
    val recorder: Recorder by lazy { Recorder() }
    val reminders: Reminders by lazy { Reminders(appContext, db) }
    val library: Library by lazy { Library(db, reminders) }
    /** The search by meaning: Omni gives the words her notes may use for what she looks for. */
    val meaning: Meaning by lazy { Meaning(engine, localModel) }
    /** What she was writing and didn't send (kept a day). */
    val draft: Draft by lazy { Draft(appContext.getSharedPreferences("draft", Context.MODE_PRIVATE)) }

    private val captures: File by lazy { File(appContext.filesDir, "captures").apply { mkdirs() } }

    val mind: Mind by lazy {
        Mind(
            scope = scope,
            db = db,
            recorder = recorder,
            hearing = Hearing(settings, api, engine, localModel, appContext.cacheDir),
            thinking = Thinking(settings, api, engine, localModel, novelo),
            keeper = Keeper(db),
            reminders = reminders,
            photos = MlKitPhotoReader(appContext),
            mic = AndroidMicHolder(appContext),
            dir = captures,
            offline = { WhenOnline.schedule(appContext) },
            peek = com.villamediana.novelo.mind.PhonePeek(appContext, MlKitPhotoReader(appContext)),
            about = com.villamediana.novelo.mind.OmniAbout(engine, localModel),
        )
    }

    private val photoCount = AtomicInteger()

    /** Where the camera (or the gallery) puts a photo before it goes to [mind]. */
    fun newPhotoFile(ext: String = "jpg"): File = File(captures, "photo-${System.currentTimeMillis()}-${photoCount.incrementAndGet()}.$ext")

    /**
     * A photo just taken in the app's camera: in the phone's gallery too from the start
     * (Pictures/Novelo, whatever happens to the app), then waiting on the main screen.
     */
    fun taken(file: File, at: Long) {
        scope.launch(Dispatchers.IO) {
            ToGallery.save(appContext, file.path, at, 0)
            mind.stage(listOf(file to at))
        }
    }

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

    /**
     * Files picked from the phone (any type): copied into the app with their own names, then they
     * wait on the main screen like photos — sent as they are, or with what she says next.
     */
    fun fromFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val resolver = appContext.contentResolver
            val lost = ArrayList<String>()
            val picked = uris.mapNotNull { uri ->
                val name = runCatching {
                    resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "arquivo"
                val file = File(captures.apply { mkdirs() }, "incoming-${System.currentTimeMillis()}-${photoCount.incrementAndGet()}")
                val copied = runCatching {
                    (resolver.openInputStream(uri) ?: error("no stream")).use { input -> file.outputStream().use { input.copyTo(it) } }
                }.isSuccess
                if (!copied) {
                    file.delete()
                    lost += name
                    return@mapNotNull null
                }
                file to name
            }
            mind.stageFiles(picked)
            // One that couldn't be copied (no room, a cloud file that didn't come): she is told which.
            if (lost.isNotEmpty()) mind.problem(com.villamediana.novelo.settings.t(R.string.file_not_brought, lost.joinToString(", ")))
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
