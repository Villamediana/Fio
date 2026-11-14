package com.villamediana.novelo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.villamediana.novelo.Ask
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.ui.camera.CameraScreen
import com.villamediana.novelo.ui.entry.EntryScreen
import com.villamediana.novelo.ui.home.HomeScreen
import com.villamediana.novelo.ui.library.LibraryScreen
import com.villamediana.novelo.ui.settings.SettingsScreen
import com.villamediana.novelo.ui.theme.NoveloColors
import com.villamediana.novelo.ui.theme.Motion
import com.villamediana.novelo.ui.update.UpdateHost
import kotlinx.coroutines.delay

@Composable
fun NoveloRoot(ask: Ask?, onAskHandled: () -> Unit) {
    val nav = remember { Nav() }
    val graph = NoveloApplication.graph
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // On screen: what didn't go through yet goes again; the on-device model is let go when idle.
    // The app started: the open tasks with no category go into one (once; quietly when it can't).
    LaunchedEffect(Unit) { graph.organizer.onStart() }
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
        Ask.OpenMeds -> LaunchedEffect(ask) { nav.home(); nav.go(Screen.Library(Filter.MEDS)); onAskHandled() }
        null -> Unit
    }

    BackHandler(enabled = nav.stack.size > 1) { nav.back() }
    val library = rememberLibraryKept(nav)

    Box(Modifier.fillMaxSize().background(NoveloColors.Background)) {
        AnimatedContent(
            targetState = nav.top,
            transitionSpec = {
                val forward = nav.stack.contains(initialState) && initialState != targetState
                // One line, left to right: camera · main screen · library. Going along it slides that way.
                when {
                    targetState is Screen.Camera && initialState == Screen.Home ->
                        (slideInHorizontally(Motion.slide) { -it / 3 } + fadeIn(Motion.enter(240))) togetherWith (slideOutHorizontally(Motion.slide) { it / 6 } + fadeOut(Motion.exit(180)))
                    initialState is Screen.Camera && targetState == Screen.Home ->
                        (slideInHorizontally(Motion.slide) { it / 6 } + fadeIn(Motion.enter(240))) togetherWith (slideOutHorizontally(Motion.slide) { -it / 3 } + fadeOut(Motion.exit(180)))
                    targetState is Screen.Library && initialState == Screen.Home ->
                        (slideInHorizontally(Motion.slide) { it / 3 } + fadeIn(Motion.enter(240))) togetherWith (slideOutHorizontally(Motion.slide) { -it / 6 } + fadeOut(Motion.exit(180)))
                    initialState is Screen.Library && targetState == Screen.Home ->
                        (slideInHorizontally(Motion.slide) { -it / 6 } + fadeIn(Motion.enter(240))) togetherWith (slideOutHorizontally(Motion.slide) { it / 3 } + fadeOut(Motion.exit(180)))
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
                is Screen.Library -> library.SaveableStateProvider(LIBRARY_KEPT) { LibraryScreen(nav, screen) }
                is Screen.Entry -> EntryScreen(nav, screen.id)
                is Screen.Med -> com.villamediana.novelo.ui.meds.MedScreen(nav, screen.id)
                is Screen.Settings -> SettingsScreen(nav, openAi = screen.ai)
                Screen.Camera -> CameraScreen(nav)
            }
        }
        UpdateHost()
    }
}

/**
 * The library keeps where it was (each tab's scroll, the search) while an entry is open over it —
 * back from the entry, the list is where she left it. Out of the stack (back home), it starts over.
 */
@Composable
internal fun rememberLibraryKept(nav: Nav): SaveableStateHolder {
    val kept = rememberSaveableStateHolder()
    val inStack = nav.stack.any { it is Screen.Library }
    LaunchedEffect(inStack) { if (!inStack) kept.removeState(LIBRARY_KEPT) }
    return kept
}

internal const val LIBRARY_KEPT = "library"
