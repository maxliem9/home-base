package com.homebase.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val HomeBaseColorScheme = lightColorScheme(
    primary = Hb.accent,
    onPrimary = Hb.onAccent,
    primaryContainer = Hb.accentSoft,
    onPrimaryContainer = Hb.accentInk,
    secondary = Hb.clay,
    background = Hb.paper,
    onBackground = Hb.ink,
    surface = Hb.surface,
    onSurface = Hb.ink,
    surfaceVariant = Hb.surface2,
    onSurfaceVariant = Hb.ink2,
    outline = Hb.line,
    outlineVariant = Hb.lineSoft,
    error = Hb.danger,
    onError = Color.White,
)

@Composable
fun HomeBaseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HomeBaseColorScheme,
        content = content,
    )
}
