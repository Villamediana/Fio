package com.villamediana.fio

import android.content.Context
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
import com.villamediana.fio.update.Updates
import com.villamediana.fio.update.createUpdates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File
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
    val engine: LocalEngine by lazy { LocalEngine() }
    /** On the phone: the model that hears and the one that thinks. */
    val localAudio: LocalModel by lazy { LocalModel(appContext, scope, LocalModel.Spec.AUDIO) }
    val localOmni: LocalModel by lazy { LocalModel(appContext, scope, LocalModel.Spec.OMNI) }
    val recorder: Recorder by lazy { Recorder() }
    val reminders: Reminders by lazy { Reminders(appContext, db) }
    val library: Library by lazy { Library(db, reminders) }

    private val captures: File by lazy { File(appContext.filesDir, "captures").apply { mkdirs() } }

    val mind: Mind by lazy {
        Mind(
            scope = scope,
            db = db,
            recorder = recorder,
            hearing = Hearing(settings, api, engine, localAudio, appContext.cacheDir),
            thinking = Thinking(settings, api, engine, localOmni),
            keeper = Keeper(db),
            reminders = reminders,
            photos = MlKitPhotoReader(appContext),
            mic = AndroidMicHolder(appContext),
            dir = captures,
        )
    }

    /** Where the camera puts a photo before it goes to [mind]. */
    fun newPhotoFile(): File = File(captures, "photo-${System.currentTimeMillis()}.jpg")

    /** The app updating itself from the GitHub releases (Ajustes › Sobre, the update sheet). */
    val updates: Updates by lazy { createUpdates(appContext, http, scope) }
}
