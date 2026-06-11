package com.homebase.time

import com.homebase.db.AbsSettingsTable
import com.homebase.db.AbsencesTable
import com.homebase.db.CustomHolidaysTable
import com.homebase.db.PartTimeRulesTable
import com.homebase.db.TimeEntriesTable
import com.homebase.db.TimeWorkTargetsTable
import com.homebase.db.UsersTable
import com.homebase.holidays.GermanHolidays
import com.homebase.model.ProjectForecastDto
import com.homebase.model.TimeForecastDto
import com.homebase.model.UserForecastDto
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Work forecast (#31) for one ISO week (Mon–Sun), per person:
 *
 *  - Daily target = the person's weekly target (sum over their project targets)
 *    ÷ workdays (Mon–Fri minus part-time-free days). Holidays and absences do NOT
 *    shrink the divisor — they are workdays whose target is credited instead of worked.
 *  - Credits: vacation/sick/child-sick days and statutory/custom holidays credit the
 *    daily target to the person's default project. Half days (absence `half`,
 *    custom holiday `half`) credit 0.5×; part-time-free days and weekends get nothing.
 *  - Today's target redistributes the week remainder: what is still open after credits
 *    and the days already recorded, spread over the remaining recordable days — so
 *    overtime on Monday shortens the rest of the week (and vice versa).
 *  - Expected end = now + (today's target − today's recorded time), only while a
 *    timer is running and never in the past.
 *
 * All date attribution (which day an entry belongs to) uses the entry's *start* in
 * the server zone — the same convention as the day grouping in the clients and the
 * CSV export. The [clock] is injectable for tests.
 */
class ForecastService(private val clock: Clock = Clock.systemDefaultZone()) {

    fun forecast(dateParam: LocalDate? = null): TimeForecastDto {
        val zone = clock.zone
        val now = Instant.now(clock)
        val today = LocalDate.now(clock)
        val day = dateParam ?: today
        val weekStart = day.with(DayOfWeek.MONDAY)
        val weekDays = (0L..6L).map { weekStart.plusDays(it) }
        val weekStartInstant = weekStart.atStartOfDay(zone).toInstant()
        val weekEndInstant = weekStart.plusDays(7).atStartOfDay(zone).toInstant()

        return transaction {
            val users = UsersTable.selectAll()
                .orderBy(UsersTable.createdAt, SortOrder.ASC)
                .map { it[UsersTable.username] }
            val targets = TimeWorkTargetsTable.selectAll().toList()
            val partTime = PartTimeRulesTable.selectAll().toList()
            val customHolidays = CustomHolidaysTable.selectAll().toList()
            val settings = AbsSettingsTable.selectAll().toList()
            val absences = AbsencesTable.selectAll()
                .where { (AbsencesTable.date greaterEq weekStart) and (AbsencesTable.date lessEq weekStart.plusDays(6)) }
                .toList()
            // Entries whose start falls into the week, plus still-running timers (their
            // elapsed time counts and the hero needs the expected end while they run).
            val entries = TimeEntriesTable.selectAll()
                .where {
                    ((TimeEntriesTable.startedAt greaterEq weekStartInstant) and (TimeEntriesTable.startedAt less weekEndInstant)) or
                        TimeEntriesTable.stoppedAt.isNull()
                }
                .toList()

            val userForecasts = users.map { user ->
                forecastUser(
                    user = user,
                    day = day,
                    today = today,
                    now = now,
                    weekDays = weekDays,
                    targets = targets.filter { it[TimeWorkTargetsTable.userId] == user },
                    partTime = partTime.filter { it[PartTimeRulesTable.userId] == user },
                    absences = absences.filter { it[AbsencesTable.userId] == user },
                    customHolidays = customHolidays,
                    settings = settings.filter { it[AbsSettingsTable.userId] == user },
                    entries = entries.filter { it[TimeEntriesTable.userId] == user },
                )
            }

            TimeForecastDto(
                date = day.toString(),
                weekStart = weekStart.toString(),
                users = userForecasts,
            )
        }
    }

