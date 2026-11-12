package com.villamediana.fio.mind

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

/** Text (and maybe a photo) → the model's raw answer. */
interface Thinker {
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

/** Speech → text, on the phone (LFM3) or through the API chosen in Ajustes. */
class Hearing(
    private val settings: Settings,
    private val api: Api,
    private val engine: LocalEngine,
    private val model: LocalModel,
    private val tmp: File,
) : Ears {
    fun readiness(s: FioSettings): String? = when (s.hear) {
        Source.LOCAL -> when {
            !LocalEngine.available -> "o modelo no celular ainda não vem nesta versão — escolha uma API em Ajustes"
            !model.ready -> "baixe o ${model.spec.name} em Ajustes"
            else -> null
        }
        Source.API -> when {
            !s.apiReady -> "configure a API em Ajustes"
            s.hearModel.isBlank() -> "escolha o modelo que transcreve em Ajustes"
            else -> null
        }
    }

    override suspend fun transcribe(wav: File, from: Heard, onPiece: suspend (Heard) -> Unit, onPartial: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val s = settings.current()
        readiness(s)?.let { throw NotReady(Need.HEAR, it) }
        val samples = Wav.readFloats(wav)
        if (Pieces.isSilent(samples)) return@withContext ""
        val by = if (s.hear == Source.LOCAL) "local:$LOCAL_PIECE" else "api:$API_PIECE"
        val start = if (from.by == by) from else Heard(by = by)
        var done = start.text
        if (done.isNotEmpty()) onPartial(done)
        when (s.hear) {
            Source.LOCAL -> {
                val parts = Pieces.cut(samples, maxSeconds = LOCAL_PIECE)
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
            Source.API -> {
                val ep = Api.Endpoint(s.apiBase, settings.apiKey().orEmpty())
                val parts = if (wav.length() <= 20L * 1024 * 1024) listOf(null) else Pieces.cut(samples, maxSeconds = API_PIECE)
                for ((i, p) in parts.withIndex()) {
                    if (i < start.pieces) continue
                    val file = if (p == null) wav else File(tmp, "piece-${System.nanoTime()}.wav").also { Wav.write(it, p) }
                    try {
                        done = join(done, api.transcribe(ep, s.hearModel, file, null))
                        onPartial(done)
                        onPiece(Heard(done, i + 1, by))
                    } finally {
                        if (p != null) file.delete()
                    }
                }
                done
            }
        }
    }

    override suspend fun warm() {
        val s = settings.current()
        if (s.hear == Source.LOCAL && readiness(s) == null) engine.warm(model.files)
    }

    override fun stop() {
        engine.cancel()
        api.cancelAll()
    }

    private fun join(a: String, b: String) = listOf(a.trim(), b.trim()).filter { it.isNotEmpty() }.joinToString(" ")

    private companion object {
        /** Seconds per piece: what the model on the phone hears at once, and what goes per request to the API. */
        const val LOCAL_PIECE = 28
        const val API_PIECE = 300
    }
}



/** Text (and maybe a photo) → the model's raw answer, on the phone or through the API. */
class Thinking(
    private val settings: Settings,
    private val api: Api,
    private val engine: LocalEngine,
    private val model: LocalModel,
) : Thinker {
    fun readiness(s: FioSettings): String? = when (s.think) {
        Source.LOCAL -> when {
            !LocalEngine.available -> "o modelo no celular ainda não vem nesta versão — escolha uma API em Ajustes"
            !model.ready -> "baixe o ${model.spec.name} em Ajustes"
            else -> null
        }
        Source.API -> when {
            !s.apiReady -> "configure a API em Ajustes"
            s.thinkModel.isBlank() -> "escolha o modelo que organiza em Ajustes"
            else -> null
        }
    }

    /** The API model, and the one on the phone when its projector (eyes and ears) is there. */
    override suspend fun seesImages(): Boolean = when (settings.current().think) {
        Source.API -> true
        Source.LOCAL -> LocalEngine.available && model.spec.projector != null
    }

    /** The photo is looked at lightly (its text comes read by the phone): no need to send more than this. */
    override suspend fun imageSide(): Int = 768

    /** What each API server took and refused, so a refusal is paid once. */
    private val asks = ApiAsks()

    override suspend fun warm() {
        val s = settings.current()
        // The instructions too: on the phone they are most of the work of organizing.
        if (s.think == Source.LOCAL && readiness(s) == null) engine.warm(model.files, Brain.chatmlHead(Brain.SYSTEM))
    }

    override fun stop() {
        engine.cancel()
        api.cancelAll()
    }

    override suspend fun answer(system: String, user: String, image: ByteArray?): String = withContext(Dispatchers.IO) {
        val s = settings.current()
        readiness(s)?.let { throw NotReady(Need.THINK, it) }
        when (s.think) {
            Source.LOCAL -> engine.generate(
                model.files,
                Brain.chatml(system, if (image != null) LocalEngine.MEDIA + "\n" + user else user),
                Brain.JSON_GRAMMAR,
                image,
                // The instructions are the same every time: the phone runs them once and keeps them.
                fixed = Brain.chatmlHead(system),
            )
            Source.API -> {
                val ep = Api.Endpoint(s.apiBase, settings.apiKey().orEmpty())
                // Asked with little reasoning (organizing doesn't need more, and it answers much
                // sooner) and a light look at the photo. A server that refuses something of the
                // request gets it again without it — and that is remembered, so the next answers
                // don't pay for the same refusal ([ApiAsks]).
                val key = s.apiBase + "|" + s.thinkModel
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
