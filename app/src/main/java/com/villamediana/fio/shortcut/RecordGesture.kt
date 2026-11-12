package com.villamediana.fio.shortcut

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.villamediana.fio.FioActivity
import com.villamediana.fio.FioApplication
import com.villamediana.fio.mind.MindState

/**
 * "Novelo · Gravar": what the phone's own gesture opens (the side key pressed twice on a Samsung, a
 * double tap on the back of a Pixel and others — they open an app, and this is one). It starts
 * recording at once, locked or not, with a buzz; the same gesture again stops it (two buzzes) and
 * what was said goes through as always. Nothing shows: the phone stays where it was.
 *
 * Off until she turns it on in Ajustes ([Gesture]): only then is it among the apps to choose.
 */
class RecordGesture : ComponentActivity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
    }

    override fun onResume() {
        super.onResume()
        if (done) return
        done = true
        // Without the microphone allowed, the app itself (its first steps ask for it).
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(this, FioActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        val mind = FioApplication.graph.mind
        when {
            mind.state.value is MindState.Listening -> { mind.stopListening(); Buzz.stop(this) }
            mind.startListening(locked = true) -> Buzz.start(this)
            else -> Buzz.fail(this)
        }
        // A moment on screen (invisible): the microphone's service starts while the app is in front,
        // which Android asks of it; then back to wherever she was.
        window.decorView.postDelayed({ finish() }, 450)
    }
}

/** The gesture's entry, on or off (an app among the phone's to choose only while it is on). */
object Gesture {
    private fun component(context: Context) = ComponentName(context, RecordGesture::class.java)

    fun on(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(component(context)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun set(context: Context, on: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            component(context),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }
}

/** What she feels in the hand: one firm tap when it starts listening, two when it stops, a long one when it can't. */
object Buzz {
    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)

    fun start(context: Context) = play(context, VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))

    fun stop(context: Context) = play(context, VibrationEffect.createWaveform(longArrayOf(0, 45, 110, 45), -1))

    fun fail(context: Context) = play(context, VibrationEffect.createOneShot(320, VibrationEffect.DEFAULT_AMPLITUDE))

    private fun play(context: Context, effect: VibrationEffect) {
        runCatching { vibrator(context)?.takeIf { it.hasVibrator() }?.vibrate(effect) }
    }
}
