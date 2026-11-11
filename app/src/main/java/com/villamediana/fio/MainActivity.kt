package com.villamediana.fio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.fio.ui.FioRoot
import com.villamediana.fio.ui.theme.FioTheme
import com.villamediana.fio.update.UpdateNotifier

/** What a notification asked the app to do when it opens. */
sealed interface Ask {
    data class OpenEntry(val id: Long) : Ask
    data class TalkAbout(val id: Long) : Ask
}

class MainActivity : ComponentActivity() {
    private var ask by mutableStateOf<Ask?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        read(intent)
        setContent {
            val settings by FioApplication.graph.settings.now.collectAsStateWithLifecycle()
            FioTheme(mode = settings.theme, accent = settings.accent) {
                FioRoot(ask = ask, onAskHandled = { ask = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        read(intent)
    }

    private fun read(intent: Intent?) {
        if (intent?.getBooleanExtra(UpdateNotifier.EXTRA_OPEN, false) == true) FioApplication.graph.updates.openSheet()
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
        private const val EXTRA_OPEN_ENTRY = "com.villamediana.fio.OPEN_ENTRY"
        private const val EXTRA_TALK_ABOUT = "com.villamediana.fio.TALK_ABOUT"

        fun openEntry(context: Context, id: Long): Intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_ENTRY, id)

        fun talkAbout(context: Context, id: Long): Intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_TALK_ABOUT, id)
    }
}
