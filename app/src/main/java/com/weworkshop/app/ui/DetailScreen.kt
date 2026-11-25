package com.weworkshop.app.ui

import android.app.WallpaperManager
import android.content.Context
import android.media.MediaMetadataRetriever
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
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
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.weworkshop.app.R
import com.weworkshop.app.download.DownloadManager
import com.weworkshop.app.download.DownloadStatus
import com.weworkshop.app.scene.NativeScene
import com.weworkshop.app.scene.SceneConverter
import com.weworkshop.app.scene.SceneWallpaper
import com.weworkshop.app.steam.Workshop
import com.weworkshop.app.steam.WorkshopItem
import com.weworkshop.app.wallpaper.VideoWallpaperService
import com.weworkshop.app.wallpaper.WebWallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    id: Long,
    onBack: () -> Unit,
    onDownloads: () -> Unit,
    onBrowseTag: (String) -> Unit,
    onOpenItem: (Long) -> Unit,
) {
    val ctx = LocalContext.current
    val downloads by DownloadManager.entries.collectAsStateWithLifecycle()
    val entry = downloads[id]
    // Downloaded wallpapers show immediately from the local entry; Steam data fills in behind.
    var item by remember {
        mutableStateOf(
            Workshop.cache[id] ?: DownloadManager.entries.value[id]?.takeIf { it.status is DownloadStatus.Done }?.let { e ->
                WorkshopItem(
                    id = e.id, title = e.title, previewUrl = e.previewUrl, fileSize = e.fileSize,
                    tags = listOfNotNull(e.type), description = "", subscriptions = 0, favorited = 0,
                    timeUpdated = 0, voteScore = 0f, votesUp = 0, partial = true,
                )
            },
        )
    }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

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
                    .background(Color(0x22000000)),
            ) {
                AsyncImage(
                    model = it.previewUrl,
                    contentDescription = it.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
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
                if (!it.partial) {
                    Tag(stringResource(R.string.tag_subscriptions, formatCount(it.subscriptions)))
                    Tag(stringResource(R.string.tag_favorites, formatCount(it.favorited)))
                }
                if (it.timeUpdated > 0) {
                    Tag(stringResource(R.string.tag_updated, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it.timeUpdated * 1000))))
                }
            }

            DownloadPanel(it, entry?.status, onDownloads, onOpenItem)

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

/** EXPERIMENTAL scene -> MP4 conversion controls. */
/**
 * One way of putting the item on screen: a full width button with an ℹ️ on its right that explains
 * what this rendering does and when to pick it.
 */
/** First frame of [video], used by the still wallpaper route. */
private fun firstFrame(video: File): Bitmap? {
    val mmr = MediaMetadataRetriever()
    return try {
        mmr.setDataSource(video.absolutePath)
        mmr.getFrameAtTime(0)
    } catch (e: Throwable) {
        null
    } finally {
        runCatching { mmr.release() }
    }
}

