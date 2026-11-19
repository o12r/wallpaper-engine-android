package com.weworkshop.app.wallpaper

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.weworkshop.app.AppSettings
import com.weworkshop.app.R
import com.weworkshop.app.download.DownloadManager
import com.weworkshop.app.ui.AppTheme
import com.weworkshop.app.ui.PreviewScreen
import com.weworkshop.app.ui.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings screen for the "WE Video Wallpaper" live wallpaper. The system live-wallpaper picker
 * opens it via its Settings button (android:settingsActivity); the app opens it from Settings.
 */
class WallpaperSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppSettings.init(this)
        DownloadManager.init(this)
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var previewId by remember { mutableStateOf<Long?>(null) }
                    val p = previewId
                    if (p != null) {
                        BackHandler { previewId = null }
                        PreviewScreen(p, onBack = { previewId = null })
                    } else {
                        SettingsScreen(onBack = { finish() }, onPreview = { previewId = it })
                    }
                }
            }
        }
    }
}

private data class VideoChoice(val id: Long, val title: String, val preview: String, val file: File)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(onBack: () -> Unit, onPreview: (Long) -> Unit) {
    val ctx = LocalContext.current
    var choices by remember { mutableStateOf<List<VideoChoice>?>(null) }
    var selected by remember { mutableStateOf(WallpaperPrefs.resolveVideo(ctx)?.absolutePath) }
    var muted by remember { mutableStateOf(WallpaperPrefs.muted(ctx)) }

    LaunchedEffect(Unit) {
        choices = withContext(Dispatchers.IO) {
            val entries = DownloadManager.entries.value
            WallpaperPrefs.downloadedVideos().map { (id, f) ->
                val e = entries[id]
                VideoChoice(id, e?.title ?: "#$id", e?.previewUrl ?: "", f)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wp_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Button(onClick = { applyWallpaper(ctx) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Wallpaper, null); Spacer(Modifier.size(8.dp))
                    Text(stringResource(if (VideoWallpaperService.isActive(ctx)) R.string.wp_active_reapply else R.string.wp_apply))
                }
            }
            item {
                Card {
                    Row(
                        Modifier.fillMaxWidth().clickable { muted = !muted; WallpaperPrefs.setMuted(ctx, muted) }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.wp_muted), Modifier.weight(1f))
                        Switch(checked = muted, onCheckedChange = { muted = it; WallpaperPrefs.setMuted(ctx, it) })
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.wp_choose_header),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            val list = choices
            if (list != null && list.isEmpty()) {
                item { Text(stringResource(R.string.wp_none), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(list.orEmpty(), key = { it.id }) { c ->
                val isSel = c.file.absolutePath == selected
                Card(
                    border = if (isSel) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                    modifier = Modifier.fillMaxWidth().clickable {
                        WallpaperPrefs.select(ctx, c.file)
                        selected = c.file.absolutePath
                    },
                ) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = c.preview, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(6.dp)),
                        )
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${c.file.name} · ${formatSize(c.file.length())}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (isSel) Icon(Icons.Filled.CheckCircle, stringResource(R.string.wp_selected), tint = MaterialTheme.colorScheme.primary)
                        IconButton(onClick = { onPreview(c.id) }) { Icon(Icons.Filled.Fullscreen, stringResource(R.string.preview)) }
                    }
                }
            }
        }
    }
}

private fun applyWallpaper(ctx: Context) {
    try {
        ctx.startActivity(VideoWallpaperService.changeIntent(ctx))
    } catch (_: ActivityNotFoundException) {
        try {
            ctx.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            Toast.makeText(ctx, ctx.getString(R.string.pick_in_list), Toast.LENGTH_LONG).show()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, ctx.getString(R.string.wp_no_picker), Toast.LENGTH_LONG).show()
        }
    }
}
