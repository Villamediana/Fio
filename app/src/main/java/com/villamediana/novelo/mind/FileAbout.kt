package com.villamediana.novelo.mind

import com.villamediana.novelo.local.LocalEngine
import com.villamediana.novelo.local.LocalModel
import com.villamediana.novelo.settings.Lang
import com.villamediana.novelo.settings.t

/**
 * What a photo shows and what a file is, in a sentence: what the search finds them by, and what
 * the model that organizes gets instead of looking itself. Written on the phone by Omni (the
 * Novelo's ears): offline, free, nothing sent anywhere.
 */
interface About {
    /** What [image] shows (a photo, or several side by side); null when it can't say. */
    suspend fun photo(image: ByteArray): String?

    /**
     * What [file] is, from its name, its start and what she [said] with it; [look]: its first page as
     * a picture, when it had no text to read. Null when it can't say.
     */
    suspend fun file(file: FileSeen, said: String?, look: ByteArray?): String?
}

/** Nobody to say it (the tests; a build without the engine). */
object NoAbout : About {
    override suspend fun photo(image: ByteArray): String? = null
    override suspend fun file(file: FileSeen, said: String?, look: ByteArray?): String? = null
}

/**
 * Who sees (Ajustes › Inteligência › Quem vê): Omni on the phone, or her API — the photo (or a
 * document's first page) goes to it with the same ask. A failure says nothing (the text read on the
 * phone still goes to the model that thinks).
 */
class Seeing(
    private val settings: com.villamediana.novelo.settings.Settings,
    private val api: com.villamediana.novelo.ai.Api,
    private val phone: About,
) : About {
    override suspend fun photo(image: ByteArray): String? = when (settings.current().see) {
        com.villamediana.novelo.settings.Source.NOVELO -> phone.photo(image)
        com.villamediana.novelo.settings.Source.API ->
            if (image.isEmpty()) null else byApi(OmniAbout.PHOTO, t(com.villamediana.novelo.R.string.see_photo_ask), image)?.let { OmniAbout.clean(it, 400) }
    }

    override suspend fun file(file: FileSeen, said: String?, look: ByteArray?): String? = when (settings.current().see) {
        com.villamediana.novelo.settings.Source.NOVELO -> phone.file(file, said, look)
        com.villamediana.novelo.settings.Source.API -> byApi(OmniAbout.FILE, OmniAbout.ask(file, said), look)?.lineSequence()?.firstOrNull()?.let { OmniAbout.clean(it, 180) }
    }

    private suspend fun byApi(ask: String, user: String, image: ByteArray?): String? {
        val a = settings.current().api(com.villamediana.novelo.settings.Job.SEE)
        val key = settings.apiKey(com.villamediana.novelo.settings.Job.SEE)
        if (!a.ready || a.model.isBlank() || key.isNullOrBlank()) return null
        val system = ask.replace("{lang}", Lang.current.label)
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { api.chat(a.endpoint(key), a.model, system, user, image = image, jsonOut = false) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
    }
}

/** Omni, on the phone. */
class OmniAbout(private val engine: LocalEngine, private val model: LocalModel) : About {
    private val here: Boolean get() = LocalEngine.available && model.ready

    override suspend fun photo(image: ByteArray): String? {
        if (!here || image.isEmpty()) return null
        val ask = PHOTO.replace("{lang}", Lang.current.label)
        return clean(LocalEngine.clean(engine.generate(model.files, Brain.chatml(ask, LocalEngine.MEDIA), grammar = null, image = image, maxTokens = PHOTO_TOKENS)), PHOTO_MAX)
    }

    override suspend fun file(file: FileSeen, said: String?, look: ByteArray?): String? {
        if (!here) return null
        val ask = FILE.replace("{lang}", Lang.current.label)
        val user = buildString {
            if (look != null) append(LocalEngine.MEDIA).append('\n')
            append(ask(file, said))
        }
        val out = engine.generate(model.files, Brain.chatml(ask, user), LINE, image = look, maxTokens = FILE_TOKENS, fixed = Brain.chatmlHead(ask))
        return clean(LocalEngine.clean(out), FILE_MAX)
    }

    companion object {
        private const val PHOTO_TOKENS = 200
        private const val FILE_TOKENS = 80
        private const val PHOTO_MAX = 400
        private const val FILE_MAX = 180

        val PHOTO: String =
            "Describe this photo in one or two sentences, in {lang}, with the words she would use to look for it later: " +
                "the place, the people, animals and things, the colours, the moment, and any writing you can read. " +
                "Only what can be seen, nothing made up. Plain text."

        val FILE: String = """
Diga numa frase curta o que é este arquivo que ela guardou: que tipo de documento é e de quem ou de quê (por exemplo "currículo do Miguel, 2026", "contrato de aluguel do apartamento", "planilha de gastos de novembro"), com as palavras que ela usaria pra procurá-lo. Use o nome, o começo dele e o que ela disse; não invente nada. Escreva em {lang}. Só a frase.
""".trim()

        /** One line of text. */
        val LINE: String = """
root ::= [^\n\x00-\x1F]{1,180}
""".trim()

        /** What Omni gets about a file. */
        fun ask(f: FileSeen, said: String?): String = buildString {
            append("Nome: ").append(f.name.take(120)).append('\n')
            FileKind.label(f.name, f.mime)?.let { append("Tipo: ").append(it).append('\n') }
            f.peek.trim().takeIf { it.isNotEmpty() }?.let { append("Começo: ").append(it.take(Brain.PEEK).replace('\n', ' ')).append('\n') }
            said?.trim()?.takeIf { it.isNotEmpty() }?.let { append("O que ela disse ao mandar: ").append(it.take(300).replace('\n', ' ')).append('\n') }
        }

        /** Omni's words, without quotes around them, at most [max] characters. */
        fun clean(text: String, max: Int): String? =
            text.trim().trim('"', '“', '”', '\'').trim().takeIf { it.isNotEmpty() }?.take(max)
    }
}
