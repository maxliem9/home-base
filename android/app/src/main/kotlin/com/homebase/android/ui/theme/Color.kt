package com.homebase.android.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Convert an OKLCH color to an sRGB [Color].
 *
 * The HomeBase design tokens are authored in OKLCH (see docs/android/android/hb-mobile.css).
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

/**
 * HomeBase design tokens — "klar" look, clay accent (hue 35), light theme.
 * Single source of truth for screen colors; mirrors hb-mobile.css 1:1.
 */
object Hb {
    private const val ACCENT_HUE = 35.0
    private const val ACCENT2_HUE = 42.0

    // surfaces & ink
    val paper = oklch(0.96, 0.014, 128.0)
    val surface = oklch(0.988, 0.008, 128.0)
    val surface2 = oklch(0.935, 0.018, 128.0)
    val surface3 = oklch(0.9, 0.024, 128.0)
    val ink = oklch(0.26, 0.022, 152.0)
    val ink2 = oklch(0.44, 0.02, 152.0)
    val ink3 = oklch(0.57, 0.018, 150.0)
    val line = oklch(0.87, 0.018, 130.0)
    val lineSoft = oklch(0.915, 0.014, 130.0)

    // accent (clay)
    val accent = oklch(0.52, 0.078, ACCENT_HUE)
    val accentStrong = oklch(0.44, 0.085, ACCENT_HUE)
    val accentSoft = oklch(0.925, 0.04, ACCENT_HUE)
    val accentSoft2 = oklch(0.87, 0.058, ACCENT_HUE)
    val accentInk = oklch(0.4, 0.08, ACCENT_HUE)
    val onAccent = oklch(0.985, 0.018, ACCENT_HUE)
    val clay = oklch(0.56, 0.092, ACCENT2_HUE)
    val claySoft = oklch(0.91, 0.045, ACCENT2_HUE)

    // status / semantic
    val overBg = oklch(0.93, 0.05, 32.0)
    val overInk = oklch(0.5, 0.13, 32.0)
    val danger = oklch(0.55, 0.14, 28.0)

    val prioHigh = oklch(0.58, 0.16, 25.0)
    val prioMedium = oklch(0.72, 0.13, 70.0)
    val prioLow = oklch(0.64, 0.08, 195.0)

    // user avatar hue when the username is missing/blank (neutral grey-green).
    private const val USER_HUE_FALLBACK = 150.0

    // overlays
    val scrim = oklch(0.2, 0.02, 70.0, alpha = 0.42f)
    val toastBg = oklch(0.26, 0.022, 152.0)
    val toastInk = oklch(0.97, 0.01, 128.0)

    /** Project swatch palette (hex, from seed data). */
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
