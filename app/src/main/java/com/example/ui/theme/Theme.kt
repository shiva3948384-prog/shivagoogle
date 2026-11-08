package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.domain.models.ThemeMode

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = Color.White,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = DarkSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF281E47),
    onSecondaryContainer = Color(0xFFE9D8FD),
    tertiary = DarkTertiary,
    onTertiary = Color(0xFF06202B),
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceElevated,
    onSurfaceVariant = DarkTextSecondary,
    surfaceContainerLowest = Color(0xFF06080D),
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurfaceElevated,
    surfaceContainerHigh = DarkSurfaceHigh,
    surfaceContainerHighest = Color(0xFF27334A),
    outline = DarkOutline,
    outlineVariant = Color(0xFF192233),
    error = StatusErrorRed,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color.White,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = LightSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE9FE),
    onSecondaryContainer = Color(0xFF4C1D95),
    tertiary = LightTertiary,
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceElevated,
    onSurfaceVariant = LightTextSecondary,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF3F6FB),
    surfaceContainer = LightSurfaceElevated,
    surfaceContainerHigh = LightSurfaceHigh,
    surfaceContainerHighest = Color(0xFFD8E2F0),
    outline = LightOutline,
    outlineVariant = Color(0xFFE5ECF6),
    error = StatusErrorRed,
    onError = Color.White
)

val SmarterAiShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

@Composable
fun NexusAiTheme(
    darkTheme: Boolean = true,
    reducedAnimations: Boolean = false,
    themeMode: ThemeMode? = null,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM, null -> darkTheme
    }
    val colorScheme = if (isDark) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = SmarterAiShapes,
        content = content
    )
}
