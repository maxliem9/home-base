package com.homebase.routes

import com.homebase.db.ProjectsTable
import com.homebase.db.TimeWorkTargetsTable
import com.homebase.model.BASE_TARGET_PERIOD
import com.homebase.model.CreateTargetPeriodRequest
import com.homebase.model.ErrorResponse
import com.homebase.model.TimeWsMessage
import com.homebase.model.UpsertWorkTargetRequest
import com.homebase.model.TimeCreditDto
import com.homebase.model.WorkTargetDto
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
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import java.util.UUID

/** Postgres SQLState for unique_violation. */
private const val PG_UNIQUE_VIOLATION = "23505"

/** Partial unique index that enforces one default per person (V20). */
private const val DEFAULT_INDEX_NAME = "time_work_targets_one_default"

/**
 * Returns true if [this] is a Postgres unique-constraint violation on the
 * default-project index. Other SQL errors (wrong types, FK failures, …) are
 * NOT caught here and must bubble up as 500.
 */
private fun ExposedSQLException.isDefaultIndexConflict(): Boolean {
    val cause = cause as? java.sql.SQLException ?: return false
    return cause.sqlState == PG_UNIQUE_VIOLATION &&
        cause.message.orEmpty().contains(DEFAULT_INDEX_NAME, ignoreCase = true)
}

private const val TIME_WS_CHANNEL = "time"
// Sanity bound for a weekly target; a week only has 168 hours.
private const val MAX_WEEKLY_HOURS = 168.0

/**
 * Wochensoll targets + work forecast (#31), nested under /time.
 *
 * Like the absence planner, targets are household-shared: either user may
 * configure either person's weekly hours and default project — the userId in the
 * path is the *target* person, not the caller.
 */
