package com.homebase.android.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Convert an OKLCH color to an sRGB [Color].
 *
 * The HomeBase design tokens are authored in OKLCH.
 * Rather than transcribe hand-converted hex values, we convert here so the palette stays
 * faithful to the source and so deterministic per-recipe hues can be computed at runtime.
 *
 * @param l lightness 0..1
 * @param c chroma
 * @param h hue in degrees
 */
fun oklch(l: Double, c: Double, h: Double, alpha: Float = 1f): Color {
    val hr = Math.toRadians(h)
    val a = c * cos(hr)
    val b = c * sin(hr)

    // OKLab -> LMS'
    val l_ = l + 0.3963377774 * a + 0.2158037573 * b
    val m_ = l - 0.1055613458 * a - 0.0638541728 * b
    val s_ = l - 0.0894841775 * a - 1.2914855480 * b

    val lc = l_ * l_ * l_
    val mc = m_ * m_ * m_
    val sc = s_ * s_ * s_

    // LMS -> linear sRGB
    val rl = 4.0767416621 * lc - 3.3077115913 * mc + 0.2309699292 * sc
    val gl = -1.2684380046 * lc + 2.6097574011 * mc - 0.3413193965 * sc
    val bl = -0.0041960863 * lc - 0.7034186147 * mc + 1.7076147010 * sc

    return Color(gamma(rl), gamma(gl), gamma(bl), alpha)
}

private fun gamma(x: Double): Float {
    val v = x.coerceIn(0.0, 1.0)
    val g = if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1.0 / 2.4) - 0.055
    return g.coerceIn(0.0, 1.0).toFloat()
}

private const val ACCENT_HUE = 35.0
private const val ACCENT2_HUE = 42.0

/**
 * The theme-swappable design tokens — every surface/ink/line/accent/status colour that differs
 * between the light and dark theme (#244). Authored as a single bundle so [Hb] can resolve the
 * active set from [LocalHbPalette] and every `Hb.<token>` call site stays unchanged. Hue-derived
 * helpers (avatar/recipe/project colours, see [Hb]) are NOT here — they are not theme-dependent.
 */
data class HbPalette(
    // surfaces & ink
    val paper: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val line: Color,
    val lineSoft: Color,
    // accent (clay)
    val accent: Color,
    val accentStrong: Color,
    val accentSoft: Color,
    val accentSoft2: Color,
    val accentInk: Color,
    val onAccent: Color,
    val clay: Color,
    val claySoft: Color,
    // status / semantic
    val overBg: Color,
    val overInk: Color,
    val danger: Color,
    val prioHigh: Color,
    val prioMedium: Color,
    val prioLow: Color,
    // overlays
    val scrim: Color,
    val toastBg: Color,
    val toastInk: Color,
)

/**
 * HomeBase design tokens — "klar" look, clay accent (hue 35), LIGHT theme.
 * Mirrors the web index.css `:root` 1:1.
 */
val HbLightPalette = HbPalette(
    paper = oklch(0.96, 0.014, 128.0),
    surface = oklch(0.988, 0.008, 128.0),
    surface2 = oklch(0.935, 0.018, 128.0),
    surface3 = oklch(0.9, 0.024, 128.0),
    ink = oklch(0.26, 0.022, 152.0),
    ink2 = oklch(0.44, 0.02, 152.0),
    ink3 = oklch(0.57, 0.018, 150.0),
    line = oklch(0.87, 0.018, 130.0),
    lineSoft = oklch(0.915, 0.014, 130.0),
    accent = oklch(0.52, 0.078, ACCENT_HUE),
    accentStrong = oklch(0.44, 0.085, ACCENT_HUE),
    accentSoft = oklch(0.925, 0.04, ACCENT_HUE),
    accentSoft2 = oklch(0.87, 0.058, ACCENT_HUE),
    accentInk = oklch(0.4, 0.08, ACCENT_HUE),
    onAccent = oklch(0.985, 0.018, ACCENT_HUE),
    clay = oklch(0.56, 0.092, ACCENT2_HUE),
    claySoft = oklch(0.91, 0.045, ACCENT2_HUE),
    overBg = oklch(0.93, 0.05, 32.0),
    overInk = oklch(0.5, 0.13, 32.0),
    danger = oklch(0.55, 0.14, 28.0),
    prioHigh = oklch(0.58, 0.16, 25.0),
    prioMedium = oklch(0.72, 0.13, 70.0),
    prioLow = oklch(0.64, 0.08, 195.0),
    scrim = oklch(0.2, 0.02, 70.0, alpha = 0.42f),
    toastBg = oklch(0.26, 0.022, 152.0),
    toastInk = oklch(0.97, 0.01, 128.0),
)

