package com.villamediana.novelo.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.villamediana.novelo.R
import com.villamediana.novelo.local.LocalModel
import com.villamediana.novelo.settings.Job
import com.villamediana.novelo.settings.NoveloSettings
import com.villamediana.novelo.settings.Source
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.ui.components.NoveloIcons
import com.villamediana.novelo.ui.components.Pill
import com.villamediana.novelo.ui.components.StitchColor
import com.villamediana.novelo.ui.components.stitched
import com.villamediana.novelo.ui.setup.LocalDownload
import com.villamediana.novelo.ui.theme.NoveloColors
import com.villamediana.novelo.ui.theme.NoveloText

/**
 * What still has to be there for Novelo to hear, see and think (she set it up, or it is still to come).
 * [locks]: without it nothing can be said (who sees only lacks for photos and documents).
 */
enum class Missing(val locks: Boolean = true) { EARS, HEAR_API, THINK_API, SEE(locks = false) }

/**
 * What is missing for what she chose: the ears on the phone when the Novelo hears (a build that
 * can't have them, only for tests, doesn't count), her API that hears, her API that thinks.
 */
fun missing(s: NoveloSettings, ears: LocalModel.State, engine: Boolean): List<Missing> = buildList {
    when (s.hear) {
        Source.NOVELO -> if (engine && ears != LocalModel.State.Ready) add(Missing.EARS)
        Source.API -> s.api(Job.HEAR).let { if (!it.ready || it.model.isBlank()) add(Missing.HEAR_API) }
    }
    if (s.think == Source.API) s.api(Job.THINK).let { if (!it.ready || it.model.isBlank()) add(Missing.THINK_API) }
    // Who sees: the model on the phone (when it isn't already asked for as the ears), or her API.
    when (s.see) {
        Source.NOVELO -> if (engine && ears != LocalModel.State.Ready && Missing.EARS !in this) add(Missing.SEE)
        Source.API -> s.api(Job.SEE).let { if (!it.ready || it.model.isBlank()) add(Missing.SEE) }
    }
}

/**
 * Under the button while something is missing: what it is, and the way to it right here — the ears
 * downloaded here (or her API instead, in Ajustes), her API set up in Ajustes. A tap on the locked
 * button ([nudge] goes up) shakes it a little, so the eye goes there.
 */
@Composable
internal fun ReadyCard(missing: List<Missing>, nudge: Int, onSettings: () -> Unit) {
    val density = LocalDensity.current
    val shake = remember { Animatable(0f) }
    LaunchedEffect(nudge) {
        if (nudge == 0) return@LaunchedEffect
        shake.snapTo(0f)
        shake.animateTo(0f, keyframes {
            durationMillis = 420
            -9f at 60
            9f at 140
            -6f at 220
            5f at 300
            -2f at 370
        })
    }
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .graphicsLayer { translationX = with(density) { shake.value.dp.toPx() } }
            .clip(shape)
            .background(NoveloColors.Surface)
            .stitched(shape, StitchColor)
            .padding(horizontal = 20.dp, vertical = 18.dp)
            .animateContentSize()
            .testTag("ready-card"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The lock only when the button is locked.
            if (missing.any { it.locks }) {
                Icon(NoveloIcons.Lock, null, tint = NoveloColors.Accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(7.dp))
            }
            Text(t(R.string.ready_title), style = NoveloText.Invite, color = NoveloColors.Text, textAlign = TextAlign.Center)
        }
        missing.forEach { m ->
            Spacer(Modifier.height(12.dp))
            when (m) {
                Missing.EARS -> {
                    Text(t(R.string.ready_ears), style = MaterialTheme.typography.bodyMedium, color = NoveloColors.TextMuted, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    // Right here: Baixar, the progress, Wi-Fi or mobile data — as in the first steps.
                    LocalDownload(centered = true)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        t(R.string.ready_or_api),
                        style = MaterialTheme.typography.labelLarge,
                        color = NoveloColors.TextFaint,
                        modifier = Modifier.clip(CircleShape).clickable(onClick = onSettings).padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
                Missing.HEAR_API, Missing.THINK_API, Missing.SEE -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Text(
                        t(when (m) { Missing.HEAR_API -> R.string.ready_hear_api; Missing.SEE -> R.string.ready_see; else -> R.string.ready_think_api }),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NoveloColors.TextMuted,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(10.dp))
                    Pill(t(R.string.ready_set_up), selected = true, onClick = onSettings)
                }
            }
        }
    }
}
