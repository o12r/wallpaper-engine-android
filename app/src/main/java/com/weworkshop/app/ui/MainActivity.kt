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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Root()
                }
            }
        }
    }
}

@Composable
private fun Root() {
    val state by SteamSession.state.collectAsStateWithLifecycle()
    val prompt by SteamSession.prompt.collectAsStateWithLifecycle()

    prompt?.let { AuthPromptDialog(it) }

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
                onSettings = { nav.navigate("settings") },
            )
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
