package com.villamediana.novelo.ui.library

import sh.calvin.reorderable.ReorderableItem

import com.villamediana.novelo.R
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.settings.tn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.ui.components.CategoryChip
import com.villamediana.novelo.data.Categories
import com.villamediana.novelo.data.Kind
import com.villamediana.novelo.data.Status
import com.villamediana.novelo.mind.Recall
import com.villamediana.novelo.ui.entry.PhotoRow
import com.villamediana.novelo.ui.components.Mosaic
import com.villamediana.novelo.ui.components.PhotoTile
import com.villamediana.novelo.mind.PhotoRecall
import com.villamediana.novelo.mind.Photo
import com.villamediana.novelo.ui.Filter
import com.villamediana.novelo.ui.Nav
import com.villamediana.novelo.ui.Screen
import com.villamediana.novelo.ui.components.Dates
import com.villamediana.novelo.ui.components.EntryCard
import com.villamediana.novelo.ui.components.NoveloIcons
import com.villamediana.novelo.ui.components.GroupGap
import com.villamediana.novelo.ui.components.PT
import com.villamediana.novelo.ui.components.SWIPE_FOLLOW
import com.villamediana.novelo.ui.components.SWIPE_LEAVING_MS
import com.villamediana.novelo.ui.components.SectionLabel
import com.villamediana.novelo.ui.components.StitchColor
import com.villamediana.novelo.ui.components.paper
import com.villamediana.novelo.ui.components.stitched
import com.villamediana.novelo.ui.theme.NoveloText
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import com.villamediana.novelo.ui.components.groupShape
import com.villamediana.novelo.ui.components.swipeGoes
import com.villamediana.novelo.ui.graph.Connections
import com.villamediana.novelo.ui.home.QuietIcon
import com.villamediana.novelo.ui.theme.NoveloColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

