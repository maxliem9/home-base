package com.homebase.android.ui.time

import com.homebase.android.data.model.TimeEntryDto
import com.homebase.android.data.model.UserForecastDto
import com.homebase.android.ui.util.Format
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// Pure, unit-testable time math for the Zeiterfassung screen: live ticking of
// the forecast snapshot + project-card day/week saldi (#64) and the split-entry
// validation/preview (#66). Mirrors web/src/components/TimeView.tsx.
// ---------------------------------------------------------------------------

private val DE = Locale.GERMAN

/**
 * Seconds a running timer has accumulated since the forecast snapshot at
 * [forecastAt] (#64) — 0 without a snapshot; never negative. Whether the
 * person actually has a running timer is checked at the call site.
 */
fun liveExtraSeconds(forecastAt: Instant?, now: Instant): Long =
    forecastAt?.let { ChronoUnit.SECONDS.between(it, now).coerceAtLeast(0) } ?: 0L

/**
 * Tick the forecast snapshot live (#64): add [extraSeconds] (seconds since the
 * snapshot) to week-Ist, "noch h:mm", the Heute line and — via [liveProjectId] —
 * the running entry's project saldo. Mirrors the web's WeekBalance props
 * liveExtraSeconds/liveProjectId; the rendering itself stays untouched.
 */
fun UserForecastDto.withLiveExtra(extraSeconds: Long, liveProjectId: String?): UserForecastDto {
    if (extraSeconds <= 0L) return this
    return copy(
        weekRecordedSeconds = weekRecordedSeconds + extraSeconds,
        weekRemainingSeconds = weekRemainingSeconds - extraSeconds,
        todayRecordedSeconds = todayRecordedSeconds + extraSeconds,
        todayRemainingSeconds = todayRemainingSeconds - extraSeconds,
        projects = projects.map { p ->
            if (p.projectId == liveProjectId) {
                p.copy(recordedSeconds = p.recordedSeconds + extraSeconds, deltaSeconds = p.deltaSeconds + extraSeconds)
            } else p
        },
    )
}

// ---------------------------------------------------------------------------
// Project-card day/week saldo (#64) — web reference: projectStats memo
// ---------------------------------------------------------------------------

/** Day + week saldo of one project card, incl. the German fallback labels. */
data class ProjectCardStats(
    val daySeconds: Long,
    /** "Heute" / "Gestern" / "Vorgestern" / weekday / "3. Juni" */
    val dayLabel: String,
    val weekSeconds: Long,
    /** "Diese Woche" / "Letzte Woche" / "12.–18. Mai" */
    val weekLabel: String,
)

/**
 * Today's / this week's sums of [entries] — or, when the current day/week has
 * no entries yet, the last active day / week before it (e.g. on Sunday show
 * Friday's saldo if the weekend is empty). Running entries count their elapsed
 * time up to [now], so callers ticking `now` get live figures.
 */
fun projectCardStats(
    entries: List<TimeEntryDto>,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): ProjectCardStats {
    val today = now.atZone(zone).toLocalDate()
    val thisWeekStart = today.with(DayOfWeek.MONDAY)
    val days = HashMap<LocalDate, Long>()
    val weeks = HashMap<LocalDate, Long>()
    entries.forEach { e ->
        val date = Format.parseInstant(e.startedAt)?.atZone(zone)?.toLocalDate() ?: return@forEach
        val secs =
            if (e.stoppedAt == null) Format.elapsedSeconds(e.startedAt, now)
            else e.durationSeconds ?: Format.entrySeconds(e.startedAt, e.stoppedAt)
        days.merge(date, secs, Long::plus)
        weeks.merge(date.with(DayOfWeek.MONDAY), secs, Long::plus)
    }
    // fall back to the latest key before today / this week (future-dated entries don't count)
    val dayKey = if (days.containsKey(today)) today else days.keys.filter { it.isBefore(today) }.maxOrNull()
    val weekKey =
        if (weeks.containsKey(thisWeekStart)) thisWeekStart
        else weeks.keys.filter { it.isBefore(thisWeekStart) }.maxOrNull()
    return ProjectCardStats(
        daySeconds = dayKey?.let(days::getValue) ?: 0L,
        dayLabel = if (dayKey == null) "Heute" else statDayLabel(dayKey, today),
        weekSeconds = weekKey?.let(weeks::getValue) ?: 0L,
        weekLabel = if (weekKey == null) "Diese Woche" else statWeekLabel(weekKey, today),
    )
}