/**
 * DARK theme tokens (#244). Ported 1:1 from the web's designed `[data-theme="dark"]` block
 * (web/src/index.css) so the two clients stay in visual parity — dark green-tinted surfaces,
 * light ink, a lifted (lighter/more saturated) clay accent that reads on dark, and an inverted
 * on-accent. Status hues (danger/prio) carry over from light, as on the web; the `over` badge,
 * scrim and toast are tuned for a dark backdrop. This is a FIRST proposal — open to design tweaks.
 *
 * NOTE: the Abwesenheit calendar keeps its own deliberately light fill palette (AbwPalette in
 * ui/abwesenheit/AbsenceModel.kt); it is NOT re-themed here (see TODO there).
 */
val HbDarkPalette = HbPalette(
    paper = oklch(0.2, 0.014, 152.0),
    surface = oklch(0.248, 0.016, 152.0),
    surface2 = oklch(0.29, 0.018, 152.0),
    surface3 = oklch(0.34, 0.02, 152.0),
    ink = oklch(0.93, 0.01, 130.0),
    ink2 = oklch(0.74, 0.013, 135.0),
    ink3 = oklch(0.6, 0.014, 140.0),
    line = oklch(0.35, 0.016, 152.0),
    lineSoft = oklch(0.31, 0.014, 152.0),
    accent = oklch(0.7, 0.085, ACCENT_HUE),
    accentStrong = oklch(0.77, 0.095, ACCENT_HUE),
    accentSoft = oklch(0.32, 0.05, ACCENT_HUE),
    accentSoft2 = oklch(0.38, 0.065, ACCENT_HUE),
    accentInk = oklch(0.82, 0.085, ACCENT_HUE),
    onAccent = oklch(0.16, 0.02, ACCENT_HUE),
    clay = oklch(0.74, 0.085, ACCENT2_HUE),
    claySoft = oklch(0.35, 0.05, ACCENT2_HUE),
    // `over` badge on dark — web's `[data-theme="dark"] .hb-badge--over`.
    overBg = oklch(0.36, 0.06, 32.0),
    overInk = oklch(0.82, 0.1, 32.0),
    // Status hues carry over from light (web does not override them); danger lifted slightly for
    // contrast against the dark surface.
    danger = oklch(0.68, 0.15, 28.0),
    prioHigh = oklch(0.66, 0.16, 25.0),
    prioMedium = oklch(0.76, 0.13, 70.0),
    prioLow = oklch(0.7, 0.08, 195.0),
    // A denser black scrim works better over dark surfaces than the warm light-theme one.
    scrim = oklch(0.0, 0.0, 0.0, alpha = 0.6f),
    // The toast inverts: a raised dark surface with light ink (mirrors web using --surface/--ink).
    toastBg = oklch(0.34, 0.02, 152.0),
    toastInk = oklch(0.93, 0.01, 130.0),
)

/**
 * The active [HbPalette] for the current composition (#244). Defaults to [HbLightPalette] so any
 * preview/test that forgets to wrap in [HomeBaseTheme] still renders the historic light look.
 * Provided by [HomeBaseTheme]; read by every `Hb.<token>` accessor below.
 */
val LocalHbPalette = staticCompositionLocalOf { HbLightPalette }

/**
 * HomeBase design tokens — single source of truth for screen colours.
 *
 * The colour tokens (paper/surface/ink/accent/…) are theme-aware (#244): each is a
 * `@Composable @ReadOnlyComposable` getter resolving the active [HbPalette] from [LocalHbPalette],
 * so light/dark swaps without touching the hundreds of `Hb.ink` / `Hb.surface` call sites. The
 * hue-derived helpers below (avatar/project/recipe colours) are plain functions — they are not
 * theme-dependent and stay callable from non-composable code.
 *
 * Caveat: because the tokens are now `@Composable` getters they can only be read inside a
 * composition. The one historic non-composable reader (AbwPalette.workday) was switched to a
 * literal — see ui/abwesenheit/AbsenceModel.kt.
 */
object Hb {
    private const val ACCENT_HUE = 35.0

    // --- theme-swappable tokens (resolve the active palette) ---
    val paper: Color @Composable @ReadOnlyComposable get() = LocalHbPalette.current.paper
    val surface: Color @Composable @ReadOnlyComposable get() = LocalHbPalette.current.surface
    val surface2: Color @Composable @ReadOnlyComposable get() = LocalHbPalette.current.surface2
    val surface3: Color @Composable @ReadOnlyComposable get() = LocalHbPalette.current.surface3
    val ink: Color @Composable @ReadOnlyComposable get() = LocalHbPalette.current.ink
    val ink2: Color @Composable @ReadOnlyComposable get() = LocalHbPalette.current.ink2
    val ink3: Color @Composable @ReadOnlyComposable get() = LocalHbPalette.current.ink3
    val line: Color @Composable @ReadOnlyComposable get() = LocalHbPalette.current.line
    val lineSoft: Color @Composable @ReadOnlyComposable get() = LocalHbPalette.current.lineSoft

