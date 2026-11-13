package com.villamediana.novelo.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.villamediana.novelo.R
import com.villamediana.novelo.settings.ThemeMode
import com.villamediana.novelo.ui.home.HomeScreen
import com.villamediana.novelo.ui.home.Opening
import com.villamediana.novelo.ui.home.OpeningFrame
import com.villamediana.novelo.ui.home.OpeningTime
import com.villamediana.novelo.ui.home.Orb
import com.villamediana.novelo.ui.home.OrbMode
import com.villamediana.novelo.ui.home.OrbSpot
import com.villamediana.novelo.ui.home.orbSpot
import com.villamediana.novelo.ui.theme.NoveloColors
import com.villamediana.novelo.ui.theme.NoveloTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/** The opening starts as the splash's logo and ends as the button, with nothing jumping at either end. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OpeningTest {
    @get:Rule val compose = createComposeRule()

    @After fun clear() { Opening.end() }

    private fun capture(): Bitmap {
        compose.waitForIdle()
        return compose.onRoot().captureToImage().asAndroidBitmap()
    }

    private fun save(b: Bitmap, name: String) {
        val dir = File("build/novelo-shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Pixels that differ by more than [by] in some channel, as a share of all of them. */
    private fun differ(a: Bitmap, b: Bitmap, by: Int = 48): Double {
        var n = 0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val p = a.getPixel(x, y)
            val q = b.getPixel(x, y)
            val d = maxOf(abs((p shr 16 and 255) - (q shr 16 and 255)), abs((p shr 8 and 255) - (q shr 8 and 255)), abs((p and 255) - (q and 255)))
            if (d > by) n++
        }
        return n.toDouble() / (a.width * a.height)
    }

    @Test
    fun theOpeningsLastFrameIsTheButton() {
        val level = mutableFloatStateOf(0f)
        compose.setContent {
            NoveloTheme(ThemeMode.LIGHT) {
                Box(Modifier.fillMaxSize().background(NoveloColors.Background)) {
                    Box(Modifier.align(Alignment.Center)) {
                        Orb(mode = OrbMode.IDLE, level = level, onStart = { false }, onLock = {}, onStop = {}, onCancel = {}, modifier = Modifier.orbSpot())
                    }
                    Opening.run?.let { OpeningFrame(it) { OpeningTime.END } }
                }
            }
        }
        val button = capture()
        val spot = OrbSpot.center!!
        compose.runOnIdle { Opening.start(Opening.Run(from = Offset(spot.x, spot.y + 180f))) }
        val opening = capture()
        save(button, "opening-end-button")
        save(opening, "opening-end-frame")
        val share = differ(button, opening)
        println("last frame vs button: ${"%.5f".format(share * 100)}% of pixels differ")
        assertTrue("the last frame should be the button ($share)", share < 0.00001)
    }

    @Test
    fun theOpeningsFirstFrameIsTheSplash() {
        val center = mutableStateOf<Offset?>(null)
        val showSplash = mutableStateOf(true)
        compose.setContent {
            NoveloTheme(ThemeMode.LIGHT) {
                Box(Modifier.fillMaxSize().background(colorResource(R.color.window_bg))) {
                    if (showSplash.value) {
                        Image(
                            painterResource(R.drawable.novelo_splash_vermilion), null,
                            Modifier.size(288.dp).align(Alignment.Center).onGloballyPositioned { center.value = it.boundsInWindow().center },
                        )
                    }
                    Opening.run?.let { OpeningFrame(it) { 0f } }
                }
            }
        }
        val splash = capture()
        val at = center.value!!
        compose.runOnIdle {
            OrbSpot.center = Offset(at.x, at.y - 120f)
            OrbSpot.radius = 200f
            showSplash.value = false
            Opening.start(Opening.Run(from = at))
        }
        val opening = capture()
        save(splash, "opening-start-splash")
        save(opening, "opening-start-frame")
        val share = differ(splash, opening)
        println("first frame vs splash: ${"%.5f".format(share * 100)}% of pixels differ")
        assertTrue("the first frame should be the splash ($share)", share < 0.00001)
    }

    @Test
    fun theOpeningOverTheHome() {
        val t = mutableFloatStateOf(0f)
        val theme = mutableStateOf(ThemeMode.LIGHT)
        val nav = Nav()
        compose.setContent {
            NoveloTheme(theme.value) {
                Box(Modifier.fillMaxSize()) {
                    HomeScreen(nav, talkAbout = null, onTalkHandled = {})
                    Opening.run?.let { OpeningFrame(it) { t.floatValue } }
                }
            }
        }
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { mode ->
            compose.runOnIdle { theme.value = mode }
            compose.waitForIdle()
            val root = compose.onRoot().fetchSemanticsNode().size
            compose.runOnIdle { Opening.start(Opening.Run(from = Offset(root.width / 2f, root.height / 2f))) }
            var last: Bitmap? = null
            listOf(0, 160, 320, 480, 640, 800, 1000, 1200, 1460).forEach { ms ->
                compose.runOnIdle { t.floatValue = ms.toFloat() }
                last = capture().also { save(it, "opening-${mode.name.lowercase()}-%04d".format(ms)) }
            }
            compose.runOnIdle { Opening.end() }
            val home = capture().also { save(it, "opening-${mode.name.lowercase()}-done") }
            // The button takes over from the opening's last frame without a pixel moving.
            val share = differ(last!!, home)
            assertTrue("$mode: the home after the opening should be its last frame ($share)", share < 0.00001)
        }
    }
}
