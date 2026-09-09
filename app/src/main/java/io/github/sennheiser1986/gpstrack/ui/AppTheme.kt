package io.github.sennheiser1986.gpstrack.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val lightScheme = lightColorScheme(
    primary = Color(0xFF1E7A46),
    secondary = Color(0xFF4C9A6B),
    tertiary = Color(0xFF2F6FB0),
    error = Color(0xFFB3261E),
)

private val darkScheme = darkColorScheme(
    primary = Color(0xFF7ED9A6),
    secondary = Color(0xFF9BD6B4),
    tertiary = Color(0xFF9FC6F0),
    error = Color(0xFFF2B8B5),
)

/**
 * Applies the app's Material 3 theme, preferring the device's dynamic colours where available.
 *
 * @param useDarkTheme whether to use the dark scheme; defaults to the system setting.
 * @param content the composable tree to theme.
 */
@Composable
fun TrackRecorderTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (useDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        useDarkTheme -> darkScheme
        else -> lightScheme
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
