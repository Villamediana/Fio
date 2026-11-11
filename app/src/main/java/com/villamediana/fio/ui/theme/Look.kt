package com.villamediana.fio.ui.theme

import android.app.Activity
import android.app.UiModeManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.villamediana.fio.R
import com.villamediana.fio.settings.FioSettings
import com.villamediana.fio.settings.ThemeMode

/**
 * The app outside its own screens in the theme she chose: light or dark and the accent of the
 * splash from the very first frame, and the icon on the phone in her accent.
 */
object Look {
    /** Accent → the suffix of its icon (.MainActivity…) and its splash. The first is the default. */
    private val ALIASES = listOf(
        "vermilion" to "", "blue" to "Blue", "green" to "Green", "pink" to "Pink",
        "purple" to "Purple", "teal" to "Teal", "graphite" to "Graphite",
    )
    private val SPLASH = mapOf(
        "vermilion" to R.style.Theme_Fio_Splash, "blue" to R.style.Theme_Fio_Splash_Blue, "green" to R.style.Theme_Fio_Splash_Green,
        "pink" to R.style.Theme_Fio_Splash_Pink, "purple" to R.style.Theme_Fio_Splash_Purple, "teal" to R.style.Theme_Fio_Splash_Teal,
        "graphite" to R.style.Theme_Fio_Splash_Graphite,
    )

    @Volatile private var mode: Int? = null
    @Volatile private var splash: Int? = null

    fun splashTheme(accent: String): Int = SPLASH[FioAccents.byId(accent).id] ?: R.style.Theme_Fio_Splash

    /**
     * Light or dark as she chose, for the whole app (Android 12+: the system opens it that way, the
     * splash and the window too), and the splash in her accent from the next opening (Android 13+).
     */
    fun screen(activity: Activity, s: FioSettings) {
        if (Build.VERSION.SDK_INT >= 31) {
            val m = when (s.theme) {
                ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
                ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
            }
            if (m != mode) {
                runCatching { activity.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(m) }
                mode = m
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val t = splashTheme(s.accent)
            if (t != splash) {
                runCatching { activity.splashScreen.setSplashScreenTheme(t) }
                splash = t
            }
        }
    }

    /** The icon in her accent: its own entry on, the others off (only when that changes anything). */
    fun icon(context: Context, accent: String) {
        val pm = context.packageManager
        val want = FioAccents.byId(accent).id
        fun component(suffix: String) = ComponentName(context.packageName, "com.villamediana.fio.MainActivity$suffix")
        fun on(id: String, suffix: String) = when (pm.getComponentEnabledSetting(component(suffix))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> id == ALIASES.first().first
            else -> false
        }
        if (ALIASES.all { (id, suffix) -> on(id, suffix) == (id == want) }) return
        // The new one first: there is never a moment with no icon.
        ALIASES.first { it.first == want }.let { (_, suffix) ->
            pm.setComponentEnabledSetting(component(suffix), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        }
        ALIASES.filter { (id, suffix) -> id != want && on(id, suffix) }.forEach { (_, suffix) ->
            pm.setComponentEnabledSetting(component(suffix), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }
}
