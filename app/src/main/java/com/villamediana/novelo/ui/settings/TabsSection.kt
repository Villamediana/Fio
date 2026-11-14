package com.villamediana.novelo.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.R
import com.villamediana.novelo.settings.NoveloSettings
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.ui.Filter
import com.villamediana.novelo.ui.components.GroupGap
import com.villamediana.novelo.ui.components.NoveloIcons
import com.villamediana.novelo.ui.components.groupShape
import com.villamediana.novelo.ui.library.TabOrder
import com.villamediana.novelo.ui.library.title
import com.villamediana.novelo.ui.theme.NoveloColors
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableColumn

/** The tabs shown, in a few words ("Hoje · Tarefas · Diário…"). */
internal fun tabsSummary(s: NoveloSettings): String = TabOrder.visible(s).joinToString(" · ") { title(it) }

/**
 * Ajustes › Abas: the library's tabs as a list to put in her order — held (or by the dots on the left)
 * and dragged — and an eye on each to hide it or show it again. One always stays.
 */
@Composable
fun TabsSection() {
    val graph = NoveloApplication.graph
    val s by graph.settings.now.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    var order by remember(s.tabOrder) { mutableStateOf(TabOrder.order(s)) }
    val hidden = TabOrder.hidden(s)
    fun save(o: List<Filter>, h: Set<Filter>) {
        graph.scope.launch { graph.settings.setTabs(o.map(TabOrder::key), h.map(TabOrder::key).toSet()) }
    }

    Column(Modifier.fillMaxWidth()) {
        ReorderableColumn(
            list = order,
            onSettle = { from, to ->
                order = order.toMutableList().apply { add(to, removeAt(from)) }
                save(order, hidden)
            },
            onMove = { haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) },
            verticalArrangement = Arrangement.spacedBy(GroupGap),
            modifier = Modifier.testTag("tabs-order"),
        ) { i, f, dragging ->
            key(f) {
                ReorderableItem {
                    val shape = groupShape(i, order.size)
                    val shown = f !in hidden
                    val ink by animateColorAsState(if (shown) NoveloColors.Text else NoveloColors.TextFaint, label = "tab")
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .graphicsLayer { if (dragging) { scaleX = 1.02f; scaleY = 1.02f; shadowElevation = 12f; this.shape = shape; clip = true } }
                            .clip(shape)
                            .background(NoveloColors.Surface)
                            .longPressDraggableHandle(onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) })
                            .padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
                            .testTag("tab-row-${TabOrder.key(f)}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The dots to take it by.
                        Box(
                            Modifier.size(44.dp).draggableHandle(onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) })
                                .semantics { contentDescription = t(R.string.tabs_move, title(f)) },
                            contentAlignment = Alignment.Center,
                        ) { Grip() }
                        Text(title(f), style = MaterialTheme.typography.titleMedium, color = ink, modifier = Modifier.weight(1f))
                        // The eye: shown or hidden (the last one shown can't go).
                        val canHide = !shown || order.count { it !in hidden } > 1
                        Box(
                            Modifier.size(44.dp).clip(CircleShape)
                                .clickable(enabled = canHide) { save(order, if (shown) hidden + f else hidden - f) }
                                .semantics { contentDescription = t(if (shown) R.string.tabs_hide else R.string.tabs_show, title(f)) }
                                .testTag("tab-eye-${TabOrder.key(f)}"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (shown) NoveloIcons.Eye else NoveloIcons.EyeOff,
                                null,
                                tint = when { !shown -> NoveloColors.TextFaint; canHide -> NoveloColors.Accent; else -> NoveloColors.TextFaint.copy(alpha = 0.5f) },
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(t(R.string.tabs_line), style = MaterialTheme.typography.bodySmall, color = NoveloColors.TextMuted, modifier = Modifier.padding(horizontal = 6.dp))
    }
}

/** Six quiet dots, two by three: where a row is taken to move it. */
@Composable
private fun Grip() {
    val color = NoveloColors.TextFaint
    Canvas(Modifier.size(width = 10.dp, height = 16.dp)) {
        val r = 1.6.dp.toPx()
        for (x in 0..1) for (y in 0..2) drawCircle(color, r, Offset(r + x * (size.width - 2 * r), r + y * (size.height - 2 * r) / 2))
    }
}
