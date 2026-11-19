package com.weworkshop.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.weworkshop.app.R
import com.weworkshop.app.update.UpdateChecker
import com.weworkshop.app.update.UpdateChecker.State

@Composable
fun UpdateDialog() {
    val show by UpdateChecker.dialog.collectAsStateWithLifecycle()
    val state by UpdateChecker.state.collectAsStateWithLifecycle()
    if (!show) return
    val ctx = LocalContext.current
    val release = when (val s = state) {
        is State.Available -> s.release
        is State.Downloading -> s.release
        is State.NeedPermission -> s.release
        is State.Failed -> s.release
        else -> null
    } ?: return

    val downloading = state is State.Downloading
    AlertDialog(
        onDismissRequest = { if (!downloading) UpdateChecker.dismiss() },
        title = { Text(stringResource(R.string.update_available_title, release.tag)) },
        text = {
            Column {
                Text(
                    stringResource(
                        R.string.update_available_body,
                        release.version, UpdateChecker.currentVersion, formatSize(release.apkSize),
                    ),
                )
                if (release.notes.isNotBlank()) {
                    Text(
                        release.notes.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp).heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                when (val s = state) {
                    is State.Downloading -> {
                        Text(stringResource(R.string.update_downloading, s.percent), Modifier.padding(top = 12.dp))
                        LinearProgressIndicator(
                            progress = { s.percent / 100f },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                    is State.NeedPermission -> Text(
                        stringResource(R.string.update_allow_install),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    is State.Failed -> Text(
                        stringResource(R.string.update_failed, s.message),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    else -> {}
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !downloading, onClick = { UpdateChecker.downloadAndInstall(ctx, release) }) {
                Text(stringResource(R.string.update_install))
            }
        },
        dismissButton = {
            if (!downloading) Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl)))
                }) { Text(stringResource(R.string.update_open_release)) }
                TextButton(onClick = { UpdateChecker.skip(release) }) { Text(stringResource(R.string.update_skip)) }
                TextButton(onClick = { UpdateChecker.dismiss() }) { Text(stringResource(R.string.update_later)) }
            }
        },
    )
}
