package com.homebase.routes

import com.homebase.db.dbQuery
import com.homebase.db.CalendarEventsTable
import com.homebase.model.*
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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID

private const val EVENTS_WS_CHANNEL = "events"

// Guard rail: a single range query may span at most ~one year (inclusive), so a stray/huge
// range can't pull the whole table. Mirrors MealPlanRoutes.
private const val MAX_RANGE_DAYS = 370

private const val MAX_TITLE_LEN = 200
private const val MAX_LOCATION_LEN = 500
private const val MAX_NOTES_LEN = 5000

// Event kinds for the (later) colour-coded calendar rendering (#427 integration). OTHER is the
// neutral fallback / default. Mirrors the issue's examples (Arzt, Tierarzt, Geburtstag …).
private val EVENT_TYPES = setOf("APPOINTMENT", "BIRTHDAY", "VET", "OTHER")

fun Route.eventRoutes() {
    suspend fun notify() =
        WsSessionManager.broadcast(EVENTS_WS_CHANNEL, appJson.encodeToString(CalendarEventWsMessage("EVENT_CHANGED")))

    route("/events") {

        // Events within an inclusive [from, to] date range (the calendar view fetches the
        // visible window). Household-shared — every user sees every event.
        get {
            val from = call.request.queryParameters["from"]?.let { parseDate(it) } ?: return@get call.invalidRange()
            val to = call.request.queryParameters["to"]?.let { parseDate(it) } ?: return@get call.invalidRange()
            if (from.isAfter(to)) return@get call.invalidRange()
            if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
                return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("RANGE_TOO_LARGE", "range must not exceed $MAX_RANGE_DAYS days"))
            }
            val events = dbQuery {
                CalendarEventsTable.selectAll()
                    .where { (CalendarEventsTable.date greaterEq from) and (CalendarEventsTable.date lessEq to) }
                    .orderBy(CalendarEventsTable.date to SortOrder.ASC, CalendarEventsTable.startTime to SortOrder.ASC_NULLS_FIRST)
                    .map { it.toEventDto() }
            }
            call.respond(events)
        }

        // Create a new event. Household-shared — any user may create; created_by is the caller.
        post {
            val req = call.receive<CalendarEventRequest>()
            val parsed = parseEvent(req) ?: return@post call.invalidEvent()
            val username = call.username()

            val dto = dbQuery {
                val id = UUID.randomUUID()
                CalendarEventsTable.insert {
                    it[CalendarEventsTable.id] = id
                    it[title] = parsed.title
                    it[type] = parsed.type
                    it[date] = parsed.date
                    it[allDay] = parsed.allDay
                    it[startTime] = parsed.startTime
                    it[endTime] = parsed.endTime
                    it[location] = parsed.location
                    it[notes] = parsed.notes
                    it[createdBy] = username
                    it[createdAt] = Instant.now()
                }
                CalendarEventsTable.selectAll().where { CalendarEventsTable.id eq id }.single().toEventDto()
            }
            notify()
            call.respond(HttpStatusCode.Created, dto)
        }

        // Replace an existing event. Household-shared — no owner check; created_by/created_at
        // are preserved (provenance of the original creation).
        put("/{id}") {
            val id = call.uuidParam() ?: return@put
            val req = call.receive<CalendarEventRequest>()
            val parsed = parseEvent(req) ?: return@put call.invalidEvent()

            val dto = dbQuery {
                if (CalendarEventsTable.selectAll().where { CalendarEventsTable.id eq id }.empty()) return@dbQuery null
                CalendarEventsTable.update({ CalendarEventsTable.id eq id }) {
                    it[title] = parsed.title
                    it[type] = parsed.type
                    it[date] = parsed.date
                    it[allDay] = parsed.allDay
                    it[startTime] = parsed.startTime
                    it[endTime] = parsed.endTime
                    it[location] = parsed.location
                    it[notes] = parsed.notes
                }
                CalendarEventsTable.selectAll().where { CalendarEventsTable.id eq id }.single().toEventDto()
            } ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Event not found"))

            notify()
            call.respond(dto)
        }

        // Delete an event. Household-shared — any user may delete any event.
        delete("/{id}") {
            val id = call.uuidParam() ?: return@delete
            val existed = dbQuery {
                CalendarEventsTable.deleteWhere { CalendarEventsTable.id eq id } > 0
            }
            if (!existed) return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Event not found"))
            notify()
            call.respond(HttpStatusCode.NoContent)
        }
    }

    webSocket("/ws/events") {
        WsSessionManager.add(EVENTS_WS_CHANNEL, this)
        try {
            for (frame in incoming) {
                if (frame is Frame.Close) break
            }
        } finally {
            WsSessionManager.remove(EVENTS_WS_CHANNEL, this)
        }
    }
}

