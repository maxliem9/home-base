package com.homebase.routes

import com.homebase.db.AbsSettingsTable
import com.homebase.db.AbsencesTable
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
                    settings = AbsSettingsTable.selectAll()
                        .map { it.toSettingsDto() },
                )
            }
            call.respond(state)
        }

        absenceEntryRoutes(::notify)
        partTimeRoutes(::notify)
        kitaRoutes(::notify)
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

            val dto = transaction {
                if (KitaClosuresTable.selectAll().where { KitaClosuresTable.id eq id }.empty()) return@transaction null
                KitaClosuresTable.update({ KitaClosuresTable.id eq id }) {
                    date?.let { v -> it[KitaClosuresTable.date] = v }
                    req.label?.let { v -> it[label] = v }
                }
                KitaClosuresTable.selectAll().where { KitaClosuresTable.id eq id }.single().toKitaDto()
            } ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Kita closure not found"))

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

private fun Route.settingsRoutes(notify: suspend () -> Unit) {
    // Upsert per-person settings; the row is created with defaults on first edit.
    put("/settings/{userId}") {
        val userId = call.parameters["userId"]!!
        val req = call.receive<UpdateAbsSettingsRequest>()
        if (req.state != null && req.state !in STATE_CODES) {
            return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_STATE", "state must be a German Bundesland code"))
        }
        val expires = if (req.carryoverExpires != null) {
            parseDate(req.carryoverExpires) ?: return@put call.invalidDate()
        } else null

        val dto = transaction {
            if (!userExists(userId)) return@transaction null
            val existing = AbsSettingsTable.selectAll().where { AbsSettingsTable.userId eq userId }.singleOrNull()
            if (existing == null) {
                AbsSettingsTable.insert {
                    it[AbsSettingsTable.userId] = userId
                    it[state] = req.state ?: "BE"
                    it[allowance] = req.allowance ?: 30.0
                    it[carryover] = req.carryover ?: 0.0
                    it[carryoverExpires] = expires
                    it[kindKrankCap] = req.kindKrankCap ?: 15
                }
            } else {
                AbsSettingsTable.update({ AbsSettingsTable.userId eq userId }) {
                    req.state?.let { v -> it[state] = v }
                    req.allowance?.let { v -> it[allowance] = v }
                    req.carryover?.let { v -> it[carryover] = v }
                    if (req.carryoverExpires != null) it[carryoverExpires] = expires
                    req.kindKrankCap?.let { v -> it[kindKrankCap] = v }
                }
            }
            AbsSettingsTable.selectAll().where { AbsSettingsTable.userId eq userId }.single().toSettingsDto()
        } ?: return@put call.userNotFound()

        notify()
        call.respond(dto)
    }
}

// ---------- shared helpers ----------

private fun userExists(username: String): Boolean =
    !UsersTable.selectAll().where { UsersTable.username eq username }.empty()

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

private fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()

private suspend fun ApplicationCall.invalidDate() =
    respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_DATE", "dates must be in YYYY-MM-DD format"))

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

private fun ResultRow.toSettingsDto() = AbsSettingsDto(
    userId = this[AbsSettingsTable.userId],
    state = this[AbsSettingsTable.state],
    allowance = this[AbsSettingsTable.allowance],
    carryover = this[AbsSettingsTable.carryover],
    carryoverExpires = this[AbsSettingsTable.carryoverExpires]?.toString(),
    kindKrankCap = this[AbsSettingsTable.kindKrankCap],
)
