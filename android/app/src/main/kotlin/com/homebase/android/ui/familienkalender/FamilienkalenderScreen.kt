package com.homebase.android.ui.familienkalender

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebase.android.R
import com.homebase.android.data.model.AbsenceDto
import com.homebase.android.data.model.CalendarEventDto
import com.homebase.android.data.model.MealPlanEntryDto
import com.homebase.android.data.model.TodoDto
import com.homebase.android.ui.abwesenheit.AbsTypes
import com.homebase.android.ui.components.HbAppBar
import com.homebase.android.ui.components.HbBottomSheet
import com.homebase.android.ui.components.HbButton
import com.homebase.android.ui.components.HbButtonSize
import com.homebase.android.ui.components.HbButtonVariant
import com.homebase.android.ui.components.HbIcon
import com.homebase.android.ui.components.HbIconButton
import com.homebase.android.ui.components.HbIcons
import com.homebase.android.ui.components.HbScreenScaffold
import com.homebase.android.ui.components.HbToast
import com.homebase.android.ui.theme.Hb
import com.homebase.android.ui.theme.HbType
import com.homebase.android.ui.util.Format
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Per-domain marker colours — distinct, mid-lightness so they read on both light and dark. */
private object CalColors {
    val event = Color(0xFF4F86E0)    // blue — real appointments/events (#434)
    val absence = Color(0xFFD9A441)  // amber — Urlaub/Krank/Kind-krank
    val todo = Color(0xFFD2664B)     // clay — due todos
    val meal = Color(0xFF5BA86B)     // green — planned meals
    val kita = Color(0xFF8B6BC0)     // violet — kita closures (cell background tint)
}

/** Markers shown per cell before the rest collapse — keeps a packed day readable. */
private const val MAX_DOTS = 4

@Composable
fun FamilienkalenderScreen(
    viewModel: FamilienkalenderViewModel,
    onOpenDrawer: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val buckets = remember(state.todos, state.absences, state.kitaClosures, state.meals, state.events, state.monthAnchor) {
        state.buckets
    }
    val gridDays = remember(state.monthAnchor) { state.gridDays }

    var selectedDate by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.error) { if (state.error != null) { delay(3000); viewModel.clearError() } }

    HbScreenScaffold(
        appBar = {
            HbAppBar(
                title = stringResource(R.string.family_calendar_title),
                eyebrow = stringResource(R.string.family_calendar_eyebrow),
                onLeft = onOpenDrawer,
            )
        },
        overlay = {
            selectedDate?.let { date ->
                DayDetailSheet(
                    date = date,
                    bucket = buckets[date],
                    onDismiss = { selectedDate = null },
                )
            }
            if (state.error != null) HbToast(message = state.error!!, icon = null)
        },
    ) {
        MonthNavRow(
            monthAnchor = state.monthAnchor,
            onPrev = viewModel::prevMonth,
            onNext = viewModel::nextMonth,
            onToday = viewModel::goToday,
        )
        WeekdayHeader()
        MonthGrid(
            monthAnchor = state.monthAnchor,
            gridDays = gridDays,
            buckets = buckets,
            onClickDay = { selectedDate = it },
        )
        CalendarLegend()
    }
}

@Composable
private fun MonthNavRow(monthAnchor: LocalDate, onPrev: () -> Unit, onNext: () -> Unit, onToday: () -> Unit) {
    val locale = Locale.getDefault()
    val title = "${monthAnchor.month.getDisplayName(TextStyle.FULL, locale)} ${monthAnchor.year}"
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HbIconButton(HbIcons.chevronLeft, onPrev)
        Text(
            title,
            style = HbType.body.copy(fontWeight = FontWeight.SemiBold),
            color = Hb.ink,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        HbIconButton(HbIcons.chevronRight, onNext)
        HbButton(
            stringResource(R.string.family_calendar_today),
            onToday,
            variant = HbButtonVariant.Ghost,
            size = HbButtonSize.Sm,
        )
    }
}