/** Compact day label like the web's dayGroupLabel: Heute/Gestern/Vorgestern/Wochentag/"3. Juni". */
internal fun statDayLabel(date: LocalDate, today: LocalDate): String =
    when (ChronoUnit.DAYS.between(date, today)) {
        0L -> "Heute"
        1L -> "Gestern"
        2L -> "Vorgestern"
        in 3L..6L -> date.dayOfWeek.getDisplayName(TextStyle.FULL, DE)
        else -> "${date.dayOfMonth}. ${date.month.getDisplayName(TextStyle.FULL, DE)}"
    }

/** Week label like the web's weekLabel: "Diese Woche" / "Letzte Woche" / "12.–18. Mai". */
internal fun statWeekLabel(weekStart: LocalDate, today: LocalDate): String {
    val currentStart = today.with(DayOfWeek.MONDAY)
    if (weekStart == currentStart) return "Diese Woche"
    if (weekStart == currentStart.minusWeeks(1)) return "Letzte Woche"
    val end = weekStart.plusDays(6)
    val endMonth = end.month.getDisplayName(TextStyle.FULL, DE)
    return if (weekStart.month == end.month) {
        "${weekStart.dayOfMonth}.–${end.dayOfMonth}. $endMonth"
    } else {
        "${weekStart.dayOfMonth}. ${weekStart.month.getDisplayName(TextStyle.FULL, DE)} – ${end.dayOfMonth}. $endMonth"
    }
}

// ---------------------------------------------------------------------------
// Split an entry (#66) — web reference: SplitEntryModal
// ---------------------------------------------------------------------------

/** Default cut: the entry's midpoint, snapped down to the full minute. */
fun defaultSplitAt(startedAtIso: String, stoppedAtIso: String?): Instant? {
    val start = Format.parseInstant(startedAtIso) ?: return null
    val stop = Format.parseInstant(stoppedAtIso) ?: return null
    val midMs = (start.toEpochMilli() + stop.toEpochMilli()) / 2
    return Instant.ofEpochMilli(midMs / 60_000 * 60_000)
}

/** Result of validating a split draft — either both planned parts or a German message. */
sealed interface SplitCheck {
    /** [breakMinutes] already rounded to whole minutes; [secondStart] = splitAt + break. */
    data class Valid(val splitAt: Instant, val breakMinutes: Int, val secondStart: Instant) : SplitCheck
    data class Invalid(val message: String) : SplitCheck
}

/**
 * Validate a split draft exactly like the web modal (#66): the cut must lie
 * strictly inside the entry, the break input may use a comma and is rounded to
 * whole minutes (empty = 0), and the break must end before the entry does.
 */
fun checkSplit(startedAtIso: String, stoppedAtIso: String?, splitAt: Instant, breakText: String): SplitCheck {
    val start = Format.parseInstant(startedAtIso)
    val stop = Format.parseInstant(stoppedAtIso)
    if (start == null || stop == null || !splitAt.isAfter(start) || !stop.isAfter(splitAt)) {
        return SplitCheck.Invalid("Die Trennzeit muss zwischen Start und Ende liegen")
    }
    val raw = breakText.trim()
    val parsed = if (raw.isEmpty()) 0.0 else raw.replace(',', '.').toDoubleOrNull()
    val breakMinutes = parsed?.takeIf { it.isFinite() }?.roundToInt()
    if (breakMinutes == null || breakMinutes < 0) {
        return SplitCheck.Invalid("Pause in Minuten angeben (z. B. 30)")
    }
    val secondStart = splitAt.plusSeconds(breakMinutes * 60L)
    if (!stop.isAfter(secondStart)) {
        return SplitCheck.Invalid("Die Pause muss vor dem Ende des Eintrags enden")
    }
    return SplitCheck.Valid(splitAt, breakMinutes, secondStart)
}
