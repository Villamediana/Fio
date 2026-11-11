package com.villamediana.fio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.villamediana.fio.Ask
import com.villamediana.fio.FioApplication
import com.villamediana.fio.ui.camera.CameraScreen
import com.villamediana.fio.ui.entry.EntryScreen
import com.villamediana.fio.ui.graph.GraphScreen
import com.villamediana.fio.ui.home.HomeScreen
import com.villamediana.fio.ui.library.LibraryScreen
import com.villamediana.fio.ui.settings.SettingsScreen
import com.villamediana.fio.ui.theme.FioColors
import com.villamediana.fio.ui.theme.Motion
import com.villamediana.fio.ui.update.UpdateHost
import kotlinx.coroutines.delay

@Composable
fun FioRoot(ask: Ask?, onAskHandled: () -> Unit) {
    val nav = remember { Nav() }
    val graph = FioApplication.graph
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // On screen: what didn't go through yet goes again; the on-device model is let go when idle.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            graph.mind.retryPending()
            while (true) {
                delay(60_000)
                graph.engine.releaseIfIdle()
            }
        }
    }

    var talk: Long? = null
    when (ask) {
        is Ask.OpenEntry -> LaunchedEffect(ask) { nav.home(); nav.go(Screen.Entry(ask.id)); onAskHandled() }
        is Ask.TalkAbout -> { LaunchedEffect(ask) { nav.home() }; talk = ask.id }
        null -> Unit
    }

    BackHandler(enabled = nav.stack.size > 1) { nav.back() }

    Box(Modifier.fillMaxSize().background(FioColors.Background)) {
        AnimatedContent(
            targetState = nav.top,
            transitionSpec = {
                val forward = nav.stack.contains(initialState) && initialState != targetState
                when {
                    targetState is Screen.Camera -> (fadeIn(Motion.enter(260)) + scaleIn(Motion.enter(320), 0.96f)) togetherWith fadeOut(Motion.exit(200))
                    targetState is Screen.Library && initialState == Screen.Home ->
                        (slideInVertically(Motion.slide) { it / 5 } + fadeIn(Motion.enter(260))) togetherWith fadeOut(Motion.exit(180))
                    initialState is Screen.Library && targetState == Screen.Home ->
                        fadeIn(Motion.enter(260)) togetherWith (slideOutVertically(Motion.slide) { it / 5 } + fadeOut(Motion.exit(200)))
                    initialState is Screen.Library && targetState is Screen.Library -> fadeIn(Motion.enter(200)) togetherWith fadeOut(Motion.exit(120))
                    forward -> (slideInHorizontally(Motion.slide) { it / 4 } + fadeIn(Motion.enter(240))) togetherWith fadeOut(Motion.exit(160))
                    else -> fadeIn(Motion.enter(240)) togetherWith (slideOutHorizontally(Motion.slide) { it / 4 } + fadeOut(Motion.exit(180)))
                }
            },
            contentKey = { s -> if (s is Screen.Library) "library" else s },
            label = "screens",
        ) { screen ->
            when (screen) {
                Screen.Home -> HomeScreen(nav, talkAbout = talk, onTalkHandled = onAskHandled)
                is Screen.Library -> LibraryScreen(nav, screen)
                is Screen.Entry -> EntryScreen(nav, screen.id)
                Screen.Settings -> SettingsScreen(nav)
                Screen.Camera -> CameraScreen(nav)
                is Screen.Graph -> GraphScreen(nav, screen.topicId)
            }
        }
        UpdateHost()
    }
}
