package com.homebase.service

import com.homebase.db.AbsSettingsTable
import com.homebase.db.AbsencesTable
import com.homebase.db.CustomHolidaysTable
import com.homebase.db.KitaClosuresTable
import com.homebase.db.PartTimeRulesTable
import com.homebase.db.UsersTable
import com.homebase.db.dbQuery
import com.homebase.model.*
import com.homebase.routes.userExists
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import java.time.LocalDate
import java.util.UUID

/**
 * Owns the absence/household-calendar domain's persistence (issue #566, following the TodoService
 * pattern of #546): the full snapshot, absence entries, part-time rules, kita closures, custom
 * holidays and per-person/per-year settings. Methods are `suspend` + `dbQuery {}` (#549).
 *
 * The household calendar is intentionally shared — either user edits either person's data — so there
 * is no ownership gate. HTTP validation (date/type/half/weekday/state/year parsing) and the
 * post-commit `ABSENCE_CHANGED` broadcast stay in the route; unknown-user is reported as `null`.
 */
class AbsenceService {

    /** Full snapshot — clients refetch this on every change. */
    suspend fun snapshot(): AbsenceStateDto = dbQuery {
        val users = UsersTable.selectAll()
            .orderBy(UsersTable.createdAt, SortOrder.ASC)
            .map { it[UsersTable.username] }
        AbsenceStateDto(
            users = users,
            absences = AbsencesTable.selectAll()
                .orderBy(AbsencesTable.date, SortOrder.ASC)
                .map { it.toAbsenceDto() },
            partTime = PartTimeRulesTable.selectAll()
                .map { it.toPartTimeDto() },
            kitaClosures = KitaClosuresTable.selectAll()
                .orderBy(KitaClosuresTable.date, SortOrder.ASC)
                .map { it.toKitaDto() },
            customHolidays = CustomHolidaysTable.selectAll()
                .orderBy(CustomHolidaysTable.month, SortOrder.ASC)
                .orderBy(CustomHolidaysTable.day, SortOrder.ASC)
                .map { it.toCustomHolidayDto() },
            settings = AbsSettingsTable.selectAll()
                .orderBy(AbsSettingsTable.userId, SortOrder.ASC)
                .orderBy(AbsSettingsTable.year, SortOrder.ASC)
                .map { it.toSettingsDto() },
        )
    }

    // ---- Absence entries -------------------------------------------------

    /** null = unknown user (→ 404). Caller has validated type/half and parsed the date. */
    suspend fun setAbsence(userId: String, date: LocalDate, type: String, half: String?): AbsenceDto? = dbQuery {
        if (!userExists(userId)) return@dbQuery null
        upsertAbsence(userId, date, type, half)
    }

    /** false = unknown user (→ 404). [type] null clears the dates. Caller has validated + parsed. */
    suspend fun batchAbsence(userId: String, dates: List<LocalDate>, type: String?, half: String?): Boolean = dbQuery {
        if (!userExists(userId)) return@dbQuery false
        dates.forEach { d ->
            AbsencesTable.deleteWhere { (AbsencesTable.userId eq userId) and (AbsencesTable.date eq d) }
            if (type != null) {
                AbsencesTable.insert {
                    it[id] = UUID.randomUUID()
                    it[AbsencesTable.userId] = userId
                    it[AbsencesTable.date] = d
                    it[AbsencesTable.type] = type
                    it[AbsencesTable.half] = half
                }
            }
        }
        true
    }

    suspend fun clearAbsence(userId: String, date: LocalDate) {
        dbQuery {
            AbsencesTable.deleteWhere { (AbsencesTable.userId eq userId) and (AbsencesTable.date eq date) }
        }
    }

    // ---- Part-time rules -------------------------------------------------

    /** null = unknown user (→ 404). Caller has validated the weekday + parsed the dates. */
    suspend fun createPartTime(userId: String, weekday: Int, start: LocalDate, end: LocalDate?): PartTimeRuleDto? = dbQuery {
        if (!userExists(userId)) return@dbQuery null
        val id = UUID.randomUUID()
        PartTimeRulesTable.insert {
            it[PartTimeRulesTable.id] = id
            it[PartTimeRulesTable.userId] = userId
            it[PartTimeRulesTable.weekday] = weekday
            it[startDate] = start
            it[endDate] = end
        }
        PartTimeRulesTable.selectAll().where { PartTimeRulesTable.id eq id }.single().toPartTimeDto()
    }

