package com.homebase.time

import com.homebase.db.dbQuery
import com.homebase.db.AbsSettingsTable
import com.homebase.db.AbsencesTable
import com.homebase.db.CustomHolidaysTable
import com.homebase.db.PartTimeRulesTable
import com.homebase.db.TimeWorkTargetsTable
import com.homebase.db.UsersTable
import com.homebase.holidays.GermanHolidays
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID

/**
 * Shared work-credit logic for absences and holidays (#31), factored out of
 * [ForecastService] so the *historical* surfaces (CSV export, per-week project
 * breakdown) credit sick/vacation/child-sick days and holidays exactly the way the
 * live Wochenbilanz does — one source of truth, no drift between the two views.
 *
 * A credit books the person's **daily target** to their **default project** on a
 * workday they were absent (or that was a holiday). Daily target = weekly target ÷
 * that week's workdays (Mon–Fri minus part-time-free days); half days credit 0.5×.
 * Holidays and absences never shrink the workday divisor — they are workdays whose
 * target is credited instead of worked.
 */

/** One day's credit for one person: [seconds] booked to [projectId] (their default). */
data class TimeCredit(
    val user: String,
    val date: LocalDate,
    val projectId: UUID,
    val seconds: Long,
    /** KRANK | URLAUB | KIND_KRANK (the entered absence) or FEIERTAG (holiday only). */
    val type: String,
)

/** Portion (0/0.5/1) of the daily target credited on [d] plus the label for it. */
internal data class DayCredit(val portion: Double, val type: String)

/** 1.0 for a Mon–Fri day that is not part-time-free for [partTime]; else 0. */
internal fun workPortion(d: LocalDate, partTime: List<ResultRow>): Double {
    if (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) return 0.0
    val free = partTime.any { rule ->
        rule[PartTimeRulesTable.weekday] == d.dayOfWeek.value &&
            !d.isBefore(rule[PartTimeRulesTable.startDate]) &&
            (rule[PartTimeRulesTable.endDate]?.let { !d.isAfter(it) } ?: true)
    }
    return if (free) 0.0 else 1.0
}

/**
 * The credit on workday [d], or null when nothing is credited. Statutory holidays win
 * over custom ones (same precedence as the web client); a half holiday plus a half
 * absence stack, capped at one full day. The [type] labels the credit for reporting:
 * the entered absence wins over a coinciding holiday (a sick half-day on a holiday is
 * still "the day you were sick"), so the total stays exact while the label stays the
 * meaningful, user-entered one.
 */
internal fun dayCredit(
    d: LocalDate,
    partTime: List<ResultRow>,
    absences: List<ResultRow>,
    customHolidays: List<ResultRow>,
    settings: List<ResultRow>,
): DayCredit? {
    if (workPortion(d, partTime) == 0.0) return null
    val statutory = GermanHolidays.holidays(d.year, stateFor(settings, d.year)).containsKey(d)
    val holiday = if (statutory) 1.0 else customHolidays
        .firstOrNull { it[CustomHolidaysTable.month] == d.monthValue && it[CustomHolidaysTable.day] == d.dayOfMonth }
        ?.let { if (it[CustomHolidaysTable.half]) 0.5 else 1.0 } ?: 0.0
    val absenceRow = absences.firstOrNull { it[AbsencesTable.date].isEqual(d) }
    val absence = absenceRow?.let { if (it[AbsencesTable.half] != null) 0.5 else 1.0 } ?: 0.0
    val portion = minOf(1.0, holiday + absence)
    if (portion == 0.0) return null
    val type = absenceRow?.get(AbsencesTable.type) ?: "FEIERTAG"
    return DayCredit(portion, type)
}

/** The user's Bundesland for [year]: exact row, else nearest earlier, else nearest
 *  later — same inheritance idea as the absence settings. Falls back to BE. */
internal fun stateFor(settings: List<ResultRow>, year: Int): String {
    val row = settings.filter { it[AbsSettingsTable.year] <= year }.maxByOrNull { it[AbsSettingsTable.year] }
        ?: settings.minByOrNull { it[AbsSettingsTable.year] }
    return row?.get(AbsSettingsTable.state) ?: "BE"
}

