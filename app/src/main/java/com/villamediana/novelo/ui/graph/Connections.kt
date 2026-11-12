package com.villamediana.novelo.ui.graph

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.data.TopicCount
import com.villamediana.novelo.data.TopicPair
import com.villamediana.novelo.ui.components.EntryCard
import com.villamediana.novelo.ui.components.GroupGap
import com.villamediana.novelo.ui.components.groupShape
import com.villamediana.novelo.ui.theme.NoveloColors
import com.villamediana.novelo.ui.theme.Motion
import com.villamediana.novelo.ui.theme.Nunito
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * The knots of what was said — people, projects, places, subjects — and the threads between them.
 * It is one of the library's tabs: it fills the space under them, and a touched knot lifts its
 * entries in a card at the bottom.
 */
@Composable
fun Connections(startTopic: Long?, onOpen: (Long) -> Unit, empty: @Composable () -> Unit, modifier: Modifier = Modifier) {
    val lib = NoveloApplication.graph.library
    val topics by lib.topTopics(48).collectAsStateWithLifecycle(initialValue = null)
    val pairs by lib.topicPairs().collectAsStateWithLifecycle(initialValue = emptyList())
    val known = topics.orEmpty()
    val layout by produceState<Map<Long, Offset>>(emptyMap(), known, pairs) {
        value = withContext(Dispatchers.Default) { Force.layout(known, pairs) }
    }
    var selected by remember(startTopic) { mutableStateOf(startTopic) }
    val entries by produceState(emptyList<Entry>(), selected) { value = selected?.let { lib.entriesOfTopic(it).sortedByDescending { e -> e.createdAt } }.orEmpty() }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(layout.isNotEmpty()) { if (layout.isNotEmpty()) appear.animateTo(1f, tween(700)) }
    val measurer = rememberTextMeasurer()
    val palette = NoveloColors.palette

    Box(modifier.fillMaxSize()) {
        if (topics?.isEmpty() == true) empty()
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
                        val unit = minOf(size.width, size.height) * 0.40f
                        val hit = known.minByOrNull { t ->
                            val p = layout[t.id] ?: return@minByOrNull Float.MAX_VALUE
                            (c + pan + p * unit * scale - tap).getDistance()
                        }
                        val p = hit?.let { layout[it.id] }
                        selected = if (p != null && (c + pan + p * unit * scale - tap).getDistance() < 48.dp.toPx()) hit.id else null
                    }
                },
        ) {
            val c = Offset(size.width / 2f, size.height / 2f) + pan
            val unit = minOf(size.width, size.height) * 0.40f * scale
            val a = appear.value
            val maxW = (pairs.maxOfOrNull { it.weight } ?: 1).toFloat()
            pairs.forEach { e ->
                val p = layout[e.a] ?: return@forEach
                val q = layout[e.b] ?: return@forEach
                val lit = selected == null || selected == e.a || selected == e.b
                drawLine(
                    palette.accent.copy(alpha = a * (if (lit) 0.22f + 0.30f * (e.weight / maxW) else 0.06f)),
                    c + p * unit, c + q * unit,
                    strokeWidth = (1f + 1f * (e.weight / maxW)).dp.toPx(),
                )
            }
            val maxC = (known.maxOfOrNull { it.count } ?: 1).toFloat()
            known.forEach { t ->
                val p = layout[t.id] ?: return@forEach
                val near = selected == null || selected == t.id || pairs.any { (it.a == selected && it.b == t.id) || (it.b == selected && it.a == t.id) }
                val r = (4f + 9f * sqrt(t.count / maxC)).dp.toPx() * (0.6f + 0.4f * a)
                // Knots on the thread, in its color; the one you touched gets a halo and the rest step back.
                if (selected == t.id) drawCircle(palette.accent.copy(alpha = 0.16f), radius = r * 2f, center = c + p * unit)
                drawCircle(if (near) palette.accent else palette.surface3, radius = r, center = c + p * unit)
                label(measurer, t.name, c + p * unit + Offset(0f, r + 4.dp.toPx()), palette.text.copy(alpha = a * (if (near) 0.9f else 0.3f)), palette.background)
            }
        }

        AnimatedVisibility(
            visible = selected != null && entries.isNotEmpty(),
            enter = slideInVertically(Motion.slide) { it / 2 } + fadeIn(Motion.enter()),
            exit = slideOutVertically(Motion.slide) { it / 2 } + fadeOut(Motion.exit()),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(NoveloColors.Surface2)
                    .padding(top = 16.dp),
            ) {
                val name = known.firstOrNull { it.id == selected }?.name.orEmpty()
                Text(name, style = MaterialTheme.typography.titleLarge, color = NoveloColors.Text, modifier = Modifier.padding(horizontal = 20.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(top = 12.dp), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                    itemsIndexed(entries, key = { _, e -> e.id }) { i, e ->
                        Box(Modifier.padding(bottom = if (i == entries.lastIndex) 0.dp else GroupGap)) {
                            EntryCard(e, onClick = { onOpen(e.id) }, shape = groupShape(i, entries.size, outer = 22.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.label(m: TextMeasurer, text: String, at: Offset, color: Color, paper: Color) {
    val layout = m.measure(text.take(22), TextStyle(fontFamily = Nunito, fontSize = 12.sp, color = color))
    val left = at.x - layout.size.width / 2f
    // The name sits on paper, so a thread passing behind it never crosses the letters.
    val pad = 3.dp.toPx()
    drawRoundRect(
        paper,
        topLeft = Offset(left - pad, at.y),
        size = androidx.compose.ui.geometry.Size(layout.size.width + pad * 2, layout.size.height.toFloat()),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(pad),
    )
    drawText(layout, topLeft = Offset(left, at.y))
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
