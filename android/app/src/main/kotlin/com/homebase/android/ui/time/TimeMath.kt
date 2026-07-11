package com.homebase.android.ui.time

import androidx.annotation.StringRes
import com.homebase.android.R
import com.homebase.android.data.model.TimeCreditDto
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

private fun isEn(loc: Locale): Boolean = loc.language == Locale.ENGLISH.language

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

/** Day + week saldo of one project card, incl. the locale-aware fallback labels. */
data class ProjectCardStats(
    val daySeconds: Long,
    /** "Heute"/"Today" · "Gestern"/"Yesterday" · "Vorgestern"/"2 days ago" · weekday · date */
    val dayLabel: String,
    val weekSeconds: Long,
    /** "Diese Woche"/"This week" · "Letzte Woche"/"Last week" · date range */
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
    locale: Locale = Locale.getDefault(),
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
        dayLabel = if (dayKey == null) (if (isEn(locale)) "Today" else "Heute") else statDayLabel(dayKey, today, locale),
        weekSeconds = weekKey?.let(weeks::getValue) ?: 0L,
        weekLabel = if (weekKey == null) (if (isEn(locale)) "This week" else "Diese Woche") else statWeekLabel(weekKey, today, locale),
    )
}

/** Compact day label like the web's dayGroupLabel: Heute/Gestern/Vorgestern/Wochentag/date — in [locale]. */
internal fun statDayLabel(date: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String {
    val en = isEn(locale)
    return when (ChronoUnit.DAYS.between(date, today)) {
        0L -> if (en) "Today" else "Heute"
        1L -> if (en) "Yesterday" else "Gestern"
        2L -> if (en) "2 days ago" else "Vorgestern"
        in 3L..6L -> date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        else -> {
            val month = date.month.getDisplayName(TextStyle.FULL, locale)
            if (en) "${date.dayOfMonth} $month" else "${date.dayOfMonth}. $month"
        }
    }
}

/** Week label like the web's weekLabel: "Diese Woche" / "This week" / date range — in [locale]. */
internal fun statWeekLabel(weekStart: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String {
    val en = isEn(locale)
    val currentStart = today.with(DayOfWeek.MONDAY)
    if (weekStart == currentStart) return if (en) "This week" else "Diese Woche"
    if (weekStart == currentStart.minusWeeks(1)) return if (en) "Last week" else "Letzte Woche"
    val end = weekStart.plusDays(6)
    val endMonth = end.month.getDisplayName(TextStyle.FULL, locale)
    return if (weekStart.month == end.month) {
        if (en) "${weekStart.dayOfMonth}–${end.dayOfMonth} $endMonth"
        else "${weekStart.dayOfMonth}.–${end.dayOfMonth}. $endMonth"
    } else {
        val startMonth = weekStart.month.getDisplayName(TextStyle.FULL, locale)
        if (en) "${weekStart.dayOfMonth} $startMonth – ${end.dayOfMonth} $endMonth"
        else "${weekStart.dayOfMonth}. $startMonth – ${end.dayOfMonth}. $endMonth"
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

/** Result of validating a split draft — either both planned parts or a localized message resource. */
sealed interface SplitCheck {
    /** [breakMinutes] already rounded to whole minutes; [secondStart] = splitAt + break. */
    data class Valid(val splitAt: Instant, val breakMinutes: Int, val secondStart: Instant) : SplitCheck
    /** [messageRes] resolves the localized error via `stringResource` (#204). */
    data class Invalid(@StringRes val messageRes: Int) : SplitCheck
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
        return SplitCheck.Invalid(R.string.time_split_err_range)
    }
    val raw = breakText.trim()
    val parsed = if (raw.isEmpty()) 0.0 else raw.replace(',', '.').toDoubleOrNull()
    val breakMinutes = parsed?.takeIf { it.isFinite() }?.roundToInt()
    if (breakMinutes == null || breakMinutes < 0) {
        return SplitCheck.Invalid(R.string.time_split_err_break)
    }
    val secondStart = splitAt.plusSeconds(breakMinutes * 60L)
    if (!stop.isAfter(secondStart)) {
        return SplitCheck.Invalid(R.string.time_split_err_break_overrun)
    }
    return SplitCheck.Valid(splitAt, breakMinutes, secondStart)
}

// ---------------------------------------------------------------------------
// Projekt-Detail per-week aggregation (#31). Mirrors web's ProjectDetail: recorded
// entries plus absence/holiday credits (sick/vacation/holiday hours booked to the
// default project) folded into each week's total, per-user bar and a credited line.
// `count` stays entry-only — a credit is not a tracked entry.
// ---------------------------------------------------------------------------

/** One week's stats for the project detail; [creditedSeconds] is the part of
 *  [totalSeconds] that came from absences/holidays (not from tracked entries). */
data class WeekStat(
    val weekStart: LocalDate,
    val byUser: List<Pair<String, Long>>,
    val totalSeconds: Long,
    val count: Int,
    val creditedSeconds: Long = 0,
)

/**
 * Build Monday-anchored week stats for a project, newest first, capped at [maxWeeks].
 * [entries] should be the project's finished entries; [credits] the project's absence/
 * holiday credits (already filtered to this project — they only ever land on someone's
 * default project). A week that was entirely absent (credits, no entries) still appears.
 */
fun buildWeekStats(
    entries: List<TimeEntryDto>,
    credits: List<TimeCreditDto>,
    zone: ZoneId = ZoneId.systemDefault(),
    maxWeeks: Int = 6,
): List<WeekStat> {
    class Acc {
        var total = 0L
        var count = 0
        var credited = 0L
        val byUser = LinkedHashMap<String, Long>()
        fun add(user: String, seconds: Long) { byUser[user] = (byUser[user] ?: 0L) + seconds }
    }

    val byWeek = LinkedHashMap<LocalDate, Acc>()
    for (e in entries) {
        val ws = Format.parseInstant(e.startedAt)?.atZone(zone)?.toLocalDate()?.with(DayOfWeek.MONDAY) ?: continue
        val secs = Format.entrySeconds(e.startedAt, e.stoppedAt)
        val acc = byWeek.getOrPut(ws) { Acc() }
        acc.total += secs
        acc.count += 1
        acc.add(e.userId, secs)
    }
    for (c in credits) {
        val ws = runCatching { LocalDate.parse(c.date) }.getOrNull()?.with(DayOfWeek.MONDAY) ?: continue
        val acc = byWeek.getOrPut(ws) { Acc() }
        acc.total += c.seconds
        acc.credited += c.seconds
        acc.add(c.userId, c.seconds)
    }
    return byWeek.entries
        .map { (ws, acc) ->
            WeekStat(
                weekStart = ws,
                byUser = acc.byUser.toList().sortedByDescending { it.second },
                totalSeconds = acc.total,
                count = acc.count,
                creditedSeconds = acc.credited,
            )
        }
        .sortedByDescending { it.weekStart }
        .take(maxWeeks)
}
