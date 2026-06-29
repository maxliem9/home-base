package com.homebase.routes

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Minimal, dependency-free RFC 5545 (iCalendar) builder for the family-calendar subscription
 * feed (issue #427, Phase 2). Calendar clients (Apple/Google) are strict, so this keeps to the
 * parts of the spec that matter here:
 *  - CRLF line endings,
 *  - TEXT escaping (backslash, comma, semicolon, newline),
 *  - line folding at 75 octets (UTF-8 aware),
 *  - all-day events via VALUE=DATE (DTSTART;VALUE=DATE + a DTEND one day later, the exclusive end),
 *  - a DTSTAMP on every VEVENT.
 *
 * Most entries are modelled as an all-day VEVENT (not VTODO): a VTODO renders inconsistently across
 * clients (Apple shows reminders, Google ignores them entirely), whereas an all-day VEVENT shows
 * up as a date banner everywhere — which is exactly what a "what's happening that day" overlay
 * wants. A due todo therefore appears as an all-day event on its due date.
 *
 * Real calendar events (the #434 entity) with a clock time are the exception: they get a timed
 * VEVENT ([addTimedEvent]) with a real DTSTART/DTEND so they show at the right hour, and they are
 * OPAQUE (busy) rather than TRANSPARENT — they are actual appointments, not background overlays.
 */

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
private val UTC_STAMP_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

/** Max content octets per line before folding (RFC 5545 §3.1: 75 octets, excluding CRLF). */
private const val FOLD_LIMIT = 75

/** Escapes a TEXT value per RFC 5545 §3.3.11: backslash, semicolon, comma, and newlines. */
internal fun icalEscapeText(value: String): String =
    value
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\r\n", "\\n")
        .replace("\n", "\\n")
        .replace("\r", "\\n")

/**
 * Folds a single content line to <= 75 octets, continuing with CRLF + a single leading space.
 * Folding is octet-based but must never split a UTF-8 multibyte sequence, so we accumulate whole
 * code points and break before adding one would exceed the limit. The continuation space counts
 * toward the next line's budget (RFC 5545 §3.1).
 */
internal fun icalFoldLine(line: String): String {
    val bytes = line.toByteArray(Charsets.UTF_8)
    if (bytes.size <= FOLD_LIMIT) return line

    val out = StringBuilder()
    var lineOctets = 0
    var first = true
    var i = 0
    while (i < line.length) {
        val cp = line.codePointAt(i)
        val charCount = Character.charCount(cp)
        val piece = line.substring(i, i + charCount)
        val pieceOctets = piece.toByteArray(Charsets.UTF_8).size
        val budget = if (first) FOLD_LIMIT else FOLD_LIMIT - 1 // continuation lines start with a space
        if (lineOctets + pieceOctets > budget) {
            out.append("\r\n ")
            first = false
            lineOctets = 1 // the leading space already on this continuation line
        }
        out.append(piece)
        lineOctets += pieceOctets
        i += charCount
    }
    return out.toString()
}

/**
 * Accumulates VEVENTs and renders a complete VCALENDAR. Lines are CRLF-joined and folded.
 * `refreshMinutes` emits both the Apple (`X-PUBLISHED-TTL`) and RFC 7986 (`REFRESH-INTERVAL`)
 * hints so subscribers re-poll on their own (the feed is otherwise static between fetches).
 */
internal class ICalBuilder(
    private val prodId: String = "-//HomeBase//Familienkalender//DE",
    private val calName: String = "HomeBase",
    private val refreshMinutes: Int = 60,
) {
    private val lines = mutableListOf<String>()

    init {
        lines += "BEGIN:VCALENDAR"
        lines += "VERSION:2.0"
        lines += "PRODID:${prodId}"
        lines += "CALSCALE:GREGORIAN"
        lines += "METHOD:PUBLISH"
        lines += "X-WR-CALNAME:${calName}"
        lines += "X-PUBLISHED-TTL:PT${refreshMinutes}M"
        lines += "REFRESH-INTERVAL;VALUE=DURATION:PT${refreshMinutes}M"
    }

    /**
     * Adds an all-day VEVENT. [start] is the (inclusive) day; the DTEND is set to start+1 day,
     * the exclusive end an all-day VEVENT requires. [uid] must be globally stable per source
     * entry so re-subscribes update rather than duplicate. [dtStamp] is the feed build time.
     */
    fun addAllDayEvent(
        uid: String,
        start: LocalDate,
        summary: String,
        description: String? = null,
        location: String? = null,
        dtStamp: Instant,
        transparent: Boolean = true,
    ) {
        lines += "BEGIN:VEVENT"
        lines += "UID:${uid}"
        lines += "DTSTAMP:${UTC_STAMP_FORMAT.format(dtStamp)}"
        lines += "DTSTART;VALUE=DATE:${DATE_FORMAT.format(start)}"
        lines += "DTEND;VALUE=DATE:${DATE_FORMAT.format(start.plusDays(1))}"
        lines += "SUMMARY:${icalEscapeText(summary)}"
        if (!description.isNullOrBlank()) {
            lines += "DESCRIPTION:${icalEscapeText(description)}"
        }
        if (!location.isNullOrBlank()) {
            lines += "LOCATION:${icalEscapeText(location)}"
        }
        lines += "TRANSP:${if (transparent) "TRANSPARENT" else "OPAQUE"}"
        lines += "END:VEVENT"
    }

    /**
     * Adds a timed VEVENT for a real appointment ([date] + clock [start], optional [end]). The
     * local wall-clock time is resolved at [zone] (the server's zone, matching the rest of the app)
     * and emitted as UTC instants (`…Z`) — unambiguous on every client without shipping a VTIMEZONE
     * block. A missing [end] defaults to a one-hour duration. Timed events are OPAQUE (busy).
     */
    fun addTimedEvent(
        uid: String,
        date: LocalDate,
        start: LocalTime,
        end: LocalTime?,
        summary: String,
        description: String? = null,
        location: String? = null,
        dtStamp: Instant,
        zone: ZoneId,
    ) {
        val startInstant = date.atTime(start).atZone(zone).toInstant()
        // Derive the end as an *instant* off the start so the default +1h (or any value that would
        // roll past midnight, e.g. a 23:30 start) advances the day rather than wrapping to an
        // earlier same-day time — a same-day DTEND < DTSTART would be an invalid interval. An
        // explicit end is honoured only when strictly after start (the backend guarantees
        // end >= start on the same date); end <= start falls back to a one-hour block (we never
        // emit zero-length VEVENTs). Non-existent/ambiguous local times on DST switch days resolve
        // via ZonedDateTime's default gap/overlap rules.
        val endInstant = end?.takeIf { it.isAfter(start) }
            ?.let { date.atTime(it).atZone(zone).toInstant() }
            ?: startInstant.plusSeconds(3600)
        lines += "BEGIN:VEVENT"
        lines += "UID:${uid}"
        lines += "DTSTAMP:${UTC_STAMP_FORMAT.format(dtStamp)}"
        lines += "DTSTART:${UTC_STAMP_FORMAT.format(startInstant)}"
        lines += "DTEND:${UTC_STAMP_FORMAT.format(endInstant)}"
        lines += "SUMMARY:${icalEscapeText(summary)}"
        if (!description.isNullOrBlank()) {
            lines += "DESCRIPTION:${icalEscapeText(description)}"
        }
        if (!location.isNullOrBlank()) {
            lines += "LOCATION:${icalEscapeText(location)}"
        }
        lines += "TRANSP:OPAQUE"
        lines += "END:VEVENT"
    }

    /** Renders the whole calendar: each line escaped already, folded, CRLF-joined, trailing CRLF. */
    fun build(): String {
        val all = lines + "END:VCALENDAR"
        return all.joinToString("\r\n") { icalFoldLine(it) } + "\r\n"
    }
}
