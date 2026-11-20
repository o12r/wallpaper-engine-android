package com.weworkshop.app.wallpaper

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
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
import kotlin.math.roundToInt

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

private const val STATUS_BAR_FIX_URL = "https://github.com/o12r/live-wallpaper-statusbar-fix/releases/latest"

private data class VideoChoice(val id: Long, val title: String, val preview: String, val file: File)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(onBack: () -> Unit, onPreview: (Long) -> Unit) {
    val ctx = LocalContext.current
    var choices by remember { mutableStateOf<List<VideoChoice>?>(null) }
    var selected by remember { mutableStateOf(WallpaperPrefs.resolveVideo(ctx)?.absolutePath) }
    var muted by remember { mutableStateOf(WallpaperPrefs.muted(ctx)) }
    var statusText by remember { mutableStateOf(WallpaperPrefs.statusText(ctx)) }
    // What Auto decided for the selected video: true = dark text, false = light, null = unknown.
    var autoColors by remember { mutableStateOf<VideoWallpaperService.Companion.VideoColors?>(null) }
    var look by remember { mutableStateOf(WallpaperPrefs.look(ctx)) }
    val autoDark = autoColors?.darkText(look)
    val aspect = remember { screenAspect(ctx) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var editingCrop by remember { mutableStateOf(false) }
    LaunchedEffect(selected) {
        val f = selected?.let(::File) ?: return@LaunchedEffect
        frame = withContext(Dispatchers.IO) { loadPreviewFrame(f) }
    }
    if (editingCrop) {
        CropEditorScreen(
            frame, look, aspect,
            onDone = { look = it; WallpaperPrefs.setLook(ctx, it); editingCrop = false },
            onCancel = { editingCrop = false },
        )
        return
    }
    var analyzing by remember { mutableStateOf(false) }

    LaunchedEffect(selected, look.geometryKey) {
        val f = selected?.let(::File) ?: return@LaunchedEffect
        autoColors = WallpaperPrefs.cachedColors(ctx, f)
        if (autoColors == null) {
            analyzing = true
            autoColors = withContext(Dispatchers.IO) { VideoWallpaperService.analyze(ctx, f) }
            analyzing = false
        }
    }

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
                Card {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(stringResource(R.string.wp_status_text))
                        Spacer(Modifier.size(8.dp))
                        val options = listOf(
                            WallpaperPrefs.TEXT_AUTO to R.string.wp_status_auto,
                            WallpaperPrefs.TEXT_DARK to R.string.wp_status_dark,
                            WallpaperPrefs.TEXT_LIGHT to R.string.wp_status_light,
                        )
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            options.forEachIndexed { i, (value, label) ->
                                SegmentedButton(
                                    selected = statusText == value,
                                    onClick = { statusText = value; WallpaperPrefs.setStatusText(ctx, value) },
                                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                                ) { Text(stringResource(label)) }
                            }
                        }
                        Spacer(Modifier.size(8.dp))
                        Text(
                            when {
                                analyzing -> stringResource(R.string.wp_status_analyzing)
                                autoDark == true -> stringResource(R.string.wp_status_auto_dark)
                                autoDark == false -> stringResource(R.string.wp_status_auto_light)
                                else -> stringResource(R.string.wp_status_auto_failed)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            stringResource(R.string.wp_status_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // Launchers like ZTE MiFavor ignore the hint; a separate root/LSPosed module fixes that.
                        val uriHandler = LocalUriHandler.current
                        Spacer(Modifier.size(8.dp))
                        Text(
                            stringResource(R.string.wp_status_module),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier.clickable { uriHandler.openUri(STATUS_BAR_FIX_URL) },
                        )
                    }
                }
            }
            item {
                LookCard(look) { look = it; WallpaperPrefs.setLook(ctx, it) }
            }
            item {
                TransformCard(frame, look, aspect, onEdit = { editingCrop = true }) {
                    look = look.copy(rotation = 0, flipH = false, flipV = false, zoom = 1f, panX = 0f, panY = 0f)
                    WallpaperPrefs.setLook(ctx, look)
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

/** Shade, blur, saturation and upscaling. Sliders save when released (blur rebuilds the player). */
@Composable
private fun LookCard(look: WallpaperLook, onChange: (WallpaperLook) -> Unit) {
    var draft by remember(look) { mutableStateOf(look) }
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(stringResource(R.string.wp_effects))
            LookSlider(R.string.wp_shade, draft.shade, 0..100, { draft = draft.copy(shade = it) }) { onChange(draft) }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                listOf(false to R.string.wp_shade_black, true to R.string.wp_shade_white).forEachIndexed { i, (white, label) ->
                    SegmentedButton(
                        selected = draft.shadeWhite == white,
                        onClick = { onChange(draft.copy(shadeWhite = white)) },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(stringResource(label)) }
                }
            }
            Text(
                stringResource(R.string.wp_shade_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LookSlider(R.string.wp_blur, draft.blur, 0..100, { draft = draft.copy(blur = it) }) { onChange(draft) }
            LookSlider(R.string.wp_saturation, draft.saturation, 0..200, { draft = draft.copy(saturation = it) }) { onChange(draft) }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp)
                    .clickable { onChange(draft.copy(upscale = !draft.upscale)) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.wp_upscale))
                    Text(
                        stringResource(if (draft.blur > 0) R.string.wp_upscale_blur else R.string.wp_upscale_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = draft.upscale, onCheckedChange = { onChange(draft.copy(upscale = it)) })
            }
            val reset = WallpaperLook(rotation = draft.rotation, flipH = draft.flipH, flipV = draft.flipV, zoom = draft.zoom, panX = draft.panX, panY = draft.panY)

            if (draft != reset) {
                TextButton(onClick = { onChange(reset) }, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.wp_effects_reset))
                }
            }
        }
    }
}

@Composable
private fun LookSlider(label: Int, value: Int, range: IntRange, onValue: (Int) -> Unit, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f))
        Text("$value%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(
        value = value.toFloat(),
        onValueChange = { onValue(it.roundToInt()) },
        onValueChangeFinished = onDone,
        valueRange = range.first.toFloat()..range.last.toFloat(),
    )
}

/** What the screen shows (crop, rotation, flips), with a button to open the crop editor. */
@Composable
private fun TransformCard(frame: Bitmap?, look: WallpaperLook, aspect: Float, onEdit: () -> Unit, onReset: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CropThumbnail(frame, look, aspect, Modifier.size(width = 72.dp, height = 144.dp))
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.wp_transform))
                Text(
                    stringResource(R.string.wp_transform_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    TextButton(onClick = onEdit) { Text(stringResource(R.string.wp_crop_edit)) }
                    if (look.hasGeometry) TextButton(onClick = onReset) { Text(stringResource(R.string.wp_crop_reset)) }
                }
            }
        }
    }
}