@Composable
fun LibraryScreen(nav: Nav, screen: Screen.Library) {
    val graph = NoveloApplication.graph
    val scope = rememberCoroutineScope()
    val entries by graph.library.all().collectAsStateWithLifecycle(initialValue = null)
    var searching by rememberSaveable(screen.search, screen.query) { mutableStateOf(screen.search) }
    var query by rememberSaveable(screen.search, screen.query) { mutableStateOf(screen.query) }
    var showDone by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    // Searching is the whole library, words matched (and their neighbours in the graph)…
    val recall by produceState<Recall?>(null, entries) { value = graph.library.recall() }
    var debounced by remember { mutableStateOf(query) }
    LaunchedEffect(query) { delay(180); debounced = query }
    // …and by meaning: once she stops typing, Omni gives the words her notes may use for it, and
    // what they find comes after what her own words found.
    val byMeaning by produceState(false) { value = withContext(Dispatchers.IO) { graph.meaning.available } }
    LaunchedEffect(searching, byMeaning) { if (searching && byMeaning) graph.meaning.warm() }
    var meant by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    val asked = debounced.trim()
    val looking = asked.isNotEmpty() && byMeaning && meant?.first != asked
    LaunchedEffect(asked, byMeaning) {
        if (asked.isEmpty() || !byMeaning) return@LaunchedEffect
        delay(MEANING_PAUSE_MS)
        meant = asked to runCatching { graph.meaning.words(asked) }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else emptyList() }
    }
    val more = meant?.takeIf { it.first == asked }?.second.orEmpty()
    val hits = remember(recall, debounced, more) {
        if (debounced.isBlank()) null else recall?.let { r ->
            val own = r.search(debounced, limit = 60)
            val near = if (more.isEmpty()) emptyList() else r.search(more.joinToString(" "), limit = 60).filter { h -> own.none { it.entry.id == h.entry.id } }
            (own + near).take(60)
        }
    }
    val terms = remember(recall, debounced, more) { recall?.terms(debounced).orEmpty() + more }
    // The photos: the gallery, the day's diary, and the search (by what each one shows).
    val photos by graph.library.loosePhotos().collectAsStateWithLifecycle(initialValue = emptyList())
    val photoIndex = remember(photos) { PhotoRecall(photos) }
    val photoHits = remember(photoIndex, debounced, more) {
        if (debounced.isBlank()) emptyList() else {
            val own = photoIndex.search(debounced, limit = 30)
            val near = if (more.isEmpty()) emptyList() else photoIndex.search(more.joinToString(" "), limit = 30).filter { p -> own.none { it.id == p.id } }
            (own + near).take(30)
        }
    }
    val dayPhotos = remember(entries, photos) { DayPhotosIndex.of(entries.orEmpty(), photos, ZoneId.systemDefault()) }
    // The files: their tab, and the search (by name and what each one is) — none inside something sensitive.
    val files by remember { graph.library.files(withSensitive = false) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val fileIndex = remember(files) { com.villamediana.novelo.mind.FileRecall(files) }
    val fileHits = remember(fileIndex, debounced, more) {
        if (debounced.isBlank()) emptyList() else {
            val own = fileIndex.search(debounced, limit = 20)
            val near = if (more.isEmpty()) emptyList() else fileIndex.search(more.joinToString(" "), limit = 20).filter { f -> own.none { it.id == f.id } }
            (own + near).take(20)
        }
    }

    LaunchedEffect(searching) { if (searching) runCatching { focus.requestFocus() } }

    val toggle: (Long) -> Unit = { id -> scope.launch { graph.library.toggleDone(id) } }
    val open: (Long) -> Unit = { nav.go(Screen.Entry(it)) }
    // Tarefas: the category she is looking at (null: all of them).
    var category by rememberSaveable { mutableStateOf<String?>(null) }

    // The tabs are pages side by side: a swipe goes to the next one, a touch on a word too.
    // The tab asked by the screen is the page; the page a swipe stops on is the tab kept (so
    // coming back from an entry lands where you were).
    val pager = rememberPagerState(initialPage = TABS.indexOf(screen.filter).coerceAtLeast(0)) { TABS.size }
    val current by rememberUpdatedState(screen)
    LaunchedEffect(screen.filter) { TABS.indexOf(screen.filter).takeIf { it >= 0 && it != pager.currentPage }?.let { pager.scrollToPage(it) } }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { p ->
            val f = TABS[p]
            if (f != current.filter) nav.go(current.copy(filter = f, topic = null))
        }
    }

    // On the first tab, a swipe back (to the right) is the way home: the page leans with the
    // finger and, far or quick enough, the main screen comes back.
    val density = LocalDensity.current
    var pull by remember { mutableFloatStateOf(0f) }
    val back by rememberUpdatedState { nav.back() }
    val edge = remember(pager) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (pull > 0f && available.x < 0f) {
                    val used = maxOf(available.x, -pull)
                    pull += used
                    return Offset(used, 0f)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.x > 0f && pager.currentPage == 0) {
                    pull += available.x
                    return Offset(available.x, 0f)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pull <= 0f) return Velocity.Zero
                // Gone or not, the lean goes back — and all the way, even when a quick touch cuts
                // the way back short: the screen reused when she comes right back (before its way
                // out ended) must not stay off-center. Going, it keeps the finger's way while it
                // leaves and is back in the middle after (see swipeNav).
                val going = swipeGoes(pull, available.x, density) == 1
                if (going) back()
                try {
                    if (going) delay(SWIPE_LEAVING_MS)
                    else animate(pull, 0f, animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f)) { v, _ -> pull = v }
                } finally {
                    pull = 0f
                }
                return available
            }
        }
    }

    Column(Modifier.fillMaxSize().paper().statusBarsPadding().navigationBarsPadding().imePadding()) {
        // On Tarefas, Notas and Diário, a quiet + makes one by hand (it opens on its title).
        // Diário: the + writes today's by hand, only while today has none (one diary a day; it
        // makes today's, not another day's).
        val todayWritten = remember(entries) {
            val today = com.villamediana.novelo.mind.DayDiary.bounds(System.currentTimeMillis(), ZoneId.systemDefault())
            entries.orEmpty().any { it.kindEnum == Kind.DIARY && it.createdAt in today }
        }
        val makes = when (TABS.getOrNull(pager.currentPage)) {
            Filter.TASKS -> Kind.TASK
            Filter.NOTES -> Kind.NOTE
            Filter.DIARY -> Kind.DIARY.takeIf { entries != null && !todayWritten }
            else -> null
        }
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = if (makes != null && !searching) 4.dp else 16.dp, top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            QuietIcon(NoveloIcons.Back, t(R.string.back), { nav.back() }, alpha = 0.8f)
            SearchBar(
                searching = searching,
                query = query,
                onQuery = { query = it },
                onOpen = { searching = true },
                onClear = { if (query.isEmpty()) searching = false else query = "" },
                focus = focus,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            if (makes != null && !searching) {
                QuietIcon(NoveloIcons.Plus, t(when (makes) { Kind.TASK -> R.string.new_task; Kind.NOTE -> R.string.new_note; else -> R.string.new_diary }), {
                    scope.launch { nav.go(Screen.Entry(graph.library.create(makes))) }
                }, alpha = 0.55f)
            }
        }

        AnimatedVisibility(visible = !searching || query.isBlank(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Tabs(
                selected = pager.currentPage,
                position = { pager.currentPage + pager.currentPageOffsetFraction },
                onSelect = { i -> scope.launch { pager.animateScrollToPage(i) } },
            )
        }

        val list = entries
        if (hits != null) {
            val nothing = hits.isEmpty() && photoHits.isEmpty() && fileHits.isEmpty()
            val found = listOf((if (nothing) t(if (looking) R.string.search_by_meaning else R.string.search_nothing) else null) to hits.map { it.entry })
            Entries(Filter.ALL, found, list.orEmpty(), terms = terms, showDone = false, onShowDone = {}, open = open, toggle = toggle, photos = photoHits, files = fileHits)
            return@Column
        }
        Box(Modifier.fillMaxSize().nestedScroll(edge)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                val f = TABS[page]
                Box(Modifier.fillMaxSize().graphicsLayer { if (page == 0) translationX = pull * SWIPE_FOLLOW }) {
                    when {
                        f == Filter.LINKS -> Connections(startTopic = screen.topic, onOpen = open, empty = { Empty(Filter.LINKS) })
                        f == Filter.GALLERY -> Gallery(photos, empty = { Empty(Filter.GALLERY) })
                        f == Filter.TASKS && list != null -> {
                            // The categories (the ones with the most open tasks first) as filters over the list.
                            val all = sectionsFor(f, list, showDone)
                            val names = remember(list, showDone) { Categories.inUse(list.filter { it.kindEnum == Kind.TASK && (showDone || it.statusEnum != Status.DONE) }) }
                            val chosen = category?.takeIf { it in names }
                            Column(Modifier.fillMaxSize()) {
                                if (names.isNotEmpty()) CategoryFilter(names, chosen, onChoose = { category = it })
                                Entries(
                                    f, inCategory(all, chosen), list, terms = null, showDone = showDone, onShowDone = { showDone = !showDone }, open = open, toggle = toggle,
                                    whole = all, showCategories = chosen == null,
                                )
                            }
                        }
                        list != null -> Entries(f, sectionsFor(f, list, showDone), list, terms = null, showDone = showDone, onShowDone = { showDone = !showDone }, open = open, toggle = toggle, dayPhotos = dayPhotos)
                    }
                }
            }
        }
    }
}