/** A validated, normalised event ready to persist. */
private data class ParsedEvent(
    val title: String,
    val type: String,
    val date: LocalDate,
    val allDay: Boolean,
    val startTime: LocalTime?,
    val endTime: LocalTime?,
    val location: String?,
    val notes: String?,
)

/**
 * Validates + normalises a create/update request, or returns null on any invalid input (the caller
 * answers 400). Rules: title non-blank ≤ 200; type ∈ EVENT_TYPES (default OTHER); date is
 * YYYY-MM-DD; all-day events carry no time; non-all-day may have start (optional) and end (only
 * with start, end ≥ start); location/notes trimmed-to-null and length-bounded.
 */
private fun parseEvent(req: CalendarEventRequest): ParsedEvent? {
    val title = req.title.trim()
    if (title.isEmpty() || title.length > MAX_TITLE_LEN) return null

    val type = (req.type?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: "OTHER")
    if (type !in EVENT_TYPES) return null

    val date = parseDate(req.date) ?: return null

    val start = req.startTime?.let { parseTime(it) ?: return null }
    val end = req.endTime?.let { parseTime(it) ?: return null }

    if (req.allDay) {
        // All-day events must not carry a time (matches the DB CHECK).
        if (start != null || end != null) return null
    } else {
        // end without start is meaningless; end must not precede start.
        if (end != null && start == null) return null
        if (start != null && end != null && end.isBefore(start)) return null
    }

    val location = req.location?.trim()?.takeIf { it.isNotEmpty() }
    if (location != null && location.length > MAX_LOCATION_LEN) return null
    val notes = req.notes?.trim()?.takeIf { it.isNotEmpty() }
    if (notes != null && notes.length > MAX_NOTES_LEN) return null

    return ParsedEvent(title, type, date, req.allDay, if (req.allDay) null else start, if (req.allDay) null else end, location, notes)
}

private fun ResultRow.toEventDto() = CalendarEventDto(
    id = this[CalendarEventsTable.id].toString(),
    title = this[CalendarEventsTable.title],
    type = this[CalendarEventsTable.type],
    date = this[CalendarEventsTable.date].toString(),
    allDay = this[CalendarEventsTable.allDay],
    startTime = this[CalendarEventsTable.startTime]?.toString(),
    endTime = this[CalendarEventsTable.endTime]?.toString(),
    location = this[CalendarEventsTable.location],
    notes = this[CalendarEventsTable.notes],
    createdBy = this[CalendarEventsTable.createdBy],
    createdAt = this[CalendarEventsTable.createdAt].toString(),
)

private fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()

// Accepts "HH:mm" and "HH:mm:ss" (LocalTime.parse handles both ISO forms).
private fun parseTime(value: String): LocalTime? = runCatching { LocalTime.parse(value.trim()) }.getOrNull()

private suspend fun ApplicationCall.invalidRange() =
    respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_RANGE", "from and to must be YYYY-MM-DD with from <= to"))

private suspend fun ApplicationCall.invalidEvent() =
    respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_EVENT", "title (1..$MAX_TITLE_LEN), date (YYYY-MM-DD) and a valid type/time are required; all-day events carry no time"))
