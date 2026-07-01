package com.homebase.routes

import com.homebase.db.AbsencesTable
import com.homebase.db.CalendarEventsTable
import com.homebase.db.KitaClosuresTable
import com.homebase.db.MealPlanEntriesTable
import com.homebase.db.PartTimeRulesTable
import com.homebase.db.RecipesTable
import com.homebase.db.TodoListsTable
import com.homebase.db.TodosTable
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.transactions.transaction
// Note: greaterEq/lessEq/neq are imported from SqlExpressionBuilder explicitly (not just via the
// wildcard) so the date-range + status comparisons below resolve as infix operators on the columns.
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// The subscription feed shows a rolling window around "today" so calendar clients stay snappy and
// the payload bounded — past entries fall off, the near future is covered. A subscriber re-polls
// periodically (REFRESH-INTERVAL), so the window simply slides forward over time.
private const val WINDOW_DAYS_BACK = 90L      // ~3 months of recent history
private const val WINDOW_DAYS_AHEAD = 366L     // ~1 year ahead

private const val VISIBILITY_PRIVATE = "PRIVATE"

private val SLOT_LABEL_DE = mapOf(
    "BREAKFAST" to "Frühstück",
    "LUNCH" to "Mittagessen",
    "DINNER" to "Abendessen",
)

private val ABSENCE_LABEL_DE = mapOf(
    "URLAUB" to "Urlaub",
    "KRANK" to "Krank",
    "KIND_KRANK" to "Kind-krank",
)

// A small emoji prefix per event kind so the feed summary scans at a glance (matches the ✓ todos
// use). Unknown types fall back to the neutral pin.
private val EVENT_EMOJI = mapOf(
    "APPOINTMENT" to "📅",
    "BIRTHDAY" to "🎂",
    "VET" to "🐾",
    "OTHER" to "📌",
)

/**
 * iCal subscription feed (issue #427, Phase 2): one read-only `text/calendar` document overlaying
 * the household's dated data so the family can subscribe once in Apple/Google Calendar.
 *
 * Auth: this route sits under `authenticate("auth-jwt")`, which accepts the JWT via the
 * `?token=` query param (the lowest-priority path in `configureAuthentication`, the same one
 * note-image / WebSocket loads use) — calendar apps can set neither an Authorization header nor a
 * WebSocket subprotocol. nginx masks `token=` in its access log; we never log it here.
 *
 * Contents (no new schema — reuses the existing reads):
 *  - due todos (open only — DONE ones are history, not upcoming), each on its `due_date`;
 *  - absences (Urlaub/Krank/Kind-krank, half-days noted in the title);
 *  - part-time free days (a standing "off every <weekday>" rule, as a weekly-recurring banner);
 *  - kita closures (household-wide);
 *  - planned meals (recipe-backed or free-text);
 *  - calendar events (#434 — appointments/birthdays/vet; timed ones get a real clock time).
 *
 * Privacy: the feed is stricter than the in-app `GET /todos`. Because a calendar provider fetches
 * the `.ics` over a subscription URL (the content thus leaves HomeBase and may be seen by that
 * provider or anyone the subscriber shares the calendar with), NO PRIVATE-list todo appears —
 * not even the caller's own. Only list-less and SHARED-list todos are exported.
 * Absences/kita/meals/events/part-time are household-shared and need no per-user filter.
 *
 * Overlay entries (todos/absences/kita/meals) are all-day VEVENTs (see [ICalBuilder] for the
 * VEVENT-vs-VTODO rationale); part-time free days are all-day VEVENTs with a weekly RRULE; real
 * events with a start time get a timed VEVENT instead.
 */