fun Route.workTargetRoutes() {
    route("/targets") {
        get {
            val targets = transaction {
                TimeWorkTargetsTable.selectAll()
                    .orderBy(
                        TimeWorkTargetsTable.userId to SortOrder.ASC,
                        TimeWorkTargetsTable.validFrom to SortOrder.ASC,
                    )
                    .map { it.toTargetDto() }
            }
            call.respond(targets)
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

            val target: Any? = try {
                transaction {
                    if (!userExists(userId)) return@transaction null
                    ProjectsTable.selectAll().where { ProjectsTable.id eq projectId }.singleOrNull()
                        ?: return@transaction ErrorResponse("NOT_FOUND", "Project not found")

                    // Everything below is scoped to the one period: a person's default
                    // project, hours sum and the invariant are all per-period.
                    val rows = TimeWorkTargetsTable.selectAll()
                        .where { (TimeWorkTargetsTable.userId eq userId) and (TimeWorkTargetsTable.validFrom eq validFrom) }
                        .toList()
                    val existing = rows.firstOrNull { it[TimeWorkTargetsTable.projectId] == projectId }
                    val defaultProjectId = rows.firstOrNull { it[TimeWorkTargetsTable.isDefault] }?.get(TimeWorkTargetsTable.projectId)
                    val newHours = hours ?: existing?.get(TimeWorkTargetsTable.weeklyHours) ?: 0.0
                    val sumAfter = rows.filter { it[TimeWorkTargetsTable.projectId] != projectId }
                        .sumOf { it[TimeWorkTargetsTable.weeklyHours] } + newHours

                    // Invariant (#59): configured hours ⇒ exactly one default project, so the
                    // absence/holiday credits always have a target. Removing the last default
                    // while hours remain is rejected; switching it (isDefault=true elsewhere)
                    // stays the way to change it.
                    if (req.isDefault == false && defaultProjectId == projectId && sumAfter > 0) {
                        return@transaction ErrorResponse(
                            "DEFAULT_REQUIRED",
                            "a default project is required while weekly hours are configured — set another project as default first",
                        )
                    }
                    // First configured hours for a person without any default → this row
                    // becomes the default automatically (self-heals legacy data too).
                    val autoDefault = req.isDefault == null && defaultProjectId == null && sumAfter > 0

                    // A person has exactly one default project per period (credits land
                    // there) — making this one the default clears any other in the same
                    // period (V44 partial index backstop).
                    if (req.isDefault == true) {
                        TimeWorkTargetsTable.update({ (TimeWorkTargetsTable.userId eq userId) and (TimeWorkTargetsTable.validFrom eq validFrom) and TimeWorkTargetsTable.isDefault }) {
                            it[isDefault] = false
                        }
                    }
                    if (existing == null) {
                        TimeWorkTargetsTable.insert {
                            it[id] = UUID.randomUUID()
                            it[TimeWorkTargetsTable.userId] = userId
                            it[TimeWorkTargetsTable.projectId] = projectId
                            it[weeklyHours] = hours ?: 0.0
                            it[isDefault] = req.isDefault ?: autoDefault
                            it[TimeWorkTargetsTable.validFrom] = validFrom
                        }
                    } else {
                        TimeWorkTargetsTable.update({ (TimeWorkTargetsTable.userId eq userId) and (TimeWorkTargetsTable.projectId eq projectId) and (TimeWorkTargetsTable.validFrom eq validFrom) }) {
                            hours?.let { v -> it[weeklyHours] = v }
                            req.isDefault?.let { v -> it[isDefault] = v }
                            if (autoDefault) it[isDefault] = true
                        }
                    }
                    TimeWorkTargetsTable.selectAll()
                        .where { (TimeWorkTargetsTable.userId eq userId) and (TimeWorkTargetsTable.projectId eq projectId) and (TimeWorkTargetsTable.validFrom eq validFrom) }
                        .single().toTargetDto()
                }
            } catch (e: ExposedSQLException) {
                // Two concurrent requests both set isDefault=true for the same person and
                // period on different projects: the clear-then-set in transaction A is
                // invisible to transaction B, so both commit a default row → the partial
                // unique index `time_work_targets_one_default` (V44) rejects the second
                // commit. Return 409 so the client knows to retry; any other SQL error
                // (FK violation, type mismatch, …) is re-thrown and becomes a 500. (#57)
                if (e.isDefaultIndexConflict()) {
                    ErrorResponse("DEFAULT_CONFLICT", "gleichzeitiger Default-Wechsel — bitte wiederholen")
                } else {
                    throw e
                }
            }

            when (target) {
                null -> call.respond(HttpStatusCode.NotFound, ErrorResponse("USER_NOT_FOUND", "User not found"))
                is ErrorResponse -> call.respond(
                    when (target.code) {
                        "DEFAULT_REQUIRED" -> HttpStatusCode.Conflict
                        "DEFAULT_CONFLICT" -> HttpStatusCode.Conflict
                        else -> HttpStatusCode.NotFound
                    },
                    target,
                )
                is WorkTargetDto -> {
                    WsSessionManager.broadcast(TIME_WS_CHANNEL, appJson.encodeToString(TimeWsMessage("TARGET_UPDATED", target = target)))
                    call.respond(target)
                }
            }
        }

        // Create a new Wochensoll period for a person, seeded from the currently-effective
        // one (the latest period on/before validFrom) so the caller starts from the values
        // in force then — including the default project. The clients edit the seeded cells
        // afterwards via PUT. Household-shared like the rest of the targets API.
        post("/{userId}/periods") {
            val userId = call.parameters["userId"]!!
            val req = call.receive<CreateTargetPeriodRequest>()
            val validFrom = parsePeriod(req.validFrom)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_DATE", "validFrom must be YYYY-MM-DD"))
            if (validFrom.toString() == BASE_TARGET_PERIOD) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_DATE", "the base period already exists"))
            }

            val result: Any? = transaction {
                if (!userExists(userId)) return@transaction null
                val rows = TimeWorkTargetsTable.selectAll().where { TimeWorkTargetsTable.userId eq userId }.toList()
                if (rows.any { it[TimeWorkTargetsTable.validFrom] == validFrom }) {
                    return@transaction ErrorResponse("PERIOD_EXISTS", "a period with this start date already exists")
                }
                // Seed from the latest period on/before the new start date.
                val sourceDate = rows.map { it[TimeWorkTargetsTable.validFrom] }
                    .filter { !it.isAfter(validFrom) }
                    .maxOrNull()
                val seed = if (sourceDate != null) rows.filter { it[TimeWorkTargetsTable.validFrom] == sourceDate } else emptyList()
                seed.forEach { row ->
                    TimeWorkTargetsTable.insert {
                        it[id] = UUID.randomUUID()
                        it[TimeWorkTargetsTable.userId] = userId
                        it[projectId] = row[TimeWorkTargetsTable.projectId]
                        it[weeklyHours] = row[TimeWorkTargetsTable.weeklyHours]
                        it[isDefault] = row[TimeWorkTargetsTable.isDefault]
                        it[TimeWorkTargetsTable.validFrom] = validFrom
                    }
                }
                TimeWorkTargetsTable.selectAll()
                    .where { (TimeWorkTargetsTable.userId eq userId) and (TimeWorkTargetsTable.validFrom eq validFrom) }
                    .map { it.toTargetDto() }
            }

            when (result) {
                null -> call.respond(HttpStatusCode.NotFound, ErrorResponse("USER_NOT_FOUND", "User not found"))
                is ErrorResponse -> call.respond(HttpStatusCode.Conflict, result)
                else -> {
                    @Suppress("UNCHECKED_CAST") val created = result as List<WorkTargetDto>
                    // target-less TARGET_UPDATED: the whole period changed; clients refetch.
                    WsSessionManager.broadcast(TIME_WS_CHANNEL, appJson.encodeToString(TimeWsMessage("TARGET_UPDATED")))
                    call.respond(HttpStatusCode.Created, created)
                }
            }
        }

        // Delete a whole Wochensoll period for a person. The base period (1970-01-01) is the
        // always-present fallback and cannot be removed; weeks before the earliest remaining
        // period simply have no target (0h).
        delete("/{userId}/periods/{validFrom}") {
            val userId = call.parameters["userId"]!!
            val validFrom = parsePeriod(call.parameters["validFrom"])
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_DATE", "validFrom must be YYYY-MM-DD"))
            if (validFrom.toString() == BASE_TARGET_PERIOD) {
                return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("BASE_PERIOD", "the base period cannot be deleted"))
            }
            val deleted = transaction {
                TimeWorkTargetsTable.deleteWhere {
                    (TimeWorkTargetsTable.userId eq userId) and (TimeWorkTargetsTable.validFrom eq validFrom)
                }
            }
            if (deleted == 0) {
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

private fun ResultRow.toTargetDto() = WorkTargetDto(
    userId = this[TimeWorkTargetsTable.userId],
    projectId = this[TimeWorkTargetsTable.projectId].toString(),
    weeklyHours = this[TimeWorkTargetsTable.weeklyHours],
    isDefault = this[TimeWorkTargetsTable.isDefault],
    validFrom = this[TimeWorkTargetsTable.validFrom].toString(),
)
