package com.homebase.routes

import com.homebase.model.*
import com.homebase.service.AbsenceService
import com.homebase.service.isValidMonthDay
import com.homebase.ws.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.LocalDate
import java.time.temporal.ChronoUnit

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

// Sanity bound for the per-year settings key — wide enough for any real calendar use, tight enough
// to reject a stray/garbage year from creating an absurd row.
private val SETTINGS_YEAR_RANGE = 2000..2200

/**
 * HTTP surface for the absence / household-calendar domain. Handlers validate (date/type/half/
 * weekday/state/year) and call [AbsenceService] for all persistence, then broadcast. No handler
 * touches an `Absences*Table.`/`dbQuery {}` (issue #566, following the TodoService pattern of #546).
 * The household calendar is shared (either user edits either person's data). Broadcasts use the
 * generic SyncEnvelope via broadcastSync (#552).
 */
fun Route.absenceRoutes() {
    val service = AbsenceService()

    suspend fun notify() =
        WsSessionManager.broadcastSync(ABSENCE_WS_CHANNEL, "ABSENCE_CHANGED")

    route("/absence") {
        // Full snapshot — clients refetch this on every change.
        get {
            call.respond(service.snapshot())
        }

        absenceEntryRoutes(service, ::notify)
        partTimeRoutes(service, ::notify)
        kitaRoutes(service, ::notify)
        holidayRoutes(service, ::notify)
        settingsRoutes(service, ::notify)
    }

    syncChannel(ABSENCE_WS_CHANNEL)
}