/** One tab's list (or the search's, when [terms] is given): groups under a quiet label, each group one block. */
@Composable
private fun Entries(
    filter: Filter,
    sections: List<Pair<String?, List<Entry>>>,
    all: List<Entry>,
    terms: List<String>?,
    showDone: Boolean,
    onShowDone: () -> Unit,
    open: (Long) -> Unit,
    toggle: (Long) -> Unit,
    /** The search's photos, above what it found. */
    photos: List<Photo> = emptyList(),
    /** The groups whole, when [sections] is only some of them (a category): a drag's order goes into these. */
    whole: List<Pair<String?, List<Entry>>>? = null,
    /** The tasks' categories on their cards (not when the list is already one category). */
    showCategories: Boolean = filter == Filter.TODAY || filter == Filter.TASKS,
    /** The search's files, after its photos. */
    files: List<com.villamediana.novelo.data.KeptFile> = emptyList(),
    /** The day's photos on the day's diary card. */
    dayPhotos: DayPhotosIndex = DayPhotosIndex.EMPTY,
) {
    val searched = terms != null
    // Tarefas: a task held and dragged moves within its group (doing, to do, waiting); Notas: a note
    // moves in the one list. Let go, the order is kept.
    val movable = (filter == Filter.TASKS || filter == Filter.NOTES) && !searched
    val graph = com.villamediana.novelo.NoveloApplication.graph
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    var shown by remember(sections) { mutableStateOf(sections) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val reorder = sh.calvin.reorderable.rememberReorderableLazyListState(listState) { from, to ->
        val group = shown.indexOfFirst { (_, list) -> list.any { it.id == from.key } }
        val list = shown.getOrNull(group)?.second ?: return@rememberReorderableLazyListState
        val a = list.indexOfFirst { it.id == from.key }
        val b = list.indexOfFirst { it.id == to.key }
        if (a < 0 || b < 0) return@rememberReorderableLazyListState // another group, or a label: it stays in its own
        shown = shown.toMutableList().also { it[group] = it[group].first to list.toMutableList().apply { add(b, removeAt(a)) } }
        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentFrequentTick)
    }
    val groups = if (movable) shown else sections
    // The handle that crosses the list (FastScroll), in every tab: what each line of the list says
    // where it is — its month where the tab goes by day (a day's label with its day), its group
    // (Atrasadas, A fazer…) in Hoje and Tarefas.
    val byGroup = filter == Filter.TODAY || filter == Filter.TASKS
    // The diary's days without a label (all but Hoje and Ontem) keep apart by a gap.
    val gapped = filter == Filter.DIARY && !searched
    val tags = remember(groups, searched, byGroup) {
        if (searched) emptyList()
        else buildList<String?> {
            groups.forEach { (label, items) ->
                if (items.isEmpty()) return@forEach
                if (label != null) add(if (byGroup) label else month(items.first().createdAt))
                else if (gapped) add(month(items.first().createdAt))
                items.forEach { add(if (byGroup) label else month(it.createdAt)) }
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("entries"),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = if (searched) 14.dp else 0.dp, bottom = 40.dp),
        ) {
            if (!searched && groups.all { it.second.isEmpty() }) {
                item { Empty(filter) }
            }
            if (photos.isNotEmpty()) {
                item(key = "label-photos") { SectionLabel(t(R.string.photos), Modifier.animateItem()) }
                item(key = "photos") { PhotoRow(photos, side = 16.dp, height = 150.dp, tag = "found-photo") }
                item(key = "photos-gap") { Spacer(Modifier.height(14.dp)) }
            }
            if (files.isNotEmpty()) {
                item(key = "label-files") { SectionLabel(t(R.string.tab_files), Modifier.animateItem()) }
                itemsIndexed(files, key = { _, f -> "file-${f.id}" }) { i, f ->
                    val context = androidx.compose.ui.platform.LocalContext.current
                    Box(Modifier.animateItem().padding(bottom = 8.dp)) {
                        com.villamediana.novelo.ui.entry.FileRow(f, onClick = { com.villamediana.novelo.ui.entry.openFile(context, f) }, where = where(f), modifier = Modifier.testTag("found-file-$i"))
                    }
                }
                item(key = "files-gap") { Spacer(Modifier.height(6.dp)) }
            }
            groups.forEach { (label, items) ->
                if (label != null && (items.isNotEmpty() || searched)) item(key = "label-$label") { SectionLabel(label, Modifier.animateItem()) }
                else if (label == null && gapped && items.isNotEmpty()) item(key = "gap-${items.first().id}") { Spacer(Modifier.height(DAY_GAP)) }
                // One block per group: the cards touch, only the ends are round.
                itemsIndexed(items, key = { _, e -> e.id }) { i, e ->
                    val shape = groupShape(i, items.size, outer = 22.dp)
                    @Composable
                    fun card(modifier: Modifier) {
                        if (filter == Filter.DIARY && !searched) DiaryCard(e, onClick = { open(e.id) }, shape = shape, photos = dayPhotos.of(e.id))
                        else EntryCard(
                            e,
                            onClick = { open(e.id) },
                            terms = terms.orEmpty(),
                            onToggle = { toggle(e.id) },
                            showKind = searched || filter == Filter.ALL || filter == Filter.TODAY,
                            // Where the group already says it, the card doesn't again.
                            showStatus = searched || (filter != Filter.TASKS && !(filter == Filter.TODAY && label == DOING)),
                            inToday = !searched && filter == Filter.TODAY,
                            shape = shape,
                            dated = !searched && filter == Filter.NOTES,
                            showCategory = !searched && showCategories,
                            modifier = modifier,
                        )
                    }
                    if (movable) {
                        ReorderableItem(reorder, key = e.id) { dragging ->
                            Box(
                                Modifier
                                    .padding(bottom = if (i == items.lastIndex) 0.dp else GroupGap)
                                    .graphicsLayer { if (dragging) { scaleX = 1.02f; scaleY = 1.02f; shadowElevation = 12f; this.shape = shape; clip = true } }
                                    .longPressDraggableHandle(
                                        onDragStarted = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) },
                                        onDragStopped = {
                                            shown.firstOrNull { (_, list) -> list.any { it.id == e.id } }?.second?.map { it.id }?.let { ids ->
                                                // Only one category on the screen: its new order goes into the whole group's.
                                                val full = whole?.firstOrNull { (_, list) -> list.any { it.id == e.id } }?.second?.map { it.id }
                                                graph.scope.launch { graph.library.reorder(if (full != null) mergeOrder(full, ids) else ids) }
                                            }
                                        },
                                    )
                                    .testTag(if (filter == Filter.NOTES) "note-$i" else "task-$i"),
                            ) { card(Modifier) }
                        }
                    } else {
                        Box(Modifier.animateItem().padding(bottom = if (i == items.lastIndex) 0.dp else GroupGap)) { card(Modifier) }
                    }
                }
            }
            if (!searched && filter == Filter.TASKS) {
                val done = all.count { it.kindEnum == Kind.TASK && it.statusEnum == Status.DONE }
                if (done > 0) item(key = "done-toggle") {
                    Text(
                        if (showDone) t(R.string.done_hide) else t(R.string.done_show, done),
                        style = MaterialTheme.typography.labelLarge,
                        color = NoveloColors.TextMuted,
                        modifier = Modifier.animateItem().padding(top = 10.dp).clip(CircleShape).clickable(onClick = onShowDone).padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
        if (!searched) FastScroll(listState, labelAt = { tags.getOrNull(it) ?: tags.lastOrNull() })
    }
}

/**
 * Where a file the search found lives, in a few words: its item's title, or the day's diary. (The
 * Arquivos tab is gone, 1.47.0: each file lives in its item — a task, the diary, or a note.)
 */
internal fun where(f: com.villamediana.novelo.data.KeptFile): String = f.home?.title?.take(40) ?: t(R.string.file_in_diary)

/** "Janeiro 2022": where the handle on the edge is (FastScroll). */
internal fun month(at: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(at).atZone(zone).let { d -> d.month.getDisplayName(TextStyle.FULL_STANDALONE, PT).replaceFirstChar { it.titlecase(PT) } + " " + d.year }

/**
 * The tabs shown, in order. Hidden for now, their code kept: Conexões (the drawing stays in
 * ui/graph) and Tudo (Miguel, 1.46.0; the search, which looks in everything, still goes as ALL).
 * Arquivos is gone (1.47.0): a file lives in its item.
 */
private val TABS = Filter.entries - Filter.LINKS - Filter.ALL

private fun title(f: Filter) = when (f) {
    Filter.TODAY -> t(R.string.today)
    Filter.TASKS -> t(R.string.tab_tasks)
    Filter.NOTES -> t(R.string.tab_notes)
    Filter.DIARY -> t(R.string.tab_diary)
    Filter.GALLERY -> t(R.string.gallery)
    Filter.ALL -> t(R.string.tab_all)
    Filter.LINKS -> t(R.string.tab_links)
}

/**
 * The tabs are just words: the one you are in is ink with a running stitch of the thread under it,
 * the others wait in gray. The stitch slides to the new tab; a tab off the edge comes into view.
 */
@Composable
private fun Tabs(selected: Int, position: () -> Float, onSelect: (Int) -> Unit) {
    val scroll = rememberScrollState()
    val bounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() } // x, width (inside the row's padding)
    val density = LocalDensity.current
    val start = with(density) { 10.dp.toPx() }
    val target = bounds[selected]
    LaunchedEffect(selected, target) {
        val (x, w) = target ?: return@LaunchedEffect
        val left = start + x
        val right = left + w
        val view = scroll.viewportSize
        if (view > 0) {
            val margin = with(density) { 28.dp.toPx() }
            when {
                left - margin < scroll.value -> scroll.animateScrollTo((left - margin).toInt().coerceAtLeast(0))
                right + margin > scroll.value + view -> scroll.animateScrollTo((right + margin - view).toInt())
            }
        }
    }
    val accent = NoveloColors.Accent
    val last = TABS.lastIndex
    Row(
        Modifier
            .fillMaxWidth()
            .fadingEdges(scroll)
            .horizontalScroll(scroll)
            .padding(start = 10.dp, end = 10.dp, top = 2.dp)
            .drawBehind {
                // The stitch goes with the pages: between two tabs while the finger is between two pages.
                val at = position().coerceIn(0f, last.toFloat())
                val i = at.toInt().coerceAtMost(last)
                val a = bounds[i] ?: return@drawBehind
                val b = bounds[(i + 1).coerceAtMost(last)] ?: a
                val t = at - i
                val center = (a.first + a.second / 2f) * (1 - t) + (b.first + b.second / 2f) * t
                // As wide as the word (the tab's padding off), a running stitch.
                val pad = 20.dp.toPx()
                val w = ((a.second - pad) * (1 - t) + (b.second - pad) * t).coerceAtLeast(12.dp.toPx())
                val y = size.height - 6.dp.toPx()
                drawLine(
                    accent,
                    start = Offset(center - w / 2f, y),
                    end = Offset(center + w / 2f, y),
                    strokeWidth = 2.4.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TABS.forEachIndexed { i, f ->
            Tab(
                title(f),
                selected = selected == i,
                onClick = { onSelect(i) },
                modifier = Modifier.onGloballyPositioned { c -> bounds[i] = c.positionInParent().x to c.size.width.toFloat() },
            )
        }
    }
}

@Composable
private fun Tab(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color by animateColorAsState(if (selected) NoveloColors.Text else NoveloColors.TextFaint, label = "tab")
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal),
        color = color,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClick)
            .semantics { role = Role.Tab; this.selected = selected }
            .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 15.dp),
    )
}

