package com.weworkshop.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.weworkshop.app.R
import com.weworkshop.app.download.DownloadManager
import com.weworkshop.app.download.DownloadStatus
import com.weworkshop.app.steam.Workshop
import com.weworkshop.app.steam.WorkshopItem
import com.weworkshop.app.wallpaper.VideoWallpaperService
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    id: Long,
    onBack: () -> Unit,
    onDownloads: () -> Unit,
    onPreview: (Long) -> Unit,
    onBrowseTag: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var item by remember { mutableStateOf(Workshop.cache[id]) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val downloads by DownloadManager.entries.collectAsStateWithLifecycle()
    val entry = downloads[id]

    LaunchedEffect(id, reload) {
        error = null
        try {
            item = Workshop.details(id)
        } catch (e: Throwable) {
            if (item == null) error = e.message ?: e.javaClass.simpleName
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(item?.title ?: entry?.title ?: stringResource(R.string.detail_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    IconButton(onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://steamcommunity.com/sharedfiles/filedetails/?id=$id")))
                    }) { Icon(Icons.Filled.OpenInBrowser, stringResource(R.string.open_in_browser)) }
                },
            )
        },
    ) { padding ->
        val it = item
        if (it == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                if (error != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { reload++ }) { Text(stringResource(R.string.retry)) }
                } else CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
                    .background(Color(0x22000000)).clickable { onPreview(id) },
            ) {
                AsyncImage(
                    model = it.previewUrl,
                    contentDescription = it.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                Icon(
                    Icons.Filled.Fullscreen, stringResource(R.string.preview), tint = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                        .clip(RoundedCornerShape(50)).background(Color(0x88000000)).padding(4.dp),
                )
            }
            if (it.extraPreviews.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    it.extraPreviews.forEach { url ->
                        AsyncImage(
                            model = url, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(120.dp, 68.dp).clip(RoundedCornerShape(6.dp)),
                        )
                    }
                }
            }

            Text(it.title, style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                it.type?.let { t ->
                    Tag(typeLabel(t), MaterialTheme.colorScheme.primary.copy(alpha = 0.8f), onClick = { onBrowseTag(t) })
                }
                it.ageRating?.let { a -> Tag(ageLabel(a), if (a == "Everyone") Color(0xCC2E7D32) else Color(0xCCB3261E)) }
                Tag(formatSize(it.fileSize))
                Tag(stringResource(R.string.tag_subscriptions, formatCount(it.subscriptions)))
                Tag(stringResource(R.string.tag_favorites, formatCount(it.favorited)))
                if (it.timeUpdated > 0) {
                    Tag(stringResource(R.string.tag_updated, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it.timeUpdated * 1000))))
                }
            }

            DownloadPanel(it, entry?.status, onDownloads, onPreview)

            val otherTags = it.tags.filter { t -> t !in Workshop.TYPE_TAGS && t !in Workshop.AGE_TAGS }
            if (otherTags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    otherTags.forEach { t ->
                        AssistChip(
                            onClick = { onBrowseTag(t) },
                            label = { Text(if (t in Workshop.RESOLUTION_TAGS) resolutionLabel(t) else t) },
                            leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(16.dp)) },
                        )
                    }
                }
            }
            if (it.description.isNotBlank()) {
                Card {
                    SelectionContainer {
                        Text(it.description.trim(), Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadPanel(item: WorkshopItem, status: DownloadStatus?, onDownloads: () -> Unit, onPreview: (Long) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (status) {
                null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { DownloadManager.enqueue(item) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Download, null); Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.download_to_phone, formatSize(item.fileSize)))
                    }
                    OutlinedButton(onClick = { onPreview(item.id) }) { Text(stringResource(R.string.preview)) }
                }

                DownloadStatus.Queued -> {
                    Text(stringResource(R.string.queued))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { DownloadManager.cancel(item.id) }) { Text(stringResource(R.string.cancel_download)) }
                        TextButton(onClick = onDownloads) { Text(stringResource(R.string.view_downloads)) }
                    }
                }

                is DownloadStatus.Running -> {
                    Text("${(status.progress * 100).toInt()}%  ${status.message}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { DownloadManager.cancel(item.id) }) { Text(stringResource(R.string.cancel_download)) }
                        TextButton(onClick = onDownloads) { Text(stringResource(R.string.view_downloads)) }
                    }
                }

                is DownloadStatus.Failed -> {
                    Text(stringResource(R.string.download_failed, status.message), color = MaterialTheme.colorScheme.error)
                    Button(onClick = { DownloadManager.enqueue(item) }) {
                        Icon(Icons.Filled.Refresh, null); Spacer(Modifier.size(8.dp)); Text(stringResource(R.string.retry))
                    }
                }

                is DownloadStatus.Done -> {
                    val files = remember(status) { DownloadManager.contentFiles(item.id) }
                    val video = files.filter { it.extension.lowercase() in VideoWallpaperService.VIDEO_EXTENSIONS }.maxByOrNull { it.length() }
                    val image = files.filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }.maxByOrNull { it.length() }

                    Text(stringResource(R.string.downloaded_files, files.size), color = MaterialTheme.colorScheme.secondary)
                    Text(
                        stringResource(R.string.saved_to, status.exportedTo ?: DownloadManager.itemDir(item.id).absolutePath),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        when {
                            video != null -> Button(onClick = { setVideoWallpaper(ctx, video) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Wallpaper, null); Spacer(Modifier.size(8.dp))
                                Text(stringResource(R.string.set_live_wallpaper))
                            }

                            image != null -> FilledTonalButton(
                                onClick = { scope.launch { setStaticWallpaper(ctx, image) } },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.Wallpaper, null); Spacer(Modifier.size(8.dp))
                                Text(stringResource(R.string.set_static_wallpaper))
                            }

                            else -> Spacer(Modifier.weight(1f))
                        }
                        OutlinedButton(onClick = { onPreview(item.id) }) { Text(stringResource(R.string.preview)) }
                    }
                    if (video == null && item.type == "Scene") {
                        Text(
                            stringResource(R.string.scene_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (item.type == "Web" || item.type == "Application") {
                        Text(
                            stringResource(R.string.web_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { DownloadManager.delete(item.id) }) { Text(stringResource(R.string.delete)) }
                        OutlinedButton(onClick = { DownloadManager.enqueue(item) }) { Text(stringResource(R.string.redownload)) }
                    }
                }
            }
        }
    }
}
