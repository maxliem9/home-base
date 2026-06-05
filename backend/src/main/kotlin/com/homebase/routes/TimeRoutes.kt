package com.homebase.routes

import com.homebase.db.ProjectsTable
import com.homebase.db.TimeEntriesTable
import com.homebase.model.*
import com.homebase.ws.WsSessionManager
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

private const val TIME_WS_CHANNEL = "time"
private val HEX_COLOR = Regex("^#[0-9A-Fa-f]{6}$")
private val TIMER_START_LOCKS = ConcurrentHashMap<String, Any>()

fun Route.timeRoutes() {
    val json = Json { ignoreUnknownKeys = true }

    route("/time") {
        projectRoutes(json)
        entryRoutes(json)

        get("/running") {
            val username = call.username()
            val entry = transaction {
                TimeEntriesTable.selectAll()
                    .where { (TimeEntriesTable.userId eq username) and TimeEntriesTable.stoppedAt.isNull() }
                    .singleOrNull()
                    ?.toEntryDto()
            }
            if (entry == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NO_RUNNING_TIMER", "No timer is currently running"))
                return@get
            }
            call.respond(entry)
        }
    }

    webSocket("/ws/time") {
        WsSessionManager.add(TIME_WS_CHANNEL, this)
        try {
            for (frame in incoming) {
                if (frame is Frame.Close) break
            }
        } finally {
            WsSessionManager.remove(TIME_WS_CHANNEL, this)
        }
    }
}

private fun Route.projectRoutes(json: Json) {
    route("/projects") {
        get {
            val projects = transaction {
                ProjectsTable.selectAll()
                    .orderBy(ProjectsTable.archived, SortOrder.ASC)
                    .orderBy(ProjectsTable.name, SortOrder.ASC)
                    .map { it.toProjectDto() }
            }
            call.respond(projects)
        }

        post {
            val username = call.username()
            val req = call.receive<CreateProjectRequest>()
            if (req.name.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_PROJECT", "name must not be blank"))
                return@post
            }
            if (!HEX_COLOR.matches(req.color)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_COLOR", "color must be a hex value like #4F46E5"))
                return@post
            }

            val project = transaction {
                val id = UUID.randomUUID()
                ProjectsTable.insert {
                    it[ProjectsTable.id] = id
                    it[name] = req.name.trim()
                    it[color] = req.color
                    it[archived] = false
                    it[createdBy] = username
                    it[createdAt] = Instant.now()
                }
                ProjectsTable.selectAll().where { ProjectsTable.id eq id }.single().toProjectDto()
            }

            WsSessionManager.broadcast(TIME_WS_CHANNEL, json.encodeToString(TimeWsMessage("PROJECT_CREATED", project = project)))
            call.respond(HttpStatusCode.Created, project)
        }

        put("/{id}") {
            val id = call.uuidParam() ?: return@put
            val req = call.receive<UpdateProjectRequest>()
            if (req.name?.isBlank() == true) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_PROJECT", "name must not be blank"))
                return@put
            }
            if (req.color != null && !HEX_COLOR.matches(req.color)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_COLOR", "color must be a hex value like #4F46E5"))
                return@put
            }

            val project = transaction {
                ProjectsTable.selectAll().where { ProjectsTable.id eq id }.singleOrNull()
                    ?: return@transaction null
                ProjectsTable.update({ ProjectsTable.id eq id }) {
                    req.name?.let { v -> it[name] = v.trim() }
                    req.color?.let { v -> it[color] = v }
                }
                ProjectsTable.selectAll().where { ProjectsTable.id eq id }.single().toProjectDto()
            }
            if (project == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Project not found"))
                return@put
            }

            WsSessionManager.broadcast(TIME_WS_CHANNEL, json.encodeToString(TimeWsMessage("PROJECT_UPDATED", project = project)))
            call.respond(project)
        }

        patch("/{id}/archive") {
            val id = call.uuidParam() ?: return@patch
            // Body is optional; default action is to archive. Pass {"archived": false} to restore.
            val req = runCatching { call.receive<ArchiveProjectRequest>() }.getOrNull()
            val target = req?.archived ?: true

            val project = transaction {
                ProjectsTable.selectAll().where { ProjectsTable.id eq id }.singleOrNull()
                    ?: return@transaction null
                ProjectsTable.update({ ProjectsTable.id eq id }) {
                    it[archived] = target
                }
                ProjectsTable.selectAll().where { ProjectsTable.id eq id }.single().toProjectDto()
            }
            if (project == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Project not found"))
                return@patch
            }

            WsSessionManager.broadcast(TIME_WS_CHANNEL, json.encodeToString(TimeWsMessage("PROJECT_UPDATED", project = project)))
            call.respond(project)
        }
    }
}

