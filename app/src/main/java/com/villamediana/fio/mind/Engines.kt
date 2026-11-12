package com.villamediana.fio.mind

import com.villamediana.fio.R
import com.villamediana.fio.settings.t
import com.villamediana.fio.ai.Api
import com.villamediana.fio.ai.ApiAsks
import com.villamediana.fio.ai.ApiException
import com.villamediana.fio.audio.Pieces
import com.villamediana.fio.audio.Wav
import com.villamediana.fio.local.LocalEngine
import com.villamediana.fio.local.LocalModel
import com.villamediana.fio.settings.FioSettings
import com.villamediana.fio.settings.Settings
import com.villamediana.fio.settings.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Something is missing before Fio can hear or think (the button still records: nothing is lost). */
class NotReady(val what: Need, message: String) : Exception(message)

enum class Need { HEAR, THINK }

/** What was heard so far of a recording: the text of its first [pieces] pieces, cut the [by] way. */
data class Heard(val text: String = "", val pieces: Int = 0, val by: String? = null)

/** Speech → text. */
interface Ears {
    /**
     * [from]: pieces already heard (a restart goes on from there, when they were cut the same way);
     * [onPiece]: each piece done, to be kept; [onPartial]: the text so far, as it comes.
     */
    suspend fun transcribe(wav: File, from: Heard = Heard(), onPiece: suspend (Heard) -> Unit = {}, onPartial: (String) -> Unit): String

    /** Gets ready while she is still talking (the model on the phone loads in the meantime). */
    suspend fun warm() {}

    /** Lets go of what is going on now (the model on the phone or the request), so a stop is quick. */
    fun stop() {}
}

/**
 * What organizing on the phone gets, as things (the API gets them written out by [Brain.context]):
 * [said] (for a [photo], what is written in it), the open tasks, the item she answers about,
 * what may be related, the photo(s) to look at and whether photos were sent with what she said.
 */
data class Talk(
    val said: String,
    val now: Long,
    val zone: java.time.ZoneId,
    val openTasks: List<com.villamediana.fio.data.Entry>,
    val focus: com.villamediana.fio.data.Entry?,
    val related: List<com.villamediana.fio.data.Entry>,
    val photo: Boolean,
    val image: ByteArray?,
    val photos: Boolean,
)

/** Text (and maybe a photo) → the model's raw answer. */
interface Thinker {
    /** On the phone the plan is made there, whole ([LocalOrganizer]); null when the API thinks ([answer]). */
    suspend fun organize(talk: Talk): Plan? = null

    /** True when the model that thinks can look at the photo itself (otherwise it gets the text read from it). */
    suspend fun seesImages(): Boolean

    /** The long side of the photo it gets, in px (a small model on the phone wants a smaller one). */
    suspend fun imageSide(): Int = 1600

    suspend fun answer(system: String, user: String, image: ByteArray? = null): String

    /** Gets ready while she is still talking (the model on the phone loads in the meantime). */
    suspend fun warm() {}

    /** Lets go of what is going on now (the model on the phone or the request), so a stop is quick. */
    fun stop() {}
}

/** Speech → text: always LFM3-Omni, on the phone (whoever thinks). */
class Hearing(
    private val engine: LocalEngine,
    private val model: LocalModel,
) : Ears {
    fun readiness(): String? = when {
        !LocalEngine.available -> t(R.string.need_phone_not_in_version)
        !model.hearReady -> t(R.string.need_download_pack)
        else -> null
    }

    override suspend fun transcribe(wav: File, from: Heard, onPiece: suspend (Heard) -> Unit, onPartial: (String) -> Unit): String = withContext(Dispatchers.IO) {
        readiness()?.let { throw NotReady(Need.HEAR, it) }
        val samples = Wav.readFloats(wav)
        if (Pieces.isSilent(samples)) return@withContext ""
        val by = "local:$PIECE"
        val start = if (from.by == by) from else Heard(by = by)
        var done = start.text
        if (done.isNotEmpty()) onPartial(done)
        val parts = Pieces.cut(samples, maxSeconds = PIECE)
        for ((i, p) in parts.withIndex()) {
            if (i < start.pieces) continue
            val before = done
            val text = engine.transcribe(model.files, p, null) { partial -> onPartial(join(before, partial)) }
            done = join(before, text)
            onPartial(done)
            onPiece(Heard(done, i + 1, by))
        }
        done
    }

    override suspend fun warm() {
        if (readiness() == null) engine.warm(model.files)
    }

    override fun stop() {
        engine.cancel()
    }

    private fun join(a: String, b: String) = listOf(a.trim(), b.trim()).filter { it.isNotEmpty() }.joinToString(" ")

    private companion object {
        /** Seconds per piece: what the model on the phone hears at once. */
        const val PIECE = 28
    }
}

