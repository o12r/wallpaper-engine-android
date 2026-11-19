package com.weworkshop.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.weworkshop.app.R
import com.weworkshop.app.download.DownloadManager
import com.weworkshop.app.steam.Workshop
import com.weworkshop.app.steam.WorkshopItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Lists the account's Workshop subscriptions. Nothing is downloaded until the user asks. */
class SubscriptionsViewModel : ViewModel() {
    val items = mutableStateListOf<WorkshopItem>()
    var loading by mutableStateOf(false); private set
    var refreshing by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var endReached by mutableStateOf(false); private set
    var total by mutableIntStateOf(-1); private set
    private var nextPage = 1
    private var job: Job? = null

    init { refresh() }

    fun refresh(pull: Boolean = false) {
        job?.cancel()
        items.clear()
        nextPage = 1
        endReached = false
        error = null
        refreshing = pull
        load()
    }

    fun loadMore() {
        if (loading || endReached || error != null) return
        load()
    }

    fun retry() {
        error = null
        if (items.isEmpty()) refresh() else load()
    }

    private fun load() {
        job = viewModelScope.launch {
            loading = true
            try {
                val page = Workshop.subscribed(nextPage)
                val seen = items.mapTo(HashSet()) { it.id }
                items.addAll(page.items.filter { it.id !in seen })
                total = page.total
                val next = page.nextCursor?.toIntOrNull()
                if (next == null) endReached = true else nextPage = next
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                loading = false
                refreshing = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    vm: SubscriptionsViewModel = viewModel(),
) {
    val downloads by DownloadManager.entries.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.subscriptions_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = vm.refreshing,
            onRefresh = { vm.refresh(pull = true) },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            val state = rememberLazyGridState()
            val nearEnd by remember {
                derivedStateOf {
                    val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    last >= state.layoutInfo.totalItemsCount - 8
                }
            }
            LaunchedEffect(nearEnd, vm.items.size) {
                if (nearEnd && vm.items.isNotEmpty()) vm.loadMore()
            }
            LazyVerticalGrid(
                state = state,
                columns = GridCells.Adaptive(160.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    val hint = stringResource(R.string.subscriptions_hint)
                    val count = if (vm.total >= 0) stringResource(R.string.subscriptions_count, vm.total) else null
                    Text(
                        listOfNotNull(count, hint).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(vm.items, key = { it.id }) { item ->
                    WallpaperCard(item, item.id in downloads.keys) { onOpen(item.id) }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        when {
                            vm.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(vm.error!!, color = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = { vm.retry() }) { Text(stringResource(R.string.retry)) }
                            }
                            vm.loading && !vm.refreshing -> CircularProgressIndicator()
                            vm.endReached && vm.items.isEmpty() -> Text(stringResource(R.string.subscriptions_empty))
                            vm.endReached -> Text(
                                stringResource(R.string.end_reached),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
