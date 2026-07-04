package com.marnie.pos.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = PrimaryBlue,
    secondary = SuccessGreen,
    background = DarkBg,
    surface = SurfaceDark,
    onBackground = OnSurfaceDark,
    onSurface = OnSurfaceDark,
    error = DangerRed,
)

private val LightColors = lightColorScheme(
    primary = PrimaryBlue,
    secondary = SuccessGreen,
    error = DangerRed,
)

@Composable
fun MarnieTheme(useDarkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (useDarkTheme) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