fun Route.calendarRoutes() {
    get("/calendar.ics") {
        val today = LocalDate.now()
        val from = today.minusDays(WINDOW_DAYS_BACK)
        val to = today.plusDays(WINDOW_DAYS_AHEAD)
        val now = Instant.now()

        val ical = ICalBuilder()

        transaction {
            // hidden = todos in ANY private list. Unlike the in-app GET /todos (which shows the
            // caller their own private todos), the .ics is fetched by an external calendar provider
            // (Apple/Google) over a subscription URL that carries a long-lived token — anything in
            // the feed leaves HomeBase and may be seen by that provider or anyone the subscriber
            // shares the calendar with. A PRIVATE list must never leave the app, not even the
            // owner's own; so we exclude every private list, not just the partner's.
            val hiddenListIds = TodoListsTable.selectAll()
                .where { TodoListsTable.visibility eq VISIBILITY_PRIVATE }
                .map { it[TodoListsTable.id] }
                .toSet()

            // Due todos: open (not DONE) with a due_date inside the window and not in a private
            // list. The successor of a recurring todo carries its own due_date, so the
            // "one open instance" invariant means no duplicate dates here.
            TodosTable.selectAll()
                .where {
                    (TodosTable.dueDate greaterEq from) and
                        (TodosTable.dueDate lessEq to) and
                        (TodosTable.status neq "DONE")
                }
                .orderBy(TodosTable.dueDate to SortOrder.ASC)
                .forEach { row ->
                    val listId = row[TodosTable.listId]
                    if (listId != null && listId in hiddenListIds) return@forEach
                    val due = row[TodosTable.dueDate] ?: return@forEach
                    val title = row[TodosTable.title]
                    val assignee = row[TodosTable.assignee]
                    val summary = "✓ " + title + (assignee?.let { " ($it)" } ?: "")
                    ical.addAllDayEvent(
                        uid = "todo-${row[TodosTable.id]}@homebase",
                        start = due,
                        summary = summary,
                        description = row[TodosTable.description],
                        dtStamp = now,
                    )
                }

            // Absences (household-shared). Half-days note vm/nm in the title.
            AbsencesTable.selectAll()
                .where { (AbsencesTable.date greaterEq from) and (AbsencesTable.date lessEq to) }
                .orderBy(AbsencesTable.date to SortOrder.ASC)
                .forEach { row ->
                    val type = row[AbsencesTable.type]
                    val label = ABSENCE_LABEL_DE[type] ?: type
                    val half = row[AbsencesTable.half]
                    val halfSuffix = when (half) {
                        "vm" -> " (vormittags)"
                        "nm" -> " (nachmittags)"
                        else -> ""
                    }
                    ical.addAllDayEvent(
                        uid = "absence-${row[AbsencesTable.id]}@homebase",
                        start = row[AbsencesTable.date],
                        summary = "$label: ${row[AbsencesTable.userId]}$halfSuffix",
                        dtStamp = now,
                    )
                }

            // Kita closures (household-wide background marker).
            KitaClosuresTable.selectAll()
                .where { (KitaClosuresTable.date greaterEq from) and (KitaClosuresTable.date lessEq to) }
                .orderBy(KitaClosuresTable.date to SortOrder.ASC)
                .forEach { row ->
                    ical.addAllDayEvent(
                        uid = "kita-${row[KitaClosuresTable.id]}@homebase",
                        start = row[KitaClosuresTable.date],
                        summary = "Kita: ${row[KitaClosuresTable.label]}",
                        dtStamp = now,
                    )
                }

            // Part-time free days: a standing "off every <weekday>" rule per person. These are
            // computed day-states (never persisted per date), so the feed emits each rule as one
            // weekly-recurring all-day banner the client expands. Skip rules that already ended
            // before the window; anchor the series on the first matching weekday >= start_date.
            PartTimeRulesTable.selectAll()
                .where {
                    (PartTimeRulesTable.endDate.isNull()) or (PartTimeRulesTable.endDate greaterEq from)
                }
                .orderBy(PartTimeRulesTable.startDate to SortOrder.ASC)
                .forEach { row ->
                    val weekday = row[PartTimeRulesTable.weekday] // ISO 1..7
                    if (weekday !in 1..7) return@forEach
                    val startDate = row[PartTimeRulesTable.startDate]
                    val endDate = row[PartTimeRulesTable.endDate]
                    // Advance start_date forward to the first day that actually falls on `weekday`.
                    val delta = ((weekday - startDate.dayOfWeek.value) % 7 + 7) % 7
                    val firstOccurrence = startDate.plusDays(delta.toLong())
                    // A rule whose [start, end] span is too narrow to contain even one occurrence
                    // never fires — skip it (no VEVENT with a DTSTART past its own UNTIL).
                    if (endDate != null && firstOccurrence.isAfter(endDate)) return@forEach
                    ical.addRecurringWeeklyAllDayEvent(
                        uid = "parttime-${row[PartTimeRulesTable.id]}@homebase",
                        firstOccurrence = firstOccurrence,
                        weekday = weekday,
                        until = endDate,
                        summary = "Teilzeit frei: ${row[PartTimeRulesTable.userId]}",
                        dtStamp = now,
                    )
                }

            // Planned meals — recipe-backed (joined title) or free text (#293). LEFT join so
            // free-text entries (no recipe) come through too.
            (MealPlanEntriesTable leftJoin RecipesTable).selectAll()
                .where { (MealPlanEntriesTable.date greaterEq from) and (MealPlanEntriesTable.date lessEq to) }
                .orderBy(MealPlanEntriesTable.date to SortOrder.ASC, MealPlanEntriesTable.slot to SortOrder.ASC)
                .forEach { row ->
                    val dish = row[MealPlanEntriesTable.dishTitle]
                        ?: row.getOrNull(RecipesTable.title)
                        ?: return@forEach
                    val slot = row[MealPlanEntriesTable.slot]
                    val slotLabel = SLOT_LABEL_DE[slot] ?: slot
                    ical.addAllDayEvent(
                        uid = "meal-${row[MealPlanEntriesTable.id]}@homebase",
                        start = row[MealPlanEntriesTable.date],
                        summary = "$slotLabel: $dish",
                        dtStamp = now,
                    )
                }

            // Calendar events (#434) — household-shared. Timed events get a real DTSTART/DTEND;
            // all-day (or time-less) ones a date banner. notes -> DESCRIPTION, location -> LOCATION.
            val zone = ZoneId.systemDefault()
            CalendarEventsTable.selectAll()
                .where { (CalendarEventsTable.date greaterEq from) and (CalendarEventsTable.date lessEq to) }
                .orderBy(CalendarEventsTable.date to SortOrder.ASC, CalendarEventsTable.startTime to SortOrder.ASC_NULLS_FIRST)
                .forEach { row ->
                    val emoji = EVENT_EMOJI[row[CalendarEventsTable.type]] ?: EVENT_EMOJI.getValue("OTHER")
                    val summary = "$emoji ${row[CalendarEventsTable.title]}"
                    val uid = "event-${row[CalendarEventsTable.id]}@homebase"
                    val date = row[CalendarEventsTable.date]
                    val notes = row[CalendarEventsTable.notes]
                    val location = row[CalendarEventsTable.location]
                    val start = row[CalendarEventsTable.startTime]
                    if (!row[CalendarEventsTable.allDay] && start != null) {
                        ical.addTimedEvent(
                            uid = uid,
                            date = date,
                            start = start,
                            end = row[CalendarEventsTable.endTime],
                            summary = summary,
                            description = notes,
                            location = location,
                            dtStamp = now,
                            zone = zone,
                        )
                    } else {
                        ical.addAllDayEvent(
                            uid = uid,
                            start = date,
                            summary = summary,
                            description = notes,
                            location = location,
                            dtStamp = now,
                            transparent = false,
                        )
                    }
                }
        }

        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Inline.withParameter(ContentDisposition.Parameters.FileName, "homebase.ics").toString(),
        )
        call.respondText(ical.build(), ContentType.parse("text/calendar; charset=utf-8"))
    }
}
