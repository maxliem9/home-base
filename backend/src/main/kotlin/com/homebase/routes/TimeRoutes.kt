package com.homebase.routes

import com.homebase.model.*
import com.homebase.plugins.appJson
import com.homebase.service.TimeService
import com.homebase.time.TimeCreditService
import com.homebase.ws.WsSessionManager
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.encodeToString
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

private const val TIME_WS_CHANNEL = "time"
private val HEX_COLOR = Regex("^#[0-9A-Fa-f]{6}$")

private suspend fun broadcastTime(message: TimeWsMessage) =
    WsSessionManager.broadcast(TIME_WS_CHANNEL, appJson.encodeToString(message))

/**
 * HTTP surface for the time-tracking domain. Handlers parse/validate, keep the CSV rendering, call
 * [TimeService] for all persistence, then broadcast. No handler touches a `TimeEntriesTable.`/
 * `dbQuery {}` (issue #564, following the TodoService pattern of #546). Time keeps its typed
 * `TimeWsMessage` envelope + hand-rolled WS endpoint (not migrated to the generic #552 syncChannel).
 * The forecast/credits routes already delegate to `time/` services and are unchanged.
 */
fun Route.timeRoutes() {
    val service = TimeService()

    route("/time") {
        projectRoutes(service)
        entryRoutes(service)
        exportRoutes(service)
        workTargetRoutes()
        forecastRoute()
        creditsRoute()

        // All currently running timers across the shared household (0..2).
        get("/running/all") {
            call.respond(service.runningAll())
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

private fun Route.projectRoutes(service: TimeService) {
    route("/projects") {
        get {
            call.respond(service.listProjects())
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
            val project = service.createProject(req.name, req.color, username)
            broadcastTime(TimeWsMessage("PROJECT_CREATED", project = project))
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
            val project = service.updateProject(id, req)
            if (project == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Project not found"))
                return@put
            }
            broadcastTime(TimeWsMessage("PROJECT_UPDATED", project = project))
            call.respond(project)
        }

        patch("/{id}/archive") {
            val id = call.uuidParam() ?: return@patch
            // Body is optional; default action is to archive. Pass {"archived": false} to restore.
            val req = runCatching { call.receive<ArchiveProjectRequest>() }.getOrNull()
            val target = req?.archived ?: true
            val project = service.archiveProject(id, target)
            if (project == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Project not found"))
                return@patch
            }
            broadcastTime(TimeWsMessage("PROJECT_UPDATED", project = project))
            call.respond(project)
        }
    }
}

private fun Route.entryRoutes(service: TimeService) {
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
            call.respond(service.listEntries(projectId as? UUID, userId, from, to))
        }

        post("/start") {
            val caller = call.username()
            val req = call.receive<StartTimerRequest>()
            val projectId = runCatching { UUID.fromString(req.projectId) }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "projectId must be a valid UUID"))
            val targetUser = req.userId?.trim()?.takeIf { it.isNotEmpty() } ?: caller
            when (val r = service.startTimer(caller, targetUser, projectId, req.description)) {
                TimeService.StartResult.UserNotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("USER_NOT_FOUND", "User not found"))
                TimeService.StartResult.ProjectNotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Project not found"))
                is TimeService.StartResult.Conflict -> call.respond(HttpStatusCode.Conflict, r.error)
                is TimeService.StartResult.Ok -> {
                    r.stopped?.let { broadcastTime(TimeWsMessage("ENTRY_UPDATED", entry = it)) }
                    broadcastTime(TimeWsMessage("ENTRY_CREATED", entry = r.started))
                    call.respond(HttpStatusCode.Created, r.started)
                }
            }
        }

        post("/stop") {
            val caller = call.username()
            // Optional body {userId}: stop the partner's timer. No/empty body → self.
            val body = runCatching { call.receive<StopTimerRequest>() }.getOrNull()
            val targetUser = body?.userId?.trim()?.takeIf { it.isNotEmpty() } ?: caller
            when (val r = service.stopTimer(caller, targetUser)) {
                TimeService.StopResult.UserNotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("USER_NOT_FOUND", "User not found"))
                TimeService.StopResult.NoRunningTimer ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NO_RUNNING_TIMER", "No timer is currently running"))
                is TimeService.StopResult.Ok -> {
                    broadcastTime(TimeWsMessage("ENTRY_UPDATED", entry = r.entry))
                    call.respond(r.entry)
                }
            }
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
            val targetUser = req.userId?.trim()?.takeIf { it.isNotEmpty() } ?: caller
            when (val r = service.createEntry(caller, targetUser, projectId, started, stopped, req.description)) {
                TimeService.CreateEntryResult.UserNotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("USER_NOT_FOUND", "User not found"))
                TimeService.CreateEntryResult.ProjectNotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Project not found"))
                is TimeService.CreateEntryResult.Conflict -> call.respond(HttpStatusCode.Conflict, r.error)
                is TimeService.CreateEntryResult.Ok -> {
                    broadcastTime(TimeWsMessage("ENTRY_CREATED", entry = r.entry))
                    call.respond(HttpStatusCode.Created, r.entry)
                }
            }
        }

        // Split a completed entry into two parts at a cut time, with an optional untracked gap (#62).
        post("/{id}/split") {
            val id = call.uuidParam() ?: return@post
            val req = call.receive<SplitTimeEntryRequest>()
            val splitAt = parseInstant(req.splitAt)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_DATE", "splitAt must be an ISO-8601 timestamp"))
            val breakMinutes = req.breakMinutes ?: 0
            if (breakMinutes < 0) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_RANGE", "breakMinutes must not be negative"))
            }
            when (val r = service.splitEntry(id, splitAt, breakMinutes)) {
                TimeService.SplitResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Time entry not found"))
                is TimeService.SplitResult.Invalid -> call.respond(HttpStatusCode.BadRequest, r.error)
                is TimeService.SplitResult.Ok -> {
                    broadcastTime(TimeWsMessage("ENTRY_UPDATED", entry = r.response.first))
                    broadcastTime(TimeWsMessage("ENTRY_CREATED", entry = r.response.second))
                    call.respond(r.response)
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
            when (val r = service.updateEntry(id, req, newProjectId, newStarted, newStopped)) {
                TimeService.UpdateEntryResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Time entry not found"))
                is TimeService.UpdateEntryResult.Fault -> call.respond(
                    when (r.error.code) {
                        "NOT_FOUND" -> HttpStatusCode.NotFound
                        "PROJECT_ARCHIVED" -> HttpStatusCode.Conflict
                        else -> HttpStatusCode.BadRequest
                    },
                    r.error,
                )
                is TimeService.UpdateEntryResult.Ok -> {
                    broadcastTime(TimeWsMessage("ENTRY_UPDATED", entry = r.entry))
                    call.respond(r.entry)
                }
            }
        }

        delete("/{id}") {
            val id = call.uuidParam() ?: return@delete
            val deleted = service.deleteEntry(id)
            if (deleted == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Time entry not found"))
                return@delete
            }
            broadcastTime(TimeWsMessage("ENTRY_DELETED", entry = deleted))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * Server-side CSV export of completed time entries. Reuses the same `project_id`/`from`/`to` filters
 * as the entry list; the service loads the rows, this renders the CSV (UTF-8 BOM, `;`, DE-Excel).
 */
private fun Route.exportRoutes(service: TimeService) {
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

        val loaded = service.loadCsvData(projectId, from, to)

        // Absence/holiday credits (#31) over the same window. Range: the query bounds if given, else
        // the tracked-entry span (a timesheet is anchored to when tracking started) up to today.
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

        val filename = exportFilename(from, to, zone)
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, filename).toString(),
        )
        // Stream the CSV straight to the response writer instead of materialising the whole document as
        // one String first (#559) — Ktor flushes it in chunks, so peak memory is a row, not the report.
        call.respondTextWriter(ContentType.parse("text/csv; charset=UTF-8")) {
            writeTimeCsv(this, loaded.entries, creditRows, zone)
        }
    }
}

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
 * Streams the CSV body to [out]: UTF-8 BOM, `;`-separated, CRLF line endings (Excel-friendly). Recorded
 * [entries] and absence/holiday [credits] are merged into one table ordered by day; a credit row
 * carries a date and its credited hours but no end time. Writing directly to the response avoids
 * building the whole document in a String first (#559).
 */
private fun writeTimeCsv(out: Appendable, entries: List<TimeService.CsvRow>, credits: List<CreditCsvRow>, zone: ZoneId) {
    // One sortable line type for both sources: entries anchor on their start instant, credits on the
    // day's start — so a credit sorts among that day's entries.
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

    out.append('\uFEFF') // BOM → Excel detects UTF-8 and renders umlauts correctly
    // `sep=;` directive: tells Excel the delimiter is a semicolon before it guesses from the locale.
    out.append("sep=;\r\n")
    out.append("Projekt;Nutzer;Start;Ende;Dauer (h);Dauer (hh:mm);Beschreibung\r\n")
    for (line in allLines) {
        out.append(line.cells.joinToString(";") { csvField(it) }).append("\r\n")
    }
}

// Leading characters Excel/LibreOffice interpret as a formula — even inside a quoted field — turning
// a crafted description into executable spreadsheet content (CSV injection).
private val FORMULA_TRIGGERS = setOf('=', '+', '-', '@', '\t')

/**
 * RFC-4180 escaping plus formula neutralisation: a field starting with a formula trigger gets a
 * leading apostrophe, which spreadsheet apps read as "treat as text".
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