/** The search sits at the top as a quiet field; touching it is already typing. */
@Composable
private fun SearchBar(
    searching: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onOpen: () -> Unit,
    onClear: () -> Unit,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .height(50.dp)
            .clip(CircleShape)
            .background(NoveloColors.Surface)
            .stitched(CircleShape, StitchColor, inset = 5.dp)
            .then(if (searching) Modifier else Modifier.clickable(onClick = onOpen))
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(NoveloIcons.Search, null, tint = NoveloColors.TextMuted, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(if (searching) t(R.string.search_kept) else t(R.string.search), style = MaterialTheme.typography.bodyLarge, color = NoveloColors.TextFaint, maxLines = 1)
            if (searching) {
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = NoveloColors.Text),
                    cursorBrush = SolidColor(NoveloColors.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        }
        AnimatedVisibility(searching, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.size(38.dp).clip(CircleShape).clickable(onClick = onClear), contentAlignment = Alignment.Center) {
                Icon(NoveloIcons.Close, t(R.string.clear), tint = NoveloColors.TextMuted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** A row that runs past the screen melts into the paper at the edge it continues on. */
private fun Modifier.fadingEdges(scroll: androidx.compose.foundation.ScrollState): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val w = 36.dp.toPx()
        if (scroll.canScrollForward) {
            drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - w, endX = size.width), blendMode = BlendMode.DstIn)
        }
        if (scroll.canScrollBackward) {
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = w), blendMode = BlendMode.DstIn)
        }
    }