@Composable
private fun WeekdayHeader() {
    val locale = Locale.getDefault()
    // Mon..Sun short names from java.time so they localize (Mo Di Mi … / Mon Tue …).
    val labels = (0L..6L).map {
        DayOfWeek.MONDAY.plus(it).getDisplayName(TextStyle.SHORT, locale)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
        labels.forEach { wd ->
            Text(
                wd,
                style = HbType.small,
                color = Hb.ink3,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MonthGrid(
    monthAnchor: LocalDate,
    gridDays: List<LocalDate>,
    buckets: Map<String, DayBucket>,
    onClickDay: (String) -> Unit,
) {
    val today = LocalDate.now()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        gridDays.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { day ->
                    DayCell(
                        day = day,
                        inMonth = day.monthValue == monthAnchor.monthValue && day.year == monthAnchor.year,
                        isToday = day.isEqual(today),
                        bucket = buckets[day.toString()],
                        onClick = { onClickDay(day.toString()) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    bucket: DayBucket?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasKita = bucket?.kita != null
    val dots = bucket?.let { collectDotColors(it) }.orEmpty()
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .aspectRatio(0.82f)
            .clip(shape)
            .background(
                when {
                    hasKita -> CalColors.kita.copy(alpha = if (Hb.isDark) 0.28f else 0.14f)
                    isToday -> Hb.accentSoft
                    else -> Hb.surface
                },
                shape,
            )
            .border(1.dp, if (isToday) Hb.accent else Hb.lineSoft, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            day.dayOfMonth.toString(),
            style = HbType.small.copy(fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium),
            color = when {
                isToday -> Hb.accentInk
                inMonth -> Hb.ink
                else -> Hb.ink3
            },
        )
        if (dots.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                dots.take(MAX_DOTS).forEach { c ->
                    Box(Modifier.size(6.dp).clip(CircleShape).background(c, CircleShape))
                }
            }
        }
    }
}

/** Domain dot colours for a day, in marker order (events, absence, todos, meals); capped by caller. */
private fun collectDotColors(b: DayBucket): List<Color> = buildList {
    repeat(b.events.size) { add(CalColors.event) }
    repeat(b.absences.size) { add(CalColors.absence) }
    repeat(b.todos.size) { add(CalColors.todo) }
    repeat(b.meals.size) { add(CalColors.meal) }
}

@Composable
private fun CalendarLegend() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LegendDot(CalColors.event, R.string.family_calendar_cat_events)
        LegendDot(CalColors.absence, R.string.family_calendar_cat_absence)
        LegendDot(CalColors.todo, R.string.family_calendar_cat_todos)
        LegendDot(CalColors.meal, R.string.family_calendar_cat_meals)
        LegendDot(CalColors.kita, R.string.family_calendar_cat_kita)
    }
}

@Composable
private fun LegendDot(color: Color, labelRes: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color, CircleShape))
        Text(stringResource(labelRes), style = HbType.small, color = Hb.ink2)
    }
}

// --- day detail -------------------------------------------------------------

@Composable
private fun DayDetailSheet(date: String, bucket: DayBucket?, onDismiss: () -> Unit) {
    val title = Format.longWeekdayDate(LocalDate.parse(date))
    HbBottomSheet(
        onDismiss = onDismiss,
        title = title,
        footer = {
            HbButton(
                stringResource(R.string.action_close),
                onDismiss,
                variant = HbButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        if (bucket == null || bucket.isEmpty) {
            Text(
                stringResource(R.string.family_calendar_detail_empty),
                style = HbType.body,
                color = Hb.ink2,
            )
            return@HbBottomSheet
        }

        if (bucket.events.isNotEmpty()) {
            DetailSection(R.string.family_calendar_section_events, HbIcons.calendar) {
                bucket.events.forEach { EventRow(it) }
            }
        }
        if (bucket.absences.isNotEmpty()) {
            DetailSection(R.string.family_calendar_section_absence, HbIcons.users) {
                bucket.absences.forEach { AbsenceRow(it) }
            }
        }
        bucket.kita?.let { kita ->
            DetailSection(R.string.family_calendar_section_kita, HbIcons.home) {
                DetailRow(kita.label)
            }
        }
        if (bucket.todos.isNotEmpty()) {
            DetailSection(R.string.family_calendar_section_todos, HbIcons.checkCircle) {
                bucket.todos.forEach { TodoRow(it) }
            }
        }
        if (bucket.meals.isNotEmpty()) {
            DetailSection(R.string.family_calendar_section_meals, HbIcons.utensils) {
                bucket.meals.forEach { MealRow(it) }
            }
        }
    }
}

@Composable
private fun DetailSection(titleRes: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            HbIcon(icon, size = 15.dp, tint = Hb.accent)
            Text(
                stringResource(titleRes),
                style = HbType.sectionLabel,
                color = Hb.ink3,
            )
        }
        content()
    }
}

@Composable
private fun DetailRow(text: String, muted: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(Hb.ink3, CircleShape))
        Text(
            text,
            style = HbType.body,
            color = Hb.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (muted != null) Text(muted, style = HbType.small, color = Hb.ink3)
    }
}

@Composable
private fun EventRow(e: CalendarEventDto) {
    val time = if (!e.allDay && !e.startTime.isNullOrBlank()) {
        shortTime(e.startTime) + (e.endTime?.takeIf { it.isNotBlank() }?.let { "–${shortTime(it)}" } ?: "")
    } else null
    val parts = listOfNotNull(time, e.title, e.location).joinToString(" · ")
    DetailRow(parts)
}

@Composable
private fun AbsenceRow(a: AbsenceDto) {
    val type = stringResource(AbsTypes.labelRes(a.type))
    val half = when (a.half) {
        "vm" -> stringResource(R.string.absence_half_morning)
        "nm" -> stringResource(R.string.absence_half_afternoon)
        else -> null
    }
    DetailRow("${a.userId} · $type" + (half?.let { " ($it)" } ?: ""))
}

@Composable
private fun TodoRow(t: TodoDto) {
    DetailRow(t.title, muted = t.assignee)
}

@Composable
private fun MealRow(m: MealPlanEntryDto) {
    val slot = stringResource(mealSlotLabelRes(m.slot))
    val dish = m.recipeTitle ?: m.dishTitle.orEmpty()
    DetailRow("$slot: $dish")
}

private fun mealSlotLabelRes(slot: String): Int = when (slot) {
    "BREAKFAST" -> R.string.meal_plan_slot_breakfast
    "LUNCH" -> R.string.meal_plan_slot_lunch
    else -> R.string.meal_plan_slot_dinner
}

/** "HH:mm" from an "HH:mm[:ss]" string. */
private fun shortTime(t: String): String = if (t.length >= 5) t.substring(0, 5) else t
