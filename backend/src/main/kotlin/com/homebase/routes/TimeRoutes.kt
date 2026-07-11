package com.homebase.routes

import com.homebase.db.ProjectsTable
import com.homebase.db.TimeEntriesTable
import com.homebase.db.dbQuery
import com.homebase.model.*
import com.homebase.time.TimeCreditService
import com.homebase.ws.WsSessionManager
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import com.homebase.plugins.appJson
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.vendors.ForUpdateOption
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

private const val TIME_WS_CHANNEL = "time"
private val HEX_COLOR = Regex("^#[0-9A-Fa-f]{6}$")
private val TIMER_START_LOCKS = ConcurrentHashMap<String, Any>()

fun Route.timeRoutes() {
    route("/time") {
        projectRoutes()
        entryRoutes()
        exportRoutes()
        workTargetRoutes()
        forecastRoute()
        creditsRoute()

        // All currently running timers across the shared household (0..2). Lets the
        // dashboard and the time view show the partner's live timer without pulling the
        // whole entries list just to find it.
        get("/running/all") {
            val entries = dbQuery {
                TimeEntriesTable.selectAll()
                    .where { TimeEntriesTable.stoppedAt.isNull() }
                    .orderBy(TimeEntriesTable.userId, SortOrder.ASC)
                    .map { it.toEntryDto() }
            }
            call.respond(entries)
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

private fun Route.projectRoutes() {
    route("/projects") {
        get {
            val projects = dbQuery {
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

            val project = dbQuery {
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

            WsSessionManager.broadcast(TIME_WS_CHANNEL, appJson.encodeToString(TimeWsMessage("PROJECT_CREATED", project = project)))
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

            val project = dbQuery {
                ProjectsTable.selectAll().where { ProjectsTable.id eq id }.singleOrNull()
                    ?: return@dbQuery null
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

            WsSessionManager.broadcast(TIME_WS_CHANNEL, appJson.encodeToString(TimeWsMessage("PROJECT_UPDATED", project = project)))
            call.respond(project)
        }

        patch("/{id}/archive") {
            val id = call.uuidParam() ?: return@patch
            // Body is optional; default action is to archive. Pass {"archived": false} to restore.
            val req = runCatching { call.receive<ArchiveProjectRequest>() }.getOrNull()
            val target = req?.archived ?: true

            val project = dbQuery {
                ProjectsTable.selectAll().where { ProjectsTable.id eq id }.singleOrNull()
                    ?: return@dbQuery null
                ProjectsTable.update({ ProjectsTable.id eq id }) {
                    it[archived] = target
                }
                ProjectsTable.selectAll().where { ProjectsTable.id eq id }.single().toProjectDto()
            }
            if (project == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Project not found"))
                return@patch
            }

            WsSessionManager.broadcast(TIME_WS_CHANNEL, appJson.encodeToString(TimeWsMessage("PROJECT_UPDATED", project = project)))
            call.respond(project)
        }
    }
}

private fun Route.entryRoutes() {
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

            val entries = dbQuery {
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
            val caller = call.username()
            val req = call.receive<StartTimerRequest>()
            val projectId = runCatching { UUID.fromString(req.projectId) }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "projectId must be a valid UUID"))
            // Shared household: start on behalf of the partner when a userId is given.
            val targetUser = req.userId?.trim()?.takeIf { it.isNotEmpty() } ?: caller
            if (targetUser != caller && !dbQuery { userExists(targetUser) }) {
                return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("USER_NOT_FOUND", "User not found"))
            }

            // Lock on the *target* user so concurrent starts for the same person serialize
            // (the one-running-timer-per-user invariant is per target, not per caller).
            val startLock = TIMER_START_LOCKS.computeIfAbsent(targetUser) { Any() }
            val result: Any? = synchronized(startLock) {
                // Blocking transaction (not dbQuery) on purpose: a suspend call cannot cross a
                // synchronized{} monitor. The lock serializes concurrent starts for one user (#549).
                transaction {
                    val project = ProjectsTable.selectAll().where { ProjectsTable.id eq projectId }.singleOrNull()
                        ?: return@transaction null
                    if (project[ProjectsTable.archived]) {
                        return@transaction ErrorResponse("PROJECT_ARCHIVED", "Project is archived")
                    }
                    val now = Instant.now()
                    // Stop any timer that is still running for the target user.
                    val stopped = TimeEntriesTable.selectAll()
                        .where { (TimeEntriesTable.userId eq targetUser) and TimeEntriesTable.stoppedAt.isNull() }
                        .forUpdate(ForUpdateOption.ForUpdate)
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
                        it[userId] = targetUser
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
                        WsSessionManager.broadcast(TIME_WS_CHANNEL, appJson.encodeToString(TimeWsMessage("ENTRY_UPDATED", entry = it)))
                    }
                    WsSessionManager.broadcast(TIME_WS_CHANNEL, appJson.encodeToString(TimeWsMessage("ENTRY_CREATED", entry = startedDto)))
                    call.respond(HttpStatusCode.Created, startedDto)
                }
            }
        }

        post("/stop") {
            val caller = call.username()
            // Optional body {userId}: stop the partner's timer. No/empty body → self.
            val body = runCatching { call.receive<StopTimerRequest>() }.getOrNull()
            val targetUser = body?.userId?.trim()?.takeIf { it.isNotEmpty() } ?: caller
            if (targetUser != caller && !dbQuery { userExists(targetUser) }) {
                return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("USER_NOT_FOUND", "User not found"))
            }
            val entry = dbQuery {
                val running = TimeEntriesTable.selectAll()
                    .where { (TimeEntriesTable.userId eq targetUser) and TimeEntriesTable.stoppedAt.isNull() }
                    .forUpdate(ForUpdateOption.ForUpdate)
                    .singleOrNull() ?: return@dbQuery null
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
            WsSessionManager.broadcast(TIME_WS_CHANNEL, appJson.encodeToString(TimeWsMessage("ENTRY_UPDATED", entry = entry)))
            call.respond(entry)
        }

        post {
            val caller = call.username()
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
            // Shared household: record the entry for the partner when a userId is given
            // (mirrors /start and /stop). The clients confirm cross-person writes (#129).
            val targetUser = req.userId?.trim()?.takeIf { it.isNotEmpty() } ?: caller
            if (targetUser != caller && !dbQuery { userExists(targetUser) }) {
                return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("USER_NOT_FOUND", "User not found"))
            }

            val entry: Any? = dbQuery {
                val project = ProjectsTable.selectAll().where { ProjectsTable.id eq projectId }.singleOrNull()
                    ?: return@dbQuery null
                if (project[ProjectsTable.archived]) {
                    return@dbQuery ErrorResponse("PROJECT_ARCHIVED", "Project is archived")
                }
                val id = UUID.randomUUID()
                val now = Instant.now()
                TimeEntriesTable.insert {
                    it[TimeEntriesTable.id] = id
                    it[TimeEntriesTable.projectId] = projectId
                    it[userId] = targetUser
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
                    WsSessionManager.broadcast(TIME_WS_CHANNEL, appJson.encodeToString(TimeWsMessage("ENTRY_CREATED", entry = entry)))
                    call.respond(HttpStatusCode.Created, entry)
                }
            }
        }

        // Split a completed entry into two parts at a cut time, with an optional
        // untracked gap between them (#62) — covers "forgot to clock out for the
        // break" and "forgot to switch the project" (split, then edit part two).
        // The original row becomes part one (keeps its id), part two is created.
        post("/{id}/split") {
            val id = call.uuidParam() ?: return@post
            val req = call.receive<SplitTimeEntryRequest>()
            val splitAt = parseInstant(req.splitAt)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_DATE", "splitAt must be an ISO-8601 timestamp"))
            val breakMinutes = req.breakMinutes ?: 0
            if (breakMinutes < 0) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_RANGE", "breakMinutes must not be negative"))
            }

            val outcome: Any? = dbQuery {
                val existing = TimeEntriesTable.selectAll()
                    .where { TimeEntriesTable.id eq id }
                    .forUpdate(ForUpdateOption.ForUpdate)
                    .singleOrNull() ?: return@dbQuery null
                val stopped = existing[TimeEntriesTable.stoppedAt]
                    ?: return@dbQuery ErrorResponse("ENTRY_RUNNING", "A running timer cannot be split — stop it first")
                val started = existing[TimeEntriesTable.startedAt]
                if (!splitAt.isAfter(started) || !stopped.isAfter(splitAt)) {
                    return@dbQuery ErrorResponse("INVALID_RANGE", "splitAt must lie strictly between startedAt and stoppedAt")
                }
                // computed only after the range check — the cut is inside a real entry
                // here, so adding the break cannot overflow Instant (DateTimeException)
                val secondStart = splitAt.plusSeconds(breakMinutes * 60L)
                if (!stopped.isAfter(secondStart)) {
                    return@dbQuery ErrorResponse("INVALID_RANGE", "the break must end before the entry's stoppedAt")
                }
                val now = Instant.now()
                TimeEntriesTable.update({ TimeEntriesTable.id eq id }) {
                    it[stoppedAt] = splitAt
                    it[updatedAt] = now
                }
                val secondId = UUID.randomUUID()
                TimeEntriesTable.insert {
                    it[TimeEntriesTable.id] = secondId
                    it[projectId] = existing[TimeEntriesTable.projectId]
                    it[userId] = existing[TimeEntriesTable.userId]
                    it[startedAt] = secondStart
                    it[TimeEntriesTable.stoppedAt] = stopped
                    it[description] = existing[TimeEntriesTable.description]
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                SplitTimeEntryResponse(
                    first = TimeEntriesTable.selectAll().where { TimeEntriesTable.id eq id }.single().toEntryDto(),
                    second = TimeEntriesTable.selectAll().where { TimeEntriesTable.id eq secondId }.single().toEntryDto(),
                )
            }

            when (outcome) {
                null -> call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Time entry not found"))
                is ErrorResponse -> call.respond(
                    if (outcome.code == "ENTRY_RUNNING") HttpStatusCode.Conflict else HttpStatusCode.BadRequest,
                    outcome,
                )
                is SplitTimeEntryResponse -> {
                    WsSessionManager.broadcast(TIME_WS_CHANNEL, appJson.encodeToString(TimeWsMessage("ENTRY_UPDATED", entry = outcome.first)))
                    WsSessionManager.broadcast(TIME_WS_CHANNEL, appJson.encodeToString(TimeWsMessage("ENTRY_CREATED", entry = outcome.second)))
                    call.respond(outcome)
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

            val outcome = dbQuery {
                val existing = TimeEntriesTable.selectAll()
                    .where { TimeEntriesTable.id eq id }
                    .forUpdate(ForUpdateOption.ForUpdate)
                    .singleOrNull() ?: return@dbQuery null
                if (newProjectId != null) {
                    val project = ProjectsTable.selectAll().where { ProjectsTable.id eq newProjectId }.singleOrNull()
                        ?: return@dbQuery ErrorResponse("NOT_FOUND", "Project not found")
                    if (project[ProjectsTable.archived]) {
                        return@dbQuery ErrorResponse("PROJECT_ARCHIVED", "Project is archived")
                    }
                }
                val effectiveStart = newStarted ?: existing[TimeEntriesTable.startedAt]
                val effectiveStop = newStopped ?: existing[TimeEntriesTable.stoppedAt]
                if (effectiveStop != null && !effectiveStop.isAfter(effectiveStart)) {
                    return@dbQuery ErrorResponse("INVALID_RANGE", "stoppedAt must be after startedAt")
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
                    WsSessionManager.broadcast(TIME_WS_CHANNEL, appJson.encodeToString(TimeWsMessage("ENTRY_UPDATED", entry = outcome)))
                    call.respond(outcome)
                }
            }
        }

        delete("/{id}") {
            val id = call.uuidParam() ?: return@delete
            val deleted = dbQuery {
                val existing = TimeEntriesTable.selectAll()
                    .where { TimeEntriesTable.id eq id }
                    .forUpdate(ForUpdateOption.ForUpdate)
                    .singleOrNull() ?: return@dbQuery null
                TimeEntriesTable.deleteWhere { TimeEntriesTable.id eq id }
                existing.toEntryDto()
            }
            if (deleted == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Time entry not found"))
                return@delete
            }
            WsSessionManager.broadcast(TIME_WS_CHANNEL, appJson.encodeToString(TimeWsMessage("ENTRY_DELETED", entry = deleted)))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * Server-side CSV export of completed time entries for external processing
 * (Excel/LibreOffice). Reuses the same `project_id` / `from` / `to` filters as the
 * entry list. The format decisions:
 *  - delimiter `;` and a UTF-8 BOM so German Excel opens it correctly (comma is the
 *    decimal separator there);
 *  - timestamps rendered in the server's local zone (same convention as the digest);
 *  - duration offered both as decimal hours ("1,50") and as hh:mm so either workflow works.
 * Running (not-yet-stopped) entries are omitted — a report covers finished work.
 */
private fun Route.exportRoutes() {
    val zone = ZoneId.systemDefault()

    get("/export.csv") {
        val projectId = call.request.queryParameters["project_id"]?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "project_id must be a valid UUID"))
        }
        val from = call.request.queryParameters["from"]?.let {
            parseInstant(it) ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_DATE", "from must be an ISO-8601 timestamp"))
        }
        val to = call.request.queryParameters["to"]?.let {
            parseInstant(it) ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_DATE", "to must be an ISO-8601 timestamp"))
        }

        val loaded = dbQuery {
            val projectNames = ProjectsTable.selectAll()
                .associate { it[ProjectsTable.id] to it[ProjectsTable.name] }
            val query = TimeEntriesTable.selectAll()
                .andWhere { TimeEntriesTable.stoppedAt.isNotNull() }
            projectId?.let { pid -> query.andWhere { TimeEntriesTable.projectId eq pid } }
            from?.let { f -> query.andWhere { TimeEntriesTable.startedAt greaterEq f } }
            to?.let { t -> query.andWhere { TimeEntriesTable.startedAt lessEq t } }
            val entries = query.orderBy(TimeEntriesTable.startedAt, SortOrder.ASC).map { row ->
                CsvRow(
                    project = projectNames[row[TimeEntriesTable.projectId]] ?: "—",
                    user = row[TimeEntriesTable.userId],
                    startedAt = row[TimeEntriesTable.startedAt],
                    stoppedAt = row[TimeEntriesTable.stoppedAt]!!,
                    description = row[TimeEntriesTable.description] ?: "",
                )
            }
            LoadedCsv(entries, projectNames)
        }

        // Absence/holiday credits (#31) over the same window, so a past week that was
        // partly sick/vacation shows those hours in the export just like the live
        // Wochenbilanz. Range: the query bounds if given, else the tracked-entry span
        // (a timesheet is anchored to when tracking started) up to today.
        val toDate = to?.atZone(zone)?.toLocalDate() ?: LocalDate.now(zone)
        val fromDate = from?.atZone(zone)?.toLocalDate()
            ?: loaded.entries.minOfOrNull { it.startedAt.atZone(zone).toLocalDate() }
            ?: toDate
        val creditRows = TimeCreditService().credits(fromDate, toDate)
            .filter { projectId == null || it.projectId == projectId }
            .map { c ->
                CreditCsvRow(
                    project = loaded.projectNames[c.projectId] ?: "—",
                    user = c.user,
                    date = c.date,
                    seconds = c.seconds,
                    label = creditLabel(c.type),
                )
            }

        val csv = buildTimeCsv(loaded.entries, creditRows, zone)
        val filename = exportFilename(from, to, zone)
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, filename).toString(),
        )
        call.respondText(csv, ContentType.parse("text/csv; charset=UTF-8"))
    }
}

