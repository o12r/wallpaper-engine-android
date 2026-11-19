package com.weworkshop.app.ui

import android.app.Activity
import android.graphics.Color as AColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.weworkshop.app.AppSettings
import com.weworkshop.app.ThemeMode

private val SteamBlue = Color(0xFF66C0F4)
private val SteamNavy = Color(0xFF1B2838)
private val SteamDeep = Color(0xFF171A21)
private val SteamPanel = Color(0xFF2A475E)

private val Dark = darkColorScheme(
    primary = SteamBlue,
    onPrimary = SteamDeep,
    primaryContainer = SteamPanel,
    onPrimaryContainer = Color(0xFFDCEFFF),
    secondary = Color(0xFFA4D007),
    onSecondary = SteamDeep,
    secondaryContainer = Color(0xFF2C3D12),
    onSecondaryContainer = Color(0xFFD9F59A),
    background = SteamDeep,
    onBackground = Color(0xFFC7D5E0),
    surface = SteamNavy,
    onSurface = Color(0xFFE4EDF4),
    surfaceVariant = Color(0xFF223547),
    onSurfaceVariant = Color(0xFFA9BACB),
    surfaceContainerLowest = Color(0xFF12161C),
    surfaceContainerLow = Color(0xFF1A2633),
    surfaceContainer = Color(0xFF1F3043),
    surfaceContainerHigh = Color(0xFF26394E),
    surfaceContainerHighest = Color(0xFF2D4359),
    outline = Color(0xFF4A6377),
    outlineVariant = Color(0xFF2E4356),
)

/** Pure #000 backgrounds for OLED screens; accents stay Steam blue. */
private val Black = Dark.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF161A1F),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF080A0C),
    surfaceContainer = Color(0xFF0E1114),
    surfaceContainerHigh = Color(0xFF15191E),
    surfaceContainerHighest = Color(0xFF1C2127),
    primaryContainer = Color(0xFF13293A),
    outline = Color(0xFF3A4652),
    outlineVariant = Color(0xFF22292F),
)

private val Light = lightColorScheme(
    primary = Color(0xFF1A6FA3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E9F9),
    onPrimaryContainer = Color(0xFF00344F),
    secondary = Color(0xFF4C7A00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD4EFA5),
    onSecondaryContainer = Color(0xFF162800),
    background = Color(0xFFF3F6F9),
    onBackground = Color(0xFF16202A),
    surface = Color(0xFFF8FAFC),
    onSurface = Color(0xFF16202A),
    surfaceVariant = Color(0xFFDFE7EF),
    onSurfaceVariant = Color(0xFF475665),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F5F8),
    surfaceContainer = Color(0xFFEAF0F5),
    surfaceContainerHigh = Color(0xFFE3EAF1),
    surfaceContainerHighest = Color(0xFFDCE4EC),
    outline = Color(0xFF7A8996),
    outlineVariant = Color(0xFFC5D0DA),
)

fun schemeFor(mode: ThemeMode, systemDark: Boolean): ColorScheme = when (mode) {
    ThemeMode.LIGHT -> Light
    ThemeMode.DARK -> Dark
    ThemeMode.BLACK -> Black
    ThemeMode.SYSTEM -> if (systemDark) Dark else Light
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val mode by AppSettings.theme.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.BLACK -> true
        ThemeMode.SYSTEM -> systemDark
    }
    val activity = LocalContext.current as? Activity
    LaunchedEffect(dark, activity) {
        // Keep status / navigation bar icons readable when the theme differs from the system's.
        (activity as? ComponentActivity)?.enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(AColor.TRANSPARENT)
            else SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT),
            navigationBarStyle = if (dark) SystemBarStyle.dark(AColor.TRANSPARENT)
            else SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT),
        )
    }
    MaterialTheme(colorScheme = schemeFor(mode, systemDark), content = content)
}
