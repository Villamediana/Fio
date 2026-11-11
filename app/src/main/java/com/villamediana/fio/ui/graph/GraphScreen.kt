package com.villamediana.fio.ui.graph

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.fio.FioApplication
import com.villamediana.fio.data.Entry
import com.villamediana.fio.data.TopicCount
import com.villamediana.fio.data.TopicPair
import com.villamediana.fio.ui.Nav
import com.villamediana.fio.ui.Screen
import com.villamediana.fio.ui.components.EntryCard
import com.villamediana.fio.ui.home.QuietIcon
import com.villamediana.fio.ui.theme.FioColors
import com.villamediana.fio.ui.theme.Outfit
import com.villamediana.fio.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/** The knots of what was said — people, projects, places, subjects — and the threads between them. */
@Composable
fun GraphScreen(nav: Nav, startTopic: Long?) {
    val lib = FioApplication.graph.library
    val topics by lib.topTopics(48).collectAsStateWithLifecycle(initialValue = emptyList())
    val pairs by lib.topicPairs().collectAsStateWithLifecycle(initialValue = emptyList())
    val layout by produceState<Map<Long, Offset>>(emptyMap(), topics, pairs) {
        value = withContext(Dispatchers.Default) { Force.layout(topics, pairs) }
    }
    var selected by remember { mutableStateOf(startTopic) }
    val entries by produceState(emptyList<Entry>(), selected) { value = selected?.let { lib.entriesOfTopic(it).sortedByDescending { e -> e.createdAt } }.orEmpty() }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(layout.isNotEmpty()) { if (layout.isNotEmpty()) appear.animateTo(1f, tween(700)) }
    val measurer = rememberTextMeasurer()
    val palette = FioColors.palette

    Box(Modifier.fillMaxSize().background(FioColors.Background)) {
        if (topics.isEmpty()) {
            Text(
                "As conexões aparecem conforme você fala de pessoas, lugares e projetos.",
                style = MaterialTheme.typography.titleMedium,
                color = FioColors.TextMuted,
                modifier = Modifier.align(Alignment.Center).padding(40.dp),
            )
        }
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, p, z, _ ->
                        scale = (scale * z).coerceIn(0.5f, 3f)
                        pan += p
                    }
                }
                .pointerInput(layout, scale, pan) {
                    detectTapGestures { tap ->
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val unit = minOf(size.width, size.height) * 0.42f
                        val hit = topics.minByOrNull { t ->
                            val p = layout[t.id] ?: return@minByOrNull Float.MAX_VALUE
                            (c + pan + p * unit * scale - tap).getDistance()
                        }
                        val p = hit?.let { layout[it.id] }
                        selected = if (p != null && (c + pan + p * unit * scale - tap).getDistance() < 48.dp.toPx()) hit.id else null
                    }
                },
        ) {
            val c = Offset(size.width / 2f, size.height / 2f) + pan
            val unit = minOf(size.width, size.height) * 0.42f * scale
            val a = appear.value
            val maxW = (pairs.maxOfOrNull { it.weight } ?: 1).toFloat()
            pairs.forEach { e ->
                val p = layout[e.a] ?: return@forEach
                val q = layout[e.b] ?: return@forEach
                val lit = selected == null || selected == e.a || selected == e.b
                drawLine(
                    palette.text.copy(alpha = a * (if (lit) 0.10f + 0.30f * (e.weight / maxW) else 0.04f)),
                    c + p * unit, c + q * unit,
                    strokeWidth = (1f + 2f * (e.weight / maxW)).dp.toPx(),
                )
            }
            val maxC = (topics.maxOfOrNull { it.count } ?: 1).toFloat()
            topics.forEach { t ->
                val p = layout[t.id] ?: return@forEach
                val near = selected == null || selected == t.id || pairs.any { (it.a == selected && it.b == t.id) || (it.b == selected && it.a == t.id) }
                val r = (6f + 14f * sqrt(t.count / maxC)).dp.toPx() * (0.6f + 0.4f * a)
                val color = typeColor(t.type, palette)
                drawCircle(color.copy(alpha = if (near) 1f else 0.25f), radius = r, center = c + p * unit)
                if (selected == t.id) drawCircle(color.copy(alpha = 0.25f), radius = r * 1.8f, center = c + p * unit)
                label(measurer, t.name, c + p * unit + Offset(0f, r + 4.dp.toPx()), palette.text.copy(alpha = a * (if (near) 0.9f else 0.3f)))
            }
        }

        Row(Modifier.statusBarsPadding().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            QuietIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar", { nav.back() }, alpha = 0.8f)
            Text("Conexões", style = MaterialTheme.typography.headlineMedium, color = FioColors.Text, modifier = Modifier.padding(start = 6.dp))
        }

        AnimatedVisibility(
            visible = selected != null && entries.isNotEmpty(),
            enter = slideInVertically { it / 2 } + fadeIn(),
            exit = slideOutVertically { it / 2 } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(FioColors.Surface2)
                    .navigationBarsPadding()
                    .padding(top = 18.dp),
            ) {
                val name = topics.firstOrNull { it.id == selected }?.name.orEmpty()
                Text(name, style = MaterialTheme.typography.titleLarge, color = FioColors.Text, modifier = Modifier.padding(horizontal = 22.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp).padding(top = 10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    items(entries, key = { it.id }) { e ->
                        Box(Modifier.padding(bottom = 8.dp)) { EntryCard(e, onClick = { nav.go(Screen.Entry(e.id)) }) }
                    }
                }
            }
        }
    }
}