private data class LoadedCsv(val entries: List<CsvRow>, val projectNames: Map<UUID, String>)

private data class CsvRow(
    val project: String,
    val user: String,
    val startedAt: Instant,
    val stoppedAt: Instant,
    val description: String,
)

/** An absence/holiday credit rendered as a whole-day CSV line (no end time). */
private data class CreditCsvRow(
    val project: String,
    val user: String,
    val date: LocalDate,
    val seconds: Long,
    val label: String,
)

/** German report label for a credit type; the entered absence keeps its own name. */
private fun creditLabel(type: String): String = when (type) {
    "KRANK" -> "Krank (Zeitgutschrift)"
    "URLAUB" -> "Urlaub (Zeitgutschrift)"
    "KIND_KRANK" -> "Kind krank (Zeitgutschrift)"
    "FEIERTAG" -> "Feiertag (Zeitgutschrift)"
    else -> "Zeitgutschrift"
}

private val CSV_DATETIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
private val CSV_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val FILENAME_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/**
 * Builds the CSV body: UTF-8 BOM, `;`-separated, CRLF line endings (Excel-friendly).
 * Recorded [entries] and absence/holiday [credits] are merged into one table ordered
 * by day; a credit row carries a date and its credited hours but no end time.
 */
private fun buildTimeCsv(entries: List<CsvRow>, credits: List<CreditCsvRow>, zone: ZoneId): String {
    // One sortable line type for both sources: entries anchor on their start instant,
    // credits on the day's start — so a credit sorts among that day's entries.
    data class Line(val at: Instant, val cells: List<String>)

    val entryLines = entries.map { r ->
        val seconds = Duration.between(r.startedAt, r.stoppedAt).seconds
        Line(
            r.startedAt,
            listOf(
                r.project,
                r.user,
                CSV_DATETIME.format(r.startedAt.atZone(zone)),
                CSV_DATETIME.format(r.stoppedAt.atZone(zone)),
                String.format(Locale.GERMANY, "%.2f", seconds / 3600.0),
                "%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60),
                r.description,
            ),
        )
    }
    val creditLines = credits.map { c ->
        Line(
            c.date.atStartOfDay(zone).toInstant(),
            listOf(
                c.project,
                c.user,
                CSV_DATE.format(c.date),
                "",
                String.format(Locale.GERMANY, "%.2f", c.seconds / 3600.0),
                "%02d:%02d".format(c.seconds / 3600, (c.seconds % 3600) / 60),
                c.label,
            ),
        )
    }
    val allLines = (entryLines + creditLines).sortedBy { it.at }

    val sb = StringBuilder()
    sb.append('\uFEFF') // BOM → Excel detects UTF-8 and renders umlauts correctly
    // `sep=;` directive: tells Excel the delimiter is a semicolon before it guesses
    // from the locale (comma in some regions). Must be the first line after the BOM.
    sb.append("sep=;\r\n")
    sb.append("Projekt;Nutzer;Start;Ende;Dauer (h);Dauer (hh:mm);Beschreibung\r\n")
    for (line in allLines) {
        sb.append(line.cells.joinToString(";") { csvField(it) }).append("\r\n")
    }
    return sb.toString()
}

// Leading characters Excel/LibreOffice interpret as a formula — even inside a quoted
// field — turning a crafted description into executable spreadsheet content (CSV
// injection, e.g. "=HYPERLINK(…)").
private val FORMULA_TRIGGERS = setOf('=', '+', '-', '@', '\t')

/**
 * RFC-4180 escaping (quote fields containing the delimiter, quotes or newlines) plus
 * formula neutralisation: a field starting with a formula trigger gets a leading
 * apostrophe, which spreadsheet apps read as "treat as text". The apostrophe stays
 * visible in the cell — the accepted tradeoff of the standard mitigation.
 */
private fun csvField(value: String): String {
    val neutralized = if (value.firstOrNull() in FORMULA_TRIGGERS) "'$value" else value
    return if (neutralized.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + neutralized.replace("\"", "\"\"") + "\""
    } else {
        neutralized
    }
}

private fun exportFilename(from: Instant?, to: Instant?, zone: ZoneId): String =
    if (from != null && to != null) {
        "zeiterfassung_${FILENAME_DATE.format(from.atZone(zone))}_${FILENAME_DATE.format(to.atZone(zone))}.csv"
    } else {
        "zeiterfassung_export.csv"
    }

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
