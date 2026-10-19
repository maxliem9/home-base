package com.homebase.android.ui.time

import androidx.annotation.StringRes
import com.homebase.android.R
import com.homebase.android.data.model.TimeCreditDto
import com.homebase.android.data.model.TimeEntryDto
import com.homebase.android.data.model.UserForecastDto
import com.homebase.android.data.model.WorkTargetDto
import com.homebase.android.ui.util.Format
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

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

/** Week bar split into soll + overtime (fractions of the bar width, 0–1). */
data class WeekBarSplit(val baseFraction: Float, val overFraction: Float, val overSeconds: Long)

/**
 * Splits the Wochensoll bar: under target it fills toward the target as before; above
 * target it rescales to the Ist so the overtime gets its own segment instead of being
 * swallowed by a capped 100 % bar. Mirrors web `weekBarSplit` (worktarget.ts).
 */
fun weekBarSplit(doneSeconds: Long, targetSeconds: Long): WeekBarSplit {
    val done = doneSeconds.coerceAtLeast(0)
    if (targetSeconds <= 0) return WeekBarSplit(0f, 0f, 0)
    if (done <= targetSeconds) return WeekBarSplit(done.toFloat() / targetSeconds, 0f, 0)
    val base = targetSeconds.toFloat() / done
    return WeekBarSplit(base, 1f - base, done - targetSeconds)
}

/**
 * Weekly target (seconds) of [userId] on [projectId] for the week starting [weekStart] —
 * from the period in force then: the person's latest `validFrom` ≤ weekStart, like the
 * backend forecast/credits. No row for the project in that period (or a week before the
 * person's first period) → 0. Mirrors web `weeklyTargetSeconds` (worktarget.ts).
 */
fun weeklyTargetSeconds(targets: List<WorkTargetDto>, userId: String, projectId: String, weekStart: LocalDate): Long {
    val row = periodRows(targets, userId, weekStart).firstOrNull { it.projectId == projectId } ?: return 0
    return (row.weeklyHours * 3600).roundToLong()
}

/** The person's target rows of the period in force for the week starting [weekStart]. */
private fun periodRows(targets: List<WorkTargetDto>, userId: String, weekStart: LocalDate): List<WorkTargetDto> {
    val ws = weekStart.toString()
    val mine = targets.filter { it.userId == userId }
    val period = mine.map { it.validFrom }.filter { it <= ws }.maxOrNull() ?: return emptyList()
    return mine.filter { it.validFrom == period }
}

/**
 * Balance of one person on one project in one week against that week's project target:
 * positive = overtime, negative = hours missing; `null` without a target. Credits are booked from the person's TOTAL weekly target (all projects) onto the default
 * project (backend TimeCredits.kt), so only this project's share of [creditedSeconds] counts
 * toward it — otherwise a vacation week reads as overtime with a 40 h p1 + 5 h p2 split.
 * Mirrors web `projectWeekBalance`.
 */
fun projectWeekBalance(
    targets: List<WorkTargetDto>, userId: String, projectId: String, weekStart: LocalDate,
    seconds: Long, creditedSeconds: Long,
): Long? {
    val rows = periodRows(targets, userId, weekStart)
    val target = (rows.firstOrNull { it.projectId == projectId }?.weeklyHours ?: 0.0) * 3600
    if (target <= 0.0) return null
    val total = rows.sumOf { it.weeklyHours } * 3600
    val foreignCredit = creditedSeconds * (1 - target / total)
    return (seconds - foreignCredit - target).roundToLong()
}

/** Displayable overtime / missing hours of one person in one week (seconds, both ≥ 0). */
data class BalanceParts(val over: Long = 0, val under: Long = 0)

/**
 * Splits a week balance; sub-minute differences (< 30 s) count as on target. Missing hours
 * only for finished weeks — the running week is still open. Mirrors web `balanceParts`.
 */
