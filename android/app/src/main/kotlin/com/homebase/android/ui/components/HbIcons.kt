package com.homebase.android.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * HomeBase stroke-icon set — 24×24 viewBox, 1.8 stroke, round caps/joins, currentColor.
 * Path data is copied verbatim from the original design mockup so the mobile icons
 * match the design's thin grotesque style. Recolor via Icon(tint = ...).
 */
object HbIcons {
    private fun stroke(path: String): ImageVector =
        ImageVector.Builder(
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = addPathNodes(path),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()

    val home = stroke("M3 11.5 12 4l9 7.5M5 10v9a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-9")
    val check = stroke("M4 12.5 9 17.5 20 6.5")
    val checkCircle = stroke("M9 12.5 11 14.5 15.5 9.5 M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z")
    val circle = stroke("M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z")
    val plus = stroke("M12 5v14M5 12h14")
    val minus = stroke("M5 12h14")
    val cart = stroke("M3 4h2l2.4 12.2a1 1 0 0 0 1 .8h8.2a1 1 0 0 0 1-.8L21 8H6 M10 21a1 1 0 1 0 0-2 1 1 0 0 0 0 2Z M17 21a1 1 0 1 0 0-2 1 1 0 0 0 0 2Z")
    val note = stroke("M6 3h9l4 4v14a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Z M14 3v5h5")
    val clock = stroke("M12 7v5l3 2 M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z")
    val chef = stroke("M7 21h10 M8 17h8v-2a4 4 0 1 0-2.5-7.4 3.5 3.5 0 0 0-7 0A4 4 0 1 0 8 15v2Z")
    // meal planner (#250) — path copied from the web icon set (web/src/ui/Icon.tsx "utensils")
    val utensils = stroke("M3 2v7c0 1.1.9 2 2 2h0a2 2 0 0 0 2-2V2 M5 2v20 M21 15V2a5 5 0 0 0-5 5v6c0 1.1.9 2 2 2h3Zm0 0v7")
    val play = stroke("M8 5.5v13l11-6.5-11-6.5Z")
    val stop = stroke("M7 7h10v10H7z")
    val search = stroke("M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16Z M21 21l-4.3-4.3")
    val tag = stroke("M3 3h7l11 11-7 7L3 10V3Z M7.5 7.5h.01")
    val trash = stroke("M4 7h16 M9 7V5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2 M6 7l1 13a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1l1-13")
    val edit = stroke("M4 20h4L19 9l-4-4L4 16v4Z M14 6l4 4")
    // split an entry (#66) — path copied from the web icon set (web/src/ui/Icon.tsx "scissors")
    val scissors = stroke("M6 9a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z M6 20a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z M20 4 8.1 15.9 M14.5 14.5 20 20 M8.1 8.1l3.4 3.4")
    val x = stroke("M6 6l12 12M18 6 6 18")
    val chevronRight = stroke("M9 6l6 6-6 6")
    val chevronLeft = stroke("M15 6l-6 6 6 6")
    val chevronDown = stroke("M6 9l6 6 6-6")
    val chevronUp = stroke("M6 15l6-6 6 6")
    val calendar = stroke("M4 6a1 1 0 0 1 1-1h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6Z M4 9h16 M8 3v4 M16 3v4")
    val inbox = stroke("M4 13h4l1.5 3h5L16 13h4 M4 13 6 5h12l2 8v6a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-6Z")
    val flag = stroke("M5 21V4 M5 4h12l-2 4 2 4H5")
    val lock = stroke("M7 10V8a5 5 0 0 1 10 0v2 M5 10h14v10H5z")
    val users = stroke("M9 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Z M2.5 20a6.5 6.5 0 0 1 13 0 M16 4.5a3.5 3.5 0 0 1 0 7 M18 14.2A6.5 6.5 0 0 1 21.5 20")
    val archive = stroke("M4 7h16v3H4z M5 10h14v9a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1v-9Z M10 14h4")
    val send = stroke("M4 11.5 20 4l-6 16-2.5-7L4 11.5Z")
    val sparkle = stroke("M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8L12 3Z")
    val dot = stroke("M12 12m-3 0a3 3 0 1 0 6 0 3 3 0 1 0-6 0")
    val menu = stroke("M4 7h16 M4 12h16 M4 17h16")
    val more = stroke("M12 6.5a.6.6 0 1 0 0-1.2.6.6 0 0 0 0 1.2Z M12 12.6a.6.6 0 1 0 0-1.2.6.6 0 0 0 0 1.2Z M12 18.7a.6.6 0 1 0 0-1.2.6.6 0 0 0 0 1.2Z")
    val bell = stroke("M6 9a6 6 0 1 1 12 0c0 5 2 6 2 6H4s2-1 2-6 M9.5 19a2.5 2.5 0 0 0 5 0")
    val list = stroke("M8 6h12 M8 12h12 M8 18h12 M4 6h.01 M4 12h.01 M4 18h.01")
    val grid = stroke("M4 4h6v6H4z M14 4h6v6h-6z M4 14h6v6H4z M14 14h6v6h-6z")
    val folder = stroke("M4 6a1 1 0 0 1 1-1h4l2 2h8a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6Z")
    // repeat / not-yet-synced marker — Lucide "repeat" (web ShoppingView sync badge & banner)
    val repeat = stroke("M17 2l4 4-4 4 M3 11v-1a4 4 0 0 1 4-4h14 M7 22l-4-4 4-4 M21 13v1a4 4 0 0 1-4 4H3")
    // gear — path copied from the web icon set (web/src/ui/Icon.tsx "settings")
    val settings = stroke("M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z M19.4 13a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-2.9 1.2V21a2 2 0 1 1-4 0v-.1A1.7 1.7 0 0 0 8 19.4a1.7 1.7 0 0 0-1.9.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0-1.2-2.9H2a2 2 0 1 1 0-4h.1A1.7 1.7 0 0 0 3.4 8a1.7 1.7 0 0 0-.3-1.9l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.9.3H8a1.7 1.7 0 0 0 1-1.5V2a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.9-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.9V8a1.7 1.7 0 0 0 1.5 1H22a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1Z")
    // logout / sign-out — path copied from the web icon set (web/src/ui/Icon.tsx "logout")
    val logout = stroke("M14 4h4a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-4 M9 12h11 M16 8l4 4-4 4")
    // link / URL import (#460) — Lucide "link" (two chain links), used for "Aus URL importieren"
    val link = stroke("M10 13a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-1.5 1.5 M14 11a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l1.5-1.5")
}