private fun typeColor(type: String, p: Palette): Color = when (type) {
    "person" -> p.diary
    "project" -> p.task
    "place" -> p.note
    "org" -> p.textMuted
    else -> p.text
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.label(m: TextMeasurer, text: String, at: Offset, color: Color) {
    val layout = m.measure(text.take(22), TextStyle(fontFamily = Outfit, fontSize = 12.sp, color = color))
    drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y))
}

/** A small force layout: ties pull, everything pushes a little, all inside the unit circle. */
object Force {
    fun layout(topics: List<TopicCount>, pairs: List<TopicPair>, iterations: Int = 400): Map<Long, Offset> {
        if (topics.isEmpty()) return emptyMap()
        val ids = topics.map { it.id }
        val index = ids.withIndex().associate { it.value to it.index }
        val n = ids.size
        val x = FloatArray(n) { i -> 0.7f * kotlin.math.cos(i * 2.399963f) * sqrt((i + 0.5f) / n) }
        val y = FloatArray(n) { i -> 0.7f * kotlin.math.sin(i * 2.399963f) * sqrt((i + 0.5f) / n) }
        val edges = pairs.mapNotNull { e -> val a = index[e.a]; val b = index[e.b]; if (a != null && b != null) Triple(a, b, e.weight) else null }
        val k = 0.9f / sqrt(n.toFloat())
        repeat(iterations) { step ->
            val t = 0.08f * (1f - step / iterations.toFloat()) + 0.004f
            val dx = FloatArray(n)
            val dy = FloatArray(n)
            for (i in 0 until n) for (j in i + 1 until n) {
                val ex = x[i] - x[j]
                val ey = y[i] - y[j]
                val d2 = (ex * ex + ey * ey).coerceAtLeast(0.0004f)
                val f = k * k / d2
                dx[i] += ex * f; dy[i] += ey * f
                dx[j] -= ex * f; dy[j] -= ey * f
            }
            edges.forEach { (a, b, w) ->
                val ex = x[a] - x[b]
                val ey = y[a] - y[b]
                val d = sqrt(ex * ex + ey * ey).coerceAtLeast(0.01f)
                val f = d / k * (0.6f + 0.4f * w.coerceAtMost(5)) * 0.45f
                dx[a] -= ex / d * f * 0.1f; dy[a] -= ey / d * f * 0.1f
                dx[b] += ex / d * f * 0.1f; dy[b] += ey / d * f * 0.1f
            }
            for (i in 0 until n) {
                // a gentle pull to the middle keeps loose knots on screen
                dx[i] -= x[i] * 0.05f; dy[i] -= y[i] * 0.05f
                val len = sqrt(dx[i] * dx[i] + dy[i] * dy[i]).coerceAtLeast(1e-6f)
                val move = minOf(len, t)
                x[i] += dx[i] / len * move
                y[i] += dy[i] / len * move
            }
        }
        val maxR = (0 until n).maxOf { sqrt(x[it] * x[it] + y[it] * y[it]) }.coerceAtLeast(0.01f)
        return ids.withIndex().associate { (i, id) -> id to Offset(x[i] / maxR, y[i] / maxR) }
    }
}
