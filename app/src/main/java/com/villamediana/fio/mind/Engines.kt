package com.villamediana.fio.mind

import com.villamediana.fio.ai.Api
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

/** Speech → text. */
interface Ears {
    suspend fun transcribe(wav: File, onPartial: (String) -> Unit): String
}

/** Text (and maybe a photo) → the model's raw answer. */
interface Thinker {
    /** True when the model that thinks can look at the photo itself (otherwise it gets the text read from it). */
    suspend fun seesImages(): Boolean
    suspend fun answer(system: String, user: String, image: ByteArray? = null): String
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
            !model.ready -> "baixe o modelo no celular em Ajustes"
            else -> null
        }
        Source.API -> when {
            !s.apiReady -> "configure a API em Ajustes"
            s.hearModel.isBlank() -> "escolha o modelo que transcreve em Ajustes"
            else -> null
        }
    }

    override suspend fun transcribe(wav: File, onPartial: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val s = settings.current()
        readiness(s)?.let { throw NotReady(Need.HEAR, it) }
        val samples = Wav.readFloats(wav)
        if (Pieces.isSilent(samples)) return@withContext ""
        when (s.hear) {
            Source.LOCAL -> {
                val parts = Pieces.cut(samples, maxSeconds = 28)
                val done = StringBuilder()
                for (p in parts) {
                    val before = done.toString()
                    val text = engine.transcribe(model.files, p, s.lang.asr) { partial -> onPartial(join(before, partial)) }
                    done.clear().append(join(before, text))
                    onPartial(done.toString())
                }
                done.toString()
            }
            Source.API -> {
                val ep = Api.Endpoint(s.apiBase, settings.apiKey().orEmpty())
                val lang = s.lang.key.takeIf { it != "auto" }
                val parts = if (wav.length() <= 20L * 1024 * 1024) listOf(null) else Pieces.cut(samples, maxSeconds = 300)
                val done = StringBuilder()
                for (p in parts) {
                    val file = if (p == null) wav else File(tmp, "piece-${System.nanoTime()}.wav").also { Wav.write(it, p) }
                    try {
                        done.clear().append(join(done.toString(), api.transcribe(ep, s.hearModel, file, lang)))
                        onPartial(done.toString())
                    } finally {
                        if (p != null) file.delete()
                    }
                }
                done.toString()
            }
        }
    }

    private fun join(a: String, b: String) = listOf(a.trim(), b.trim()).filter { it.isNotEmpty() }.joinToString(" ")
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
            !model.ready -> "baixe o modelo no celular em Ajustes"
            else -> null
        }
        Source.API -> when {
            !s.apiReady -> "configure a API em Ajustes"
            s.thinkModel.isBlank() -> "escolha o modelo que organiza em Ajustes"
            else -> null
        }
    }

    override suspend fun seesImages(): Boolean = settings.current().think == Source.API

    override suspend fun answer(system: String, user: String, image: ByteArray?): String = withContext(Dispatchers.IO) {
        val s = settings.current()
        readiness(s)?.let { throw NotReady(Need.THINK, it) }
        when (s.think) {
            Source.LOCAL -> engine.generate(model.files, Brain.chatml(system, user), Brain.JSON_GRAMMAR)
            Source.API -> {
                val ep = Api.Endpoint(s.apiBase, settings.apiKey().orEmpty())
                // A model that doesn't see, or a server without the JSON mode, refuses the request
                // as written: it goes again with only the text, then without asking for the mode
                // (the instructions already ask for JSON).
                try {
                    api.chat(ep, s.thinkModel, system, user, image)
                } catch (e: ApiException) {
                    if (!e.refused) throw e
                    try {
                        if (image == null) throw e
                        api.chat(ep, s.thinkModel, system, user, null)
                    } catch (e2: ApiException) {
                        if (!e2.refused) throw e2
                        api.chat(ep, s.thinkModel, system, user, null, jsonOut = false)
                    }
                }
            }
        }
    }
}
