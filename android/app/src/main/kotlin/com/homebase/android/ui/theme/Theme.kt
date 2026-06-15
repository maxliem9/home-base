package com.homebase.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

// MaterialTheme scheme for the LIGHT palette — drives the few Material3 surfaces (date picker,
// dropdown menus, ripples) that read MaterialTheme.colorScheme rather than an Hb.* token directly.
private val LightColors = lightColorScheme(
    primary = HbLightPalette.accent,
    onPrimary = HbLightPalette.onAccent,
    primaryContainer = HbLightPalette.accentSoft,
    onPrimaryContainer = HbLightPalette.accentInk,
    secondary = HbLightPalette.clay,
    background = HbLightPalette.paper,
    onBackground = HbLightPalette.ink,
    surface = HbLightPalette.surface,
    onSurface = HbLightPalette.ink,
    surfaceVariant = HbLightPalette.surface2,
    onSurfaceVariant = HbLightPalette.ink2,
    outline = HbLightPalette.line,
    outlineVariant = HbLightPalette.lineSoft,
    error = HbLightPalette.danger,
    onError = Color.White,
)

// DARK counterpart (#244) — same role mapping against the dark tokens.
private val DarkColors = darkColorScheme(
    primary = HbDarkPalette.accent,
    onPrimary = HbDarkPalette.onAccent,
    primaryContainer = HbDarkPalette.accentSoft,
    onPrimaryContainer = HbDarkPalette.accentInk,
    secondary = HbDarkPalette.clay,
    background = HbDarkPalette.paper,
    onBackground = HbDarkPalette.ink,
    surface = HbDarkPalette.surface,
    onSurface = HbDarkPalette.ink,
    surfaceVariant = HbDarkPalette.surface2,
    onSurfaceVariant = HbDarkPalette.ink2,
    outline = HbDarkPalette.line,
    outlineVariant = HbDarkPalette.lineSoft,
    error = HbDarkPalette.danger,
    onError = HbDarkPalette.paper,
)

/**
 * App theme wrapper (#244). [dark] is the *resolved* light/dark (the caller turns the stored
 * `light|dark|system` choice — and `isSystemInDarkTheme()` for `system` — into a boolean). It
 * picks the matching [HbPalette] and publishes it through [LocalHbPalette] so every `Hb.<token>`
 * accessor recolours, and selects the matching [MaterialTheme] colour scheme for the handful of
 * pure-Material surfaces. Defaults to light so existing call sites/tests are unchanged.
 */
@Composable
fun HomeBaseTheme(dark: Boolean = false, content: @Composable () -> Unit) {
    val palette = if (dark) HbDarkPalette else HbLightPalette
    CompositionLocalProvider(LocalHbPalette provides palette) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            content = content,
        )
    }
}