/** Text (and maybe a photo) → the model's raw answer, on the phone or through the API. */
class Thinking(
    private val settings: Settings,
    private val api: Api,
    private val engine: LocalEngine,
    private val model: LocalModel,
) : Thinker {
    private val organizer = LocalOrganizer(engine, model)

    fun readiness(s: FioSettings): String? = when (s.think) {
        Source.LOCAL -> when {
            !LocalEngine.available -> t(R.string.need_phone_not_in_version)
            !model.ready -> t(R.string.need_download_pack)
            else -> null
        }
        Source.API -> when {
            !s.apiReady -> t(R.string.need_api)
            s.thinkModel.isBlank() -> t(R.string.need_think_model)
            else -> null
        }
    }

    /** The API model, and the one on the phone when its projector (eyes and ears) is there. */
    override suspend fun seesImages(): Boolean = when (settings.current().think) {
        Source.API -> true
        Source.LOCAL -> LocalEngine.available
    }

    /** The photo is looked at lightly (its text comes read by the phone): no need to send more than this. */
    override suspend fun imageSide(): Int = 768

    /** What each API server took and refused, so a refusal is paid once. */
    private val asks = ApiAsks()

    /** On the phone hearing comes first (Omni is loaded for it); the API needs nothing ahead. */
    override suspend fun warm() {}

    override suspend fun organize(talk: Talk): Plan? {
        val s = settings.current()
        if (s.think != Source.LOCAL) return null
        readiness(s)?.let { throw NotReady(Need.THINK, it) }
        return withContext(Dispatchers.IO) { organizer.plan(talk) }
    }

    override fun stop() {
        engine.cancel()
        api.cancelAll()
    }

    override suspend fun answer(system: String, user: String, image: ByteArray?): String = withContext(Dispatchers.IO) {
        val s = settings.current()
        readiness(s)?.let { throw NotReady(Need.THINK, it) }
        when (s.think) {
            // On the phone the plan is made by [organize]; nothing asks this.
            Source.LOCAL -> throw IllegalStateException(t(R.string.local_no_answer))
            Source.API -> {
                val ep = s.endpoint(settings.apiKey().orEmpty())
                // Asked with little reasoning (organizing doesn't need more, and it answers much
                // sooner) and a light look at the photo. A server that refuses something of the
                // request gets it again without it — and that is remembered, so the next answers
                // don't pay for the same refusal ([ApiAsks]).
                val key = s.apiAddress + "|" + s.thinkModel
                var last: ApiException? = null
                var refusedAsk: ApiAsks.Ask? = null
                for (a in asks.plan(key, withImage = image != null)) {
                    try {
                        val out = api.chat(
                            ep, s.thinkModel, system, user,
                            image = if (a.image) image else null,
                            jsonOut = a.json,
                            effort = if (a.effort) "low" else null,
                            lowDetail = a.lowDetail,
                        )
                        refusedAsk?.let { asks.learn(key, it, a) }
                        return@withContext out
                    } catch (e: ApiException) {
                        if (!e.refused) throw e
                        last = e
                        refusedAsk = a
                    }
                }
                throw last!!
            }
        }
    }
}
