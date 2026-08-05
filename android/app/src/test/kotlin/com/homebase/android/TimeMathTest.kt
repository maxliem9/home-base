package com.homebase.android

import com.homebase.android.R
import com.homebase.android.data.model.ProjectForecastDto
import com.homebase.android.data.model.TimeCreditDto
import com.homebase.android.data.model.TimeEntryDto
import com.homebase.android.data.model.UserForecastDto
import com.homebase.android.ui.time.SplitCheck
import com.homebase.android.ui.time.buildWeekStats
import com.homebase.android.ui.time.checkSplit
import com.homebase.android.ui.time.defaultSplitAt
import com.homebase.android.ui.time.liveExtraSeconds
import com.homebase.android.ui.time.projectCardStats
import com.homebase.android.ui.time.withLiveExtra
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Unit tests for the pure Zeiterfassung math (#64/#66): live ticking of the
 * forecast snapshot, the project cards' day/week saldo incl. last-active
 * fallback, and the split-entry validation/preview. Mirrors the web's
 * WeekBalance/projectStats/SplitEntryModal semantics in TimeView.tsx.
 */
class TimeMathTest {

    // All card tests run in a fixed zone on a fixed "now": Sunday 2026-06-07,
    // 12:00 Berlin (the week runs Mon 2026-06-01 … Sun 2026-06-07).
    private val zone = ZoneId.of("Europe/Berlin")
    private val sundayNoon = Instant.parse("2026-06-07T10:00:00Z")

    private fun entry(
        startedAt: String,
        stoppedAt: String? = null,
        durationSeconds: Long? = null,
        id: String = "e-$startedAt",
    ) = TimeEntryDto(
        id = id, projectId = "p1", userId = "alice",
        startedAt = startedAt, stoppedAt = stoppedAt,
        description = null, durationSeconds = durationSeconds,
        createdAt = startedAt, updatedAt = stoppedAt ?: startedAt,
    )

    // --- Live-Tick (#64) ---------------------------------------------------

    @Test
    fun `liveExtraSeconds is zero without a snapshot and never negative`() {
        val now = Instant.parse("2026-06-07T10:00:30Z")
        assertEquals(0L, liveExtraSeconds(null, now))
        assertEquals(0L, liveExtraSeconds(now.plusSeconds(60), now))
    }

    @Test
    fun `liveExtraSeconds counts the seconds since the snapshot`() {
        val snapshot = Instant.parse("2026-06-07T10:00:00Z")
        assertEquals(42L, liveExtraSeconds(snapshot, snapshot.plusSeconds(42)))
    }

    @Test
    fun `withLiveExtra ticks week and today figures and only the live project's saldo`() {
        val forecast = UserForecastDto(
            userId = "alice",
            weeklyTargetHours = 40.0,
            workdayCount = 5.0,
            weekTargetSeconds = 144_000,
            weekRecordedSeconds = 10_000,
            weekCreditedSeconds = 2_000,
            weekRemainingSeconds = 132_000,
            todayTargetSeconds = 28_800,
            todayRecordedSeconds = 3_000,
            todayRemainingSeconds = 25_800,
            projects = listOf(
                ProjectForecastDto("p1", 30.0, 8_000, 2_000, -98_000),
                ProjectForecastDto("p2", 10.0, 2_000, 0, -34_000),
            ),
        )

        val live = forecast.withLiveExtra(60, "p1")

        assertEquals(10_060L, live.weekRecordedSeconds)
        assertEquals(131_940L, live.weekRemainingSeconds)
        assertEquals(3_060L, live.todayRecordedSeconds)
        assertEquals(25_740L, live.todayRemainingSeconds)
        // running project accrues the extra in its saldo row …
        assertEquals(8_060L, live.projects[0].recordedSeconds)
        assertEquals(-97_940L, live.projects[0].deltaSeconds)
        // … the other project stays untouched
        assertEquals(2_000L, live.projects[1].recordedSeconds)
        assertEquals(-34_000L, live.projects[1].deltaSeconds)
    }

    @Test
    fun `withLiveExtra without extra returns the snapshot unchanged`() {
        val forecast = UserForecastDto(
            userId = "alice", weeklyTargetHours = 40.0, workdayCount = 5.0,
            weekTargetSeconds = 144_000, weekRecordedSeconds = 0, weekCreditedSeconds = 0,
            weekRemainingSeconds = 144_000, todayTargetSeconds = 28_800,
            todayRecordedSeconds = 0, todayRemainingSeconds = 28_800,
        )
        assertSame(forecast, forecast.withLiveExtra(0, "p1"))
    }

    // --- Kachel-Saldi (#64) --------------------------------------------------

    @Test
    fun `card stats use today and this week when entries exist there`() {
        val stats = projectCardStats(
            listOf(
                entry("2026-06-07T06:00:00Z", "2026-06-07T08:00:00Z", 7_200), // today
                entry("2026-06-05T06:00:00Z", "2026-06-05T07:00:00Z", 3_600), // Friday, same week
            ),
            now = sundayNoon,
            zone = zone,
            locale = Locale.GERMAN,
        )
        assertEquals(7_200L, stats.daySeconds)
        assertEquals("Heute", stats.dayLabel)
        assertEquals(10_800L, stats.weekSeconds)
        assertEquals("Diese Woche", stats.weekLabel)
    }

    @Test
    fun `card stats fall back to the last active day - Friday saldo on Sunday`() {
        val stats = projectCardStats(
            listOf(
                entry("2026-06-05T06:00:00Z", "2026-06-05T10:00:00Z", 14_400), // Friday
                entry("2026-06-04T06:00:00Z", "2026-06-04T07:00:00Z", 3_600), // Thursday
            ),
            now = sundayNoon,
            zone = zone,
            locale = Locale.GERMAN,
        )
        // only the latest active day counts, labelled relative to today (Sunday)
        assertEquals(14_400L, stats.daySeconds)
        assertEquals("Vorgestern", stats.dayLabel)
        // Friday is still this week
        assertEquals(18_000L, stats.weekSeconds)
        assertEquals("Diese Woche", stats.weekLabel)
    }

    @Test
    fun `card stats label older fallback days with weekday or date`() {
        // Wednesday 2026-06-03 seen from Sunday 2026-06-07 → weekday label
        val wednesday = projectCardStats(
            listOf(entry("2026-06-03T06:00:00Z", "2026-06-03T07:00:00Z", 3_600)),
            now = sundayNoon,
            zone = zone,
            locale = Locale.GERMAN,
        )
        assertEquals("Mittwoch", wednesday.dayLabel)

        // 2026-05-27 is more than 6 days back → date label, week → "Letzte Woche"
        val older = projectCardStats(
            listOf(entry("2026-05-27T06:00:00Z", "2026-05-27T07:00:00Z", 3_600)),
            now = sundayNoon,
            zone = zone,
            locale = Locale.GERMAN,
        )
        assertEquals("27. Mai", older.dayLabel)
        assertEquals(3_600L, older.weekSeconds)
        assertEquals("Letzte Woche", older.weekLabel)
    }

    @Test
    fun `card stats label an older fallback week with its date range`() {
        // week Mon 2026-05-04 … Sun 2026-05-10 (same month)
        val sameMonth = projectCardStats(
            listOf(entry("2026-05-06T06:00:00Z", "2026-05-06T07:00:00Z", 3_600)),
            now = sundayNoon,
            zone = zone,
            locale = Locale.GERMAN,
        )
        assertEquals("4.–10. Mai", sameMonth.weekLabel)

        // week Mon 2026-04-27 … Sun 2026-05-03 (spans two months)
        val crossMonth = projectCardStats(
            listOf(entry("2026-04-28T06:00:00Z", "2026-04-28T07:00:00Z", 3_600)),
            now = sundayNoon,
            zone = zone,
            locale = Locale.GERMAN,
        )
        assertEquals("27. April – 3. Mai", crossMonth.weekLabel)
    }

    @Test
    fun `card stats localize day and week labels under an English locale`() {
        // Same fixtures as the German cases above, but under en → English month/weekday names.
        val wednesday = projectCardStats(
            listOf(entry("2026-06-03T06:00:00Z", "2026-06-03T07:00:00Z", 3_600)), // Wed, this week
            now = sundayNoon,
            zone = zone,
            locale = Locale.ENGLISH,
        )
        assertEquals("Wednesday", wednesday.dayLabel)
        assertEquals("This week", wednesday.weekLabel)

        // older entry → date label + cross-month week range, all in English
        val crossMonth = projectCardStats(
            listOf(entry("2026-04-28T06:00:00Z", "2026-04-28T07:00:00Z", 3_600)),
            now = sundayNoon,
            zone = zone,
            locale = Locale.ENGLISH,
        )
        assertEquals("28 April", crossMonth.dayLabel)
        assertEquals("27 April – 3 May", crossMonth.weekLabel)
    }

    @Test
    fun `card stats count a running timer live`() {
        val stats = projectCardStats(
            listOf(entry("2026-06-07T09:00:00Z")), // running since 1h
            now = sundayNoon,
            zone = zone,
            locale = Locale.GERMAN,
        )
        assertEquals(3_600L, stats.daySeconds)
        assertEquals("Heute", stats.dayLabel)
        assertEquals(3_600L, stats.weekSeconds)
    }

    @Test
    fun `card stats without entries show zeros with the default labels`() {
        val stats = projectCardStats(emptyList(), now = sundayNoon, zone = zone, locale = Locale.GERMAN)
        assertEquals(0L, stats.daySeconds)
        assertEquals("Heute", stats.dayLabel)
        assertEquals(0L, stats.weekSeconds)
        assertEquals("Diese Woche", stats.weekLabel)
    }

    // --- Eintrag splitten (#66) ----------------------------------------------

    private val start = "2026-06-03T12:03:00Z"
    private val stop = "2026-06-03T19:03:00Z"

    @Test
    fun `defaultSplitAt is the minute-snapped midpoint`() {
        assertEquals(Instant.parse("2026-06-03T15:33:00Z"), defaultSplitAt(start, stop))
        // 30s midpoint offset snaps down to the full minute
        assertEquals(
            Instant.parse("2026-06-03T12:00:00Z"),
            defaultSplitAt("2026-06-03T11:59:00Z", "2026-06-03T12:02:01Z"),
        )
        // a running entry has no end — the midpoint is taken against "now" (#634)
        assertEquals(
            Instant.parse("2026-06-03T15:33:00Z"),
            defaultSplitAt(start, null, now = Instant.parse("2026-06-03T19:03:00Z")),
        )
    }

    @Test
    fun `checkSplit accepts a cut inside the entry and plans both parts`() {
        val check = checkSplit(start, stop, Instant.parse("2026-06-03T15:33:00Z"), "45")
        assertTrue(check is SplitCheck.Valid)
        check as SplitCheck.Valid
        assertEquals(Instant.parse("2026-06-03T15:33:00Z"), check.splitAt)
        assertEquals(45, check.breakMinutes)
        assertEquals(Instant.parse("2026-06-03T16:18:00Z"), check.secondStart)
    }

    @Test
    fun `checkSplit allows comma input and rounds to whole minutes`() {
        val check = checkSplit(start, stop, Instant.parse("2026-06-03T15:33:00Z"), " 7,5 ")
        assertEquals(8, (check as SplitCheck.Valid).breakMinutes)
        // empty input means no break
        val noBreak = checkSplit(start, stop, Instant.parse("2026-06-03T15:33:00Z"), "")
        assertEquals(0, (noBreak as SplitCheck.Valid).breakMinutes)
    }

    @Test
    fun `checkSplit rejects a cut outside the entry`() {
        // The message is now a @StringRes id (localized on render); assert the id, not the text.
        val res = R.string.time_split_err_range
        // exactly on the boundaries is invalid too (strictly between)
        assertEquals(res, (checkSplit(start, stop, Instant.parse(start), "") as SplitCheck.Invalid).messageRes)
        assertEquals(res, (checkSplit(start, stop, Instant.parse(stop), "") as SplitCheck.Invalid).messageRes)
        assertEquals(
            res,
            (checkSplit(start, stop, Instant.parse("2026-06-03T20:00:00Z"), "") as SplitCheck.Invalid).messageRes,
        )
    }

    @Test
    fun `checkSplit rejects an unparseable or negative break`() {
        val cut = Instant.parse("2026-06-03T15:33:00Z")
        val res = R.string.time_split_err_break
        assertEquals(res, (checkSplit(start, stop, cut, "abc") as SplitCheck.Invalid).messageRes)
        assertEquals(res, (checkSplit(start, stop, cut, "-5") as SplitCheck.Invalid).messageRes)
    }

    @Test
    fun `checkSplit rejects a break that ends at or after the entry's end`() {
        val cut = Instant.parse("2026-06-03T18:33:00Z")
        val res = R.string.time_split_err_break_overrun
        // 30 min break would end exactly at stoppedAt → part two would be empty
        assertEquals(res, (checkSplit(start, stop, cut, "30") as SplitCheck.Invalid).messageRes)
        assertEquals(res, (checkSplit(start, stop, cut, "45") as SplitCheck.Invalid).messageRes)
        assertTrue(checkSplit(start, stop, cut, "29") is SplitCheck.Valid)
    }

    @Test
    fun `checkSplit cuts a running entry against now`() {
        val now = Instant.parse("2026-06-03T19:03:00Z")
        val check = checkSplit(start, null, Instant.parse("2026-06-03T15:33:00Z"), "45", now = now)
        assertTrue(check is SplitCheck.Valid)
        assertEquals(Instant.parse("2026-06-03T16:18:00Z"), (check as SplitCheck.Valid).secondStart)

        // a cut in the future (or the break reaching into it) is out of range, with
        // its own "…und jetzt" wording (#634)
        assertEquals(
            R.string.time_split_err_range_running,
            (checkSplit(start, null, now.plusSeconds(60), "", now = now) as SplitCheck.Invalid).messageRes,
        )
        assertEquals(
            R.string.time_split_err_break_overrun_running,
            (checkSplit(start, null, now.minusSeconds(60), "30", now = now) as SplitCheck.Invalid).messageRes,
        )
    }

    @Test
    fun `checkSplit cut error wins over break error when both are invalid`() {
        // Cut is outside the entry (before start) AND break text is unparseable — the
        // cut-range message must win, mirroring the web's SplitEntryModal precedence
        // (cutValid first, breakParses second, breakValid third).
        val cutBeforeStart = Instant.parse("2026-06-03T11:00:00Z") // before start
        val check = checkSplit(start, stop, cutBeforeStart, "abc")
        assertEquals(R.string.time_split_err_range, (check as SplitCheck.Invalid).messageRes)
    }

    // --- Projekt-Detail per-week credits (#31) -----------------------------

    private fun credit(date: String, seconds: Long, type: String = "KRANK", user: String = "alice") =
        TimeCreditDto(userId = user, date = date, projectId = "p1", seconds = seconds, type = type)

    @Test
    fun `buildWeekStats folds credits into the entry week and adds credit-only weeks`() {
        // Week Mon 2026-06-01: a 2h entry (Berlin) + an 8h sick day → 10h total.
        val entries = listOf(entry("2026-06-01T08:00:00Z", "2026-06-01T10:00:00Z"))
        val credits = listOf(
            credit("2026-06-03", 28_800),               // Wed same week
            credit("2026-05-25", 28_800, type = "FEIERTAG"), // earlier week, no entries
        )

        val weeks = buildWeekStats(entries, credits, zone)
        assertEquals(2, weeks.size)
        // newest week first
        assertEquals(LocalDate.of(2026, 6, 1), weeks[0].weekStart)
        assertEquals(36_000L, weeks[0].totalSeconds) // 2h recorded + 8h credited
        assertEquals(28_800L, weeks[0].creditedSeconds)
        assertEquals(1, weeks[0].count)               // count stays entry-only
        assertEquals(36_000L, weeks[0].byUser.single { it.first == "alice" }.second)

        // a fully-absent week still shows up, as a credit-only row
        assertEquals(LocalDate.of(2026, 5, 25), weeks[1].weekStart)
        assertEquals(28_800L, weeks[1].totalSeconds)
        assertEquals(28_800L, weeks[1].creditedSeconds)
        assertEquals(0, weeks[1].count)
    }

    @Test
    fun `buildWeekStats without credits behaves like the old entry-only aggregation`() {
        val entries = listOf(entry("2026-06-01T08:00:00Z", "2026-06-01T10:00:00Z"))
        val weeks = buildWeekStats(entries, emptyList(), zone)
        assertEquals(1, weeks.size)
        assertEquals(7_200L, weeks[0].totalSeconds)
        assertEquals(0L, weeks[0].creditedSeconds)
        assertEquals(1, weeks[0].count)
    }
}
