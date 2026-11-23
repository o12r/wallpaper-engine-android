package com.weworkshop.app.ui

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.SurfaceView
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil3.compose.AsyncImage
import com.weworkshop.app.R
import com.weworkshop.app.download.DownloadManager
import com.weworkshop.app.download.DownloadStatus
import com.weworkshop.app.steam.Workshop
import com.weworkshop.app.wallpaper.VideoWallpaperService
import com.weworkshop.app.wallpaper.WebMusicDriver
import com.weworkshop.app.wallpaper.WebWallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")

/**
 * Full-screen preview of how a wallpaper will look: plays the downloaded video exactly like the
 * live wallpaper does (looping, muted, cropped to fill), or shows the image / workshop preview.
 * A mock lock-screen clock is overlaid; tap to hide the controls.
 */
@Composable
fun PreviewScreen(id: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by DownloadManager.entries.collectAsStateWithLifecycle()
    val entry = entries[id]
    val item = Workshop.cache[id]
    val title = item?.title ?: entry?.title ?: ""
    val previewUrl = item?.previewUrl ?: entry?.previewUrl ?: ""
    val done = entry?.status is DownloadStatus.Done

    val files = remember(done) { if (done) DownloadManager.contentFiles(id) else emptyList() }
    val video = files.filter { it.extension.lowercase() in VideoWallpaperService.VIDEO_EXTENSIONS }.maxByOrNull { it.length() }
    val image = files.filter { it.extension.lowercase() in IMAGE_EXTENSIONS }.maxByOrNull { it.length() }
    val web = if (done && entry?.type.equals("Web", true)) remember(done) { WebWallpaper.entryFile(id) } else null

    var showUi by remember { mutableStateOf(true) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showUi = !showUi },
    ) {
        when {
            web != null -> WebPreview(web, Modifier.fillMaxSize())
            video != null -> VideoSurface(video, Modifier.fillMaxSize())
            image != null -> AsyncImage(model = image, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else -> AsyncImage(model = previewUrl, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }

        // Mock lock screen clock, so you can judge readability over the wallpaper.
        AnimatedVisibility(showUi, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            MockClock(Modifier.safeDrawingPadding().padding(top = 72.dp))
        }

        AnimatedVisibility(showUi, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent)))
                        .safeDrawingPadding()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onBack,
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0x66000000), contentColor = Color.White),
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                    Spacer(Modifier.size(8.dp))
                    Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                }

                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000))))
                        .safeDrawingPadding()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!done) {
                        Text(
                            stringResource(R.string.preview_not_downloaded),
                            color = Color.White, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                        )
                    }
                    when {
                        web != null -> Button(onClick = { setWebWallpaper(ctx, web) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Wallpaper, null); Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.set_web_wallpaper))
                        }

                        video != null -> Button(onClick = { setVideoWallpaper(ctx, video) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Wallpaper, null); Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.set_live_wallpaper))
                        }

                        image != null -> Button(onClick = { scope.launch { setStaticWallpaper(ctx, image) } }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Wallpaper, null); Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.set_static_wallpaper))
                        }

                        item != null && entry == null -> Button(onClick = { DownloadManager.enqueue(item) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Download, null); Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.download_to_phone, formatSize(item.fileSize)))
                        }
                    }
                    Text(stringResource(R.string.preview_tap_hint), color = Color(0xCCFFFFFF), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun MockClock(modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date(); delay(1000)
        }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            DateFormat.getTimeInstance(DateFormat.SHORT).format(now),
            color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.Light,
        )
        Text(
            DateFormat.getDateInstance(DateFormat.FULL).format(now),
            color = Color.White, style = MaterialTheme.typography.titleMedium,
        )
    }
}

/** Plays [file] the same way the live wallpaper does: SurfaceView, looping, muted, crop-to-fill. */
@Composable
fun VideoSurface(file: File, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(file) {
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare()
        player.playWhenReady = true
    }
    AndroidView(
        factory = { SurfaceView(it).also { sv -> player.setVideoSurfaceView(sv) } },
        modifier = modifier,
    )
}

/** Renders a Web-type wallpaper's page in a WebView (same setup the web live wallpaper uses). */
@Composable
fun WebPreview(file: File, modifier: Modifier = Modifier) {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val driver = remember { arrayOfNulls<WebMusicDriver>(1) }
    AndroidView(
        factory = { c ->
            android.webkit.WebView(c).also { w ->
                WebWallpaper.configure(w)
                val d = WebMusicDriver(w)
                driver[0] = d
                d.load(file)
                d.start()
            }
        },
        onRelease = { driver[0]?.stop(); it.destroy() },
        modifier = modifier,
    )
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> driver[0]?.start()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> driver[0]?.stop()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
}

fun setWebWallpaper(ctx: Context, page: File) {
    val intent = WebWallpaper.prepare(ctx, page)
    try {
        ctx.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        try {
            ctx.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            Toast.makeText(ctx, ctx.getString(R.string.pick_in_list), Toast.LENGTH_LONG).show()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, ctx.getString(R.string.live_unsupported), Toast.LENGTH_LONG).show()
        }
    }
}

fun setVideoWallpaper(ctx: Context, video: File) {
    val intent = VideoWallpaperService.prepare(ctx, video)
    try {
        ctx.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        try {
            ctx.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            Toast.makeText(ctx, ctx.getString(R.string.pick_in_list), Toast.LENGTH_LONG).show()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, ctx.getString(R.string.live_unsupported), Toast.LENGTH_LONG).show()
        }
    }
}

suspend fun setStaticWallpaper(ctx: Context, image: File) {
    val ok = withContext(Dispatchers.IO) {
        runCatching {
            image.inputStream().use {
                WallpaperManager.getInstance(ctx).setStream(
                    it, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK,
                )
            }
        }.isSuccess
    }
    Toast.makeText(ctx, ctx.getString(if (ok) R.string.wallpaper_set else R.string.wallpaper_set_failed), Toast.LENGTH_SHORT).show()
}