fun balanceParts(balance: Long?, weekFinished: Boolean): BalanceParts {
    if (balance == null) return BalanceParts()
    return BalanceParts(
        over = if (balance >= 30) balance else 0,
        under = if (weekFinished && balance <= -30) -balance else 0,
    )
}

/**
 * Seconds above the weekly target; 0 without a target and for sub-minute excess (< 30 s,
 * would only read "+0 Min."). Mirrors web `overtimeSeconds`.
 */
fun overtimeSeconds(seconds: Long, targetSeconds: Long): Long {
    if (targetSeconds <= 0) return 0
    val over = seconds - targetSeconds
    return if (over >= 30) over else 0
}

/**
 * Running balance ("Saldo") per person: overtime minus missing hours over every week whose
 * Monday is ≥ the Monday of the week containing [from] — whole weeks only, so a half week is
 * never held against a full week's target. Mirrors web `runningBalance`.
 */
fun runningBalance(weekParts: Map<LocalDate, Map<String, BalanceParts>>, from: LocalDate): Map<String, Long> {
    val fromWeek = from.with(DayOfWeek.MONDAY)
    val out = LinkedHashMap<String, Long>()
    for ((weekStart, parts) in weekParts) {
        if (weekStart.isBefore(fromWeek)) continue
        for ((uid, p) in parts) out[uid] = (out[uid] ?: 0L) + p.over - p.under
    }
    return out
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
fun defaultSplitAt(startedAtIso: String, stoppedAtIso: String?, now: Instant = Instant.now()): Instant? {
    val start = Format.parseInstant(startedAtIso) ?: return null
    // a running entry (stoppedAt == null) is cut against "now" (#634)
    val stop = (if (stoppedAtIso == null) now else Format.parseInstant(stoppedAtIso)) ?: return null
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
 *
 * A running entry (stoppedAtIso == null, #634) is validated against [now] instead
 * of a stop time — cut and break must lie in the past; its error wording says so.
 */
fun checkSplit(
    startedAtIso: String,
    stoppedAtIso: String?,
    splitAt: Instant,
    breakText: String,
    now: Instant = Instant.now(),
): SplitCheck {
    val isRunning = stoppedAtIso == null
    val start = Format.parseInstant(startedAtIso)
    val stop = if (isRunning) now else Format.parseInstant(stoppedAtIso)
    if (start == null || stop == null || !splitAt.isAfter(start) || !stop.isAfter(splitAt)) {
        return SplitCheck.Invalid(if (isRunning) R.string.time_split_err_range_running else R.string.time_split_err_range)
    }
    val raw = breakText.trim()
    val parsed = if (raw.isEmpty()) 0.0 else raw.replace(',', '.').toDoubleOrNull()
    val breakMinutes = parsed?.takeIf { it.isFinite() }?.roundToInt()
    if (breakMinutes == null || breakMinutes < 0) {
        return SplitCheck.Invalid(R.string.time_split_err_break)
    }
    val secondStart = splitAt.plusSeconds(breakMinutes * 60L)
    if (!stop.isAfter(secondStart)) {
        return SplitCheck.Invalid(
            if (isRunning) R.string.time_split_err_break_overrun_running else R.string.time_split_err_break_overrun,
        )
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
    /** credited part of [byUser] — overtime only counts this project's share of a credit */
    val creditedByUser: Map<String, Long> = emptyMap(),
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
        val creditedByUser = HashMap<String, Long>()
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
        acc.creditedByUser[c.userId] = (acc.creditedByUser[c.userId] ?: 0L) + c.seconds
    }
    return byWeek.entries
        .map { (ws, acc) ->
            WeekStat(
                weekStart = ws,
                byUser = acc.byUser.toList().sortedByDescending { it.second },
                totalSeconds = acc.total,
                count = acc.count,
                creditedSeconds = acc.credited,
                creditedByUser = acc.creditedByUser,
            )
        }
        .sortedByDescending { it.weekStart }
        .take(maxWeeks)
}
