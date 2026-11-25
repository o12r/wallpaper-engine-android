package com.weworkshop.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.History
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.weworkshop.app.AppSettings
import com.weworkshop.app.R
import com.weworkshop.app.download.DownloadManager
import com.weworkshop.app.download.DownloadStatus
import com.weworkshop.app.steam.SortOrder
import com.weworkshop.app.steam.SteamSession
import com.weworkshop.app.steam.Workshop
import com.weworkshop.app.steam.WorkshopItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.Locale

class BrowseViewModel : ViewModel() {
    var sort by mutableStateOf(SortOrder.TREND_WEEK)
    var search by mutableStateOf("")
    var appliedSearch by mutableStateOf("")
    var typeTag by mutableStateOf<String?>(null)
    var genreTag by mutableStateOf<String?>(null)
    var resolutionTag by mutableStateOf<String?>(null)
    var showPresets by mutableStateOf(false)

    val items = mutableStateListOf<WorkshopItem>()
    var loading by mutableStateOf(false)
    var refreshing by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var endReached by mutableStateOf(false)
    var total by mutableStateOf(0)

    private var cursor: String? = null
    private var generation = 0
    private var job: Job? = null

    init {
        refresh()
        // Re-query when the rating filter is changed on the settings screen.
        viewModelScope.launch { AppSettings.allowedAges.drop(1).collect { refresh() } }
    }

    /** Applies (or clears, when blank) a search; search defaults to relevance ranking. */
    fun applySearch(text: String) {
        search = text
        appliedSearch = text.trim()
        if (appliedSearch.isNotEmpty() && appliedSearch != lastSearch) sort = SortOrder.RELEVANCE
        if (appliedSearch.isEmpty() && sort == SortOrder.RELEVANCE) sort = SortOrder.TREND_WEEK
        lastSearch = appliedSearch
        AppSettings.addSearch(appliedSearch)
        refresh()
    }
    private var lastSearch = ""

    /** Shows everything with [tag] (from tapping a tag on a wallpaper's detail page). */
    fun browseTag(tag: String) {
        search = ""; appliedSearch = ""; lastSearch = ""
        if (sort == SortOrder.RELEVANCE) sort = SortOrder.TREND_WEEK
        when (tag) {
            "Preset" -> { typeTag = null; showPresets = true }
            in Workshop.TYPE_TAGS -> typeTag = tag
            in Workshop.RESOLUTION_TAGS -> resolutionTag = tag
            in Workshop.AGE_TAGS -> AppSettings.setAllowedAges(AppSettings.allowedAges.value + tag)
            else -> genreTag = tag
        }
        refresh()
    }

    fun refresh(pull: Boolean = false) {
        job?.cancel()
        generation++
        items.clear()
        cursor = null
        endReached = false
        error = null
        loading = false
        refreshing = pull
        loadMore()
    }

    fun loadMore() {
        if (loading || endReached) return
        loading = true
        error = null
        val gen = generation
        job = viewModelScope.launch {
            try {
                val page = Workshop.query(
                    sort, appliedSearch, typeTag, genreTag, resolutionTag, AppSettings.allowedAges.value, cursor,
                    includePresets = showPresets,
                )
                if (gen != generation) return@launch
                val seen = items.mapTo(HashSet()) { it.id }
                items.addAll(page.items.filter { seen.add(it.id) })
                total = page.total
                cursor = page.nextCursor
                endReached = page.nextCursor == null || page.items.isEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (gen == generation) error = e.message ?: e.javaClass.simpleName
            } finally {
                if (gen == generation) {
                    loading = false
                    refreshing = false
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    connection: SteamSession.State,
    onOpen: (Long) -> Unit,
    onDownloads: () -> Unit,
    onSubscriptions: () -> Unit,
    onSettings: () -> Unit,
    vm: BrowseViewModel = viewModel(),
) {
    val focus = LocalFocusManager.current
    val downloads by DownloadManager.entries.collectAsStateWithLifecycle()
    val activeCount = downloads.values.count { it.status is DownloadStatus.Running || it.status is DownloadStatus.Queued }
    val tagRequest by BrowseRequests.tag.collectAsStateWithLifecycle()
    LaunchedEffect(tagRequest) {
        tagRequest?.let {
            vm.browseTag(it)
            AppSettings.setFiltersExpanded(true)
            BrowseRequests.tag.value = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onSubscriptions) {
                        Icon(Icons.Filled.Subscriptions, stringResource(R.string.action_subscriptions))
                    }
                    IconButton(onClick = onDownloads) {
                        BadgedBox(badge = { if (activeCount > 0) Badge { Text("$activeCount") } }) {
                            Icon(Icons.Filled.Download, stringResource(R.string.action_downloads))
                        }
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.action_settings)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (connection !is SteamSession.State.LoggedIn) {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.browse_connecting), style = MaterialTheme.typography.bodySmall)
                }
            }

            val searchFocused = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            OutlinedTextField(
                value = vm.search,
                onValueChange = { vm.search = it },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (vm.search.isNotEmpty()) IconButton(onClick = { vm.applySearch("") }) { Icon(Icons.Filled.Clear, stringResource(R.string.clear)) }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    focus.clearFocus(); vm.applySearch(vm.search)
                }),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    .onFocusChanged { searchFocused.value = it.isFocused },
            )

