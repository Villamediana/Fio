package com.villamediana.novelo.ui.settings

import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.foundation.layout.requiredSize

import com.villamediana.novelo.R
import com.villamediana.novelo.settings.t
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.settings.ThemeMode
import com.villamediana.novelo.ui.Nav
import com.villamediana.novelo.ui.components.NoveloIcons
import com.villamediana.novelo.ui.components.StitchColor
import com.villamediana.novelo.ui.components.paper
import com.villamediana.novelo.ui.components.stitched
import com.villamediana.novelo.ui.home.drawLooseEnd
import com.villamediana.novelo.ui.home.drawStitchRing
import com.villamediana.novelo.ui.home.drawYarn
import com.villamediana.novelo.ui.home.QuietIcon
import com.villamediana.novelo.ui.theme.NoveloAccents
import com.villamediana.novelo.ui.theme.NoveloColors
import kotlinx.coroutines.launch

/** "Tema" (opened in place): the main screen in small, as it will look; light, dark or the system's; and the thread's color. */
@Composable
fun ThemeSection() {
    val st = NoveloApplication.graph.settings
    val s by st.now.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth()) {
        // The main screen in small (its 220×400 drawn at 70 %): enough to see the choice, inline.
        Box(Modifier.size(154.dp, 280.dp).align(Alignment.CenterHorizontally), contentAlignment = Alignment.Center) {
            Preview(Modifier.requiredSize(220.dp, 400.dp).graphicsLayer(scaleX = 0.7f, scaleY = 0.7f))
        }
        Spacer(Modifier.height(22.dp))

        // Escuro / Claro / Sistema
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                Triple(ThemeMode.DARK, t(R.string.theme_dark), NoveloIcons.Moon),
                Triple(ThemeMode.LIGHT, t(R.string.theme_light), NoveloIcons.Sun),
                Triple(ThemeMode.SYSTEM, t(R.string.theme_system), NoveloIcons.Contrast),
            ).forEach { (mode, label, icon) ->
                ModeChip(label, icon, selected = s.theme == mode, modifier = Modifier.weight(1f)) { scope.launch { st.setTheme(mode) } }
            }
        }
        Spacer(Modifier.height(22.dp))

        // The thread's color.
        val chosen = NoveloAccents.byId(s.accent).id
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            NoveloAccents.all.forEach { a ->
                Swatch(if (NoveloColors.palette.dark) a.dark else a.light, selected = chosen == a.id, label = a.name) { scope.launch { st.setAccent(a.id) } }
            }
        }
    }
}

@Composable
private fun ModeChip(text: String, icon: ImageVector, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) NoveloColors.Accent.copy(alpha = 0.13f) else NoveloColors.Surface, label = "modeBg")
    val fg by animateColorAsState(if (selected) NoveloColors.Accent else NoveloColors.TextMuted, label = "modeFg")
    Row(
        modifier
            .height(50.dp)
            .clip(CircleShape)
            .background(bg)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, label: String, onClick: () -> Unit) {
    val ring by animateFloatAsState(if (selected) 1f else 0f, label = "ring")
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .border(2.5.dp, NoveloColors.Text.copy(alpha = ring), CircleShape)
            .padding(5.dp)
            .clip(CircleShape)
            .background(color)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(NoveloIcons.Check, label, tint = if (color.luminance() > 0.5f) Color(0xFF17171A) else Color.White, modifier = Modifier.size(18.dp).scale(ring))
    }
}

/** The main screen in small, drawn with the theme on screen: it changes as you choose. */
@Composable
private fun Preview(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(30.dp)
    val accent = NoveloColors.Accent
    Column(
        modifier
            .width(220.dp)
            .height(400.dp)
            .clip(shape)
            .paper()
            .border(3.dp, accent.copy(alpha = 0.4f), shape)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Icon(NoveloIcons.Tune, null, tint = NoveloColors.Text.copy(alpha = 0.55f), modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
            val onAccent = NoveloColors.OnAccent
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension * 0.30f
                drawStitchRing(center, r, accent, 1f)
                drawLooseEnd(center, r, accent)
                drawCircle(accent, radius = r)
                drawYarn(center, r, onAccent.copy(alpha = 0.24f))
                drawCircle(accent, radius = r * 0.36f)
            }
            Icon(NoveloIcons.Mic, null, tint = NoveloColors.OnAccent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(t(R.string.invite), style = com.villamediana.novelo.ui.theme.NoveloText.Invite.copy(fontSize = 12.sp, lineHeight = 16.sp), color = NoveloColors.TextMuted)
        Spacer(Modifier.weight(1.1f))
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(NoveloIcons.PhotoCamera, null, tint = NoveloColors.Text.copy(alpha = 0.62f), modifier = Modifier.size(15.dp))
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.clip(CircleShape).background(NoveloColors.Surface).stitched(CircleShape, StitchColor, inset = 3.dp, width = 1.dp, dash = 2.5.dp, gap = 2.dp).padding(start = 12.dp, end = 7.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(t(R.string.today), style = MaterialTheme.typography.titleLarge.copy(fontSize = 12.sp, lineHeight = 15.sp), color = NoveloColors.Text)
                Spacer(Modifier.width(5.dp))
                Box(Modifier.size(14.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
                    Text("2", style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, lineHeight = 10.sp), color = NoveloColors.OnAccent)
                }
            }
            Spacer(Modifier.weight(1f))
            Icon(NoveloIcons.Search, null, tint = NoveloColors.Text.copy(alpha = 0.62f), modifier = Modifier.size(15.dp))
        }
    }
}
