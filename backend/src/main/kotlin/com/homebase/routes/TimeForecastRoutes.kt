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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import java.util.UUID

private const val TIME_WS_CHANNEL = "time"
// Sanity bound for a weekly target; a week only has 168 hours.
private const val MAX_WEEKLY_HOURS = 168.0

/**
 * Wochensoll targets + work forecast (#31), nested under /time.
 *
 * Like the absence planner (#127), targets are household-shared: either user may
 * configure either person's weekly hours and default project — the userId in the
 * path is the *target* person, not the caller.
 */
fun Route.workTargetRoutes(json: Json) {
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

            val target: Any? = transaction {
                if (!userExists(userId)) return@transaction null
                ProjectsTable.selectAll().where { ProjectsTable.id eq projectId }.singleOrNull()
                    ?: return@transaction ErrorResponse("NOT_FOUND", "Project not found")

                // A person has exactly one default project (credits land there) —
                // making this one the default clears any other (V20 partial index backstop).
                if (req.isDefault == true) {
                    TimeWorkTargetsTable.update({ (TimeWorkTargetsTable.userId eq userId) and TimeWorkTargetsTable.isDefault }) {
                        it[isDefault] = false
                    }
                }
                val existing = TimeWorkTargetsTable.selectAll()
                    .where { (TimeWorkTargetsTable.userId eq userId) and (TimeWorkTargetsTable.projectId eq projectId) }
                    .singleOrNull()
                if (existing == null) {
                    TimeWorkTargetsTable.insert {
                        it[id] = UUID.randomUUID()
                        it[TimeWorkTargetsTable.userId] = userId
                        it[TimeWorkTargetsTable.projectId] = projectId
                        it[weeklyHours] = hours ?: 0.0
                        it[isDefault] = req.isDefault ?: false
                    }
                } else {
                    TimeWorkTargetsTable.update({ (TimeWorkTargetsTable.userId eq userId) and (TimeWorkTargetsTable.projectId eq projectId) }) {
                        hours?.let { v -> it[weeklyHours] = v }
                        req.isDefault?.let { v -> it[isDefault] = v }
                    }
                }
                TimeWorkTargetsTable.selectAll()
                    .where { (TimeWorkTargetsTable.userId eq userId) and (TimeWorkTargetsTable.projectId eq projectId) }
                    .single().toTargetDto()
            }

            when (target) {
                null -> call.respond(HttpStatusCode.NotFound, ErrorResponse("USER_NOT_FOUND", "User not found"))
                is ErrorResponse -> call.respond(HttpStatusCode.NotFound, target)
                is WorkTargetDto -> {
                    WsSessionManager.broadcast(TIME_WS_CHANNEL, json.encodeToString(TimeWsMessage("TARGET_UPDATED", target = target)))
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
