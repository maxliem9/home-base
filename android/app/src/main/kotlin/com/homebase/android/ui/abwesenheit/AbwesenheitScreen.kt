@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.homebase.android.ui.abwesenheit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebase.android.data.model.AbsenceStateDto
import com.homebase.android.data.model.UpdateAbsSettingsRequest
import com.homebase.android.ui.components.HbAvatar
import com.homebase.android.ui.components.HbAppBar
import com.homebase.android.ui.components.HbBadge
import com.homebase.android.ui.components.HbBottomSheet
import com.homebase.android.ui.components.HbButton
import com.homebase.android.ui.components.HbButtonSize
import com.homebase.android.ui.components.HbButtonVariant
import com.homebase.android.ui.components.HbCard
import com.homebase.android.ui.components.HbDivider
import com.homebase.android.ui.components.HbFab
import com.homebase.android.ui.components.HbField
import com.homebase.android.ui.components.HbIcon
import com.homebase.android.ui.components.HbIconButton
import com.homebase.android.ui.components.HbIcons
import com.homebase.android.ui.components.HbPickText
import com.homebase.android.ui.components.HbPill
import com.homebase.android.ui.components.HbRadiusSm
import com.homebase.android.ui.components.HbScreenScaffold
import com.homebase.android.ui.components.HbSegmented
import com.homebase.android.ui.components.HbTextField
import com.homebase.android.ui.components.HbToast
import com.homebase.android.ui.components.HbTone
import com.homebase.android.ui.components.displayName
import com.homebase.android.ui.theme.Hb
import com.homebase.android.ui.theme.HbType
import com.homebase.android.ui.theme.oklch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// ---------------------------------------------------------------------------
// Abwesenheit / Familienkalender — shared household absence planner.
// Compose port of docs/android/android/m-screens-abwesenheit.jsx: month-first
// calendar, transposed year raster, combined summary, and three bottom sheets
// (day editor, Zeitraum, Einstellungen). Logic lives in AbsenceModel/Holidays.
// ---------------------------------------------------------------------------

private val WD_LONG = listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")

@Composable
fun AbwesenheitScreen(viewModel: AbsenceViewModel, onOpenDrawer: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val data = state.data
    val userIds = data.users

    var isMonth by remember { mutableStateOf(true) } // phone leads with the month view
    var year by remember { mutableStateOf(LocalDate.now().year) }
    var month by remember { mutableStateOf(LocalDate.now().monthValue - 1) } // 0-based

    var editDs by remember { mutableStateOf<String?>(null) }
    var showRange by remember { mutableStateOf(false) }
    var rangePrefill by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    val ctx = remember(data, year, userIds) { buildContext(data, year, userIds) }
    val today = AbwCal.ymd(LocalDate.now())

    Box(Modifier.fillMaxSize()) {
        HbScreenScaffold(
            appBar = {
                HbAppBar(
                    eyebrow = "Familienkalender",
                    title = "Kalender",
                    onLeft = onOpenDrawer,
                    actions = { HbIconButton(HbIcons.edit, { showSettings = true }) },
                )
            },
            fab = {
                HbFab(
                    onClick = { rangePrefill = today to today; showRange = true },
                    label = "Zeitraum",
                )
            },
        ) {
            HbSegmented(
                options = listOf("Jahr", "Monat"),
                selectedIndex = if (isMonth) 1 else 0,
                onSelect = { isMonth = it == 1 },
                modifier = Modifier.padding(horizontal = 18.dp),
            )

            Spacer(Modifier.size(16.dp))

            when {
                state.isLoading && userIds.isEmpty() ->
                    Text("Lädt …", style = HbType.meta, color = Hb.ink3, modifier = Modifier.padding(horizontal = 18.dp))
                userIds.isEmpty() ->
                    Text("Kalender konnte nicht geladen werden.", style = HbType.meta, color = Hb.ink3, modifier = Modifier.padding(horizontal = 18.dp))
                else -> {
                    SummaryCard(ctx, userIds, today)
                    Spacer(Modifier.size(16.dp))

                    if (isMonth) {
                        MonthGrid(
                            ctx = ctx, userIds = userIds, today = today, year = year, month = month,
                            onMonth = { newM ->
                                if (newM < 0) { month = 11; year -= 1 }
                                else if (newM > 11) { month = 0; year += 1 }
                                else month = newM
                            },
                            onPick = { editDs = it },
                        )
                    } else {
                        YearGrid(
                            ctx = ctx, userIds = userIds, today = today, year = year,
                            onYear = { year = it },
                            onPick = { editDs = it },
                        )
                    }

                    Spacer(Modifier.size(16.dp))
                    Legend(userIds)
                }
            }
        }

        // --- Sheets ---------------------------------------------------------
        editDs?.let { ds ->
            DayEditorSheet(ctx = ctx, ds = ds, userIds = userIds, vm = viewModel, onDismiss = { editDs = null })
        }
        if (showRange) {
            RangeSheet(
                data = data, userIds = userIds, prefill = rangePrefill, vm = viewModel,
                onDismiss = { showRange = false },
            )
        }
        if (showSettings) {
            SettingsSheet(ctx = ctx, data = data, userIds = userIds, year = year, vm = viewModel, onDismiss = { showSettings = false })
        }

        state.error?.let { msg ->
            HbToast(message = msg, icon = HbIcons.x, actionLabel = "OK", onAction = { viewModel.clearError() })
        }
    }
}