    val accent: Color @Composable @ReadOnlyComposable get() = LocalHbPalette.current.accent
    val accentStrong: Color @Composable @ReadOnlyComposable get() = LocalHbPalette.current.accentStrong
    val accentSoft: Color @Composable @ReadOnlyComposable get() = LocalHbPalette.current.accentSoft
    val accentSoft2: Color @Composable @ReadOnlyComposable get() = LocalHbPalette.current.accentSoft2
    val accentInk: Color @Composable @ReadOnlyComposable get() = LocalHbPalette.current.accentInk
    val onAccent: Color @Composable @ReadOnlyComposable get() = LocalHbPalette.current.onAccent
    val clay: Color @Composable @ReadOnlyComposable get() = LocalHbPalette.current.clay
    val claySoft: Color @Composable @ReadOnlyComposable get() = LocalHbPalette.current.claySoft

    val overBg: Color @Composable @ReadOnlyComposable get() = LocalHbPalette.current.overBg
    val overInk: Color @Composable @ReadOnlyComposable get() = LocalHbPalette.current.overInk
    val danger: Color @Composable @ReadOnlyComposable get() = LocalHbPalette.current.danger

    val prioHigh: Color @Composable @ReadOnlyComposable get() = LocalHbPalette.current.prioHigh
    val prioMedium: Color @Composable @ReadOnlyComposable get() = LocalHbPalette.current.prioMedium
    val prioLow: Color @Composable @ReadOnlyComposable get() = LocalHbPalette.current.prioLow

    val scrim: Color @Composable @ReadOnlyComposable get() = LocalHbPalette.current.scrim
    val toastBg: Color @Composable @ReadOnlyComposable get() = LocalHbPalette.current.toastBg
    val toastInk: Color @Composable @ReadOnlyComposable get() = LocalHbPalette.current.toastInk

    // user avatar hue when the username is missing/blank (neutral grey-green).
    private const val USER_HUE_FALLBACK = 150.0

    /** Project swatch palette (hex, from seed data). Theme-independent. */
    val projectSwatches = listOf(
        Color(0xFF5B9E7A), Color(0xFFC9805A), Color(0xFF6A8FC0),
        Color(0xFFC2A14D), Color(0xFFA86AB0), Color(0xFF9A9A9A),
    )

    /**
     * Deterministic avatar hue (0..360) for a username, used for avatars and
     * week-bar segments. Derived from a hash of the *full* lower-cased username so
     * HomeBase works for any household — no hard-coded roster (#155) — and so two
     * members who share a first letter (and therefore the same avatar initial) still
     * get distinct colours (Max & Martina → both "M", different hue; #89).
     * Blank/unknown → a neutral fallback hue. Mirrors hashHue in web/src/ui/format.ts;
     * keep both deterministic (exact cross-platform hue parity not required).
     */
    fun userHue(userId: String?): Double {
        val name = userId?.trim()?.lowercase()
        if (name.isNullOrEmpty()) return USER_HUE_FALLBACK
        var h = 0
        for (ch in name) h = h * 31 + ch.code // 32-bit Int overflow is intended (matches web)
        return (((h % 360) + 360) % 360).toDouble()
    }

    /**
     * Avatar hue honouring a stored per-user override (Teil von #100): a non-null `override`
     * (the hue the member chose, from the household-visible roster, see LocalAvatarHues) wins;
     * null means "automatic" and falls back to the derived [userHue]. Mirrors web's avatarColor.
     */
    fun userHue(userId: String?, override: Int?): Double =
        override?.toDouble() ?: userHue(userId)

    fun userColor(userId: String?): Color = oklch(0.62, 0.09, userHue(userId))

    /** [userColor] with a stored override winning over the derived hue (Teil von #100). */
    fun userColor(userId: String?, override: Int?): Color = oklch(0.62, 0.09, userHue(userId, override))

    /**
     * Avatar initial for a username: its first character, upper-cased (#155).
     * Blank/unknown → "?". Single source of truth for every avatar render site so
     * per-screen hard-coding can't creep back. The single-letter initial can collide
     * for same-first-letter members (Max & Martina → "M"); userHue keeps them
     * visually distinct (#89). Conceptually identical to web's userMeta().initials.
     */
    fun userInitial(userId: String?): String =
        userId?.trim()?.firstOrNull()?.uppercase() ?: "?"

    // recipe placeholder band (deterministic warm hue per recipe)
    fun recipeBandLight(hue: Double) = oklch(0.95, 0.03, hue)
    fun recipeBandDark(hue: Double) = oklch(0.93, 0.04, hue)
    fun recipeBandInk(hue: Double) = oklch(0.55, 0.07, hue)
}