    /** null = rule not found (→ 404). */
    suspend fun updatePartTime(id: UUID, weekday: Int, start: LocalDate, end: LocalDate?): PartTimeRuleDto? = dbQuery {
        if (PartTimeRulesTable.selectAll().where { PartTimeRulesTable.id eq id }.empty()) return@dbQuery null
        PartTimeRulesTable.update({ PartTimeRulesTable.id eq id }) {
            it[PartTimeRulesTable.weekday] = weekday
            it[startDate] = start
            it[endDate] = end
        }
        PartTimeRulesTable.selectAll().where { PartTimeRulesTable.id eq id }.single().toPartTimeDto()
    }

    /** false = rule not found (→ 404). */
    suspend fun deletePartTime(id: UUID): Boolean = dbQuery {
        PartTimeRulesTable.deleteWhere { PartTimeRulesTable.id eq id } > 0
    }

    // ---- Kita closures ---------------------------------------------------

    class KitaUpsert(val dto: KitaClosureDto, val created: Boolean)

    /** Idempotent: one closure per date. [created] is false when the date was already closed. */
    suspend fun upsertKita(date: LocalDate, label: String?): KitaUpsert = dbQuery {
        val existing = KitaClosuresTable.selectAll().where { KitaClosuresTable.date eq date }.singleOrNull()
        if (existing != null) KitaUpsert(existing.toKitaDto(), created = false)
        else KitaUpsert(insertKita(date, label), created = true)
    }

    /** Adds a closure for each weekday (Mon–Fri) in [from]..[to], skipping dates already closed. */
    suspend fun kitaRange(from: LocalDate, to: LocalDate, label: String?) {
        dbQuery {
            val existing = KitaClosuresTable.selectAll()
                .where { (KitaClosuresTable.date greaterEq from) and (KitaClosuresTable.date lessEq to) }
                .map { it[KitaClosuresTable.date] }
                .toSet()
            var d = from
            while (!d.isAfter(to)) {
                if (d.dayOfWeek.value <= 5 && d !in existing) insertKita(d, label)
                d = d.plusDays(1)
            }
        }
    }

    sealed interface KitaUpdateResult {
        data class Ok(val dto: KitaClosureDto) : KitaUpdateResult
        data object Conflict : KitaUpdateResult
        data object NotFound : KitaUpdateResult
    }

    /** Caller parsed [date] (null = unchanged). */
    suspend fun updateKita(id: UUID, date: LocalDate?, label: String?): KitaUpdateResult = dbQuery {
        if (KitaClosuresTable.selectAll().where { KitaClosuresTable.id eq id }.empty()) return@dbQuery KitaUpdateResult.NotFound
        // Moving onto a date another closure occupies would violate unique(date) → clean 409.
        if (date != null && !KitaClosuresTable.selectAll()
                .where { (KitaClosuresTable.date eq date) and (KitaClosuresTable.id neq id) }
                .empty()
        ) return@dbQuery KitaUpdateResult.Conflict
        KitaClosuresTable.update({ KitaClosuresTable.id eq id }) {
            date?.let { v -> it[KitaClosuresTable.date] = v }
            label?.let { v -> it[KitaClosuresTable.label] = v }
        }
        KitaUpdateResult.Ok(KitaClosuresTable.selectAll().where { KitaClosuresTable.id eq id }.single().toKitaDto())
    }

    /** false = closure not found (→ 404). */
    suspend fun deleteKita(id: UUID): Boolean = dbQuery {
        KitaClosuresTable.deleteWhere { KitaClosuresTable.id eq id } > 0
    }

    // ---- Custom holidays (#51) ------------------------------------------

    class HolidayUpsert(val dto: CustomHolidayDto, val created: Boolean)

    /** Idempotent: one holiday per (month, day). Caller has validated the month/day. */
    suspend fun upsertHoliday(month: Int, day: Int, half: Boolean, label: String?): HolidayUpsert = dbQuery {
        val existing = CustomHolidaysTable.selectAll()
            .where { (CustomHolidaysTable.month eq month) and (CustomHolidaysTable.day eq day) }
            .singleOrNull()
        if (existing != null) HolidayUpsert(existing.toCustomHolidayDto(), created = false)
        else HolidayUpsert(insertCustomHoliday(month, day, half, label), created = true)
    }

