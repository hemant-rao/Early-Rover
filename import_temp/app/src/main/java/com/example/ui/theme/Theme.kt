package com.example.ui.theme

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

private val SleekDarkColorScheme = darkColorScheme(
    primary = SleekDarkPrimary,
    secondary = SleekDarkSecondary,
    background = SleekDarkBackground,
    surface = SleekDarkCardBg,
    onBackground = SleekDarkActiveText,
    onSurface = SleekDarkActiveText,
    onPrimary = Color.White,
    onSecondary = Color.White,
    outline = SleekDarkBorder,
    surfaceVariant = SleekDarkCardBg,
    onSurfaceVariant = SleekDarkMutedText,
    error = Color(0xFFEF4444),
    primaryContainer = SleekDarkPrimary.copy(alpha = 0.2f),
    onPrimaryContainer = SleekDarkActiveText,
    secondaryContainer = SleekDarkSecondary.copy(alpha = 0.15f),
    onSecondaryContainer = SleekDarkActiveText
)

private val SleekLightColorScheme = lightColorScheme(
    primary = SleekLightPrimary,
    secondary = SleekLightSecondary,
    background = SleekLightBackground,
    surface = SleekLightCardBg,
    onBackground = SleekLightActiveText,
    onSurface = SleekLightActiveText,
    onPrimary = Color.White,
    onSecondary = Color.White,
    outline = SleekLightBorder,
    surfaceVariant = SleekLightCardBg,
    onSurfaceVariant = SleekLightMutedText,
    error = Color(0xFFEF4444),
    primaryContainer = SleekLightPrimary.copy(alpha = 0.25f),
    onPrimaryContainer = SleekLightActiveText,
    secondaryContainer = SleekLightSecondary.copy(alpha = 0.2f),
    onSecondaryContainer = SleekLightActiveText
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Keep exact designer palette for brand consistency unless explicitly disabled
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> SleekDarkColorScheme
        else -> SleekLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