// ---------------------------------------------------------------------------
// Combined summary card (.abwm-sum) — both people stacked
// ---------------------------------------------------------------------------

@Composable
private fun SummaryCard(ctx: AbsCtx, userIds: List<String>, today: String) {
    HbCard(pad = false, modifier = Modifier.padding(horizontal = 18.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            userIds.forEachIndexed { i, uid ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Hb.lineSoft))
                PersonSummary(summarize(ctx, uid, today), ctx.hue[uid] ?: Hb.userHue(uid), uid)
            }
        }
    }
}

@Composable
private fun PersonSummary(s: AbsSummary, hue: Double, uid: String) {
    val accentColor = oklch(0.6, 0.1, hue)
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            HbAvatar(uid, size = 30.dp)
            Column(Modifier.weight(1f)) {
                Text(displayName(uid), style = HbType.rowTitle.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = Hb.ink)
                Text("${AbwCal.stateName(s.state)} · Anspruch ${fmtDays(s.allowance)}", style = HbType.small.copy(fontSize = 11.5.sp), color = Hb.ink3)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(fmtDays(s.remaining), style = HbType.mono(24.0, FontWeight.Bold), color = oklch(0.55, 0.1, hue))
                Text("ÜBRIG", style = HbType.eyebrow.copy(fontSize = 10.sp, letterSpacing = 0.05.em), color = Hb.ink3)
            }
        }
        // progress bar
        val total = maxOf(s.total, 1.0)
        val tk = (s.taken / total).coerceIn(0.0, 1.0)
        val pl = (s.planned / total).coerceIn(0.0, 1.0 - tk)
        val rest = (1.0 - tk - pl).coerceIn(0.0, 1.0)
        Row(Modifier.fillMaxWidth().height(8.dp).clip(HbPill).background(Hb.surface2)) {
            if (tk > 0) Box(Modifier.weight(tk.toFloat()).fillMaxHeight().background(accentColor))
            if (pl > 0) Box(Modifier.weight(pl.toFloat()).fillMaxHeight().background(accentColor.copy(alpha = 0.45f)))
            if (rest > 0) Spacer(Modifier.weight(rest.toFloat()))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (s.carry > 0) {
                val txt = "+${fmtDays(s.carry)} Übertrag · " +
                    if (s.carryExpired) "${fmtDays(s.carryLost)} verfallen" else "bis ${AbwCal.ddmm(s.carryExpires)}"
                HbBadge(txt, tone = if (s.carryExpired) HbTone.Over else HbTone.Accent)
            }
            HbBadge("Krank ${fmtDays(s.krank)}", tone = HbTone.Neutral)
            HbBadge("Kind-krank ${fmtDays(s.kind)} / ${s.kindCap}", tone = HbTone.Neutral)
        }
    }
}

// ---------------------------------------------------------------------------
// Legend (.abwm-legend)
// ---------------------------------------------------------------------------