    sealed interface HolidayUpdateResult {
        data class Ok(val dto: CustomHolidayDto) : HolidayUpdateResult
        data object Conflict : HolidayUpdateResult
        data object InvalidDate : HolidayUpdateResult
        data object NotFound : HolidayUpdateResult
    }

    suspend fun updateHoliday(id: UUID, req: UpdateCustomHolidayRequest): HolidayUpdateResult = dbQuery {
        val current = CustomHolidaysTable.selectAll().where { CustomHolidaysTable.id eq id }.singleOrNull()
            ?: return@dbQuery HolidayUpdateResult.NotFound
        val newMonth = req.month ?: current[CustomHolidaysTable.month]
        val newDay = req.day ?: current[CustomHolidaysTable.day]
        if (!isValidMonthDay(newMonth, newDay)) return@dbQuery HolidayUpdateResult.InvalidDate
        // Moving onto a date another holiday occupies would violate unique(month, day) → 409.
        if ((newMonth != current[CustomHolidaysTable.month] || newDay != current[CustomHolidaysTable.day]) &&
            !CustomHolidaysTable.selectAll()
                .where { (CustomHolidaysTable.month eq newMonth) and (CustomHolidaysTable.day eq newDay) and (CustomHolidaysTable.id neq id) }
                .empty()
        ) return@dbQuery HolidayUpdateResult.Conflict
        CustomHolidaysTable.update({ CustomHolidaysTable.id eq id }) {
            req.month?.let { v -> it[month] = v }
            req.day?.let { v -> it[day] = v }
            req.half?.let { v -> it[half] = v }
            req.label?.let { v -> it[label] = v }
        }
        HolidayUpdateResult.Ok(CustomHolidaysTable.selectAll().where { CustomHolidaysTable.id eq id }.single().toCustomHolidayDto())
    }

    /** false = holiday not found (→ 404). */
    suspend fun deleteHoliday(id: UUID): Boolean = dbQuery {
        CustomHolidaysTable.deleteWhere { CustomHolidaysTable.id eq id } > 0
    }

    // ---- Settings --------------------------------------------------------

    /** null = unknown user (→ 404). Caller has validated the state + parsed the carryover date. */
    suspend fun upsertSettings(userId: String, year: Int, req: UpdateAbsSettingsRequest, expires: LocalDate?): AbsSettingsDto? =
        dbQuery { upsertAbsSettings(userId, year, req, expires) }
}

// ---- Persistence helpers + mappers (run inside a transaction) ----------------------------------

/**
 * Valid recurring calendar date: month 1..12 and day within that month's length. A leap year (2000)
 * is used so Feb 29 is accepted — the holiday recurs and is valid in leap years. Internal so the
 * route's POST-holiday validation and [AbsenceService.updateHoliday] share one definition.
 */
internal fun isValidMonthDay(month: Int, day: Int): Boolean {
    if (month !in 1..12) return false
    val maxDay = runCatching { java.time.YearMonth.of(2000, month).lengthOfMonth() }.getOrElse { return false }
    return day in 1..maxDay
}

/**
 * Upsert one user's settings for a single year. On insert the stable fields (Bundesland, allowance,
 * kind-krank cap) are inherited from the nearest existing year; the carryover ("Resturlaub") is
 * deliberately NOT inherited. Returns null if the user is unknown.
 */
private fun upsertAbsSettings(userId: String, year: Int, req: UpdateAbsSettingsRequest, expires: LocalDate?): AbsSettingsDto? {
    if (!userExists(userId)) return null
    val existing = AbsSettingsTable.selectAll()
        .where { (AbsSettingsTable.userId eq userId) and (AbsSettingsTable.year eq year) }
        .singleOrNull()
    if (existing == null) {
        val base = nearestSettings(userId, year)
        AbsSettingsTable.insert {
            it[AbsSettingsTable.userId] = userId
            it[AbsSettingsTable.year] = year
            it[state] = req.state ?: base?.get(state) ?: "BE"
            it[allowance] = req.allowance ?: base?.get(allowance) ?: 30.0
            it[carryover] = req.carryover ?: 0.0
            it[carryoverExpires] = expires
            it[kindKrankCap] = req.kindKrankCap ?: base?.get(kindKrankCap) ?: 15
        }
    } else {
        AbsSettingsTable.update({ (AbsSettingsTable.userId eq userId) and (AbsSettingsTable.year eq year) }) {
            req.state?.let { v -> it[state] = v }
            req.allowance?.let { v -> it[allowance] = v }
            req.carryover?.let { v -> it[carryover] = v }
            if (req.carryoverExpires != null) it[carryoverExpires] = expires
            req.kindKrankCap?.let { v -> it[kindKrankCap] = v }
        }
    }
    return AbsSettingsTable.selectAll()
        .where { (AbsSettingsTable.userId eq userId) and (AbsSettingsTable.year eq year) }
        .single().toSettingsDto()
}

