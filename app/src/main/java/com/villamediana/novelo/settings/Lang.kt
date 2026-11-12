package com.villamediana.novelo.settings

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.Locale

/** The language the app's own words are in (what she speaks is always recognized by itself). */
enum class AppLang(val key: String, val tag: String, val label: String) {
    PT("pt", "pt-BR", "Português"),
    EN("en", "en", "English"),
    ES("es", "es", "Español");

    val locale: Locale get() = Locale.forLanguageTag(tag)

    companion object {
        fun of(key: String?): AppLang = entries.firstOrNull { it.key == key } ?: PT
    }
}

/**
 * The app's language: Portuguese unless she chose another, whatever the phone is in. Kept in plain
 * preferences so it is known before the first frame (the screens are made in it from the start).
 */
object Lang {
    private const val PREFS = "app"
    private const val KEY = "lang"

    @Volatile private var app: Context? = null
    @Volatile private var chosen: AppLang? = null
    @Volatile private var resources: Resources? = null

    /**
     * Read by every word asked on a screen, changed when the language changes: the screens are
     * drawn again right there, in the new language (nothing restarts, she stays where she is).
     */
    private val changes = androidx.compose.runtime.mutableIntStateOf(0)

    /** Once, when the app starts (before anything asks for a word). */
    fun init(context: Context) {
        app = context.applicationContext ?: context
        chosen = read(context)
        resources = null
        Locale.setDefault(current.locale)
    }

    private fun read(context: Context): AppLang =
        AppLang.of(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    val current: AppLang get() = chosen ?: app?.let(::read) ?: AppLang.PT

    /** Dates and numbers in the app's language. */
    val locale: Locale get() = current.locale

    /** Chooses [lang]: kept, and the screens on show are drawn again in it at once. */
    fun set(context: Context, lang: AppLang) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, lang.key).apply()
        chosen = lang
        resources = null
        Locale.setDefault(lang.locale)
        changes.intValue++
    }

    /** [base] speaking the app's language. */
    fun wrap(base: Context): Context {
        val config = Configuration(base.resources.configuration)
        config.setLocale(AppLang.of(base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)).locale)
        return base.createConfigurationContext(config)
    }

    /** The words, in the app's language. */
    fun res(): Resources {
        // On a screen, this word follows the language from now on (see [changes]).
        changes.intValue
        resources?.let { return it }
        val context = app ?: error("Lang.init was not called")
        val config = Configuration(context.resources.configuration).apply { setLocale(current.locale) }
        return context.createConfigurationContext(config).resources.also { resources = it }
    }
}

/** A word or sentence of the app, in its language. */
fun t(@StringRes id: Int, vararg args: Any): String = if (args.isEmpty()) Lang.res().getString(id) else Lang.res().getString(id, *args)

/** One or many ("1 foto", "3 fotos"), in the app's language. */
fun tn(@PluralsRes id: Int, count: Int): String = Lang.res().getQuantityString(id, count, count)