    private fun forecastUser(
        user: String,
        day: LocalDate,
        today: LocalDate,
        now: Instant,
        weekDays: List<LocalDate>,
        targets: List<ResultRow>,
        partTime: List<ResultRow>,
        absences: List<ResultRow>,
        customHolidays: List<ResultRow>,
        settings: List<ResultRow>,
        entries: List<ResultRow>,
    ): UserForecastDto {
        val weeklyHours = targets.sumOf { it[TimeWorkTargetsTable.weeklyHours] }
        val weekTargetSeconds = weeklyHours * 3600.0

        // 1.0 for a Mon–Fri day that is not part-time-free, else 0.
        fun workPortion(d: LocalDate): Double {
            if (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) return 0.0
            val free = partTime.any { rule ->
                rule[PartTimeRulesTable.weekday] == d.dayOfWeek.value &&
                    !d.isBefore(rule[PartTimeRulesTable.startDate]) &&
                    (rule[PartTimeRulesTable.endDate]?.let { !d.isAfter(it) } ?: true)
            }
            return if (free) 0.0 else 1.0
        }

        // Fraction of the daily target credited on a workday (0 / 0.5 / 1).
        // Statutory holidays win over custom ones (same precedence as the web client);
        // a half holiday plus a half absence stack, capped at one full day.
        fun creditPortion(d: LocalDate): Double {
            if (workPortion(d) == 0.0) return 0.0
            val statutory = GermanHolidays.holidays(d.year, stateFor(settings, d.year)).containsKey(d)
            val holiday = if (statutory) 1.0 else customHolidays
                .firstOrNull { it[CustomHolidaysTable.month] == d.monthValue && it[CustomHolidaysTable.day] == d.dayOfMonth }
                ?.let { if (it[CustomHolidaysTable.half]) 0.5 else 1.0 } ?: 0.0
            val absence = absences
                .firstOrNull { it[AbsencesTable.date].isEqual(d) }
                ?.let { if (it[AbsencesTable.half] != null) 0.5 else 1.0 } ?: 0.0
            return min(1.0, holiday + absence)
        }

        val workdayCount = weekDays.sumOf(::workPortion)
        val dailyTarget = if (workdayCount > 0) weekTargetSeconds / workdayCount else 0.0
        val weekCredited = weekDays.sumOf { dailyTarget * creditPortion(it) }

        // Recorded seconds keyed by the entry's local start date; running entries count
        // their elapsed time up to now.
        val zone = clock.zone
        val recordedByDay = HashMap<LocalDate, Long>()
        val recordedByProject = HashMap<UUID, Long>()
        var runningEntry: ResultRow? = null
        for (e in entries) {
            val started = e[TimeEntriesTable.startedAt]
            val stopped = e[TimeEntriesTable.stoppedAt]
            if (stopped == null) runningEntry = e
            val startDate = started.atZone(zone).toLocalDate()
            if (startDate !in weekDays.first()..weekDays.last()) continue
            val seconds = max(0L, Duration.between(started, stopped ?: now).seconds)
            recordedByDay.merge(startDate, seconds, Long::plus)
            recordedByProject.merge(e[TimeEntriesTable.projectId], seconds, Long::plus)
        }
        val weekRecorded = recordedByDay.values.sum()
        val todayRecorded = recordedByDay[day] ?: 0L
        val recordedBefore = recordedByDay.filterKeys { it.isBefore(day) }.values.sum()

        // Redistribute what is still open over the remaining recordable day portions
        // (today included), so earlier over-/under-time shifts the later daily targets.
        val remainingPortions = weekDays.filter { !it.isBefore(day) }.sumOf { workPortion(it) - creditPortion(it) }
        val todayPortion = workPortion(day) - creditPortion(day)
        val remainingWeekTarget = weekTargetSeconds - weekCredited - recordedBefore
        val todayTarget = if (remainingPortions > 1e-9) {
            (max(0.0, remainingWeekTarget) * todayPortion / remainingPortions).roundToLong()
        } else 0L

        val todayRemaining = todayTarget - todayRecorded
        // Projected stop time, clamped to now (a timer past its target "ends now").
        // Only meaningful for the real today and while a timer actually runs. Note:
        // a still-running timer started on an earlier day counts toward that day
        // (start-date convention), so today's recorded stays 0 and this projection
        // moves forward with `now` instead of staying start-anchored — accepted
        // edge case for over-midnight sessions.
        val expectedEndAt = if (day.isEqual(today) && runningEntry != null) {
            now.plusSeconds(max(0L, todayRemaining)).toString()
        } else null

        val defaultProjectId = targets.firstOrNull { it[TimeWorkTargetsTable.isDefault] }
            ?.get(TimeWorkTargetsTable.projectId)
        val weekCreditedSeconds = weekCredited.roundToLong()
        val projectIds = (targets.map { it[TimeWorkTargetsTable.projectId] } + recordedByProject.keys).distinct()
        val projects = projectIds.map { pid ->
            val hours = targets.firstOrNull { it[TimeWorkTargetsTable.projectId] == pid }
                ?.get(TimeWorkTargetsTable.weeklyHours) ?: 0.0
            val recorded = recordedByProject[pid] ?: 0L
            val credited = if (pid == defaultProjectId) weekCreditedSeconds else 0L
            ProjectForecastDto(
                projectId = pid.toString(),
                weeklyHours = hours,
                recordedSeconds = recorded,
                creditedSeconds = credited,
                deltaSeconds = recorded + credited - (hours * 3600.0).roundToLong(),
            )
        }.sortedWith(compareByDescending<ProjectForecastDto> { it.weeklyHours }.thenBy { it.projectId })

        return UserForecastDto(
            userId = user,
            weeklyTargetHours = weeklyHours,
            workdayCount = workdayCount,
            weekTargetSeconds = weekTargetSeconds.roundToLong(),
            weekRecordedSeconds = weekRecorded,
            weekCreditedSeconds = weekCreditedSeconds,
            weekRemainingSeconds = (weekTargetSeconds - weekCredited - weekRecorded).roundToLong(),
            todayTargetSeconds = todayTarget,
            todayRecordedSeconds = todayRecorded,
            todayRemainingSeconds = todayRemaining,
            expectedEndAt = expectedEndAt,
            projects = projects,
        )
    }

    /** The user's Bundesland for [year]: exact row, else the nearest earlier year,
     *  else the nearest later one — same inheritance idea as the absence settings (#144). */
    private fun stateFor(settings: List<ResultRow>, year: Int): String {
        val row = settings.filter { it[AbsSettingsTable.year] <= year }.maxByOrNull { it[AbsSettingsTable.year] }
            ?: settings.minByOrNull { it[AbsSettingsTable.year] }
        return row?.get(AbsSettingsTable.state) ?: "BE"
    }
}
