package com.homebase.routes

import com.homebase.model.BASE_TARGET_PERIOD
import com.homebase.model.CreateTargetPeriodRequest
import com.homebase.model.ErrorResponse
import com.homebase.model.TimeWsMessage
import com.homebase.model.UpsertWorkTargetRequest
import com.homebase.model.TimeCreditDto
import com.homebase.service.TimeService
import com.homebase.time.ForecastService
import com.homebase.time.TimeCreditService
import com.homebase.ws.WsSessionManager
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.homebase.plugins.appJson
import kotlinx.serialization.encodeToString
import java.time.LocalDate
import java.util.UUID

private const val TIME_WS_CHANNEL = "time"
// Sanity bound for a weekly target; a week only has 168 hours.
private const val MAX_WEEKLY_HOURS = 168.0

/**
 * Wochensoll targets + work forecast (#31), nested under /time. The persistence + the
 * one-default-per-period invariant (#59/#57) live in [TimeService] (issue #564); handlers parse,
 * call the service and broadcast. Targets are household-shared: the userId in the path is the
 * *target* person, not the caller.
 */
fun Route.workTargetRoutes() {
    val service = TimeService()

    route("/targets") {
        get {
            call.respond(service.listTargets())
        }

        put("/{userId}/{projectId}") {
            val userId = call.parameters["userId"]!!
            val projectId = runCatching { UUID.fromString(call.parameters["projectId"]) }.getOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "projectId must be a valid UUID"))
            val req = call.receive<UpsertWorkTargetRequest>()
            val hours = req.weeklyHours
            if (hours != null && (!hours.isFinite() || hours < 0 || hours > MAX_WEEKLY_HOURS)) {
                return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_HOURS", "weeklyHours must be between 0 and $MAX_WEEKLY_HOURS"))
            }
            val validFrom = parsePeriod(req.validFrom)
                ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_DATE", "validFrom must be YYYY-MM-DD"))

            when (val r = service.upsertTarget(userId, projectId, req, validFrom)) {
                TimeService.TargetResult.UserNotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("USER_NOT_FOUND", "User not found"))
                is TimeService.TargetResult.Fault -> call.respond(
                    when (r.error.code) {
                        "DEFAULT_REQUIRED", "DEFAULT_CONFLICT" -> HttpStatusCode.Conflict
                        else -> HttpStatusCode.NotFound
                    },
                    r.error,
                )
                is TimeService.TargetResult.Ok -> {
                    WsSessionManager.broadcast(TIME_WS_CHANNEL, appJson.encodeToString(TimeWsMessage("TARGET_UPDATED", target = r.target)))
                    call.respond(r.target)
                }
            }
        }

        // Create a new Wochensoll period for a person, seeded from the currently-effective one.
        post("/{userId}/periods") {
            val userId = call.parameters["userId"]!!
            val req = call.receive<CreateTargetPeriodRequest>()
            val validFrom = parsePeriod(req.validFrom)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_DATE", "validFrom must be YYYY-MM-DD"))
            if (validFrom.toString() == BASE_TARGET_PERIOD) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_DATE", "the base period already exists"))
            }

            when (val r = service.createPeriod(userId, validFrom)) {
                TimeService.PeriodResult.UserNotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("USER_NOT_FOUND", "User not found"))
                is TimeService.PeriodResult.Conflict -> call.respond(HttpStatusCode.Conflict, r.error)
                is TimeService.PeriodResult.Ok -> {
                    // target-less TARGET_UPDATED: the whole period changed; clients refetch.
                    WsSessionManager.broadcast(TIME_WS_CHANNEL, appJson.encodeToString(TimeWsMessage("TARGET_UPDATED")))
                    call.respond(HttpStatusCode.Created, r.targets)
                }
            }
        }

        // Delete a whole Wochensoll period for a person. The base period (1970-01-01) is the
        // always-present fallback and cannot be removed.
        delete("/{userId}/periods/{validFrom}") {
            val userId = call.parameters["userId"]!!
            val validFrom = parsePeriod(call.parameters["validFrom"])
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_DATE", "validFrom must be YYYY-MM-DD"))
            if (validFrom.toString() == BASE_TARGET_PERIOD) {
                return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("BASE_PERIOD", "the base period cannot be deleted"))
            }
            if (service.deletePeriod(userId, validFrom) == 0) {
                return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("PERIOD_NOT_FOUND", "no such period"))
            }
            WsSessionManager.broadcast(TIME_WS_CHANNEL, appJson.encodeToString(TimeWsMessage("TARGET_UPDATED")))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/** Parse a `YYYY-MM-DD` period start; blank/null → the base period. Null on malformed input. */
private fun parsePeriod(raw: String?): LocalDate? {
    if (raw.isNullOrBlank()) return LocalDate.parse(BASE_TARGET_PERIOD)
    return runCatching { LocalDate.parse(raw) }.getOrNull()
}

fun Route.forecastRoute() {
    val service = ForecastService()

    // Optional ?date=YYYY-MM-DD pins the forecast day (defaults to today in the
    // server zone) — the clients only ever ask for today; a fixed date keeps the
    // computation deterministic for tests.
    get("/forecast") {
        val date = call.request.queryParameters["date"]?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_DATE", "date must be YYYY-MM-DD"))
        }
        call.respond(service.forecast(date))
    }
}

/**
 * Absence/holiday work credits over an inclusive [from]..[to] date range (#31),
 * so the historical views can add sick/vacation/holiday hours to past weeks the same
 * way the live Wochenbilanz credits the current week. Both bounds are required
 * (YYYY-MM-DD) — the client passes the span of its loaded entries.
 */
fun Route.creditsRoute() {
    val service = TimeCreditService()

    get("/credits") {
        val from = call.request.queryParameters["from"]?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_DATE", "from must be YYYY-MM-DD"))
        } ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("MISSING_FROM", "from is required (YYYY-MM-DD)"))
        val to = call.request.queryParameters["to"]?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_DATE", "to must be YYYY-MM-DD"))
        } ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("MISSING_TO", "to is required (YYYY-MM-DD)"))

        val credits = service.credits(from, to).map {
            TimeCreditDto(
                userId = it.user,
                date = it.date.toString(),
                projectId = it.projectId.toString(),
                seconds = it.seconds,
                type = it.type,
            )
        }
        call.respond(credits)
    }
}
