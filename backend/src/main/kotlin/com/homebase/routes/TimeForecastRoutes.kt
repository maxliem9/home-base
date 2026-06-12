package com.homebase.routes

import com.homebase.db.ProjectsTable
import com.homebase.db.TimeWorkTargetsTable
import com.homebase.model.ErrorResponse
import com.homebase.model.TimeWsMessage
import com.homebase.model.UpsertWorkTargetRequest
import com.homebase.model.WorkTargetDto
import com.homebase.time.ForecastService
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
                    .orderBy(TimeWorkTargetsTable.userId, SortOrder.ASC)
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

            val target: Any? = try {
                transaction {
                    if (!userExists(userId)) return@transaction null
                    ProjectsTable.selectAll().where { ProjectsTable.id eq projectId }.singleOrNull()
                        ?: return@transaction ErrorResponse("NOT_FOUND", "Project not found")

                    val rows = TimeWorkTargetsTable.selectAll().where { TimeWorkTargetsTable.userId eq userId }.toList()
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

                    // A person has exactly one default project (credits land there) —
                    // making this one the default clears any other (V20 partial index backstop).
                    if (req.isDefault == true) {
                        TimeWorkTargetsTable.update({ (TimeWorkTargetsTable.userId eq userId) and TimeWorkTargetsTable.isDefault }) {
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
                        }
                    } else {
                        TimeWorkTargetsTable.update({ (TimeWorkTargetsTable.userId eq userId) and (TimeWorkTargetsTable.projectId eq projectId) }) {
                            hours?.let { v -> it[weeklyHours] = v }
                            req.isDefault?.let { v -> it[isDefault] = v }
                            if (autoDefault) it[isDefault] = true
                        }
                    }
                    TimeWorkTargetsTable.selectAll()
                        .where { (TimeWorkTargetsTable.userId eq userId) and (TimeWorkTargetsTable.projectId eq projectId) }
                        .single().toTargetDto()
                }
            } catch (e: ExposedSQLException) {
                // Two concurrent requests both set isDefault=true for the same person on
                // different projects: the clear-then-set in transaction A is invisible to
                // transaction B, so both end up committing a default row → the partial
                // unique index `time_work_targets_one_default` (V20) rejects the second
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
    }
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

private fun ResultRow.toTargetDto() = WorkTargetDto(
    userId = this[TimeWorkTargetsTable.userId],
    projectId = this[TimeWorkTargetsTable.projectId].toString(),
    weeklyHours = this[TimeWorkTargetsTable.weeklyHours],
    isDefault = this[TimeWorkTargetsTable.isDefault],
)
