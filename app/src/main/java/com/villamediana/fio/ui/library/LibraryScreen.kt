package com.villamediana.fio.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.fio.FioApplication
import com.villamediana.fio.data.Entry
import com.villamediana.fio.data.Kind
import com.villamediana.fio.data.Status
import com.villamediana.fio.mind.Recall
import com.villamediana.fio.ui.Filter
import com.villamediana.fio.ui.Nav
import com.villamediana.fio.ui.Screen
import com.villamediana.fio.ui.components.Dates
import com.villamediana.fio.ui.components.EntryCard
import com.villamediana.fio.ui.components.PT
import com.villamediana.fio.ui.components.Pill
import com.villamediana.fio.ui.components.SectionLabel
import com.villamediana.fio.ui.home.QuietIcon
import com.villamediana.fio.ui.theme.FioColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

@Composable
fun LibraryScreen(nav: Nav, screen: Screen.Library) {
    val graph = FioApplication.graph
    val scope = rememberCoroutineScope()
    val entries by graph.library.all().collectAsStateWithLifecycle(initialValue = null)
    var filter by rememberSaveable(screen) { mutableStateOf(screen.filter) }
    var searching by rememberSaveable(screen.search, screen.query) { mutableStateOf(screen.search) }
    var query by rememberSaveable(screen.search, screen.query) { mutableStateOf(screen.query) }
    var showDone by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    // Searching is the whole library, words matched (and their neighbours in the graph).
    val recall by produceState<Recall?>(null, entries) { value = graph.library.recall() }
    var debounced by remember { mutableStateOf(query) }
    LaunchedEffect(query) { delay(180); debounced = query }
    val hits = remember(recall, debounced) { if (debounced.isBlank()) null else recall?.search(debounced, limit = 60) }
    val terms = remember(recall, debounced) { recall?.terms(debounced).orEmpty() }

    LaunchedEffect(searching) { if (searching) runCatching { focus.requestFocus() } }

    val toggle: (Long) -> Unit = { id -> scope.launch { graph.library.toggleDone(id) } }
    val open: (Long) -> Unit = { nav.go(Screen.Entry(it)) }

    Column(Modifier.fillMaxSize().background(FioColors.Background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            QuietIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar", { nav.back() }, alpha = 0.8f)
            if (searching) {
                Row(
                    Modifier.weight(1f).clip(CircleShape).background(FioColors.Surface2).padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        if (query.isEmpty()) Text("Buscar no que você guardou", style = MaterialTheme.typography.bodyLarge, color = FioColors.TextFaint)
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = FioColors.Text),
                            cursorBrush = SolidColor(FioColors.Accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    }
                    Box(Modifier.size(36.dp).clip(CircleShape).clickable { if (query.isEmpty()) searching = false else query = "" }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, "Limpar", tint = FioColors.TextMuted, modifier = Modifier.size(18.dp))
                    }
                }
            } else {
                Text(title(filter), style = MaterialTheme.typography.headlineMedium, color = FioColors.Text, modifier = Modifier.weight(1f).padding(start = 6.dp))
                QuietIcon(Icons.Rounded.Share, "Conexões", { nav.go(Screen.Graph()) }, alpha = 0.6f)
                QuietIcon(Icons.Rounded.Search, "Buscar", { searching = true }, alpha = 0.7f)
            }
        }

        AnimatedVisibility(visible = !searching || query.isBlank(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(Filter.TODAY, Filter.TASKS, Filter.NOTES, Filter.DIARY, Filter.ALL).forEach { f ->
                    Pill(short(f), selected = filter == f, onClick = { filter = f; nav.go(Screen.Library(f)) }, dot = dotOf(f))
                }
            }
        }

        val list = entries
        if (list == null) return@Column
        val sections: List<Pair<String?, List<Entry>>> = if (hits != null) {
            listOf((if (hits.isEmpty()) "Nada com essas palavras" else null) to hits.map { it.entry })
        } else {
            sectionsFor(filter, list, showDone)
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 40.dp),
        ) {
            if (hits == null && sections.all { it.second.isEmpty() }) {
                item { Empty(filter) }
            }
            sections.forEach { (label, items) ->
                if (label != null && (items.isNotEmpty() || hits != null)) item(key = "label-$label") { SectionLabel(label, Modifier.animateItem()) }
                items(items, key = { it.id }) { e ->
                    Box(Modifier.animateItem().padding(bottom = 8.dp)) {
                        if (filter == Filter.DIARY && hits == null) DiaryCard(e, onClick = { open(e.id) })
                        else EntryCard(e, onClick = { open(e.id) }, terms = if (hits != null) terms else emptyList(), onToggle = { toggle(e.id) })
                    }
                }
            }
            if (hits == null && filter == Filter.TASKS) {
                val done = list.count { it.kindEnum == Kind.TASK && it.statusEnum == Status.DONE }
                if (done > 0) item(key = "done-toggle") {
                    Text(
                        if (showDone) "Esconder feitas" else "Ver feitas ($done)",
                        style = MaterialTheme.typography.labelLarge,
                        color = FioColors.TextMuted,
                        modifier = Modifier.animateItem().padding(top = 10.dp).clip(CircleShape).clickable { showDone = !showDone }.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

private fun title(f: Filter) = when (f) {
    Filter.TODAY -> "Hoje"
    Filter.TASKS -> "Tarefas"
    Filter.NOTES -> "Notas"
    Filter.DIARY -> "Diário"
    Filter.ALL -> "Tudo"
}

private fun short(f: Filter) = title(f)

@Composable
private fun dotOf(f: Filter) = when (f) {
    Filter.TASKS -> FioColors.of(Kind.TASK)
    Filter.NOTES -> FioColors.of(Kind.NOTE)
    Filter.DIARY -> FioColors.of(Kind.DIARY)
    else -> null
}

/** How each tab groups what is kept. */
fun sectionsFor(filter: Filter, all: List<Entry>, showDone: Boolean, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): List<Pair<String?, List<Entry>>> {
    val today = LocalDate.now(zone)
    val startToday = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val endToday = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val tasks = all.filter { it.kindEnum == Kind.TASK }
    val open = tasks.filter { it.statusEnum != Status.DONE }
    return when (filter) {
        Filter.TODAY -> {
            val late = open.filter { it.dueAt != null && it.dueAt < startToday }.sortedBy { it.dueAt }
            val due = open.filter { (it.dueAt != null && it.dueAt in startToday until endToday) || (it.remindAt != null && it.remindAt in startToday until endToday) }
                .filter { it !in late }.sortedBy { it.remindAt ?: it.dueAt }
            val doing = open.filter { it.statusEnum == Status.DOING && it !in late && it !in due }
            val said = all.filter { it.createdAt >= startToday && it.kindEnum != Kind.TASK }
            val doneToday = tasks.filter { it.doneAt != null && it.doneAt >= startToday }
            listOf("Atrasadas" to late, "Pra hoje" to due, "Em andamento" to doing, "Guardado hoje" to said, "Feitas hoje" to doneToday)
        }
        Filter.TASKS -> {
            val byStatus = open.groupBy { it.statusEnum ?: Status.TODO }
            val sortKey: (Entry) -> Long = { it.dueAt ?: (Long.MAX_VALUE - it.createdAt) }
            buildList {
                add("Fazendo" to byStatus[Status.DOING].orEmpty().sortedBy(sortKey))
                add("A fazer" to byStatus[Status.TODO].orEmpty().sortedBy(sortKey))
                add("Esperando" to byStatus[Status.WAITING].orEmpty().sortedBy(sortKey))
                if (showDone) add("Feitas" to tasks.filter { it.statusEnum == Status.DONE }.sortedByDescending { it.doneAt ?: it.updatedAt })
            }
        }
        Filter.NOTES -> byDay(all.filter { it.kindEnum == Kind.NOTE }, zone, today)
        Filter.DIARY -> byDay(all.filter { it.kindEnum == Kind.DIARY }, zone, today)
        Filter.ALL -> byDay(all, zone, today)
    }
}

private fun byDay(list: List<Entry>, zone: ZoneId, today: LocalDate): List<Pair<String?, List<Entry>>> =
    list.sortedByDescending { it.createdAt }
        .groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }
        .map { (d, items) -> Dates.day(d.atStartOfDay(zone).toInstant().toEpochMilli(), zone, today).replaceFirstChar { it.titlecase(PT) } to items }

@Composable
private fun DiaryCard(e: Entry, onClick: () -> Unit) {
    val d = Instant.ofEpochMilli(e.createdAt).atZone(ZoneId.systemDefault())
    Row(
        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).background(FioColors.Surface).clickable(onClick = onClick).padding(16.dp).animateContentSize(),
    ) {
        Column(Modifier.width(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(d.dayOfMonth.toString(), style = MaterialTheme.typography.headlineMedium, color = FioColors.of(Kind.DIARY))
            Text(d.month.getDisplayName(TextStyle.SHORT, PT).removeSuffix(".").uppercase(PT), style = MaterialTheme.typography.labelSmall, color = FioColors.TextFaint)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.titleMedium, color = FioColors.Text, maxLines = 2)
            if (e.body.isNotBlank() && !e.sensitive) {
                Spacer(Modifier.height(4.dp))
                Text(e.body, style = MaterialTheme.typography.bodyMedium, color = FioColors.TextMuted, maxLines = 4)
            }
            Spacer(Modifier.height(6.dp))
            Text(d.format(DateTimeFormatter.ofPattern("EEEE · HH:mm", PT)), style = MaterialTheme.typography.labelMedium, color = FioColors.TextFaint)
        }
    }
}

@Composable
private fun Empty(filter: Filter) {
    Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            when (filter) {
                Filter.TODAY -> "Nada pra hoje."
                Filter.TASKS -> "Nenhuma tarefa aberta."
                Filter.NOTES -> "Nenhuma nota ainda."
                Filter.DIARY -> "O diário está em branco."
                Filter.ALL -> "Nada guardado ainda."
            },
            style = MaterialTheme.typography.titleMedium,
            color = FioColors.TextMuted,
        )
        Spacer(Modifier.height(6.dp))
        Text("Fale no botão da tela inicial.", style = MaterialTheme.typography.bodyMedium, color = FioColors.TextFaint)
    }
}