private val DOING: String get() = t(R.string.section_doing)

/** How each tab groups what is kept. */
fun sectionsFor(filter: Filter, all: List<Entry>, showDone: Boolean, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): List<Pair<String?, List<Entry>>> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
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
            listOfNotNull(
                t(R.string.section_late) to late,
                t(R.string.section_for_today) to due,
                DOING to doing,
                (t(R.string.section_kept_today) to said).takeIf { SHOW_KEPT_TODAY },
                t(R.string.section_done_today) to doneToday,
            )
        }
        Filter.TASKS -> {
            val byStatus = open.groupBy { it.statusEnum ?: Status.TODO }
            // Each group by category, together (see arrange); what she moved by hand stays where she put it.
            buildList {
                add(t(R.string.status_doing) to arrange(byStatus[Status.DOING].orEmpty()))
                add(t(R.string.status_todo) to arrange(byStatus[Status.TODO].orEmpty()))
                add(t(R.string.status_waiting) to arrange(byStatus[Status.WAITING].orEmpty()))
                if (showDone) add(t(R.string.section_done) to tasks.filter { it.statusEnum == Status.DONE }.sortedByDescending { it.doneAt ?: it.updatedAt })
            }
        }
        Filter.NOTES -> listOf(null to byHand(all.filter { it.kindEnum == Kind.NOTE }))
        // The diary's card already says its day: only Hoje and Ontem get a label (Miguel, 1.55.1).
        Filter.DIARY -> byDay(all.filter { it.kindEnum == Kind.DIARY }, zone, today, nearOnly = true)
        Filter.ALL -> byDay(all, zone, today)
        Filter.GALLERY, Filter.LINKS -> emptyList()
    }
}