private fun Route.entryRoutes(json: Json) {
    route("/entries") {
        get {
            val projectId = call.request.queryParameters["project_id"]
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: return@get call.respond(
                    HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "project_id must be a valid UUID")
                ) }
            val userId = call.request.queryParameters["user_id"]
            val from = call.request.queryParameters["from"]?.let { parseInstant(it) ?: return@get call.respond(
                HttpStatusCode.BadRequest, ErrorResponse("INVALID_DATE", "from must be an ISO-8601 timestamp")
            ) }
            val to = call.request.queryParameters["to"]?.let { parseInstant(it) ?: return@get call.respond(
                HttpStatusCode.BadRequest, ErrorResponse("INVALID_DATE", "to must be an ISO-8601 timestamp")
            ) }

            val entries = transaction {
                val query = TimeEntriesTable.selectAll()
                (projectId as? UUID)?.let { pid -> query.andWhere { TimeEntriesTable.projectId eq pid } }
                userId?.let { uid -> query.andWhere { TimeEntriesTable.userId eq uid } }
                from?.let { f -> query.andWhere { TimeEntriesTable.startedAt greaterEq f } }
                to?.let { t -> query.andWhere { TimeEntriesTable.startedAt lessEq t } }
                query.orderBy(TimeEntriesTable.startedAt, SortOrder.DESC).map { it.toEntryDto() }
            }
            call.respond(entries)
        }

        post("/start") {
            val username = call.username()
            val req = call.receive<StartTimerRequest>()
            val projectId = runCatching { UUID.fromString(req.projectId) }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "projectId must be a valid UUID"))

            val startLock = TIMER_START_LOCKS.computeIfAbsent(username) { Any() }
            val result: Any? = synchronized(startLock) {
                transaction {
                    val project = ProjectsTable.selectAll().where { ProjectsTable.id eq projectId }.singleOrNull()
                        ?: return@transaction null
                    if (project[ProjectsTable.archived]) {
                        return@transaction ErrorResponse("PROJECT_ARCHIVED", "Project is archived")
                    }
                    val now = Instant.now()
                    // Stop any timer that is still running for this user.
                    val stopped = TimeEntriesTable.selectAll()
                        .where { (TimeEntriesTable.userId eq username) and TimeEntriesTable.stoppedAt.isNull() }
                        .singleOrNull()
                    val stoppedDto = stopped?.let { row ->
                        val sid = row[TimeEntriesTable.id]
                        TimeEntriesTable.update({ TimeEntriesTable.id eq sid }) {
                            it[stoppedAt] = now
                            it[updatedAt] = now
                        }
                        TimeEntriesTable.selectAll().where { TimeEntriesTable.id eq sid }.single().toEntryDto()
                    }

                    val id = UUID.randomUUID()
                    TimeEntriesTable.insert {
                        it[TimeEntriesTable.id] = id
                        it[TimeEntriesTable.projectId] = projectId
                        it[userId] = username
                        it[startedAt] = now
                        it[stoppedAt] = null
                        it[description] = req.description?.trim()?.takeIf { d -> d.isNotEmpty() }
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                    val startedDto = TimeEntriesTable.selectAll().where { TimeEntriesTable.id eq id }.single().toEntryDto()
                    stoppedDto to startedDto
                }
            }

            when (result) {
                null -> call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Project not found"))
                is ErrorResponse -> call.respond(HttpStatusCode.Conflict, result)
                is Pair<*, *> -> {
                    val stoppedDto = result.first as TimeEntryDto?
                    val startedDto = result.second as TimeEntryDto
                    stoppedDto?.let {
                        WsSessionManager.broadcast(TIME_WS_CHANNEL, json.encodeToString(TimeWsMessage("ENTRY_UPDATED", entry = it)))
                    }
                    WsSessionManager.broadcast(TIME_WS_CHANNEL, json.encodeToString(TimeWsMessage("ENTRY_CREATED", entry = startedDto)))
                    call.respond(HttpStatusCode.Created, startedDto)
                }
            }
        }

        post("/stop") {
            val username = call.username()
            val entry = transaction {
                val running = TimeEntriesTable.selectAll()
                    .where { (TimeEntriesTable.userId eq username) and TimeEntriesTable.stoppedAt.isNull() }
                    .singleOrNull() ?: return@transaction null
                val id = running[TimeEntriesTable.id]
                val now = Instant.now()
                TimeEntriesTable.update({ TimeEntriesTable.id eq id }) {
                    it[stoppedAt] = now
                    it[updatedAt] = now
                }
                TimeEntriesTable.selectAll().where { TimeEntriesTable.id eq id }.single().toEntryDto()
            }
            if (entry == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NO_RUNNING_TIMER", "No timer is currently running"))
                return@post
            }
            WsSessionManager.broadcast(TIME_WS_CHANNEL, json.encodeToString(TimeWsMessage("ENTRY_UPDATED", entry = entry)))
            call.respond(entry)
        }

        post {
            val username = call.username()
            val req = call.receive<CreateTimeEntryRequest>()
            val projectId = runCatching { UUID.fromString(req.projectId) }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "projectId must be a valid UUID"))
            val started = parseInstant(req.startedAt)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_DATE", "startedAt must be an ISO-8601 timestamp"))
            val stopped = parseInstant(req.stoppedAt)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_DATE", "stoppedAt must be an ISO-8601 timestamp"))
            if (!stopped.isAfter(started)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_RANGE", "stoppedAt must be after startedAt"))
                return@post
            }

            val entry: Any? = transaction {
                val project = ProjectsTable.selectAll().where { ProjectsTable.id eq projectId }.singleOrNull()
                    ?: return@transaction null
                if (project[ProjectsTable.archived]) {
                    return@transaction ErrorResponse("PROJECT_ARCHIVED", "Project is archived")
                }
                val id = UUID.randomUUID()
                val now = Instant.now()
                TimeEntriesTable.insert {
                    it[TimeEntriesTable.id] = id
                    it[TimeEntriesTable.projectId] = projectId
                    it[userId] = username
                    it[startedAt] = started
                    it[stoppedAt] = stopped
                    it[description] = req.description?.trim()?.takeIf { d -> d.isNotEmpty() }
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                TimeEntriesTable.selectAll().where { TimeEntriesTable.id eq id }.single().toEntryDto()
            }
            when (entry) {
                null -> call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Project not found"))
                is ErrorResponse -> call.respond(HttpStatusCode.Conflict, entry)
                is TimeEntryDto -> {
                    WsSessionManager.broadcast(TIME_WS_CHANNEL, json.encodeToString(TimeWsMessage("ENTRY_CREATED", entry = entry)))
                    call.respond(HttpStatusCode.Created, entry)
                }
            }
        }

        put("/{id}") {
            val id = call.uuidParam() ?: return@put
            val req = call.receive<UpdateTimeEntryRequest>()
            val newProjectId = req.projectId?.let {
                runCatching { UUID.fromString(it) }.getOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "projectId must be a valid UUID"))
            }
            val newStarted = req.startedAt?.let {
                parseInstant(it) ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_DATE", "startedAt must be an ISO-8601 timestamp"))
            }
            val newStopped = req.stoppedAt?.let {
                parseInstant(it) ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_DATE", "stoppedAt must be an ISO-8601 timestamp"))
            }

            val outcome = transaction {
                val existing = TimeEntriesTable.selectAll().where { TimeEntriesTable.id eq id }.singleOrNull()
                    ?: return@transaction null
                if (newProjectId != null) {
                    val project = ProjectsTable.selectAll().where { ProjectsTable.id eq newProjectId }.singleOrNull()
                        ?: return@transaction ErrorResponse("NOT_FOUND", "Project not found")
                    if (project[ProjectsTable.archived]) {
                        return@transaction ErrorResponse("PROJECT_ARCHIVED", "Project is archived")
                    }
                }
                val effectiveStart = newStarted ?: existing[TimeEntriesTable.startedAt]
                val effectiveStop = newStopped ?: existing[TimeEntriesTable.stoppedAt]
                if (effectiveStop != null && !effectiveStop.isAfter(effectiveStart)) {
                    return@transaction ErrorResponse("INVALID_RANGE", "stoppedAt must be after startedAt")
                }
                TimeEntriesTable.update({ TimeEntriesTable.id eq id }) {
                    newProjectId?.let { v -> it[projectId] = v }
                    newStarted?.let { v -> it[startedAt] = v }
                    newStopped?.let { v -> it[stoppedAt] = v }
                    req.description?.let { v -> it[description] = v.trim().takeIf { d -> d.isNotEmpty() } }
                    it[updatedAt] = Instant.now()
                }
                TimeEntriesTable.selectAll().where { TimeEntriesTable.id eq id }.single().toEntryDto()
            }

            when (outcome) {
                null -> call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Time entry not found"))
                is ErrorResponse -> {
                    val status = when (outcome.code) {
                        "NOT_FOUND" -> HttpStatusCode.NotFound
                        "PROJECT_ARCHIVED" -> HttpStatusCode.Conflict
                        else -> HttpStatusCode.BadRequest
                    }
                    call.respond(status, outcome)
                }
                is TimeEntryDto -> {
                    WsSessionManager.broadcast(TIME_WS_CHANNEL, json.encodeToString(TimeWsMessage("ENTRY_UPDATED", entry = outcome)))
                    call.respond(outcome)
                }
            }
        }

        delete("/{id}") {
            val id = call.uuidParam() ?: return@delete
            val deleted = transaction {
                val existing = TimeEntriesTable.selectAll().where { TimeEntriesTable.id eq id }.singleOrNull()
                    ?: return@transaction null
                TimeEntriesTable.deleteWhere { TimeEntriesTable.id eq id }
                existing.toEntryDto()
            }
            if (deleted == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Time entry not found"))
                return@delete
            }
            WsSessionManager.broadcast(TIME_WS_CHANNEL, json.encodeToString(TimeWsMessage("ENTRY_DELETED", entry = deleted)))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun ApplicationCall.username(): String =
    principal<JWTPrincipal>()!!.payload.getClaim("username").asString()

/** Accepts both plain instants ("…Z") and offset timestamps ("…+02:00"). */
private fun parseInstant(value: String): Instant? =
    runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()

private fun ResultRow.toProjectDto() = ProjectDto(
    id = this[ProjectsTable.id].toString(),
    name = this[ProjectsTable.name],
    color = this[ProjectsTable.color],
    archived = this[ProjectsTable.archived],
    createdBy = this[ProjectsTable.createdBy],
    createdAt = this[ProjectsTable.createdAt].toString()
)

private fun ResultRow.toEntryDto(): TimeEntryDto {
    val started = this[TimeEntriesTable.startedAt]
    val stopped = this[TimeEntriesTable.stoppedAt]
    return TimeEntryDto(
        id = this[TimeEntriesTable.id].toString(),
        projectId = this[TimeEntriesTable.projectId].toString(),
        userId = this[TimeEntriesTable.userId],
        startedAt = started.toString(),
        stoppedAt = stopped?.toString(),
        description = this[TimeEntriesTable.description],
        durationSeconds = stopped?.let { Duration.between(started, it).seconds },
        createdAt = this[TimeEntriesTable.createdAt].toString(),
        updatedAt = this[TimeEntriesTable.updatedAt].toString()
    )
}
