package com.homebase.routes

import com.homebase.db.AbsSettingsTable
import com.homebase.db.AbsencesTable
import com.homebase.db.CustomHolidaysTable
import com.homebase.db.KitaClosuresTable
import com.homebase.db.PartTimeRulesTable
import com.homebase.db.UsersTable
import com.homebase.model.*
import com.homebase.ws.WsSessionManager
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

private const val ABSENCE_WS_CHANNEL = "absence"

// Guard rails against a single request inflating the DB / blocking in one transaction.
private const val MAX_BATCH_DATES = 366       // entries/batch: at most a year of explicit dates
private const val MAX_KITA_RANGE_DAYS = 731   // kita/range: at most ~2 years span (inclusive)

private val ABSENCE_TYPES = setOf("URLAUB", "KRANK", "KIND_KRANK")
private val HALF_VALUES = setOf("vm", "nm")
private val STATE_CODES = setOf(
    "BW", "BY", "BE", "BB", "HB", "HH", "HE", "MV",
    "NI", "NW", "RP", "SL", "SN", "ST", "SH", "TH",
)

// Sanity bound for the per-year settings key — wide enough for any real calendar use,
// tight enough to reject a stray/garbage year from creating an absurd row.
private val SETTINGS_YEAR_RANGE = 2000..2200