/**
 * Hoje › "Guardado hoje" (the notes and diary said today) is hidden for now, to keep the tab to what
 * is to be done; it stays here to come back (Miguel, 1.42.0).
 */
internal const val SHOW_KEPT_TODAY = false

/**
 * A group of tasks in order, together by category: what she moved by hand ([Entry.position]) stays in
 * her order; the others (new ones, never moved) go next to the ones of their category — after the
 * last of them. The ones of a category not there yet, and the ones with none yet, come on top,
 * together, the categories with the most tasks first; inside, by due day, the newest first.
 */
internal fun arrange(tasks: List<Entry>): List<Entry> {
    val key: (Entry) -> String = { it.category?.let(Categories::fold).orEmpty() }
    val size = tasks.groupingBy(key).eachCount()
    val byDue: (Entry) -> Long = { it.dueAt ?: (Long.MAX_VALUE - it.createdAt) }
    val placed = tasks.filter { it.position != null }.sortedBy { it.position }.toMutableList()
    val loose = tasks.filter { it.position == null }
        .sortedWith(compareByDescending<Entry> { size[key(it)] ?: 0 }.thenBy(key).thenBy(byDue))
    val top = ArrayList<Entry>()
    for (t in loose) {
        val k = key(t)
        // One with no category yet (made by hand, to be organized) stays on top, where it is seen.
        val last = if (t.category == null) -1 else placed.indexOfLast { key(it) == k }
        if (last >= 0) placed.add(last + 1, t) else top += t
    }
    return top + placed
}