/**
 * Computes per-day work credits for every person over the inclusive date range
 * [from]..[to]. Weeks are treated whole (the daily target divides that week's weekly
 * target), but only days inside the range are emitted. Effective-dated Wochensoll
 * (#31 follow-up): each week uses the target period in force for it (latest valid_from
 * ≤ the week's Monday), so historical weeks credit against the value that was valid
 * then — the same period selection as the live forecast.
 *
 * Runs its own read transaction; safe to call from a route handler.
 */
class TimeCreditService {

    suspend fun credits(from: LocalDate, to: LocalDate): List<TimeCredit> {
        if (to.isBefore(from)) return emptyList()
        return dbQuery {
            val users = UsersTable.selectAll()
                .orderBy(UsersTable.createdAt, SortOrder.ASC)
                .map { it[UsersTable.username] }
            val targets = TimeWorkTargetsTable.selectAll().toList()
            val partTime = PartTimeRulesTable.selectAll().toList()
            val customHolidays = CustomHolidaysTable.selectAll().toList()
            val settings = AbsSettingsTable.selectAll().toList()
            val absences = AbsencesTable.selectAll()
                .where { (AbsencesTable.date greaterEq from) and (AbsencesTable.date lessEq to) }
                .toList()

            users.flatMap { user ->
                creditsForUser(
                    user = user,
                    from = from,
                    to = to,
                    targets = targets.filter { it[TimeWorkTargetsTable.userId] == user },
                    partTime = partTime.filter { it[PartTimeRulesTable.userId] == user },
                    absences = absences.filter { it[AbsencesTable.userId] == user },
                    customHolidays = customHolidays,
                    settings = settings.filter { it[AbsSettingsTable.userId] == user },
                )
            }
        }
    }

    private fun creditsForUser(
        user: String,
        from: LocalDate,
        to: LocalDate,
        targets: List<ResultRow>,
        partTime: List<ResultRow>,
        absences: List<ResultRow>,
        customHolidays: List<ResultRow>,
        settings: List<ResultRow>,
    ): List<TimeCredit> {
        val out = ArrayList<TimeCredit>()
        // Walk whole ISO weeks; the daily target is per-week (workdays can change with
        // part-time rules), but only days within [from, to] are emitted.
        var weekStart = from.with(DayOfWeek.MONDAY)
        val lastWeekStart = to.with(DayOfWeek.MONDAY)
        while (!weekStart.isAfter(lastWeekStart)) {
            // Effective-dated Wochensoll (#31 follow-up): the target period in force for
            // THIS week (latest valid_from ≤ its Monday), so a week before a change still
            // credits against the value that was valid then.
            val activePeriod = targets.map { it[TimeWorkTargetsTable.validFrom] }
                .filter { !it.isAfter(weekStart) }
                .maxOrNull()
            val periodTargets = if (activePeriod != null)
                targets.filter { it[TimeWorkTargetsTable.validFrom] == activePeriod } else emptyList()
            val weeklyHours = periodTargets.sumOf { it[TimeWorkTargetsTable.weeklyHours] }
            // Credits land on the default project; without hours or a default there is
            // nothing to book this week (skip it, later weeks may differ).
            val defaultProjectId = periodTargets.firstOrNull { it[TimeWorkTargetsTable.isDefault] }
                ?.get(TimeWorkTargetsTable.projectId)
            val weekDays = (0L..6L).map { weekStart.plusDays(it) }
            val workdayCount = weekDays.sumOf { workPortion(it, partTime) }
            if (weeklyHours > 0.0 && defaultProjectId != null && workdayCount > 0) {
                val dailyTarget = weeklyHours * 3600.0 / workdayCount
                for (d in weekDays) {
                    if (d.isBefore(from) || d.isAfter(to)) continue
                    val credit = dayCredit(d, partTime, absences, customHolidays, settings) ?: continue
                    val seconds = Math.round(dailyTarget * credit.portion)
                    if (seconds > 0) out.add(TimeCredit(user, d, defaultProjectId, seconds, credit.type))
                }
            }
            weekStart = weekStart.plusDays(7)
        }
        return out
    }
}
