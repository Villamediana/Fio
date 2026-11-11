package com.villamediana.fio.ui.settings

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
import com.villamediana.fio.FioApplication
import com.villamediana.fio.settings.ThemeMode
import com.villamediana.fio.ui.Nav
import com.villamediana.fio.ui.components.FioIcons
import com.villamediana.fio.ui.home.QuietIcon
import com.villamediana.fio.ui.theme.FioAccents
import com.villamediana.fio.ui.theme.FioColors
import kotlinx.coroutines.launch

/** "Tema": the main screen in small, as it will look; light, dark or the system's; and the thread's color. */
@Composable
fun ThemeScreen(nav: Nav) {
    val st = FioApplication.graph.settings
    val s by st.now.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(FioColors.Background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            QuietIcon(FioIcons.Back, "Voltar", { nav.back() }, alpha = 0.8f)
            Text("Tema", style = MaterialTheme.typography.headlineMedium, color = FioColors.Text, modifier = Modifier.padding(start = 6.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Spacer(Modifier.height(14.dp))
            Preview(Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(30.dp))

            // Escuro / Claro / Sistema
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    Triple(ThemeMode.DARK, "Escuro", FioIcons.Moon),
                    Triple(ThemeMode.LIGHT, "Claro", FioIcons.Sun),
                    Triple(ThemeMode.SYSTEM, "Sistema", FioIcons.Contrast),
                ).forEach { (mode, label, icon) ->
                    ModeChip(label, icon, selected = s.theme == mode, modifier = Modifier.weight(1f)) { scope.launch { st.setTheme(mode) } }
                }
            }
            Spacer(Modifier.height(26.dp))

            // The thread's color.
            val chosen = FioAccents.byId(s.accent).id
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                FioAccents.all.forEach { a ->
                    Swatch(if (FioColors.palette.dark) a.dark else a.light, selected = chosen == a.id, label = a.name) { scope.launch { st.setAccent(a.id) } }
                }
            }
        }
    }
}

@Composable
private fun ModeChip(text: String, icon: ImageVector, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) FioColors.Accent.copy(alpha = 0.13f) else FioColors.Surface, label = "modeBg")
    val fg by animateColorAsState(if (selected) FioColors.Accent else FioColors.TextMuted, label = "modeFg")
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
            .border(2.5.dp, FioColors.Text.copy(alpha = ring), CircleShape)
            .padding(5.dp)
            .clip(CircleShape)
            .background(color)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(FioIcons.Check, label, tint = if (color.luminance() > 0.5f) Color(0xFF17171A) else Color.White, modifier = Modifier.size(18.dp).scale(ring))
    }
}

/** The main screen in small, drawn with the theme on screen: it changes as you choose. */
@Composable
private fun Preview(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(30.dp)
    val accent = FioColors.Accent
    Column(
        modifier
            .width(220.dp)
            .height(400.dp)
            .clip(shape)
            .background(FioColors.Background)
            .border(3.dp, accent.copy(alpha = 0.4f), shape)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Icon(FioIcons.Tune, null, tint = FioColors.Text.copy(alpha = 0.55f), modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension * 0.30f
                drawCircle(accent.copy(alpha = 0.32f), radius = r * 1.26f, style = Stroke(1.dp.toPx()))
                drawCircle(accent, radius = r)
            }
            Icon(FioIcons.Mic, null, tint = FioColors.OnAccent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text("O que está na sua cabeça?", style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp), color = FioColors.TextFaint)
        Spacer(Modifier.weight(1.1f))
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(FioIcons.PhotoCamera, null, tint = FioColors.Text.copy(alpha = 0.62f), modifier = Modifier.size(15.dp))
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.clip(CircleShape).background(FioColors.Surface).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Hoje", style = MaterialTheme.typography.labelMedium, color = FioColors.Text)
                Spacer(Modifier.width(5.dp))
                Text("2", style = MaterialTheme.typography.labelMedium, color = accent)
            }
            Spacer(Modifier.weight(1f))
            Icon(FioIcons.Search, null, tint = FioColors.Text.copy(alpha = 0.62f), modifier = Modifier.size(15.dp))
        }
    }
}
