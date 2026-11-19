package com.weworkshop.app.ui

import androidx.compose.ui.res.stringResource
import com.weworkshop.app.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.weworkshop.app.steam.SteamSession

@Composable
fun LoginScreen(state: SteamSession.State) {
    var user by rememberSaveable { mutableStateOf(SteamSession.savedAccount ?: "") }
    var pass by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    val busy = state is SteamSession.State.Authenticating
    val connecting = state is SteamSession.State.Connecting

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.Wallpaper, null,
            Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.login_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
            OutlinedTextField(
                value = user, onValueChange = { user = it.trim() },
                label = { Text(stringResource(R.string.login_account)) }, singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = pass, onValueChange = { pass = it },
                label = { Text(stringResource(R.string.login_password)) }, singleLine = true, enabled = !busy,
                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                trailingIcon = {
                    IconButton(onClick = { showPass = !showPass }) {
                        Icon(if (showPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, stringResource(R.string.login_show_password))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            (state as? SteamSession.State.NeedLogin)?.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { SteamSession.login(user, pass) },
                enabled = !busy && user.isNotBlank() && pass.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.login_busy))
                } else {
                    Text(stringResource(R.string.login_button))
                }
            }
            Spacer(Modifier.height(20.dp))
            UnusualLoginTip()
            if (connecting) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.login_connecting),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Steam sometimes holds logins from new clients behind an anti-phishing questionnaire
 * ("This sign in to Steam is unusual for you"). Tell the user which answers are truthful
 * for this app, and which scam-pattern answers to avoid.
 */
@Composable
fun UnusualLoginTip() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.tip_unusual_title), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.tip_unusual_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun AuthPromptDialog(prompt: SteamSession.AuthPrompt) {
    var code by rememberSaveable { mutableStateOf("") }
    val email = prompt.codeType == SteamSession.CodeType.EMAIL
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(if (email) R.string.guard_email_title else R.string.guard_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (prompt.canApprove) {
                    Text(stringResource(R.string.guard_approve_body))
                    Spacer(Modifier.height(8.dp))
                }
                when (prompt.codeType) {
                    SteamSession.CodeType.DEVICE -> Text(
                        stringResource(if (prompt.canApprove) R.string.guard_device_or_body else R.string.guard_device_body),
                    )
                    SteamSession.CodeType.EMAIL -> Text(
                        stringResource(
                            R.string.guard_email_body,
                            prompt.email.orEmpty().ifEmpty { stringResource(R.string.guard_email_fallback) },
                        ),
                    )
                    null -> {}
                }
                if (prompt.previousIncorrect) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.guard_wrong), color = MaterialTheme.colorScheme.error)
                }
                if (prompt.codeType != null) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(5) },
                        singleLine = true,
                        enabled = !prompt.submitting,
                        label = { Text(stringResource(R.string.guard_code_label)) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (email) KeyboardType.Text else KeyboardType.Ascii,
                            imeAction = ImeAction.Done,
                        ),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.guard_waiting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (prompt.canApprove) {
                    Spacer(Modifier.height(12.dp))
                    UnusualLoginTip()
                }
            }
        },
        confirmButton = {
            if (prompt.codeType != null) {
                TextButton(enabled = code.length == 5 && !prompt.submitting, onClick = {
                    SteamSession.submitCode(code)
                    code = ""
                }) { Text(stringResource(R.string.ok)) }
            }
        },
        dismissButton = {
            TextButton(onClick = { SteamSession.cancelLogin() }) { Text(stringResource(R.string.cancel)) }
        },
    )
}
