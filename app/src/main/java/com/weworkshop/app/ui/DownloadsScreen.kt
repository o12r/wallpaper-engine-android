package com.weworkshop.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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

/**
 * Small coloured chip naming the wallpaper type, so the list can be scanned by Scene / Video / Web.
 */
@Composable
private fun TypeTag(type: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            Workshop.TYPE_LABELS[type]?.let { stringResource(it) } ?: type,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val entries by DownloadManager.entries.collectAsStateWithLifecycle()
    val queue by DownloadManager.queue.collectAsStateWithLifecycle()
    val scan = rememberPublicScan()
    var confirmDelete by remember { mutableStateOf<Pair<Long, String>?>(null) }
    confirmDelete?.let { (id, title) ->
        ConfirmDeleteDialog(title, onDismiss = { confirmDelete = null }) { files ->
            DownloadManager.delete(id, files); confirmDelete = null
        }
    }
    // Running first, then the queue in download order, then failed, then finished.
    val list = entries.values.sortedWith(
        compareBy<com.weworkshop.app.download.DownloadEntry> {
            when (it.status) {
                is DownloadStatus.Running -> 0
                DownloadStatus.Queued -> 1
                is DownloadStatus.Failed -> 2
                is DownloadStatus.Done -> if (it.status.filesMissing) 2 else 3
            }
        }.thenBy { queue.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            .thenByDescending { it.id }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val total = list.sumOf { (it.status as? DownloadStatus.Running)?.speedBps ?: 0L }
                    Column {
                        Text(stringResource(R.string.downloads_title))
                        if (total > 0) Text(
                            stringResource(R.string.dl_total_speed, formatSpeed(total)),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    if (scan.scanning) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = scan.start) { Icon(Icons.Filled.FindInPage, stringResource(R.string.scan_public)) }
                    }
                },
            )
        },
    ) { padding ->
        if (list.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.downloads_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = scan.start, enabled = !scan.scanning) { Text(stringResource(R.string.scan_public)) }
                }
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
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                e.type?.takeIf { it.isNotBlank() }?.let { TypeTag(it) }
                                Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            val sub = listOfNotNull(e.type?.takeIf { it.isBlank() }?.let { stringResource(R.string.type_unknown) }, formatSize(e.fileSize)).joinToString(" · ")
                            Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            when (val s = e.status) {
                                DownloadStatus.Queued -> {
                                    Text(
                                        stringResource(R.string.status_queued_pos, pos + 1, queue.size),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }

                                is DownloadStatus.Running -> {
                                    Text("${(s.progress * 100).toInt()}%  ${s.message}" + (if (s.speedBps > 0) "  ·  ${formatSpeed(s.speedBps)}" else ""), maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                                }

                                is DownloadStatus.Failed -> Text(
                                    stringResource(R.string.status_failed, s.message), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                                )

                                is DownloadStatus.Done -> if (s.filesMissing) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            stringResource(R.string.dl_missing),
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                                        )
                                        TextButton(onClick = { onOpen(e.id) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                            Text(stringResource(R.string.redownload), style = MaterialTheme.typography.labelMedium)
                                        }
                                        Spacer(Modifier.weight(1f))
                                        IconButton(onClick = { confirmDelete = e.id to e.title }, modifier = Modifier.size(28.dp)) {
                                            Icon(
                                                Icons.Filled.DeleteOutline, stringResource(R.string.delete),
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                } else Text(
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

                            else -> {
                                if (e.status is DownloadStatus.Done && !e.status.filesMissing) {
                                    val ctx = LocalContext.current
                                    IconButton(onClick = { ExternalOpen.share(ctx, e.id) }) {
                                        Icon(Icons.Filled.Share, stringResource(R.string.share))
                                    }
                                }
                                IconButton(onClick = { confirmDelete = e.id to e.title }) {
                                    Icon(Icons.Filled.Delete, stringResource(R.string.delete))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Asks whether to also delete the files/folder; [onChoose] gets true (delete folder) or false (only remove from list). */
@Composable
fun ConfirmDeleteDialog(title: String, onDismiss: () -> Unit, onChoose: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_confirm_title)) },
        text = { Text(stringResource(R.string.delete_confirm_text, title)) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { onChoose(true) }) { Text(stringResource(R.string.delete_with_files)) }
                TextButton(onClick = { onChoose(false) }) { Text(stringResource(R.string.delete_keep_files)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

class PublicScan(val scanning: Boolean, val start: () -> Unit)

/**
 * Scans Download/WallpaperEngine for existing wallpapers, asking for file access first if needed
 * ("All files access" on Android 11+, which the user grants in system settings).
 */
@Composable
private fun rememberPublicScan(): PublicScan {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var askAllFiles by remember { mutableStateOf(false) }
    var waitingForSettings by rememberSaveable { mutableStateOf(false) }

    fun run() {
        if (scanning) return
        scanning = true
        scope.launch {
            val n = try { DownloadManager.scanPublic() } finally { scanning = false }
            val msg = if (n > 0) ctx.getString(R.string.scan_found, n) else ctx.getString(R.string.scan_none)
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }

    val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) run() else Toast.makeText(ctx, R.string.scan_denied, Toast.LENGTH_SHORT).show()
    }

    // Coming back from the "All files access" settings page.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (waitingForSettings) {
            waitingForSettings = false
            if (DownloadManager.canScanPublic()) run()
            else Toast.makeText(ctx, R.string.scan_denied, Toast.LENGTH_SHORT).show()
        }
    }

    if (askAllFiles) {
        AlertDialog(
            onDismissRequest = { askAllFiles = false },
            title = { Text(stringResource(R.string.scan_permission_title)) },
            text = { Text(stringResource(R.string.scan_permission_body)) },
            confirmButton = {
                TextButton(onClick = {
                    askAllFiles = false
                    waitingForSettings = true
                    val app = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    try {
                        ctx.startActivity(app)
                    } catch (_: Exception) {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }) { Text(stringResource(R.string.scan_open_settings)) }
            },
            dismissButton = { TextButton(onClick = { askAllFiles = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    return PublicScan(scanning) {
        when {
            DownloadManager.canScanPublic() -> run()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> askAllFiles = true
            else -> legacyPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}
