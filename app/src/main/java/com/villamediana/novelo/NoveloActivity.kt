package com.villamediana.novelo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.core.splashscreen.SplashScreenViewProvider
import com.villamediana.novelo.ui.home.Opening
import com.villamediana.novelo.ui.home.OpeningLayer
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
import com.villamediana.novelo.update.UpdateNotifier

/** What a notification asked the app to do when it opens. */
sealed interface Ask {
    data class OpenEntry(val id: Long) : Ask
    data class TalkAbout(val id: Long) : Ask
}

/** A FragmentActivity (the system's lock prompt, for what is sensitive, needs one); Compose all the same. */
class NoveloActivity : androidx.fragment.app.FragmentActivity() {
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
                    Box(Modifier.fillMaxSize(), propagateMinConstraints = true) {
                        // The first steps once (permissions, backup, who thinks), then the app.
                        if (settings.setupDone) NoveloRoot(ask = ask, onAskHandled = { ask = null })
                        else com.villamediana.novelo.ui.setup.SetupScreen(onDone = {})
                        // The splash's ball on its way to the button.
                        OpeningLayer()
                    }
                }
            }
            first = s
            // Light or dark and the splash follow what she picks.
            graph.settings.flow.map { it.theme to it.accent }.distinctUntilChanged().collect { Look.screen(this@NoveloActivity, graph.settings.current()) }
        }
    }

    /**
     * The opening's ball becomes the button: the app draws it where the splash had it and takes it
     * from there (see Opening). Without a ball or a button on screen (opened on an item), the
     * opening just fades.
     */
    private fun land(splash: SplashScreenViewProvider) {
        val view = splash.view
        val icon = runCatching { splash.iconView }.getOrNull()
        if (icon == null || OrbSpot.center == null) {
            view.animate().alpha(0f).setDuration(SPLASH_FADE_MS).setInterpolator(DecelerateInterpolator()).withEndAction { splash.remove() }.start()
            return
        }
        val at = IntArray(2).also { icon.getLocationInWindow(it) }
        // Before Android 13 the splash is always the terracotta one (Look); from 13 on, in her accent.
        val terracotta = Build.VERSION.SDK_INT < 33
        Opening.start(
            Opening.Run(
                from = Offset(at[0] + icon.width / 2f, at[1] + icon.height / 2f),
                paper = (view.background as? ColorDrawable)?.color?.let { Color(it) },
                ball = if (terracotta) Color(getColor(R.color.splash_vermilion)) else null,
                onBall = if (terracotta) Color(getColor(R.color.splash_vermilion_on)) else null,
                dropSplash = { splash.remove() },
            ),
        )
    }

    /** A touch during the opening ends it: the button is there at once, and the touch is its. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) Opening.end()
        return super.dispatchTouchEvent(ev)
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
        if (intent?.getBooleanExtra(UpdateNotifier.EXTRA_OPEN, false) == true) NoveloApplication.graph.updates.openSheet()
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

        /** Then it fades into the app (when it has no button to land on). */
        private const val SPLASH_FADE_MS = 450L

        fun openEntry(context: Context, id: Long): Intent = Intent(context, NoveloActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_ENTRY, id)

        fun talkAbout(context: Context, id: Long): Intent = Intent(context, NoveloActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_TALK_ABOUT, id)
    }
}
