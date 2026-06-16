package com.homebase.android.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Typography roles mirroring the original design. The desktop intent is Helvetica Neue;
 * on Android we use the default grotesque (Roboto). Numerals/timers use a monospace
 * family with tabular figures.
 */
object HbType {
    private val ui = FontFamily.Default
    private val monoFamily = FontFamily.Monospace

    // greeting "Hallo, Max." — 38 / 700 / -0.03em
    val greeting = TextStyle(fontFamily = ui, fontSize = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.03).em, lineHeight = 38.sp)

    // app-bar title — 23 / 700 / -0.03em
    val appBarTitle = TextStyle(fontFamily = ui, fontSize = 23.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.03).em, lineHeight = 24.sp)
    val appBarTitleSm = TextStyle(fontFamily = ui, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em, lineHeight = 22.sp)

    // note / recipe detail title — 27 / 700 / -0.025em
    val docTitle = TextStyle(fontFamily = ui, fontSize = 27.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.025).em, lineHeight = 30.sp)

    // bottom-sheet title — 21 / 700 / -0.02em
    val sheetTitle = TextStyle(fontFamily = ui, fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em, lineHeight = 24.sp)

    // eyebrow / section label — uppercase, tracked
    val eyebrow = TextStyle(fontFamily = ui, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.07.em)
    val sectionLabel = TextStyle(fontFamily = ui, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.04.em)

    val cardTitle = TextStyle(fontFamily = ui, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    val rowTitle = TextStyle(fontFamily = ui, fontSize = 15.5.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontFamily = ui, fontSize = 15.sp, lineHeight = 22.sp)
    val label = TextStyle(fontFamily = ui, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val meta = TextStyle(fontFamily = ui, fontSize = 13.sp)
    val small = TextStyle(fontFamily = ui, fontSize = 12.sp)

    // monospace (counts, amounts, clocks) with tabular figures
    val mono = TextStyle(fontFamily = monoFamily, fontSize = 14.sp, fontFeatureSettings = "tnum")
    fun mono(sizeSp: Double, weight: FontWeight = FontWeight.SemiBold) =
        TextStyle(fontFamily = monoFamily, fontSize = sizeSp.sp, fontWeight = weight, fontFeatureSettings = "tnum", letterSpacing = (-0.02).em)
}
