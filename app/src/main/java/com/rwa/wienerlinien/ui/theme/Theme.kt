package com.rwa.wienerlinien.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary            = WlRed,
    onPrimary          = Color.White,
    primaryContainer   = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410002),
    secondary          = Neutral800,
    onSecondary        = Color.White,
    background         = Neutral50,
    onBackground       = Neutral900,
    surface            = Color.White,
    onSurface          = Neutral900,
    onSurfaceVariant   = Neutral600,
    outline            = Neutral200,
    outlineVariant     = Color(0xFFE8E8E8),
    error              = Color(0xFFC62828),
    onError            = Color.White,
    tertiary           = Color(0xFFF59E0B),
    onTertiary         = Color.White,
)

private val DarkColorScheme = darkColorScheme(
    primary            = WlRedDark,
    onPrimary          = Color.White,
    primaryContainer   = Color(0xFF7F0011),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary          = Neutral200,
    onSecondary        = Neutral900,
    background         = Color(0xFF0C0C0C),
    onBackground       = Neutral100,
    surface            = Color(0xFF161616),
    onSurface          = Neutral100,
    onSurfaceVariant   = Neutral400,
    outline            = Neutral600,
    outlineVariant     = Neutral700,
    error              = Color(0xFFEF5350),
    onError            = Color.White,
    tertiary           = Color(0xFFFBBF24),
    onTertiary         = Neutral900,
)

@Composable
fun WienerLinienTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography  = AppTypography,
        content     = content
    )
}