@Composable
private fun Legend(userIds: List<String>) {
    val hueA = Hb.userHue(userIds.getOrNull(0))
    val hueB = Hb.userHue(userIds.getOrNull(1) ?: userIds.getOrNull(0))
    FlowRow(
        Modifier.padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        LegendItem("Urlaub") { SplitSwatch(AbwPalette.urlaub(hueA), AbwPalette.urlaub(hueB)) }
        LegendItem("Krank") { Swatch(AbwPalette.krank) }
        LegendItem("Kind-krank") { Swatch(AbwPalette.kindKrank) }
        LegendItem("Feiertag") { Swatch(AbwPalette.feiertag) }
        LegendItem("Teilzeit") { Swatch(AbwPalette.teilzeit(220.0)) }
        LegendItem("Wochenende") { Swatch(AbwPalette.weekend) }
        LegendItem("Kita zu") { KitaSwatch() }
    }
}

@Composable
private fun LegendItem(label: String, swatch: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        swatch()
        Text(label, style = HbType.small.copy(fontSize = 11.5.sp), color = Hb.ink2)
    }
}

@Composable
private fun Swatch(color: Color) {
    Box(Modifier.size(13.dp).clip(RoundedCornerShape(4.dp)).background(color).border(1.dp, Hb.line, RoundedCornerShape(4.dp)))
}

@Composable
private fun SplitSwatch(a: Color, b: Color) {
    Box(
        Modifier.size(13.dp).clip(RoundedCornerShape(4.dp)).border(1.dp, Hb.line, RoundedCornerShape(4.dp))
            .drawBehind { drawSplit(a, b) },
    )
}

@Composable
private fun KitaSwatch() {
    Box(
        Modifier.size(13.dp).clip(RoundedCornerShape(4.dp)).background(Hb.surface)
            .border(2.dp, Hb.clay, RoundedCornerShape(4.dp)),
    )
}

// ---------------------------------------------------------------------------
// Month grid (.abwm-mgrid)
// ---------------------------------------------------------------------------