/** The user's settings row to inherit stable fields from: the closest year ≤ the target, else the
 *  closest later year. Null if the user has none yet. */
private fun nearestSettings(userId: String, year: Int): ResultRow? {
    val rows = AbsSettingsTable.selectAll().where { AbsSettingsTable.userId eq userId }.toList()
    return rows.filter { it[AbsSettingsTable.year] <= year }.maxByOrNull { it[AbsSettingsTable.year] }
        ?: rows.minByOrNull { it[AbsSettingsTable.year] }
}

private fun upsertAbsence(userId: String, date: LocalDate, type: String, half: String?): AbsenceDto {
    AbsencesTable.deleteWhere { (AbsencesTable.userId eq userId) and (AbsencesTable.date eq date) }
    val id = UUID.randomUUID()
    AbsencesTable.insert {
        it[AbsencesTable.id] = id
        it[AbsencesTable.userId] = userId
        it[AbsencesTable.date] = date
        it[AbsencesTable.type] = type
        it[AbsencesTable.half] = half
    }
    return AbsencesTable.selectAll().where { AbsencesTable.id eq id }.single().toAbsenceDto()
}

private fun insertKita(date: LocalDate, label: String?): KitaClosureDto {
    val id = UUID.randomUUID()
    KitaClosuresTable.insert {
        it[KitaClosuresTable.id] = id
        it[KitaClosuresTable.date] = date
        it[KitaClosuresTable.label] = label?.takeIf { l -> l.isNotBlank() } ?: "Kita geschlossen"
    }
    return KitaClosuresTable.selectAll().where { KitaClosuresTable.id eq id }.single().toKitaDto()
}

private fun insertCustomHoliday(month: Int, day: Int, half: Boolean, label: String?): CustomHolidayDto {
    val id = UUID.randomUUID()
    CustomHolidaysTable.insert {
        it[CustomHolidaysTable.id] = id
        it[CustomHolidaysTable.month] = month
        it[CustomHolidaysTable.day] = day
        it[CustomHolidaysTable.half] = half
        it[CustomHolidaysTable.label] = label?.takeIf { l -> l.isNotBlank() } ?: "Feiertag"
    }
    return CustomHolidaysTable.selectAll().where { CustomHolidaysTable.id eq id }.single().toCustomHolidayDto()
}

private fun ResultRow.toAbsenceDto() = AbsenceDto(
    id = this[AbsencesTable.id].toString(),
    userId = this[AbsencesTable.userId],
    date = this[AbsencesTable.date].toString(),
    type = this[AbsencesTable.type],
    half = this[AbsencesTable.half],
)

private fun ResultRow.toPartTimeDto() = PartTimeRuleDto(
    id = this[PartTimeRulesTable.id].toString(),
    userId = this[PartTimeRulesTable.userId],
    weekday = this[PartTimeRulesTable.weekday],
    start = this[PartTimeRulesTable.startDate].toString(),
    end = this[PartTimeRulesTable.endDate]?.toString(),
)

private fun ResultRow.toKitaDto() = KitaClosureDto(
    id = this[KitaClosuresTable.id].toString(),
    date = this[KitaClosuresTable.date].toString(),
    label = this[KitaClosuresTable.label],
)

private fun ResultRow.toCustomHolidayDto() = CustomHolidayDto(
    id = this[CustomHolidaysTable.id].toString(),
    month = this[CustomHolidaysTable.month],
    day = this[CustomHolidaysTable.day],
    half = this[CustomHolidaysTable.half],
    label = this[CustomHolidaysTable.label],
)

private fun ResultRow.toSettingsDto() = AbsSettingsDto(
    userId = this[AbsSettingsTable.userId],
    year = this[AbsSettingsTable.year],
    state = this[AbsSettingsTable.state],
    allowance = this[AbsSettingsTable.allowance],
    carryover = this[AbsSettingsTable.carryover],
    carryoverExpires = this[AbsSettingsTable.carryoverExpires]?.toString(),
    kindKrankCap = this[AbsSettingsTable.kindKrankCap],
)