private fun Route.absenceEntryRoutes(service: AbsenceService, notify: suspend () -> Unit) {
    route("/entries") {
        // Set (upsert) a single person's absence on a single day.
        post {
            val req = call.receive<SetAbsenceRequest>()
            val date = parseDate(req.date) ?: return@post call.invalidDate()
            if (req.type !in ABSENCE_TYPES) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_TYPE", "type must be one of $ABSENCE_TYPES"))
            }
            if (req.half != null && req.half !in HALF_VALUES) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_HALF", "half must be null, 'vm' or 'nm'"))
            }

            val dto = service.setAbsence(req.userId, date, req.type, req.half) ?: return@post call.userNotFound()
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

            if (!service.batchAbsence(req.userId, dates, req.type, req.half)) return@post call.userNotFound()
            notify()
            call.respond(HttpStatusCode.NoContent)
        }

        // Clear a single person's absence on a single day.
        delete {
            val userId = call.request.queryParameters["userId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("MISSING_PARAM", "userId is required"))
            val date = call.request.queryParameters["date"]?.let { parseDate(it) } ?: return@delete call.invalidDate()

            service.clearAbsence(userId, date)
            notify()
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun Route.partTimeRoutes(service: AbsenceService, notify: suspend () -> Unit) {
    route("/parttime") {
        post {
            val req = call.receive<CreatePartTimeRequest>()
            val start = parseDate(req.start) ?: return@post call.invalidDate()
            val end = req.end?.let { parseDate(it) ?: return@post call.invalidDate() }
            if (req.weekday !in 1..7) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_WEEKDAY", "weekday must be 1..7 (ISO)"))
            }

            val dto = service.createPartTime(req.userId, req.weekday, start, end) ?: return@post call.userNotFound()
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

            val dto = service.updatePartTime(id, req.weekday, start, end)
                ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Rule not found"))
            notify()
            call.respond(dto)
        }

        delete("/{id}") {
            val id = call.uuidParam() ?: return@delete
            if (!service.deletePartTime(id)) return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Rule not found"))
            notify()
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun Route.kitaRoutes(service: AbsenceService, notify: suspend () -> Unit) {
    route("/kita") {
        post {
            val req = call.receive<CreateKitaRequest>()
            val date = parseDate(req.date) ?: return@post call.invalidDate()
            val result = service.upsertKita(date, req.label)
            if (result.created) notify()
            call.respond(if (result.created) HttpStatusCode.Created else HttpStatusCode.OK, result.dto)
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

            service.kitaRange(from, to, req.label)
            notify()
            call.respond(HttpStatusCode.NoContent)
        }

        put("/{id}") {
            val id = call.uuidParam() ?: return@put
            val req = call.receive<UpdateKitaRequest>()
            val date = req.date?.let { parseDate(it) ?: return@put call.invalidDate() }

            when (val r = service.updateKita(id, date, req.label)) {
                AbsenceService.KitaUpdateResult.Conflict ->
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("DATE_CONFLICT", "Another closure already exists on that date"))
                AbsenceService.KitaUpdateResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Kita closure not found"))
                is AbsenceService.KitaUpdateResult.Ok -> {
                    notify()
                    call.respond(r.dto)
                }
            }
        }

        delete("/{id}") {
            val id = call.uuidParam() ?: return@delete
            if (!service.deleteKita(id)) return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Kita closure not found"))
            notify()
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

// Household-wide custom holidays (#51) — recurring every year on a fixed month+day, whole or half.
private fun Route.holidayRoutes(service: AbsenceService, notify: suspend () -> Unit) {
    route("/holidays") {
        post {
            val req = call.receive<CreateCustomHolidayRequest>()
            if (!isValidMonthDay(req.month, req.day)) return@post call.invalidMonthDay()
            val result = service.upsertHoliday(req.month, req.day, req.half, req.label)
            if (result.created) notify()
            call.respond(if (result.created) HttpStatusCode.Created else HttpStatusCode.OK, result.dto)
        }

        put("/{id}") {
            val id = call.uuidParam() ?: return@put
            val req = call.receive<UpdateCustomHolidayRequest>()
            when (val r = service.updateHoliday(id, req)) {
                AbsenceService.HolidayUpdateResult.Conflict ->
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("DATE_CONFLICT", "Another holiday already exists on that date"))
                AbsenceService.HolidayUpdateResult.InvalidDate -> call.invalidMonthDay()
                AbsenceService.HolidayUpdateResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Custom holiday not found"))
                is AbsenceService.HolidayUpdateResult.Ok -> {
                    notify()
                    call.respond(r.dto)
                }
            }
        }

        delete("/{id}") {
            val id = call.uuidParam() ?: return@delete
            if (!service.deleteHoliday(id)) return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Custom holiday not found"))
            notify()
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun Route.settingsRoutes(service: AbsenceService, notify: suspend () -> Unit) {
    // Upsert per-person, per-year settings; the household calendar is intentionally shared, so either
    // user may edit either person's settings. Per-year endpoint (the year-less alias was removed, #16).
    put("/settings/{userId}/{year}") {
        val userId = call.parameters["userId"]!!
        val year = call.parameters["year"]?.toIntOrNull()?.takeIf { it in SETTINGS_YEAR_RANGE }
            ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_YEAR", "year must be an integer in ${SETTINGS_YEAR_RANGE.first}..${SETTINGS_YEAR_RANGE.last}"))

        val req = call.receive<UpdateAbsSettingsRequest>()
        if (req.state != null && req.state !in STATE_CODES) {
            return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_STATE", "state must be a German Bundesland code"))
        }
        val expires = if (req.carryoverExpires != null) {
            parseDate(req.carryoverExpires) ?: return@put call.invalidDate()
        } else null

        val dto = service.upsertSettings(userId, year, req, expires) ?: return@put call.userNotFound()
        notify()
        call.respond(dto)
    }
}

// ---------- shared HTTP helpers (pure / response only) ----------

private fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()

private suspend fun ApplicationCall.invalidDate() =
    respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_DATE", "dates must be in YYYY-MM-DD format"))

private suspend fun ApplicationCall.invalidMonthDay() =
    respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_DATE", "month must be 1..12 and day a valid day of that month"))

private suspend fun ApplicationCall.userNotFound() =
    respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "User not found"))
