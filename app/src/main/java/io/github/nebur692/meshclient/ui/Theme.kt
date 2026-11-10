package io.github.nebur692.meshclient.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// The design's colours (Sistema de diseño). / Los colores del diseño.
private val Light = lightColorScheme(
    primary = Color(0xFF00629E), onPrimary = Color.White,
    primaryContainer = Color(0xFFD0E4FF), onPrimaryContainer = Color(0xFF001D34),
    secondaryContainer = Color(0xFFDCE3EC), onSecondaryContainer = Color(0xFF141C24),
    background = Color(0xFFF8F9FC), onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8F9FC), onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE3E7ED), onSurfaceVariant = Color(0xFF42474E),
    surfaceContainer = Color(0xFFEEF1F6), surfaceContainerHigh = Color(0xFFE3E7ED), surfaceContainerHighest = Color(0xFFDCE1E8),
    outline = Color(0xFF72777F), outlineVariant = Color(0xFFC2C7CF),
    error = Color(0xFFBA1A1A), onError = Color.White,
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9CCBFF), onPrimary = Color(0xFF003355),
    primaryContainer = Color(0xFF004A78), onPrimaryContainer = Color(0xFFD0E4FF),
    secondaryContainer = Color(0xFF3B4858), onSecondaryContainer = Color(0xFFD7E3F4),
    background = Color(0xFF111418), onBackground = Color(0xFFE1E2E8),
    surface = Color(0xFF111418), onSurface = Color(0xFFE1E2E8),
    surfaceVariant = Color(0xFF272A2F), onSurfaceVariant = Color(0xFFC2C7CF),
    surfaceContainer = Color(0xFF1D2024), surfaceContainerHigh = Color(0xFF272A2F), surfaceContainerHighest = Color(0xFF32353A),
    outline = Color(0xFF8C9199), outlineVariant = Color(0xFF42474E),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
)

/** Status colours: never alone, always with a word. / Colores de estado: nunca solos, siempre con palabra. */
@Immutable
data class StatusColors(val online: Color, val offline: Color)

val LocalStatusColors = staticCompositionLocalOf { StatusColors(Color(0xFF1E6B3E), Color(0xFF5B6168)) }

@Composable
fun MeshTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    // Android 12+: the wallpaper's colours (Material You); before, the design's. / Android 12+: los colores del fondo.
    val scheme: ColorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    val status = if (dark) StatusColors(Color(0xFF7DDB9A), Color(0xFFA9AEB5)) else StatusColors(Color(0xFF1E6B3E), Color(0xFF5B6168))
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides status) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