/** The groups with only the tasks of [category] (all of them when null). */
internal fun inCategory(sections: List<Pair<String?, List<Entry>>>, category: String?): List<Pair<String?, List<Entry>>> =
    if (category == null) sections else sections.map { (label, items) -> label to items.filter { it.category == category } }

/**
 * Some of a group ([moved], in their new order) put back among the whole group ([full]): each of
 * them takes the place one of them had, the others stay where they are.
 */
internal fun mergeOrder(full: List<Long>, moved: List<Long>): List<Long> {
    val these = moved.toSet()
    val next = moved.iterator()
    return full.map { if (it in these && next.hasNext()) next.next() else it }
}

/** Tarefas: all, or one category (its dot and name); a row that slides by itself without turning the tab. */
@Composable
private fun CategoryFilter(names: List<String>, chosen: String?, onChoose: (String?) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .nestedScroll(KeepSideways)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 6.dp)
            .testTag("category-filter"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CategoryChip(t(R.string.category_all), null, selected = chosen == null, onClick = { onChoose(null) })
        names.forEach { n -> CategoryChip(n, n, selected = chosen == n, onClick = { onChoose(if (chosen == n) null else n) }) }
    }
}

/** Between two days of the diary that have no label. */
private val DAY_GAP = 16.dp

/**
 * Notas in the order she set by hand (held and dragged, like the tasks): the ones not moved yet (new
 * ones) first, the newest on top; then the moved ones in their order.
 */
internal fun byHand(notes: List<Entry>): List<Entry> =
    notes.sortedWith(compareBy<Entry>({ if (it.position == null) 0 else 1 }, { it.position ?: 0L }, { -it.createdAt }))

/** A group per day, labeled with it; [nearOnly]: only today and yesterday labeled (the other days with none). */
private fun byDay(list: List<Entry>, zone: ZoneId, today: LocalDate, nearOnly: Boolean = false): List<Pair<String?, List<Entry>>> =
    list.sortedByDescending { it.createdAt }
        .groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }
        .map { (d, items) ->
            val label = Dates.day(d.atStartOfDay(zone).toInstant().toEpochMilli(), zone, today).replaceFirstChar { it.titlecase(PT) }
            (if (!nearOnly || d == today || d == today.minusDays(1)) label else null) to items
        }