@Composable
private fun RenderOption(
    label: String,
    info: String,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    var show by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (primary) {
            Button(onClick = onClick, enabled = enabled, modifier = Modifier.weight(1f)) { Text(label) }
        } else {
            OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.weight(1f)) { Text(label) }
        }
        IconButton(onClick = { show = true }) { Icon(Icons.Outlined.Info, stringResource(R.string.info_about)) }
    }
    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text(label) },
            text = { Text(info) },
            confirmButton = { TextButton(onClick = { show = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

@Composable
private fun SceneConvertRow(id: Long, state: SceneConverter.State?, converted: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (state) {
            is SceneConverter.State.Running -> {
                Text(stringResource(R.string.scene_converting, (state.progress * 100).toInt()))
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { SceneConverter.cancel(id) }) { Text(stringResource(R.string.cancel)) }
            }
            else -> {
                if (state is SceneConverter.State.Failed) {
                    Text(stringResource(R.string.scene_convert_failed, state.message), color = MaterialTheme.colorScheme.error)
                }
                OutlinedButton(onClick = { SceneConverter.convert(id) }) {
                    Text(stringResource(if (converted) R.string.scene_reconvert else R.string.scene_convert))
                }
            }
        }
    }
}

private suspend fun setBitmapWallpaper(ctx: Context, bmp: Bitmap) {
    val err = withContext(Dispatchers.IO) {
        try {
            WallpaperManager.getInstance(ctx).setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
            null
        } catch (e: Throwable) { e.message ?: e.javaClass.simpleName }
    }
    if (err == null) Toast.makeText(ctx, R.string.wallpaper_set, Toast.LENGTH_SHORT).show()
    else Toast.makeText(ctx, ctx.getString(R.string.wallpaper_set_failed) + ": " + err, Toast.LENGTH_LONG).show()
}

@Composable
private fun DownloadPanel(
    item: WorkshopItem, status: DownloadStatus?, onDownloads: () -> Unit, onOpenItem: (Long) -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        ConfirmDeleteDialog(item.title, onDismiss = { confirmDelete = false }) { files ->
            DownloadManager.delete(item.id, files); confirmDelete = false
        }
    }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var webBusy by remember { mutableStateOf(false) }
    // Bumped whenever a conversion produces a new file, so the action list is re-read from disk.
    var filesVersion by remember { mutableIntStateOf(0) }
    val cfg = LocalConfiguration.current
    val dens = LocalDensity.current.density
    val pw = ((minOf(cfg.screenWidthDp, cfg.screenHeightDp) * dens).toInt() / 2) * 2
    val ph = ((maxOf(cfg.screenWidthDp, cfg.screenHeightDp) * dens).toInt() / 2) * 2
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (status) {
                null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { DownloadManager.enqueue(item) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Download, null); Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.download_to_phone, formatSize(item.fileSize)))
                    }
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
                    Text("${(status.progress * 100).toInt()}%  ${status.message}" + (if (status.speedBps > 0) "  ·  ${formatSpeed(status.speedBps)}" else ""), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    val convStates by SceneConverter.states.collectAsStateWithLifecycle()
                    val conv = convStates[item.id]
                    // Re-list after a conversion finishes so the new MP4 is picked up.
                    // The converter drops its state entry when it finishes: re-read the folder then, so
                    // the freshly converted MP4 immediately shows up as a "set as live wallpaper" action.
                    var convWasRunning by remember(item.id) { mutableStateOf(false) }
                    LaunchedEffect(conv) {
                        if (conv is SceneConverter.State.Running) convWasRunning = true
                        else if (convWasRunning) { convWasRunning = false; filesVersion++ }
                    }
                    val files = remember(status, conv, filesVersion) { DownloadManager.contentFiles(item.id) }
                    // Converted outputs win over any video shipped with the item (scenes can carry asset clips).
                    val videos = files.filter { it.extension.lowercase() in VideoWallpaperService.VIDEO_EXTENSIONS }
                    val video = videos.firstOrNull { it.name == SceneConverter.OUTPUT_NAME }
                        ?: videos.firstOrNull { it.name == "web_live.mp4" }
                        ?: videos.maxByOrNull { it.length() }
                    val image = files.filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }.maxByOrNull { it.length() }
                    val sceneDir = if (item.type == "Scene") remember(status) { SceneWallpaper.projectDir(item.id) } else null
                    val webFile = remember(status) { WebWallpaper.resolve(item.id, item.type) }

                    Text(stringResource(R.string.downloaded_files, files.size), color = MaterialTheme.colorScheme.secondary)
                    Text(
                        stringResource(R.string.saved_to, status.exportedTo ?: DownloadManager.itemDir(item.id).absolutePath),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // The ways to put this item on screen are stacked, each with its own explanation.
                    when {
                        sceneDir != null && NativeScene.available -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            RenderOption(
                                label = stringResource(R.string.set_scene_wallpaper),
                                info = stringResource(R.string.scene_live_info),
                                primary = true,
                            ) { setSceneWallpaper(ctx, sceneDir) }
                            RenderOption(
                                label = stringResource(R.string.set_static_wallpaper),
                                info = stringResource(R.string.scene_still_info),
                                ) {
                                scope.launch {
                                    val bmp = try {
                                        withContext(Dispatchers.IO) { SceneConverter.still(item.id) }
                                    } catch (e: Throwable) {
                                        Toast.makeText(ctx, ctx.getString(R.string.scene_still_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                                        null
                                    }
                                    if (bmp != null) setBitmapWallpaper(ctx, bmp)
                                }
                            }
                            // The scene may also have been converted to a video: that route is a separate way.
                            if (video != null) {
                                RenderOption(
                                    label = stringResource(R.string.set_live_wallpaper),
                                    info = stringResource(R.string.scene_convert_note),
                                ) { setVideoWallpaper(ctx, video) }
                            }
                        }

                        webFile != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            RenderOption(
                                label = stringResource(R.string.set_web_wallpaper),
                                info = stringResource(R.string.web_note),
                                primary = true,
                            ) { setWebWallpaper(ctx, webFile.first, webFile.second) }
                            RenderOption(
                                label = stringResource(if (webBusy) R.string.web_capture_working else R.string.set_static_wallpaper),
                                info = stringResource(R.string.web_still_info),
                                enabled = !webBusy,
                            ) {
                                scope.launch {
                                    webBusy = true
                                    val bmp = try {
                                        com.weworkshop.app.wallpaper.webStill(ctx, webFile.first, webFile.second, pw, ph, 3000)
                                    } catch (e: Throwable) { null }
                                    if (bmp != null) setBitmapWallpaper(ctx, bmp)
                                    else Toast.makeText(ctx, R.string.web_capture_failed, Toast.LENGTH_LONG).show()
                                    webBusy = false
                                }
                            }
                            RenderOption(
                                label = stringResource(if (webBusy) R.string.web_capture_working else R.string.scene_convert),
                                info = stringResource(R.string.web_mp4_info),
                                enabled = !webBusy,
                            ) {
                                scope.launch {
                                    webBusy = true
                                    val out = File(DownloadManager.contentDir(item.id), "web_live.mp4")
                                    val res = try {
                                        com.weworkshop.app.wallpaper.webToMp4(ctx, webFile.first, webFile.second, pw, ph, out = out, seconds = 10, fps = 30)
                                    } catch (e: Throwable) { null }
                                    if (res != null) {
                                        filesVersion++
                                        Toast.makeText(ctx, out.absolutePath, Toast.LENGTH_LONG).show()
                                    } else Toast.makeText(ctx, R.string.web_capture_failed, Toast.LENGTH_LONG).show()
                                    webBusy = false
                                }
                            }
                            // A converted (or bundled) video is applicable as a live wallpaper too.
                            if (video != null) {
                                RenderOption(
                                    label = stringResource(R.string.set_live_wallpaper),
                                    info = stringResource(R.string.web_mp4_info),
                                ) { setVideoWallpaper(ctx, video) }
                            }
                        }

                        video != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            RenderOption(
                                label = stringResource(R.string.set_live_wallpaper),
                                info = stringResource(R.string.video_live_info),
                                primary = true,
                            ) { setVideoWallpaper(ctx, video) }
                            RenderOption(
                                label = stringResource(R.string.set_static_wallpaper),
                                info = stringResource(R.string.video_still_info),
                            ) {
                                scope.launch {
                                    val bmp = withContext(Dispatchers.IO) { firstFrame(video) }
                                    if (bmp != null) setBitmapWallpaper(ctx, bmp)
                                    else Toast.makeText(ctx, ctx.getString(R.string.scene_still_failed, ""), Toast.LENGTH_LONG).show()
                                }
                            }
                        }

                        image != null -> FilledTonalButton(
                            onClick = { scope.launch { setStaticWallpaper(ctx, image) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.Wallpaper, null); Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.set_static_wallpaper))
                        }
                    }
                    if (sceneDir != null) {
                        SceneNativeNotes(ctx, sceneDir)
                    }
                    if (SceneConverter.canConvert(item.id)) {
                        SceneConvertRow(item.id, conv, converted = video?.name == SceneConverter.OUTPUT_NAME)
                    }
                    if (video == null && item.type == "Scene") {
                        Text(
                            stringResource(R.string.scene_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val dep = if (webFile == null && video == null) remember(status) { WebWallpaper.dependencyId(item.id) } else null
                    if (dep != null) {
                        Text(
                            stringResource(R.string.preset_note, dep.toString()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { onOpenItem(dep) }) { Text(stringResource(R.string.preset_open, dep.toString())) }
                    }
                    when {
                        item.type == "Application" -> Text(
                            stringResource(R.string.app_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if ((status as? DownloadStatus.Done)?.filesMissing == true) Text(
                        stringResource(R.string.dl_missing_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.delete)) }
                        OutlinedButton(onClick = { DownloadManager.enqueue(item) }, enabled = !item.partial) { Text(stringResource(R.string.redownload)) }
                    }
                }
            }
        }
    }
}