@Composable
private fun MonthGrid(
    ctx: AbsCtx,
    userIds: List<String>,
    today: String,
    year: Int,
    month: Int,
    onMonth: (Int) -> Unit,
    onPick: (String) -> Unit,
) {
    Column(Modifier.padding(horizontal = 18.dp)) {
        // month nav
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            HbIconButton(HbIcons.chevronLeft, { onMonth(month - 1) }, iconSize = 20.dp)
            Row {
                Text("${AbwCal.MON_FULL[month]} ", style = HbType.sheetTitle.copy(fontSize = 19.sp), color = Hb.ink)
                Text("$year", style = HbType.sheetTitle.copy(fontSize = 19.sp, fontWeight = FontWeight.SemiBold), color = Hb.ink3)
            }
            HbIconButton(HbIcons.chevronRight, { onMonth(month + 1) }, iconSize = 20.dp)
        }
        // weekday header
        Row(Modifier.fillMaxWidth().padding(bottom = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            AbwCal.WD_MIN.forEach { w ->
                Text(
                    w, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = HbType.small.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold), color = Hb.ink3,
                )
            }
        }
        // 6 week rows
        val first = LocalDate.of(year, month + 1, 1)
        val lead = (first.dayOfWeek.value + 6) % 7 // Mon = 0
        val gridStart = first.minusDays(lead.toLong())
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            for (w in 0 until 6) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (dow in 0 until 7) {
                        val date = gridStart.plusDays((w * 7 + dow).toLong())
                        val ds = AbwCal.ymd(date)
                        MonthCell(
                            modifier = Modifier.weight(1f),
                            date = date, ds = ds, inMonth = date.monthValue - 1 == month,
                            isToday = ds == today, weekend = AbwCal.isWeekend(date), kita = ctx.kita.containsKey(ds),
                            chips = userIds.map { it to personDay(ctx, it, ds) },
                            onClick = { onPick(ds) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthCell(
    modifier: Modifier,
    date: LocalDate,
    ds: String,
    inMonth: Boolean,
    isToday: Boolean,
    weekend: Boolean,
    kita: Boolean,
    chips: List<Pair<String, DayState>>,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(9.dp)
    val bg = if (weekend) Hb.surface2 else Hb.surface
    Column(
        modifier
            .heightIn(min = 58.dp)
            .clip(shape)
            .background(bg, shape)
            .border(if (isToday) 1.5.dp else 1.dp, if (isToday) Hb.accent else Hb.lineSoft, shape)
            .then(if (inMonth) Modifier else Modifier.alpha(0.4f))
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (isToday) {
                Box(Modifier.size(18.dp).clip(HbPill).background(Hb.accent), contentAlignment = Alignment.Center) {
                    Text("${date.dayOfMonth}", style = HbType.mono.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = Hb.onAccent)
                }
            } else {
                Text("${date.dayOfMonth}", style = HbType.mono.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = Hb.ink2)
            }
            if (kita) Box(Modifier.size(6.dp).clip(HbPill).background(Hb.clay))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            chips.forEach { (uid, st) -> MonthChip(uid, st) }
        }
    }
}

@Composable
private fun MonthChip(uid: String, st: DayState) {
    if (st.type == null && st.holiday == null && !st.ptOff) return
    val txt = when {
        st.type != null && st.half != null -> if (st.half == "vm") "AM" else "PM"
        // ½ prefix marks a half-day custom holiday (#51); statutory ones are full days.
        st.type == null && st.holiday != null && st.holidayHalf -> "½"
        else -> Hb.userInitial(uid)
    }
    Box(
        Modifier.heightIn(min = 16.dp).widthIn(min = 16.dp).clip(RoundedCornerShape(5.dp))
            .background(colorFor(st), RoundedCornerShape(5.dp)).padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(txt, style = HbType.small.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.02).em), color = AbwPalette.onFill)
    }
}

// ---------------------------------------------------------------------------
// Year grid (.abwm-yr) — months as rows, days 1–31 as columns
// ---------------------------------------------------------------------------

@Composable
private fun YearGrid(
    ctx: AbsCtx,
    userIds: List<String>,
    today: String,
    year: Int,
    onYear: (Int) -> Unit,
    onPick: (String) -> Unit,
) {
    val uA = userIds.getOrNull(0)
    val uB = userIds.getOrNull(1) ?: uA
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.padding(horizontal = 18.dp)) {
        // year nav
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            HbIconButton(HbIcons.chevronLeft, { onYear(year - 1) }, iconSize = 20.dp)
            Text("$year", style = HbType.sheetTitle.copy(fontSize = 19.sp), color = Hb.ink)
            HbIconButton(HbIcons.chevronRight, { onYear(year + 1) }, iconSize = 20.dp)
        }
        // grid: 1px gaps over a line-soft backing
        Column(
            Modifier.clip(shape).background(Hb.lineSoft).border(1.dp, Hb.lineSoft, shape),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            // header row: corner + day-of-month ticks
            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                Box(Modifier.width(26.dp).height(15.dp).background(Hb.surface))
                for (d in 1..31) {
                    Box(Modifier.weight(1f).height(15.dp).background(Hb.surface), contentAlignment = Alignment.Center) {
                        val tick = if (d == 1 || d % 7 == 0) "$d" else ""
                        if (tick.isNotEmpty()) {
                            Text(tick, style = HbType.mono.copy(fontSize = 7.sp, fontWeight = FontWeight.Bold), color = Hb.ink3)
                        }
                    }
                }
            }
            // 12 month rows
            for (m in 0 until 12) {
                val dim = AbwCal.daysInMonth(year, m)
                Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    Box(Modifier.width(26.dp).height(16.dp).background(Hb.surface), contentAlignment = Alignment.Center) {
                        Text(AbwCal.MON_ABBR[m], style = HbType.small.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold), color = Hb.ink2)
                    }
                    for (d in 1..31) {
                        if (d > dim) {
                            Box(Modifier.weight(1f).height(16.dp).background(Hb.surface).alpha(0.4f))
                        } else {
                            val ds = "$year-${AbwCal.pad(m + 1)}-${AbwCal.pad(d)}"
                            val dayA = uA?.let { personDay(ctx, it, ds) }
                            val dayB = uB?.let { personDay(ctx, it, ds) }
                            YearCell(
                                modifier = Modifier.weight(1f),
                                colorA = colorFor(dayA), colorB = colorFor(dayB),
                                // half custom holiday is household-wide — key off A, like web (#51)
                                halfHoliday = dayA?.holidayHalf == true,
                                isToday = ds == today, kita = ctx.kita.containsKey(ds),
                                onClick = { onPick(ds) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun YearCell(modifier: Modifier, colorA: Color, colorB: Color, halfHoliday: Boolean, isToday: Boolean, kita: Boolean, onClick: () -> Unit) {
    Box(
        modifier
            .height(16.dp)
            .background(Hb.surface)
            .clickable { onClick() }
            .drawBehind {
                drawSplit(colorA, colorB)
                if (kita) {
                    val sw = 1.5.dp.toPx()
                    drawRect(Hb.clay, topLeft = Offset(sw / 2, sw / 2), size = Size(size.width - sw, size.height - sw), style = Stroke(sw))
                }
                if (isToday) {
                    val sw = 1.5.dp.toPx()
                    drawRect(Hb.accent, topLeft = Offset(sw / 2, sw / 2), size = Size(size.width - sw, size.height - sw), style = Stroke(sw))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // ½ marks a half-day custom holiday (#51); statutory + full ones carry no glyph.
        if (halfHoliday) {
            Text("½", style = HbType.mono.copy(fontSize = 8.sp, fontWeight = FontWeight.Black), color = AbwPalette.onFill)
        }
    }
}

/** Diagonal two-person split: upper-left = A, lower-right = B (anti-diagonal divider). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSplit(a: Color, b: Color) {
    val w = size.width
    val h = size.height
    if (a == b) {
        drawRect(a)
        return
    }
    val pathA = Path().apply { moveTo(0f, 0f); lineTo(w, 0f); lineTo(0f, h); close() }
    drawPath(pathA, a)
    val pathB = Path().apply { moveTo(w, 0f); lineTo(w, h); lineTo(0f, h); close() }
    drawPath(pathB, b)
    drawLine(AbwPalette.divider, Offset(w, 0f), Offset(0f, h), strokeWidth = 1f)
}

// ---------------------------------------------------------------------------
// Day editor sheet (.abwm-ed)
// ---------------------------------------------------------------------------

@Composable
private fun DayEditorSheet(ctx: AbsCtx, ds: String, userIds: List<String>, vm: AbsenceViewModel, onDismiss: () -> Unit) {
    HbBottomSheet(
        onDismiss = onDismiss,
        title = AbwCal.dayTitle(ds),
        footer = { HbButton("Fertig", onClick = onDismiss, modifier = Modifier.weight(1f)) },
    ) {
        userIds.forEach { uid ->
            EditorPerson(ctx, uid, ds, vm)
            HbDivider()
        }
        KitaEditorRow(ctx, ds, vm)
    }
}

@Composable
private fun EditorPerson(ctx: AbsCtx, uid: String, ds: String, vm: AbsenceViewModel) {
    val st = personDay(ctx, uid, ds)
    val note = when {
        st.holiday != null -> "Feiertag · ${st.holiday}" + if (st.holidayHalf) " (½)" else ""
        st.ptOff -> "ohnehin frei"
        st.weekend -> "Wochenende"
        else -> null
    }
    val typeOpts: List<Pair<String, String?>> = listOf(
        "Arbeit" to null, "Urlaub" to AbsTypes.URLAUB, "Krank" to AbsTypes.KRANK, "Kind-krank" to AbsTypes.KIND_KRANK,
    )
    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            HbAvatar(uid, size = 26.dp)
            Text(displayName(uid), style = HbType.rowTitle.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = Hb.ink, modifier = Modifier.weight(1f))
            if (note != null) Text(note, style = HbType.small.copy(fontSize = 12.sp), color = Hb.ink3)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            typeOpts.forEach { (label, id) ->
                HbPickText(
                    text = label,
                    active = st.type == id,
                    onClick = {
                        if (id == null) vm.clearAbsence(uid, ds)
                        else vm.setAbsence(uid, ds, id, if (st.type == id) st.half else null)
                    },
                )
            }
        }
        val activeType = st.type
        if (activeType != null) {
            HalfToggle(st.half) { half -> vm.setAbsence(uid, ds, activeType, half) }
        }
    }
}

@Composable
private fun HalfToggle(value: String?, onChange: (String?) -> Unit) {
    val opts: List<Pair<String, String?>> = listOf("Ganzer Tag" to null, "Vormittag (AM)" to "vm", "Nachmittag (PM)" to "nm")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        opts.forEach { (label, v) ->
            val active = value == v
            val shape = RoundedCornerShape(9.dp)
            Box(
                Modifier.weight(1f).clip(shape)
                    .background(if (active) Hb.surface2 else Hb.surface, shape)
                    .border(1.dp, if (active) Hb.accent else Hb.line, shape)
                    .clickable { onChange(v) }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = HbType.small.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = if (active) Hb.ink else Hb.ink2, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun KitaEditorRow(ctx: AbsCtx, ds: String, vm: AbsenceViewModel) {
    val kita = ctx.kita[ds]
    var label by remember(ds, kita?.id) { mutableStateOf(kita?.label ?: "") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Kita-Schließtag", style = HbType.rowTitle.copy(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold), color = Hb.ink)
                Text("Gilt für die ganze Familie", style = HbType.small.copy(fontSize = 12.5.sp), color = Hb.ink3)
            }
            ToggleSwitch(on = kita != null) {
                vm.toggleKita(ds, if (kita != null) null else "Kita geschlossen")
            }
        }
        if (kita != null) {
            HbField("Anlass (optional)") {
                HbTextField(
                    value = label,
                    onValueChange = { label = it; vm.setKitaLabel(ds, it.ifBlank { "Kita geschlossen" }) },
                    placeholder = "z. B. Brückentag",
                )
            }
        }
    }
}

@Composable
private fun ToggleSwitch(on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(width = 46.dp, height = 27.dp).clip(HbPill)
            .background(if (on) Hb.accent else Hb.surface3).clickable { onClick() }.padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(21.dp).clip(HbPill).background(Color.White))
    }
}

// ---------------------------------------------------------------------------
// Zeitraum (period) sheet (.abwm-dates)
// ---------------------------------------------------------------------------

@Composable
private fun RangeSheet(
    data: AbsenceStateDto,
    userIds: List<String>,
    prefill: Pair<String, String>?,
    vm: AbsenceViewModel,
    onDismiss: () -> Unit,
) {
    val todayStr = AbwCal.ymd(LocalDate.now())
    var targets by remember { mutableStateOf(userIds.toSet()) }
    var type by remember { mutableStateOf<String?>(AbsTypes.URLAUB) }
    var von by remember { mutableStateOf(prefill?.first ?: todayStr) }
    var bis by remember { mutableStateOf(prefill?.second ?: todayStr) }

    val typeOpts: List<Pair<String, String?>> = listOf(
        "Urlaub" to AbsTypes.URLAUB, "Krank" to AbsTypes.KRANK, "Kind-krank" to AbsTypes.KIND_KRANK, "Löschen" to null,
    )
    val firstTarget = targets.firstOrNull()
    val preview = if (type != null && firstTarget != null) eachDate(von, bis).count { isWorkdayFor(data, firstTarget, it) } else 0
    val canApply = targets.isNotEmpty() && von <= bis

    HbBottomSheet(
        onDismiss = onDismiss,
        title = "Zeitraum eintragen",
        footer = {
            HbButton("Abbrechen", onClick = onDismiss, variant = HbButtonVariant.Secondary, modifier = Modifier.weight(1f))
            HbButton(
                "Übernehmen", icon = HbIcons.check, modifier = Modifier.weight(1f), enabled = canApply,
                onClick = {
                    targets.forEach { vm.setAbsenceRange(it, type, von, bis, null) }
                    onDismiss()
                },
            )
        },
    ) {
        HbField("Für wen") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                userIds.forEach { uid ->
                    HbPickText(
                        displayName(uid),
                        active = uid in targets,
                        onClick = { targets = if (uid in targets) targets - uid else targets + uid },
                    )
                }
            }
        }
        HbField("Art") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                typeOpts.forEach { (label, id) -> HbPickText(label, active = type == id, onClick = { type = id }) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HbField("Von", Modifier.weight(1f)) { AbwDateField(von) { von = it } }
            HbField("Bis", Modifier.weight(1f)) { AbwDateField(bis) { bis = it } }
        }
        Text(
            if (type != null)
                "Nur Arbeitstage — Wochenenden, Feiertage und feste freie Tage werden übersprungen" +
                    (firstTarget?.let { " (≈ $preview Tage für ${displayName(it)})" } ?: "") + "."
            else "Löschen entfernt alle Einträge im Zeitraum.",
            style = HbType.small.copy(fontSize = 12.5.sp, lineHeight = 18.sp), color = Hb.ink3,
        )
    }
}

// ---------------------------------------------------------------------------
// Einstellungen sheet (.abwm-setp) — full-height
// ---------------------------------------------------------------------------

@Composable
private fun SettingsSheet(
    ctx: AbsCtx,
    data: AbsenceStateDto,
    userIds: List<String>,
    year: Int,
    vm: AbsenceViewModel,
    onDismiss: () -> Unit,
) {
    HbBottomSheet(
        onDismiss = onDismiss,
        title = "Kalender-Einstellungen",
        full = true,
        footer = { HbButton("Fertig", onClick = onDismiss, modifier = Modifier.weight(1f)) },
    ) {
        userIds.forEach { uid ->
            SettingsPerson(ctx, data, uid, year, vm)
            HbDivider()
        }
        KitaSettings(data, year, vm)
    }
}

@Composable
private fun SettingsPerson(ctx: AbsCtx, data: AbsenceStateDto, uid: String, year: Int, vm: AbsenceViewModel) {
    val s = ctx.settings[uid] ?: return
    val rules = data.partTime.filter { it.userId == uid }
    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            HbAvatar(uid, size = 26.dp)
            Text(displayName(uid), style = HbType.rowTitle.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = Hb.ink)
        }
        HbField("Bundesland") {
            SelectField(
                value = AbwCal.stateName(s.state),
                options = AbwCal.STATES.map { it.name to it.code },
                onSelect = { vm.updateSettings(uid, year, UpdateAbsSettingsRequest(state = it)) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HbField("Anspruch", Modifier.weight(1f)) {
                NumberField("alw-$uid", s.allowance) { vm.updateSettings(uid, year, UpdateAbsSettingsRequest(allowance = it)) }
            }
            HbField("Resturlaub", Modifier.weight(1f)) {
                NumberField("car-$uid", s.carryover) { vm.updateSettings(uid, year, UpdateAbsSettingsRequest(carryover = it)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HbField("verfällt am", Modifier.weight(1f)) {
                AbwDateField(s.carryoverExpires ?: "$year-03-31") { vm.updateSettings(uid, year, UpdateAbsSettingsRequest(carryoverExpires = it)) }
            }
            HbField("Kind-krank", Modifier.weight(1f)) {
                NumberField("kk-$uid", s.kindKrankCap.toDouble(), integer = true) {
                    vm.updateSettings(uid, year, UpdateAbsSettingsRequest(kindKrankCap = it.toInt()))
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Teilzeit · feste freie Tage", style = HbType.label, color = Hb.ink2)
            if (rules.isEmpty()) Text("Keine festen freien Tage.", style = HbType.small, color = Hb.ink3)
            rules.forEach { r -> PartTimeRow(r, vm) }
            Row(
                Modifier.clip(HbRadiusSm).clickable {
                    vm.addPartTime(uid, 1, "$year-01-01", null)
                }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                HbIcon(HbIcons.plus, size = 14.dp, tint = Hb.accentInk)
                Text("Freien Tag hinzufügen", style = HbType.meta.copy(fontWeight = FontWeight.SemiBold), color = Hb.accentInk)
            }
        }
    }
}

@Composable
private fun PartTimeRow(r: com.homebase.android.data.model.PartTimeRuleDto, vm: AbsenceViewModel) {
    Column(
        Modifier.fillMaxWidth().clip(HbRadiusSm).background(Hb.surface2).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                SelectField(
                    value = "${WD_LONG[r.weekday - 1]} frei",
                    options = (1..5).map { "${WD_LONG[it - 1]} frei" to it.toString() },
                    onSelect = { vm.updatePartTime(r.id, weekday = it.toInt()) },
                )
            }
            HbIconButton(HbIcons.trash, { vm.removePartTime(r.id) }, tint = Hb.ink3, iconSize = 18.dp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { AbwDateField(r.start) { vm.updatePartTime(r.id, start = it) } }
            Box(Modifier.weight(1f)) { AbwDateField(r.end ?: "") { vm.updatePartTime(r.id, end = it) } }
        }
    }
}

@Composable
private fun KitaSettings(data: AbsenceStateDto, year: Int, vm: AbsenceViewModel) {
    var kDate by remember { mutableStateOf("$year-01-01") }
    var rVon by remember { mutableStateOf("$year-07-27") }
    var rBis by remember { mutableStateOf("$year-08-07") }
    var rLabel by remember { mutableStateOf("Kita geschlossen") }
    val kita = data.kitaClosures.sortedBy { it.date }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("Kita-Schließtage", style = HbType.label, color = Hb.ink2)
        Text("Gelten für die ganze Familie — Hintergrundmarker, werden nicht gezählt.", style = HbType.small, color = Hb.ink3)
        if (kita.isEmpty()) Text("Keine Schließtage.", style = HbType.small, color = Hb.ink3)
        kita.forEach { k ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { AbwDateField(k.date) { vm.updateKita(k.id, date = it) } }
                Box(Modifier.weight(1.2f)) {
                    LocalCommitField("kita-${k.id}", k.label, placeholder = "Anlass") { vm.updateKita(k.id, label = it) }
                }
                HbIconButton(HbIcons.trash, { vm.removeKita(k.id) }, tint = Hb.ink3, iconSize = 18.dp)
            }
        }
        // add single
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Einzeltag", style = HbType.small, color = Hb.ink3, modifier = Modifier.width(64.dp))
            Box(Modifier.weight(1f)) { AbwDateField(kDate) { kDate = it } }
            HbButton("Hinzufügen", onClick = { vm.addKita(kDate, "Kita geschlossen") }, variant = HbButtonVariant.Soft, size = HbButtonSize.Sm, icon = HbIcons.plus)
        }
        // add range
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Zeitraum (Wochenenden übersprungen)", style = HbType.small, color = Hb.ink3)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { AbwDateField(rVon) { rVon = it } }
                Box(Modifier.weight(1f)) { AbwDateField(rBis) { rBis = it } }
            }
            HbTextField(value = rLabel, onValueChange = { rLabel = it }, placeholder = "Anlass")
            HbButton("Zeitraum hinzufügen", onClick = { vm.addKitaRange(rVon, rBis, rLabel) }, variant = HbButtonVariant.Soft, size = HbButtonSize.Sm, icon = HbIcons.plus)
        }
    }
}

// ---------------------------------------------------------------------------
// Field helpers
// ---------------------------------------------------------------------------

/** Number input with local state seeded once per [key]; commits valid values upstream. */
@Composable
private fun NumberField(key: String, initial: Double, integer: Boolean = false, onCommit: (Double) -> Unit) {
    var text by remember(key) { mutableStateOf(fmtDays(initial)) }
    HbTextField(
        value = text,
        onValueChange = { raw ->
            text = raw
            val parsed = raw.replace(',', '.').toDoubleOrNull()
            if (parsed != null) onCommit(if (integer) parsed.toLong().toDouble() else parsed)
        },
    )
}

/** Text input with local state seeded once per [key]; commits every change upstream. */
@Composable
private fun LocalCommitField(key: String, initial: String, placeholder: String, onCommit: (String) -> Unit) {
    var text by remember(key) { mutableStateOf(initial) }
    HbTextField(value = text, onValueChange = { text = it; onCommit(it) }, placeholder = placeholder)
}

/** Read-only field that opens a dropdown of [options] (label to value). */
@Composable
private fun SelectField(value: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clip(HbRadiusSm).background(Hb.surface, HbRadiusSm)
                .border(1.dp, Hb.line, HbRadiusSm).clickable { open = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, style = HbType.body.copy(fontSize = 14.sp), color = Hb.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            HbIcon(HbIcons.chevronDown, size = 16.dp, tint = Hb.ink3)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (label, v) ->
                DropdownMenuItem(text = { Text(label, style = HbType.body, color = Hb.ink) }, onClick = { onSelect(v); open = false })
            }
        }
    }
}

/** Date field: shows dd.MM.yyyy, opens a Material date picker on tap. */
@Composable
private fun AbwDateField(value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxWidth().clip(HbRadiusSm).background(Hb.surface, HbRadiusSm)
            .border(1.dp, Hb.line, HbRadiusSm).clickable { open = true }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(displayDate(value), style = HbType.body.copy(fontSize = 14.sp), color = if (value.isBlank()) Hb.ink3 else Hb.ink)
    }
    if (open) {
        val initialMillis = runCatching {
            LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrNull()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { ms ->
                        onChange(AbwCal.ymd(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()))
                    }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Abbrechen") } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

private fun displayDate(ds: String): String = runCatching {
    val d = LocalDate.parse(ds)
    "%02d.%02d.%04d".format(d.dayOfMonth, d.monthValue, d.year)
}.getOrDefault(if (ds.isBlank()) "offen" else ds)
