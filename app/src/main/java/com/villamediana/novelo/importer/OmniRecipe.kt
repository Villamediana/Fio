package com.villamediana.novelo.importer

import com.villamediana.novelo.R
import com.villamediana.novelo.local.LocalEngine
import com.villamediana.novelo.local.LocalModel
import com.villamediana.novelo.mind.Brain
import com.villamediana.novelo.settings.t
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The import's recipe written by Omni, on the phone (the Novelo's ears, already here): it is good
 * at a narrow thing like this, and the diary never leaves the phone — not even the sample. The
 * grammar holds the answer to the recipe's own fields, so it always comes as one.
 */
class OmniRecipe(private val engine: LocalEngine, private val model: LocalModel) {
    /** Why Omni can't answer now (not in this version, not downloaded yet), or null. */
    fun readiness(): String? = when {
        !LocalEngine.available -> t(R.string.need_phone_not_in_version)
        !model.ready -> t(R.string.need_download_ears)
        else -> null
    }

    suspend fun ask(system: String, user: String): String {
        val tag = Any()
        return coroutineScope {
            val work = async(Dispatchers.Default) {
                engine.generate(model.files, Brain.chatml(system, user), GRAMMAR, maxTokens = MAX_TOKENS, fixed = Brain.chatmlHead(system), tag = tag)
            }
            try {
                LocalEngine.clean(work.await())
            } catch (e: CancellationException) {
                engine.cancel(tag)
                throw e
            }
        }
    }

    companion object {
        private const val MAX_TOKENS = 600

        /** A [Recipe]: app, about and shape first, then only the fields it uses. */
        val GRAMMAR: String = """
root  ::= "{" ws "\"app\":" ws str "," ws "\"about\":" ws str "," ws "\"shape\":" ws shape ( "," ws pair ){0,16} ws "}"
pair  ::= key ":" ws str | flag ":" ws bool | "\"clean\":" ws "[" ws ( str ( ws "," ws str ){0,5} )? ws "]"
key   ::= "\"entries\"" | "\"date\"" | "\"title\"" | "\"body\"" | "\"tags\"" | "\"start\"" | "\"date_regex\"" | "\"title_regex\"" | "\"delimiter\"" | "\"date_from_name\"" | "\"date_format\"" | "\"date_locale\"" | "\"files\""
flag  ::= "\"header\"" | "\"first_line_title\"" | "\"html\""
shape ::= "\"json\"" | "\"text\"" | "\"csv\""
str   ::= "\"" ( [^"\\\x7F\x00-\x1F] | "\\" ["\\/bfnrt] | "\\u" [0-9a-fA-F]{4} ){0,240} "\""
bool  ::= "true" | "false"
ws    ::= [ \t\n]{0,4}
""".trim()
    }
}