@Composable
private fun DiaryCard(e: Entry, onClick: () -> Unit, shape: Shape, photos: List<Photo> = emptyList()) {
    val d = Instant.ofEpochMilli(e.createdAt).atZone(ZoneId.systemDefault())
    Row(
        Modifier.fillMaxWidth().clip(shape).background(NoveloColors.Surface).stitched(shape, StitchColor).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 18.dp).animateContentSize(),
    ) {
        Column(Modifier.width(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(d.dayOfMonth.toString(), style = MaterialTheme.typography.headlineMedium, color = NoveloColors.Text)
            Text(d.month.getDisplayName(TextStyle.SHORT, PT).removeSuffix("."), style = NoveloText.Hand, color = NoveloColors.TextMuted)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.titleLarge, color = NoveloColors.Text, maxLines = 2)
            if (e.body.isNotBlank() && !e.sensitive) {
                Spacer(Modifier.height(4.dp))
                Text(e.body, style = MaterialTheme.typography.bodyMedium, color = NoveloColors.TextMuted, maxLines = 4)
            }
            if (photos.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                DayThumbs(photos)
            }
            Spacer(Modifier.height(6.dp))
            // How many photos the day has (with the next one peeking past the edge: there are more).
            val when_ = d.format(DateTimeFormatter.ofPattern(t(R.string.fmt_weekday_time), PT))
            Text(if (photos.size > 1) when_ + " · " + tn(R.plurals.photos, photos.size) else when_, style = MaterialTheme.typography.labelMedium, color = NoveloColors.TextFaint)
        }
    }
}

/**
 * The day's photos on its diary card: a carousel of different widths that slides sideways — the next
 * one cut at the card's margin says there are more (and the card's line counts them). A tap opens
 * them big from that one, the day's side by side; closed, the list is where it was.
 */
@Composable
private fun DayThumbs(photos: List<Photo>) {
    var opened by rememberSaveable { mutableStateOf<Int?>(null) }
    val aspects = com.villamediana.novelo.ui.components.rememberAspects(photos, min = Mosaic.MIN, max = Mosaic.MAX) ?: return
    androidx.compose.foundation.lazy.LazyRow(
        Modifier.fillMaxWidth().nestedScroll(KeepSideways).testTag("diary-photos"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(photos, key = { _, p -> p.id }) { i, p ->
            // Different widths, even for photos all of one shape.
            val ratio = Mosaic.shape(i, aspects[p.id] ?: 1f)
            Box(Modifier.height(THUMB_TALL).width(THUMB_TALL * ratio).clip(RoundedCornerShape(14.dp)).clickable { opened = i }.testTag("diary-photo-$i")) {
                PhotoTile(p.path, Modifier.fillMaxSize(), maxPx = 360, turn = p.turn)
            }
        }
    }
    opened?.let { start -> com.villamediana.novelo.ui.components.PhotoViewer(photos, start, onClose = { opened = null }) }
}

/**
 * The carousel keeps its sideways swipe to itself: at its end, what is left of the finger (and of a
 * fling) doesn't go on to the tabs — it stops there, it doesn't turn the page.
 */
private val KeepSideways = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = Offset(available.x, 0f)
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = Velocity(available.x, 0f)
}

/** How tall the day's photos stand on its card. */
private val THUMB_TALL = 112.dp

@Composable
private fun Empty(filter: Filter) {
    Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            when (filter) {
                Filter.TODAY -> t(R.string.empty_today)
                Filter.TASKS -> t(R.string.empty_tasks)
                Filter.NOTES -> t(R.string.empty_notes)
                Filter.DIARY -> t(R.string.empty_diary)
                Filter.GALLERY -> t(R.string.empty_gallery)
                Filter.ALL -> t(R.string.empty_all)
                Filter.LINKS -> t(R.string.empty_links)
            },
            style = MaterialTheme.typography.titleMedium,
            color = NoveloColors.TextMuted,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when (filter) {
                Filter.LINKS -> t(R.string.empty_links_line)
                Filter.GALLERY -> t(R.string.empty_gallery_line)
                else -> t(R.string.empty_line)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = NoveloColors.TextFaint,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

/** How long she stops typing before the search by meaning starts (Omni takes a moment). */
private const val MEANING_PAUSE_MS = 500L