fun Route.absenceRoutes() {
    val json = Json { ignoreUnknownKeys = true }

    suspend fun notify() =
        WsSessionManager.broadcast(ABSENCE_WS_CHANNEL, json.encodeToString(AbsenceWsMessage("ABSENCE_CHANGED")))

    route("/absence") {

        // Full snapshot — clients refetch this on every change.
        get {
            val state = transaction {
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
            call.respond(state)
        }

        absenceEntryRoutes(::notify)
        partTimeRoutes(::notify)
        kitaRoutes(::notify)
        holidayRoutes(::notify)
        settingsRoutes(::notify)
    }

    webSocket("/ws/absence") {
        WsSessionManager.add(ABSENCE_WS_CHANNEL, this)
        try {
            for (frame in incoming) {
                if (frame is Frame.Close) break
            }
        } finally {
            WsSessionManager.remove(ABSENCE_WS_CHANNEL, this)
        }
    }
}

private fun Route.absenceEntryRoutes(notify: suspend () -> Unit) {
    route("/entries") {
        // Set (upsert) a single person's absence on a single day.
        post {
            val req = call.receive<SetAbsenceRequest>()
            val date = parseDate(req.date)
                ?: return@post call.invalidDate()
            if (req.type !in ABSENCE_TYPES) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_TYPE", "type must be one of $ABSENCE_TYPES"))
            }
            if (req.half != null && req.half !in HALF_VALUES) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_HALF", "half must be null, 'vm' or 'nm'"))
            }

            val dto = transaction {
                if (!userExists(req.userId)) return@transaction null
                upsertAbsence(req.userId, date, req.type, req.half)
            } ?: return@post call.userNotFound()

            notify()
            call.respond(HttpStatusCode.Created, dto)
        }

        // Bulk apply on a list of dates; type == null clears those dates.
        post("/batch") {
            val req = call.receive<BatchAbsenceRequest>()
            if (req.type != null && req.type !in ABSENCE_TYPES) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_TYPE", "type must be null or one of $ABSENCE_TYPES"))
            }
            if (req.half != null && req.half !in HALF_VALUES) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_HALF", "half must be null, 'vm' or 'nm'"))
            }
            if (req.dates.size > MAX_BATCH_DATES) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("TOO_MANY_DATES", "dates must not exceed $MAX_BATCH_DATES entries"))
            }
            val dates = req.dates.map { parseDate(it) ?: return@post call.invalidDate() }

            val ok = transaction {
                if (!userExists(req.userId)) return@transaction false
                dates.forEach { d ->
                    AbsencesTable.deleteWhere { (AbsencesTable.userId eq req.userId) and (AbsencesTable.date eq d) }
                    if (req.type != null) {
                        AbsencesTable.insert {
                            it[id] = UUID.randomUUID()
                            it[userId] = req.userId
                            it[date] = d
                            it[type] = req.type
                            it[half] = req.half
                        }
                    }
                }
                true
            }
            if (!ok) return@post call.userNotFound()

            notify()
            call.respond(HttpStatusCode.NoContent)
        }

        // Clear a single person's absence on a single day.
        delete {
            val userId = call.request.queryParameters["userId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("MISSING_PARAM", "userId is required"))
            val date = call.request.queryParameters["date"]?.let { parseDate(it) }
                ?: return@delete call.invalidDate()

            transaction {
                AbsencesTable.deleteWhere { (AbsencesTable.userId eq userId) and (AbsencesTable.date eq date) }
            }
            notify()
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun Route.partTimeRoutes(notify: suspend () -> Unit) {
    route("/parttime") {
        post {
            val req = call.receive<CreatePartTimeRequest>()
            val start = parseDate(req.start) ?: return@post call.invalidDate()
            val end = req.end?.let { parseDate(it) ?: return@post call.invalidDate() }
            if (req.weekday !in 1..7) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_WEEKDAY", "weekday must be 1..7 (ISO)"))
            }

            val dto = transaction {
                if (!userExists(req.userId)) return@transaction null
                val id = UUID.randomUUID()
                PartTimeRulesTable.insert {
                    it[PartTimeRulesTable.id] = id
                    it[userId] = req.userId
                    it[weekday] = req.weekday
                    it[startDate] = start
                    it[endDate] = end
                }
                PartTimeRulesTable.selectAll().where { PartTimeRulesTable.id eq id }.single().toPartTimeDto()
            } ?: return@post call.userNotFound()

            notify()
            call.respond(HttpStatusCode.Created, dto)
        }

        put("/{id}") {
            val id = call.uuidParam() ?: return@put
            val req = call.receive<UpdatePartTimeRequest>()
            val start = parseDate(req.start) ?: return@put call.invalidDate()
            val end = req.end?.let { parseDate(it) ?: return@put call.invalidDate() }
            if (req.weekday !in 1..7) {
                return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_WEEKDAY", "weekday must be 1..7 (ISO)"))
            }

            val dto = transaction {
                if (PartTimeRulesTable.selectAll().where { PartTimeRulesTable.id eq id }.empty()) return@transaction null
                PartTimeRulesTable.update({ PartTimeRulesTable.id eq id }) {
                    it[weekday] = req.weekday
                    it[startDate] = start
                    it[endDate] = end
                }
                PartTimeRulesTable.selectAll().where { PartTimeRulesTable.id eq id }.single().toPartTimeDto()
            } ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Rule not found"))

            notify()
            call.respond(dto)
        }

        delete("/{id}") {
            val id = call.uuidParam() ?: return@delete
            val existed = transaction {
                PartTimeRulesTable.deleteWhere { PartTimeRulesTable.id eq id } > 0
            }
            if (!existed) return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Rule not found"))
            notify()
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun Route.kitaRoutes(notify: suspend () -> Unit) {
    route("/kita") {
        post {
            val req = call.receive<CreateKitaRequest>()
            val date = parseDate(req.date) ?: return@post call.invalidDate()
            // Idempotent: one closure per date (enforced by the unique index). If the
            // day is already marked closed, return that closure instead of duplicating it.
            // (Two truly-concurrent posts for the same new date can still race here; the
            // unique index is the backstop — the loser's insert rolls back rather than
            // creating a duplicate.)
            val (dto, created) = transaction {
                val existing = KitaClosuresTable.selectAll()
                    .where { KitaClosuresTable.date eq date }
                    .singleOrNull()
                if (existing != null) existing.toKitaDto() to false
                else insertKita(date, req.label) to true
            }
            if (created) notify()
            call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, dto)
        }

        // Add a closure for each weekday in the range (weekends are skipped).
        post("/range") {
            val req = call.receive<CreateKitaRangeRequest>()
            var from = parseDate(req.from) ?: return@post call.invalidDate()
            var to = parseDate(req.to) ?: return@post call.invalidDate()
            if (from.isAfter(to)) { val t = from; from = to; to = t }
            if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_KITA_RANGE_DAYS) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("RANGE_TOO_LARGE", "range must not exceed $MAX_KITA_RANGE_DAYS days"))
            }

            transaction {
                // Skip dates that already have a closure so a re-run stays idempotent
                // and doesn't trip the unique(date) index (the DB is the hard backstop).
                val existing = KitaClosuresTable
                    .selectAll()
                    .where { (KitaClosuresTable.date greaterEq from) and (KitaClosuresTable.date lessEq to) }
                    .map { it[KitaClosuresTable.date] }
                    .toSet()
                var d = from
                while (!d.isAfter(to)) {
                    if (d.dayOfWeek.value <= 5 && d !in existing) insertKita(d, req.label)
                    d = d.plusDays(1)
                }
            }
            notify()
            call.respond(HttpStatusCode.NoContent)
        }

        put("/{id}") {
            val id = call.uuidParam() ?: return@put
            val req = call.receive<UpdateKitaRequest>()
            val date = req.date?.let { parseDate(it) ?: return@put call.invalidDate() }

            // dto==null → not found; conflict==true → target date already taken by another closure.
            val (dto, conflict) = transaction {
                if (KitaClosuresTable.selectAll().where { KitaClosuresTable.id eq id }.empty()) return@transaction null to false
                // Moving onto a date another closure occupies would violate unique(date) → clean 409.
                if (date != null && !KitaClosuresTable.selectAll()
                        .where { (KitaClosuresTable.date eq date) and (KitaClosuresTable.id neq id) }
                        .empty()
                ) return@transaction null to true
                KitaClosuresTable.update({ KitaClosuresTable.id eq id }) {
                    date?.let { v -> it[KitaClosuresTable.date] = v }
                    req.label?.let { v -> it[label] = v }
                }
                KitaClosuresTable.selectAll().where { KitaClosuresTable.id eq id }.single().toKitaDto() to false
            }
            if (conflict) return@put call.respond(HttpStatusCode.Conflict, ErrorResponse("DATE_CONFLICT", "Another closure already exists on that date"))
            if (dto == null) return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Kita closure not found"))

            notify()
            call.respond(dto)
        }

        delete("/{id}") {
            val id = call.uuidParam() ?: return@delete
            val existed = transaction {
                KitaClosuresTable.deleteWhere { KitaClosuresTable.id eq id } > 0
            }
            if (!existed) return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Kita closure not found"))
            notify()
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

// Household-wide custom holidays (#51) — recurring every year on a fixed month+day,
// whole or half. Mirrors the kita routes: idempotent POST keyed on (month, day), PUT with
// a clean 409 on a date clash, DELETE. The (month, day) unique index is the hard backstop.
private fun Route.holidayRoutes(notify: suspend () -> Unit) {
    route("/holidays") {
        post {
            val req = call.receive<CreateCustomHolidayRequest>()
            if (!isValidMonthDay(req.month, req.day)) return@post call.invalidMonthDay()
            // Idempotent: one holiday per (month, day). If the date is already taken, return
            // that holiday instead of duplicating it (the unique index is the race backstop).
            val (dto, created) = transaction {
                val existing = CustomHolidaysTable.selectAll()
                    .where { (CustomHolidaysTable.month eq req.month) and (CustomHolidaysTable.day eq req.day) }
                    .singleOrNull()
                if (existing != null) existing.toCustomHolidayDto() to false
                else insertCustomHoliday(req.month, req.day, req.half, req.label) to true
            }
            if (created) notify()
            call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, dto)
        }

        put("/{id}") {
            val id = call.uuidParam() ?: return@put
            val req = call.receive<UpdateCustomHolidayRequest>()
            // Resolve the would-be (month, day) so we can validate + clash-check before writing.
            val (dto, conflict) = transaction {
                val current = CustomHolidaysTable.selectAll()
                    .where { CustomHolidaysTable.id eq id }
                    .singleOrNull() ?: return@transaction null to false
                val newMonth = req.month ?: current[CustomHolidaysTable.month]
                val newDay = req.day ?: current[CustomHolidaysTable.day]
                if (!isValidMonthDay(newMonth, newDay)) return@transaction null to false // signalled as 400 below
                // Moving onto a date another holiday occupies would violate unique(month, day) → 409.
                if ((newMonth != current[CustomHolidaysTable.month] || newDay != current[CustomHolidaysTable.day]) &&
                    !CustomHolidaysTable.selectAll()
                        .where { (CustomHolidaysTable.month eq newMonth) and (CustomHolidaysTable.day eq newDay) and (CustomHolidaysTable.id neq id) }
                        .empty()
                ) return@transaction null to true
                CustomHolidaysTable.update({ CustomHolidaysTable.id eq id }) {
                    req.month?.let { v -> it[month] = v }
                    req.day?.let { v -> it[day] = v }
                    req.half?.let { v -> it[half] = v }
                    req.label?.let { v -> it[label] = v }
                }
                CustomHolidaysTable.selectAll().where { CustomHolidaysTable.id eq id }.single().toCustomHolidayDto() to false
            }
            // Distinguish the two null cases: a real clash → 409; otherwise either not-found or
            // an invalid resulting date. Re-check existence to pick the right 4xx.
            if (conflict) return@put call.respond(HttpStatusCode.Conflict, ErrorResponse("DATE_CONFLICT", "Another holiday already exists on that date"))
            if (dto == null) {
                val exists = transaction { !CustomHolidaysTable.selectAll().where { CustomHolidaysTable.id eq id }.empty() }
                return@put if (exists) call.invalidMonthDay()
                else call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Custom holiday not found"))
            }
            notify()
            call.respond(dto)
        }

        delete("/{id}") {
            val id = call.uuidParam() ?: return@delete
            val existed = transaction {
                CustomHolidaysTable.deleteWhere { CustomHolidaysTable.id eq id } > 0
            }
            if (!existed) return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Custom holiday not found"))
            notify()
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun Route.settingsRoutes(notify: suspend () -> Unit) {
    // Upsert per-person, per-year settings; the row is created on first edit, inheriting
    // the stable fields (Bundesland, allowance, kind-krank cap) from the nearest year so a
    // fresh year doesn't reset to hard defaults (#144).
    //
    // The household calendar is intentionally shared: like the calendar days/rules, either
    // user may edit either person's settings. This deliberately reverses the owner-only
    // restriction from #63 for the two-person trusted household (see #127).
    suspend fun handleUpsert(call: ApplicationCall, userId: String, year: Int) {
        val req = call.receive<UpdateAbsSettingsRequest>()
        if (req.state != null && req.state !in STATE_CODES) {
            return call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_STATE", "state must be a German Bundesland code"))
        }
        val expires = if (req.carryoverExpires != null) {
            parseDate(req.carryoverExpires) ?: return call.invalidDate()
        } else null

        val dto = transaction { upsertAbsSettings(userId, year, req, expires) }
            ?: return call.userNotFound()

        notify()
        call.respond(dto)
    }

    // Per-year endpoint. Both clients (Web + Android since #13/#15) always send a year;
    // the year-less alias that mapped to the current calendar year was removed in #16.
    put("/settings/{userId}/{year}") {
        val userId = call.parameters["userId"]!!
        val year = call.parameters["year"]?.toIntOrNull()?.takeIf { it in SETTINGS_YEAR_RANGE }
            ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_YEAR", "year must be an integer in ${SETTINGS_YEAR_RANGE.first}..${SETTINGS_YEAR_RANGE.last}"))
        handleUpsert(call, userId, year)
    }
}

// ---------- shared helpers ----------

/**
 * Upsert one user's settings for a single year. On insert the stable fields (Bundesland,
 * allowance, kind-krank cap) are inherited from the nearest existing year so a fresh year
 * keeps the person's setup; the carryover ("Resturlaub") is deliberately NOT inherited —
 * leftover leave belongs to its own year (#144). Returns null if the user is unknown.
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

/** The user's settings row to inherit stable fields from: the closest year ≤ the target
 *  (carry the setup forward), else the closest later year. Null if the user has none yet. */
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

// Valid recurring calendar date: month 1..12 and day within that month's length. A leap
// year (2000) is used so Feb 29 is accepted — the holiday recurs and is valid in leap years.
private fun isValidMonthDay(month: Int, day: Int): Boolean {
    if (month !in 1..12) return false
    val maxDay = runCatching { java.time.YearMonth.of(2000, month).lengthOfMonth() }.getOrElse { return false }
    return day in 1..maxDay
}

private fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()

private suspend fun ApplicationCall.invalidDate() =
    respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_DATE", "dates must be in YYYY-MM-DD format"))

private suspend fun ApplicationCall.invalidMonthDay() =
    respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_DATE", "month must be 1..12 and day a valid day of that month"))

private suspend fun ApplicationCall.userNotFound() =
    respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "User not found"))

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
