package com.ridelink.intercom.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * RideLink Theme - Motorcycle-Inspired Design
 * Phase 5.2
 */

// Light Theme Colors
private val LightPrimary = Color(0xFF1976D2)
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightPrimaryContainer = Color(0xFFBBDEFB)
private val LightOnPrimaryContainer = Color(0xFF001D36)

private val LightSecondary = Color(0xFFFF6F00)
private val LightOnSecondary = Color(0xFFFFFFFF)
private val LightSecondaryContainer = Color(0xFFFFE0B2)
private val LightOnSecondaryContainer = Color(0xFF4E2600)

private val LightTertiary = Color(0xFF607D8B)
private val LightOnTertiary = Color(0xFFFFFFFF)
private val LightTertiaryContainer = Color(0xFFCFD8DC)
private val LightOnTertiaryContainer = Color(0xFF1C313A)

private val LightBackground = Color(0xFFFAFAFA)
private val LightOnBackground = Color(0xFF1C1B1F)
private val LightSurface = Color(0xFFFFFFFF)
private val LightOnSurface = Color(0xFF1C1B1F)
private val LightSurfaceVariant = Color(0xFFE7E0EC)
private val LightOnSurfaceVariant = Color(0xFF49454F)

// Dark Theme Colors
private val DarkPrimary = Color(0xFF64B5F6)
private val DarkOnPrimary = Color(0xFF003258)
private val DarkPrimaryContainer = Color(0xFF004A77)
private val DarkOnPrimaryContainer = Color(0xFFBBDEFB)

private val DarkSecondary = Color(0xFFFFAB40)
private val DarkOnSecondary = Color(0xFF4E2600)
private val DarkSecondaryContainer = Color(0xFF753800)
private val DarkOnSecondaryContainer = Color(0xFFFFE0B2)

private val DarkTertiary = Color(0xFF90A4AE)
private val DarkOnTertiary = Color(0xFF1C313A)
private val DarkTertiaryContainer = Color(0xFF334E5E)
private val DarkOnTertiaryContainer = Color(0xFFCFD8DC)

private val DarkBackground = Color(0xFF121212)
private val DarkOnBackground = Color(0xFFE6E1E5)
private val DarkSurface = Color(0xFF1E1E1E)
private val DarkOnSurface = Color(0xFFE6E1E5)
private val DarkSurfaceVariant = Color(0xFF2C2C2C)
private val DarkOnSurfaceVariant = Color(0xFFCAC4D0)

val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant
)

val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant
)

@Composable
fun RideLinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
