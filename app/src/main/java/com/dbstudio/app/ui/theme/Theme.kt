package com.dbstudio.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B5BD6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E1FF),
    onPrimaryContainer = Color(0xFF1B1B45),
    secondary = Color(0xFF0EA6A6),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCF2F0),
    onSecondaryContainer = Color(0xFF00201F),
    tertiary = Color(0xFFB0457A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD8E7),
    onTertiaryContainer = Color(0xFF3D0028),
    background = Color(0xFFF5F6FB),
    onBackground = Color(0xFF1A1B22),
    surface = Color(0xFFF5F6FB),
    onSurface = Color(0xFF1A1B22),
    surfaceVariant = Color(0xFFE5E4F2),
    onSurfaceVariant = Color(0xFF47465A),
    surfaceContainer = Color(0xFFEFEFF7),
    surfaceContainerHigh = Color(0xFFE9E8F4),
    outline = Color(0xFF787788),
    outlineVariant = Color(0xFFC9C8DC),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBFC1FF),
    onPrimary = Color(0xFF2A2B78),
    primaryContainer = Color(0xFF414290),
    onPrimaryContainer = Color(0xFFE0E0FF),
    secondary = Color(0xFF6BD6D4),
    onSecondary = Color(0xFF003735),
    secondaryContainer = Color(0xFF00504D),
    onSecondaryContainer = Color(0xFFA8F2EF),
    tertiary = Color(0xFFFFB0CC),
    onTertiary = Color(0xFF640D42),
    tertiaryContainer = Color(0xFF8F2B60),
    onTertiaryContainer = Color(0xFFFFD8E7),
    background = Color(0xFF0F1014),
    onBackground = Color(0xFFE4E4EC),
    surface = Color(0xFF0F1014),
    onSurface = Color(0xFFE4E4EC),
    surfaceVariant = Color(0xFF2B2A38),
    onSurfaceVariant = Color(0xFFC8C6DA),
    surfaceContainer = Color(0xFF1A1A22),
    surfaceContainerHigh = Color(0xFF22222C),
    outline = Color(0xFF9290A6),
    outlineVariant = Color(0xFF3A394A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val AppTypography = Typography()

@Composable
fun DBStudioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}
