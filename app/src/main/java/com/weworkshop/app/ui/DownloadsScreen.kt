package com.weworkshop.app.ui

import androidx.compose.ui.res.stringResource
import com.weworkshop.app.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.weworkshop.app.download.DownloadManager
import com.weworkshop.app.download.DownloadStatus
import com.weworkshop.app.steam.Workshop

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val entries by DownloadManager.entries.collectAsStateWithLifecycle()
    val queue by DownloadManager.queue.collectAsStateWithLifecycle()
    // Running first, then the queue in download order, then failed, then finished.
    val list = entries.values.sortedWith(
        compareBy<com.weworkshop.app.download.DownloadEntry> {
            when (it.status) {
                is DownloadStatus.Running -> 0
                DownloadStatus.Queued -> 1
                is DownloadStatus.Failed -> 2
                is DownloadStatus.Done -> 3
            }
        }.thenBy { queue.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            .thenByDescending { it.id }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.downloads_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        if (list.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.downloads_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.downloads_location, DownloadManager.PUBLIC_DIR),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(list, key = { it.id }) { e ->
                val pos = queue.indexOf(e.id)
                Card(Modifier.fillMaxWidth().animateItem().clickable { onOpen(e.id) }) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = e.previewUrl, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(6.dp)),
                        )
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = listOfNotNull(e.type?.let { t -> Workshop.TYPE_LABELS[t]?.let { stringResource(it) } ?: t }, formatSize(e.fileSize)).joinToString(" · ")
                            Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            when (val s = e.status) {
                                DownloadStatus.Queued -> {
                                    Text(
                                        stringResource(R.string.status_queued_pos, pos + 1, queue.size),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }

                                is DownloadStatus.Running -> {
                                    Text("${(s.progress * 100).toInt()}%  ${s.message}", maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                                }

                                is DownloadStatus.Failed -> Text(
                                    stringResource(R.string.status_failed, s.message), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                                )

                                is DownloadStatus.Done -> Text(
                                    stringResource(R.string.status_done) + (s.exportedTo?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                        }
                        when (e.status) {
                            DownloadStatus.Queued -> {
                                IconButton(onClick = { DownloadManager.moveToFront(e.id) }, enabled = pos > 0) {
                                    Icon(Icons.Filled.VerticalAlignTop, stringResource(R.string.queue_to_front))
                                }
                                IconButton(onClick = { DownloadManager.move(e.id, -1) }, enabled = pos > 0) {
                                    Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.queue_up))
                                }
                                IconButton(onClick = { DownloadManager.move(e.id, 1) }, enabled = pos in 0 until queue.lastIndex) {
                                    Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.queue_down))
                                }
                                IconButton(onClick = { DownloadManager.cancel(e.id) }) {
                                    Icon(Icons.Filled.Close, stringResource(R.string.cancel_download))
                                }
                            }

                            is DownloadStatus.Running -> IconButton(onClick = { DownloadManager.cancel(e.id) }) {
                                Icon(Icons.Filled.Close, stringResource(R.string.cancel_download))
                            }

                            else -> IconButton(onClick = { DownloadManager.delete(e.id) }) {
                                Icon(Icons.Filled.Delete, stringResource(R.string.delete))
                            }
                        }
                    }
                }
            }
        }
    }
}
