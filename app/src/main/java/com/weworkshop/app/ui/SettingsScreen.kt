package com.weworkshop.app.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.weworkshop.app.AppLanguage
import com.weworkshop.app.AppSettings
import com.weworkshop.app.R
import com.weworkshop.app.ThemeMode
import com.weworkshop.app.steam.SteamSession
import com.weworkshop.app.steam.Workshop
import com.weworkshop.app.wallpaper.WallpaperSettingsActivity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val theme by AppSettings.theme.collectAsStateWithLifecycle()
    val ages by AppSettings.allowedAges.collectAsStateWithLifecycle()
    var language by remember { mutableStateOf(AppSettings.language(ctx)) }
    var confirmLogout by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section(stringResource(R.string.settings_appearance)) {
                ThemeMode.entries.forEach { m ->
                    RadioRow(stringResource(m.label), theme == m) { AppSettings.setTheme(m) }
                }
            }

            Section(stringResource(R.string.settings_language)) {
                if (AppSettings.languageSupported) {
                    // Endonyms are deliberately not translated.
                    val options = listOf(
                        AppLanguage.SYSTEM to stringResource(R.string.lang_system),
                        AppLanguage.ENGLISH to "English",
                        AppLanguage.CHINESE to "简体中文",
                    )
                    options.forEach { (lang, label) ->
                        RadioRow(label, language == lang) {
                            language = lang
                            AppSettings.setLanguage(ctx, lang)
                        }
                    }
                } else {
                    Text(
                        stringResource(R.string.lang_unsupported),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }

            Section(stringResource(R.string.settings_content)) {
                Text(
                    stringResource(R.string.settings_content_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Workshop.AGE_TAGS.forEach { a ->
                    val checked = a in ages
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !(checked && ages.size == 1)) {
                            AppSettings.setAllowedAges(if (checked) ages - a else ages + a)
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            enabled = !(checked && ages.size == 1), // keep at least one rating
                            onCheckedChange = { AppSettings.setAllowedAges(if (it) ages + a else ages - a) },
                        )
                        Text(ageLabel(a))
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { ctx.startActivity(Intent(ctx, WallpaperSettingsActivity::class.java)) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Wallpaper, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.settings_live_wallpaper), Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
            }

            Section(stringResource(R.string.settings_account)) {
                Text(stringResource(R.string.logged_in_as, SteamSession.savedAccount ?: "—"))
                OutlinedButton(onClick = { confirmLogout = true }, modifier = Modifier.padding(top = 8.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Logout, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.logout))
                }
            }
        }
    }

    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        title = { Text(stringResource(R.string.logout_confirm_title)) },
        text = { Text(stringResource(R.string.logged_in_as, SteamSession.savedAccount ?: "")) },
        confirmButton = {
            TextButton(onClick = { confirmLogout = false; SteamSession.logout() }) { Text(stringResource(R.string.logout)) }
        },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.padding(top = 4.dp))
            content()
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Text(label)
    }
}