            val history by AppSettings.searchHistory.collectAsStateWithLifecycle()
            val typed = vm.search.trim()
            val shown = history.filter { typed.isEmpty() || (it.contains(typed, ignoreCase = true) && !it.equals(typed, ignoreCase = true)) }
            if (searchFocused.value && shown.isNotEmpty()) {
                SearchHistoryList(
                    shown,
                    onPick = { focus.clearFocus(); vm.applySearch(it) },
                    onRemove = { AppSettings.removeSearch(it) },
                    onClear = { AppSettings.clearSearchHistory() },
                )
                return@Column
            }

            FilterPanel(vm)

            PullToRefreshBox(
                isRefreshing = vm.refreshing,
                onRefresh = { vm.refresh(pull = true) },
                modifier = Modifier.fillMaxSize(),
            ) {
                WallpaperGrid(vm, downloads.keys, onOpen)
            }
        }
    }
}

/** Sort / type / tag chips that collapse into a one-line summary. */
@Composable
private fun FilterPanel(vm: BrowseViewModel) {
    val expanded by AppSettings.filtersExpanded.collectAsStateWithLifecycle()
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, label = "arrow")
    val searching = vm.appliedSearch.isNotBlank()

    val sortText = stringResource(vm.sort.label)
    val typeText = vm.typeTag?.let { typeLabel(it) } ?: stringResource(R.string.all_types)
    val summary = listOfNotNull(
        if (searching) "“${vm.appliedSearch}”" else null, sortText, typeText,
        if (vm.showPresets) typeLabel("Preset") else null, vm.genreTag, vm.resolutionTag,
    ).joinToString("  ·  ")

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { AppSettings.setFiltersExpanded(!expanded) }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Tune, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(
            summary,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Filled.ExpandMore,
            stringResource(if (expanded) R.string.filter_collapse else R.string.filter_expand),
            Modifier.rotate(arrow),
        )
    }

    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(bottom = 4.dp)) {
            ChipRow(stringResource(R.string.filter_sort)) {
                SortOrder.entries.filter { it != SortOrder.RELEVANCE || searching }.forEach { s ->
                    FilterChip(
                        selected = vm.sort == s,
                        onClick = { vm.sort = s; vm.refresh() },
                        label = { Text(stringResource(s.label)) },
                    )
                }
            }
            ChipRow(stringResource(R.string.filter_type)) {
                FilterChip(
                    selected = vm.typeTag == null,
                    onClick = { vm.typeTag = null; vm.refresh() },
                    label = { Text(stringResource(R.string.all_types)) },
                )
                listOf("Video", "Scene", "Web", "Application").forEach { t ->
                    FilterChip(
                        selected = vm.typeTag == t,
                        onClick = { vm.typeTag = if (vm.typeTag == t) null else t; vm.refresh() },
                        label = { Text(typeLabel(t)) },
                    )
                }
                FilterChip(
                    selected = vm.showPresets,
                    onClick = { vm.showPresets = !vm.showPresets; vm.refresh() },
                    label = { Text(stringResource(R.string.filter_show_presets)) },
                    leadingIcon = if (vm.showPresets) {
                        { Icon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
                    } else null,
                )
            }
            ChipRow(stringResource(R.string.filter_tag)) {
                FilterChip(
                    selected = vm.genreTag == null,
                    onClick = { vm.genreTag = null; vm.refresh() },
                    label = { Text(stringResource(R.string.filter_any)) },
                )
                // A tag opened from a wallpaper's details may not be in the shortlist.
                val genres = vm.genreTag?.takeIf { it !in Workshop.GENRE_TAGS }?.let { listOf(it) + Workshop.GENRE_TAGS }
                    ?: Workshop.GENRE_TAGS
                genres.forEach { g ->
                    FilterChip(
                        selected = vm.genreTag == g,
                        onClick = { vm.genreTag = if (vm.genreTag == g) null else g; vm.refresh() },
                        label = { Text(g) },
                    )
                }
            }
            ChipRow(stringResource(R.string.filter_resolution)) {
                FilterChip(
                    selected = vm.resolutionTag == null,
                    onClick = { vm.resolutionTag = null; vm.refresh() },
                    label = { Text(stringResource(R.string.filter_any)) },
                )
                Workshop.RESOLUTION_TAGS.forEach { r ->
                    FilterChip(
                        selected = vm.resolutionTag == r,
                        onClick = { vm.resolutionTag = if (vm.resolutionTag == r) null else r; vm.refresh() },
                        label = { Text(resolutionLabel(r)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChipRow(label: String, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        content()
    }
}

@Composable
private fun WallpaperGrid(vm: BrowseViewModel, downloaded: Set<Long>, onOpen: (Long) -> Unit) {
    val state = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf {
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.layoutInfo.totalItemsCount - 8
        }
    }
    LaunchedEffect(nearEnd, vm.items.size) {
        if (nearEnd && vm.items.isNotEmpty() && vm.error == null) vm.loadMore()
    }

    LazyVerticalGrid(
        state = state,
        columns = GridCells.Adaptive(160.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(vm.items, key = { it.id }) { item ->
            WallpaperCard(item, item.id in downloaded) { onOpen(item.id) }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                when {
                    vm.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(vm.error!!, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { if (vm.items.isEmpty()) vm.refresh() else vm.loadMore() }) {
                            Text(stringResource(R.string.retry))
                        }
                    }

                    vm.loading && !vm.refreshing -> CircularProgressIndicator()
                    // Wallpaper Engine doesn't allow publishing application wallpapers to the
                    // Workshop, so this type is empty (Steam's own site lists about 1).
                    vm.endReached && vm.items.isEmpty() && vm.typeTag == "Application" ->
                        Text(stringResource(R.string.no_application_items))
                    vm.endReached && vm.items.isEmpty() -> Text(stringResource(R.string.no_results))
                    vm.endReached -> Text(stringResource(R.string.end_reached), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun WallpaperCard(item: WorkshopItem, downloaded: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box {
            AsyncImage(
                model = item.previewUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0x22000000)),
            )
            Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                item.type?.let { Tag(typeLabel(it)) }
                item.ageRating?.takeIf { it != "Everyone" }?.let { Tag(ageLabel(it), Color(0xCCB3261E)) }
            }
            if (downloaded) {
                Icon(
                    Icons.Filled.DownloadDone, stringResource(R.string.downloaded),
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                        .clip(RoundedCornerShape(50)).background(Color(0xCC2E7D32)).padding(3.dp).size(16.dp),
                )
            }
        }
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${formatSize(item.fileSize)} · ${stringResource(R.string.subs_count, formatCount(item.subscriptions))}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.voteScore > 0f) {
                LinearProgressIndicator(
                    progress = { item.voteScore },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(2.dp),
                )
            }
        }
    }
}

@Composable
fun Tag(text: String, color: Color = Color(0xCC1B2838), onClick: (() -> Unit)? = null) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(color)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** One-shot "browse this tag" requests from other screens (e.g. tapping a tag in details). */
object BrowseRequests {
    val tag = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}

/** "Portrait 1080 x 1920" → "竖屏 1080 x 1920" etc.; other resolution tags are shown as-is. */
@Composable
fun resolutionLabel(tag: String): String = when {
    tag.startsWith("Portrait ") -> stringResource(R.string.res_portrait, tag.removePrefix("Portrait "))
    tag == "Dynamic resolution" -> stringResource(R.string.res_dynamic)
    tag == "Other resolution" -> stringResource(R.string.res_other)
    else -> tag
}

@Composable
fun typeLabel(tag: String): String = Workshop.TYPE_LABELS[tag]?.let { stringResource(it) } ?: tag

@Composable
fun ageLabel(tag: String): String = when (tag) {
    "Everyone" -> stringResource(R.string.age_everyone)
    "Questionable" -> stringResource(R.string.age_questionable)
    "Mature" -> stringResource(R.string.age_mature)
    else -> tag
}

fun formatSpeed(bps: Long): String = when {
    bps < 1024 -> "$bps B/s"
    bps < 1024 * 1024 -> "%.0f KB/s".format(bps / 1024.0)
    else -> "%.1f MB/s".format(bps / 1024.0 / 1024)
}

fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
}

/** 12345 → "1.2万" in Chinese, "12.3K" otherwise. */
fun formatCount(n: Int): String {
    val zh = Locale.getDefault().language == "zh"
    return when {
        zh && n >= 10_000 -> "%.1f万".format(n / 10_000.0)
        !zh && n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        !zh && n >= 1_000 -> "%.1fK".format(n / 1_000.0)
        else -> n.toString()
    }
}

/** Recent searches shown while the search box is focused. */
@Composable
private fun SearchHistoryList(items: List<String>, onPick: (String) -> Unit, onRemove: (String) -> Unit, onClear: () -> Unit) {
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.search_history), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.material3.TextButton(onClick = onClear) { Text(stringResource(R.string.search_history_clear)) }
            }
        }
        items(items.size, key = { items[it] }) { i ->
            val q = items[i]
            Row(
                Modifier.fillMaxWidth().clickable { onPick(q) }.padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(q, Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { onRemove(q) }) { Icon(Icons.Filled.Clear, stringResource(R.string.delete)) }
            }
        }
    }
}
