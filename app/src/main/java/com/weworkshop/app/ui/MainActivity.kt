package com.weworkshop.app.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.weworkshop.app.steam.SteamSession

class MainActivity : ComponentActivity() {
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // The capture grant dies with the process (app update, reboot, force stop), so ask again on the next start.
    private val captureConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val d = r.data
            if (r.resultCode == RESULT_OK && d != null) {
                com.weworkshop.app.wallpaper.AudioCaptureService.start(applicationContext, r.resultCode, d)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        promptAudioCapture()
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Root()
                }
            }
        }
    }

    private fun promptAudioCapture() {
        val s = com.weworkshop.app.wallpaper.AudioCaptureService
        if (s.prompted || s.active.value) return
        if (!com.weworkshop.app.AppSettings.musicReactive.value) return
        if (!com.weworkshop.app.wallpaper.MusicBridge.hasAudioPermission(this)) return
        s.prompted = true
        captureConsent.launch(s.requestIntent(this))
    }
}

@Composable
private fun Root() {
    val state by SteamSession.state.collectAsStateWithLifecycle()
    val prompt by SteamSession.prompt.collectAsStateWithLifecycle()

    prompt?.let { AuthPromptDialog(it) }
    UpdateDialog()
    androidx.compose.runtime.LaunchedEffect(Unit) { com.weworkshop.app.update.UpdateChecker.autoCheck() }

    val showLogin = when (state) {
        is SteamSession.State.NeedLogin -> true
        is SteamSession.State.LoggedIn -> false
        else -> !SteamSession.hasSavedToken
    }
    if (showLogin) {
        LoginScreen(state)
        return
    }

    val nav = rememberNavController()
    NavHost(nav, startDestination = "browse") {
        composable("browse") {
            BrowseScreen(
                connection = state,
                onOpen = { nav.navigate("detail/$it") },
                onDownloads = { nav.navigate("downloads") },
                onSubscriptions = { nav.navigate("subscriptions") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("subscriptions") {
            SubscriptionsScreen(onBack = { nav.popBackStack() }, onOpen = { nav.navigate("detail/$it") })
        }
        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
        composable(
            "preview/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            PreviewScreen(id = entry.arguments!!.getLong("id"), onBack = { nav.popBackStack() })
        }
        composable(
            "detail/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            DetailScreen(
                id = entry.arguments!!.getLong("id"),
                onBack = { nav.popBackStack() },
                onDownloads = { nav.navigate("downloads") },
                onPreview = { nav.navigate("preview/$it") },
                onOpenItem = { nav.navigate("detail/$it") },
                onBrowseTag = { tag ->
                    BrowseRequests.tag.value = tag
                    nav.popBackStack("browse", inclusive = false)
                },
            )
        }
        composable("downloads") {
            DownloadsScreen(
                onBack = { nav.popBackStack() },
                onOpen = { nav.navigate("detail/$it") },
            )
        }
    }
}
