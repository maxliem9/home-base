package com.homebase

import com.homebase.routes.ICalBuilder
import com.homebase.routes.icalEscapeText
import com.homebase.routes.icalFoldLine
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the pure RFC 5545 helpers (issue #427). The route test [CalendarRouteTest]
 * exercises the feed end-to-end; this pins the two trickiest bits in isolation — TEXT escaping
 * order and the UTF-8-aware 75-octet line folding.
 */
class ICalendarTest {

    @Test
    fun `escaping order handles backslash, comma, semicolon and newline`() {
        // backslash must be escaped first so it doesn't double-escape the others
        assertEquals("a\\\\b", icalEscapeText("a\\b"))
        assertEquals("Milch\\, Brot\\; Käse", icalEscapeText("Milch, Brot; Käse"))
        assertEquals("line1\\nline2", icalEscapeText("line1\nline2"))
        assertEquals("a\\nb", icalEscapeText("a\r\nb"))
        assertEquals("a\\nb", icalEscapeText("a\rb"))
    }

    @Test
    fun `a short line is returned unchanged`() {
        val line = "SUMMARY:kurz"
        assertEquals(line, icalFoldLine(line))
    }

    @Test
    fun `a long ASCII line folds with CRLF + single leading space and no content line exceeds 75 octets`() {
        val line = "SUMMARY:" + "x".repeat(200)
        val folded = icalFoldLine(line)
        val parts = folded.split("\r\n")
        assertTrue(parts.size > 1, "expected the line to fold into multiple physical lines")
        // continuation lines start with exactly one space
        parts.drop(1).forEach { assertTrue(it.startsWith(" "), "continuation must start with a space: '$it'") }
        // every physical line is <= 75 octets
        parts.forEach { assertTrue(it.toByteArray(Charsets.UTF_8).size <= 75, "line >75 octets: '$it'") }
        // unfolding (RFC 5545: strip each CRLF + the one inserted leading space) restores the original
        assertEquals(line, folded.replace("\r\n ", ""))
    }

    @Test
    fun `folding never splits a multibyte UTF-8 sequence`() {
        // Each 🎉 is 4 UTF-8 octets; a run of them straddles the 75-octet boundary so the folder
        // must break *between* code points, never inside one. If it split a sequence, decoding the
        // bytes back to a String would introduce a U+FFFD replacement char.
        val line = "DESCRIPTION:" + "🎉".repeat(40) // 40 party-popper emoji
        val folded = icalFoldLine(line)
        // round-trips through UTF-8 cleanly (no replacement char), so no sequence was cut
        val reDecoded = String(folded.toByteArray(Charsets.UTF_8), Charsets.UTF_8)
        assertEquals(folded, reDecoded)
        assertTrue('�' !in folded, "a multibyte sequence was split (U+FFFD present)")
        // and each physical line still respects the octet cap
        folded.split("\r\n").forEach { assertTrue(it.toByteArray(Charsets.UTF_8).size <= 75) }
        // unfolding restores the original content exactly
        assertEquals(line, folded.replace("\r\n ", ""))
    }

    @Test
    fun `a timed event converts local wall-clock to a UTC instant`() {
        // 14:30 Berlin on a winter date (UTC+1) is 13:30 UTC; end 15:00 -> 14:00 UTC.
        val ical = ICalBuilder()
        ical.addTimedEvent(
            uid = "event-1@homebase",
            date = LocalDate.of(2026, 1, 15),
            start = LocalTime.of(14, 30),
            end = LocalTime.of(15, 0),
            summary = "Tierarzt",
            location = "Praxis",
            dtStamp = Instant.EPOCH,
            zone = ZoneId.of("Europe/Berlin"),
        )
        val body = ical.build()
        assertTrue(body.contains("DTSTART:20260115T133000Z"), "expected 13:30 UTC DTSTART:\n$body")
        assertTrue(body.contains("DTEND:20260115T140000Z"), "expected 14:00 UTC DTEND:\n$body")
        assertTrue(body.contains("TRANSP:OPAQUE"))
        assertTrue(body.contains("LOCATION:Praxis"))
    }

    @Test
    fun `a timed event with no end defaults to a one-hour duration`() {
        val ical = ICalBuilder()
        ical.addTimedEvent(
            uid = "event-2@homebase",
            date = LocalDate.of(2026, 1, 15),
            start = LocalTime.of(9, 0),
            end = null,
            summary = "Termin",
            dtStamp = Instant.EPOCH,
            zone = ZoneId.of("Europe/Berlin"),
        )
        val body = ical.build()
        assertTrue(body.contains("DTSTART:20260115T080000Z"), body)
        assertTrue(body.contains("DTEND:20260115T090000Z"), "expected +1h default end:\n$body")
    }

    @Test
    fun `a late-evening event with no end rolls the default hour into the next day`() {
        // Regression: the +1h default must advance the *instant* (and thus the date), not wrap to
        // an earlier same-day time. 23:30 Berlin (UTC+1) = 22:30 UTC; +1h = 23:30 UTC same day.
        // 23:45 would cross into the next UTC day — assert DTEND is strictly after DTSTART either way.
        val ical = ICalBuilder()
        ical.addTimedEvent(
            uid = "event-3@homebase",
            date = LocalDate.of(2026, 1, 15),
            start = LocalTime.of(23, 30),
            end = null,
            summary = "Spätschicht",
            dtStamp = Instant.EPOCH,
            zone = ZoneId.of("Europe/Berlin"),
        )
        val body = ical.build()
        assertTrue(body.contains("DTSTART:20260115T223000Z"), body)
        assertTrue(body.contains("DTEND:20260115T233000Z"), "expected +1h to stay a valid interval:\n$body")
        // And a start past 23:00 UTC must roll DTEND into the next day, never before DTSTART.
        val ical2 = ICalBuilder()
        ical2.addTimedEvent(
            uid = "event-4@homebase",
            date = LocalDate.of(2026, 1, 15),
            start = LocalTime.of(23, 30),
            end = null,
            summary = "Nacht",
            dtStamp = Instant.EPOCH,
            zone = ZoneId.of("UTC"),
        )
        val body2 = ical2.build()
        assertTrue(body2.contains("DTSTART:20260115T233000Z"), body2)
        assertTrue(body2.contains("DTEND:20260116T003000Z"), "expected DTEND to roll into the next day:\n$body2")
    }
}
