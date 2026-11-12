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
import com.villamediana.fio.settings.Lang
import com.villamediana.fio.ai.NoveloModels
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

    /**
     * While she is still talking: the next piece of [wav] that is already whole (from sample [at]),
     * heard now — [heard] with it, and where the next piece starts. Null when none is whole yet.
     * When she stops, [transcribe] goes on from there: only what was left is heard then.
     */
    suspend fun ahead(wav: File, heard: Heard, at: Int, onPartial: (String) -> Unit = {}): Pair<Heard, Int>? = null

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

/** Speech → text: always LFM3-Omni, on the phone (whoever thinks). */
class Hearing(
    private val engine: LocalEngine,
    private val model: LocalModel,
) : Ears {
    fun readiness(): String? = when {
        !LocalEngine.available -> t(R.string.need_phone_not_in_version)
        !model.ready -> t(R.string.need_download_ears)
        else -> null
    }

    override suspend fun transcribe(wav: File, from: Heard, onPiece: suspend (Heard) -> Unit, onPartial: (String) -> Unit): String = withContext(Dispatchers.IO) {
        readiness()?.let { throw NotReady(Need.HEAR, it) }
        val samples = Wav.readFloats(wav)
        if (Pieces.isSilent(samples)) return@withContext ""
        val by = BY
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

    override suspend fun ahead(wav: File, heard: Heard, at: Int, onPartial: (String) -> Unit): Pair<Heard, Int>? = withContext(Dispatchers.IO) {
        if (readiness() != null) return@withContext null
        val recorded = Wav.samplesSoFar(wav)
        if (recorded - at <= PIECE * Wav.RATE) return@withContext null
        val piece = Pieces.nextWhole(Wav.readRange(wav, at, recorded), PIECE) ?: return@withContext null
        val by = BY
        val before = if (heard.by == by) heard else Heard(by = by)
        val text = engine.transcribe(model.files, piece, null) { partial -> onPartial(join(before.text, partial)) }
        Heard(join(before.text, text), before.pieces + 1, by) to (at + piece.size)
    }

    override fun stop() {
        engine.cancel()
    }

    private fun join(a: String, b: String) = listOf(a.trim(), b.trim()).filter { it.isNotEmpty() }.joinToString(" ")

    private companion object {
        /** Seconds per piece: what the model on the phone hears at once. */
        const val PIECE = 28

        /**
         * How the pieces were cut (kept with what was heard): a capture half heard with other cuts
         * starts over instead of going on from pieces that don't match. "p": cut at the pauses (1.21).
         */
        const val BY = "local:${PIECE}p"
    }
}

/**
 * Text (and maybe a photo) → the model's raw answer, always online: the Novelo's own intelligence
 * (free models, [NoveloModels]) or her API. The same prompt either way.
 */
class Thinking(
    private val settings: Settings,
    private val api: Api,
    private val engine: LocalEngine,
    private val model: LocalModel,
    private val novelo: NoveloModels,
) : Thinker {
    fun readiness(s: FioSettings): String? = when (s.think) {
        Source.NOVELO -> if (novelo.available) null else t(R.string.need_novelo_not_in_version)
        Source.API -> when {
            !s.apiReady -> t(R.string.need_api)
            s.thinkModel.isBlank() -> t(R.string.need_think_model)
            else -> null
        }
    }

    /** Her API looks at the photo itself; for the Novelo's, Omni looks at it on the phone and says what it sees. */
    override suspend fun seesImages(): Boolean = true

    /** The photo is looked at lightly (its text comes read by the phone): no need to send more than this. */
    override suspend fun imageSide(): Int = 768

    /** What each API server took and refused, so a refusal is paid once. */
    private val asks = ApiAsks()

    override fun stop() {
        engine.cancel()
        api.cancelAll()
        novelo.cancel()
    }

    /**
     * What Omni sees in the photo, in a sentence or two (and the writing in it), in the app's
     * language; null when it can't look (not downloaded, or it couldn't read the photo).
     */
    private suspend fun seen(image: ByteArray): String? {
        if (!LocalEngine.available || !model.ready) return null
        val ask = "Describe this photo in one or two sentences, in ${Lang.current.label}: the place, the people, animals and things, " +
            "the colours, the moment, and any writing you can read. Only what can be seen, nothing made up. Plain text."
        return runCatching {
            LocalEngine.clean(engine.generate(model.files, Brain.chatml(ask, LocalEngine.MEDIA), grammar = null, image = image, maxTokens = 200))
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    override suspend fun answer(system: String, user: String, image: ByteArray?): String = withContext(Dispatchers.IO) {
        val s = settings.current()
        readiness(s)?.let { throw NotReady(Need.THINK, it) }
        when (s.think) {
            Source.NOVELO -> {
                // The photo goes as what Omni saw in it (free models may not look at photos).
                val look = image?.let { seen(it) }
                val words = if (look == null) user else user.trimEnd() + "\n\nO QUE SE VÊ NA FOTO (o celular olhou):\n" + look + "\n"
                novelo.answer(system, words) { Brain.parse(it) != null }
            }
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
