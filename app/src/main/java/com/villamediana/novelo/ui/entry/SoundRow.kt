package com.villamediana.novelo.ui.entry

import com.villamediana.novelo.R
import com.villamediana.novelo.settings.t
import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.audio.Wav
import com.villamediana.novelo.data.Capture
import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.ui.components.NoveloIcons
import com.villamediana.novelo.ui.components.IconAction
import com.villamediana.novelo.ui.theme.NoveloColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** The sounds kept with [entry] (a melody she sang, a sound): each one to play again. */
@Composable
internal fun EntrySounds(entry: Entry, modifier: Modifier = Modifier) {
    val lib = NoveloApplication.graph.library
    val sounds by remember(entry.id, entry.captureId) { lib.sounds(entry) }.collectAsStateWithLifecycle(initialValue = emptyList())
    if (sounds.isEmpty()) return
    Column(modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        sounds.forEach { SoundRow(it) }
    }
}

/** Play or pause, the sound drawn as bars (what has played in the accent) and the time. */
@Composable
internal fun SoundRow(c: Capture, modifier: Modifier = Modifier) {
    val path = c.audioPath ?: return
    val levels by produceState(FloatArray(0), path) {
        value = withContext(Dispatchers.IO) { runCatching { Wav.levels(File(path), BARS) }.getOrDefault(FloatArray(0)) }
    }
    var player by remember(path) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(path) { mutableStateOf(false) }
    var played by remember(path) { mutableFloatStateOf(0f) }
    val length = c.durationMs.coerceAtLeast(1)
    DisposableEffect(path) { onDispose { player?.release(); player = null } }
    // Only while it plays: nothing runs for a sound standing still.
    LaunchedEffect(playing) {
        while (playing) {
            player?.let { p -> runCatching { played = p.currentPosition / p.duration.coerceAtLeast(1).toFloat() } }
            delay(80)
        }
    }
    fun toggle() {
        val p = player ?: runCatching {
            MediaPlayer().apply {
                setDataSource(path)
                prepare()
                setOnCompletionListener { playing = false; played = 0f }
            }
        }.getOrNull()?.also { player = it } ?: return
        if (playing) { p.pause(); playing = false } else { p.start(); playing = true }
    }

    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(NoveloColors.Surface2).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(if (playing) NoveloIcons.Pause else NoveloIcons.Play, if (playing) t(R.string.sound_pause) else t(R.string.sound_play), { toggle() }, primary = true, size = 40.dp)
        val accent = NoveloColors.Accent
        val rest = NoveloColors.TextFaint
        Canvas(Modifier.weight(1f).height(32.dp).padding(horizontal = 12.dp)) {
            if (levels.isEmpty()) return@Canvas
            val gap = 2.dp.toPx()
            val w = ((size.width - gap * (levels.size - 1)) / levels.size).coerceAtLeast(1f)
            levels.forEachIndexed { i, l ->
                val h = (size.height * (0.12f + 0.88f * l)).coerceAtLeast(w)
                val x = i * (w + gap)
                drawRoundRect(
                    if ((i + 0.5f) / levels.size <= played) accent else rest,
                    topLeft = Offset(x, (size.height - h) / 2f),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(w / 2f),
                )
            }
        }
        Text(
            clock(if (playing || played > 0f) (played * length).toLong() else length),
            style = MaterialTheme.typography.labelMedium,
            color = NoveloColors.TextMuted,
            modifier = Modifier.width(40.dp).padding(end = 4.dp),
        )
    }
}

private const val BARS = 40

/** 0:23, 1:05. */
internal fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}
