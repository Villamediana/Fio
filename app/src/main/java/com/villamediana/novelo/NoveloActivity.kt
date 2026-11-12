package com.villamediana.novelo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import androidx.core.splashscreen.SplashScreenViewProvider
import com.villamediana.novelo.ui.home.OrbSpot
import com.villamediana.novelo.settings.Lang
import android.view.animation.DecelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.novelo.ui.NoveloRoot
import com.villamediana.novelo.settings.NoveloSettings
import com.villamediana.novelo.ui.theme.NoveloTheme
import com.villamediana.novelo.ui.theme.Look
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What a notification asked the app to do when it opens. */
sealed interface Ask {
    data class OpenEntry(val id: Long) : Ask
    data class TalkAbout(val id: Long) : Ask
}

class NoveloActivity : ComponentActivity() {
    private var ask by mutableStateOf<Ask?>(null)

    /** Her settings, once read: the splash stays until then (no frame in another theme). */
    private var first: NoveloSettings? = null

    /** The screens in the app's language (Portuguese unless she chose another), whatever the phone is in. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The logo stays long enough to be seen, then the app shows through it as it fades.
        val shownAt = SystemClock.uptimeMillis()
        installSplashScreen().apply {
            setKeepOnScreenCondition { first == null || SystemClock.uptimeMillis() - shownAt < SPLASH_MIN_MS }
            setOnExitAnimationListener { splash -> land(splash) }
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        read(intent)
        val graph = NoveloApplication.graph
        lifecycleScope.launch {
            // Settings that can't be read (a broken file) never keep the app from opening.
            val s = runCatching { graph.settings.current() }.getOrDefault(NoveloSettings())
            Look.screen(this@NoveloActivity, s)
            setContent {
                val settings by graph.settings.flow.collectAsStateWithLifecycle(initialValue = s)
                NoveloTheme(mode = settings.theme, accent = settings.accent) {
                    // The first steps once (permissions, backup, who thinks), then the app.
                    if (settings.setupDone) NoveloRoot(ask = ask, onAskHandled = { ask = null })
                    else com.villamediana.novelo.ui.setup.SetupScreen(onDone = {})
                }
            }
            first = s
            // Light or dark and the splash follow what she picks.
            graph.settings.flow.map { it.theme to it.accent }.distinctUntilChanged().collect { Look.screen(this@NoveloActivity, graph.settings.current()) }
        }
    }

    /**
     * The opening's ball is the button's, at the same size: it glides to where the button is while
     * the paper around it fades, and when it gets there it is simply the button. Without a ball or
     * a button on screen (opened on an item), the opening just fades.
     */
    private fun land(splash: SplashScreenViewProvider) {
        val view = splash.view
        val icon = runCatching { splash.iconView }.getOrNull()
        val target = OrbSpot.center
        val background = view.background?.mutate()
        if (icon == null || target == null || background == null) {
            view.animate().alpha(0f).setDuration(SPLASH_FADE_MS).setInterpolator(DecelerateInterpolator()).withEndAction { splash.remove() }.start()
            return
        }
        val at = IntArray(2).also { icon.getLocationInWindow(it) }
        val dx = target.x - (at[0] + icon.width / 2f)
        val dy = target.y - (at[1] + icon.height / 2f)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SPLASH_FADE_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                background.alpha = ((1f - f) * 255).toInt()
                icon.translationX = dx * f
                icon.translationY = dy * f
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = splash.remove()
            })
            start()
        }
    }

    override fun onStop() {
        super.onStop()
        // The icon on the phone in her accent: changed when she leaves, not under her finger.
        if (!isChangingConfigurations) {
            val graph = NoveloApplication.graph
            val context = applicationContext
            graph.scope.launch { runCatching { Look.icon(context, graph.settings.current().accent) } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        read(intent)
    }

    private fun read(intent: Intent?) {
        Store.opened(intent)
        val open = intent?.getLongExtra(EXTRA_OPEN_ENTRY, -1L) ?: -1L
        val talk = intent?.getLongExtra(EXTRA_TALK_ABOUT, -1L) ?: -1L
        ask = when {
            talk >= 0 -> Ask.TalkAbout(talk)
            open >= 0 -> Ask.OpenEntry(open)
            else -> ask
        }
        intent?.removeExtra(EXTRA_OPEN_ENTRY)
        intent?.removeExtra(EXTRA_TALK_ABOUT)
    }

    companion object {
        private const val EXTRA_OPEN_ENTRY = "com.villamediana.novelo.OPEN_ENTRY"
        private const val EXTRA_TALK_ABOUT = "com.villamediana.novelo.TALK_ABOUT"

        /** The logo on screen at least this long, so it is seen. */
        private const val SPLASH_MIN_MS = 700L

        /** Then it fades into the app. */
        private const val SPLASH_FADE_MS = 450L

        fun openEntry(context: Context, id: Long): Intent = Intent(context, NoveloActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_ENTRY, id)

        fun talkAbout(context: Context, id: Long): Intent = Intent(context, NoveloActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_TALK_ABOUT, id)
    }
}
