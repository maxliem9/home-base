package com.homebase.holidays

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.ConcurrentHashMap

/**
 * German statutory holidays per Bundesland — Kotlin port of the client-side
 * `web/src/components/abwesenheit/holidays.ts` (#31 needs them server-side for the
 * time forecast). The two implementations must stay in sync: same Gauß/anonymous
 * Gregorian Easter algorithm, same fixed dates and state lists.
 */
object GermanHolidays {

    val ALL_STATES: Set<String> = setOf(
        "BW", "BY", "BE", "BB", "HB", "HH", "HE", "MV",
        "NI", "NW", "RP", "SL", "SN", "ST", "SH", "TH",
    )

    private val cache = ConcurrentHashMap<String, Map<LocalDate, String>>()

    /** Easter Sunday for [year] (anonymous Gregorian algorithm). */
    fun easter(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31 // 3 = March, 4 = April
        val day = ((h + l - 7 * m + 114) % 31) + 1
        return LocalDate.of(year, month, day)
    }

    /** Buß- und Bettag — the Wednesday before Nov 23. */
    private fun bussBettag(year: Int): LocalDate =
        LocalDate.of(year, 11, 22).with(TemporalAdjusters.previousOrSame(DayOfWeek.WEDNESDAY))

    /** → { date: Feiertagsname } for the given year + Bundesland code. */
    fun holidays(year: Int, state: String): Map<LocalDate, String> =
        cache.computeIfAbsent("$year:$state") {
            val easter = easter(year)
            val defs = listOf(
                Def(LocalDate.of(year, 1, 1), "Neujahr", ALL_STATES),
                Def(LocalDate.of(year, 1, 6), "Heilige Drei Könige", setOf("BW", "BY", "ST")),
                Def(LocalDate.of(year, 3, 8), "Internationaler Frauentag", setOf("BE", "MV")),
                Def(easter.minusDays(2), "Karfreitag", ALL_STATES),
                Def(easter, "Ostersonntag", setOf("BB")),
                Def(easter.plusDays(1), "Ostermontag", ALL_STATES),
                Def(LocalDate.of(year, 5, 1), "Tag der Arbeit", ALL_STATES),
                Def(easter.plusDays(39), "Christi Himmelfahrt", ALL_STATES),
                Def(easter.plusDays(49), "Pfingstsonntag", setOf("BB")),
                Def(easter.plusDays(50), "Pfingstmontag", ALL_STATES),
                Def(easter.plusDays(60), "Fronleichnam", setOf("BW", "BY", "HE", "NW", "RP", "SL")),
                Def(LocalDate.of(year, 8, 15), "Mariä Himmelfahrt", setOf("SL", "BY")),
                Def(LocalDate.of(year, 9, 20), "Weltkindertag", setOf("TH")),
                Def(LocalDate.of(year, 10, 3), "Tag der Deutschen Einheit", ALL_STATES),
                Def(LocalDate.of(year, 10, 31), "Reformationstag", setOf("BB", "HB", "HH", "MV", "NI", "SN", "ST", "SH", "TH")),
                Def(LocalDate.of(year, 11, 1), "Allerheiligen", setOf("BW", "BY", "NW", "RP", "SL")),
                Def(bussBettag(year), "Buß- und Bettag", setOf("SN")),
                Def(LocalDate.of(year, 12, 25), "1. Weihnachtstag", ALL_STATES),
                Def(LocalDate.of(year, 12, 26), "2. Weihnachtstag", ALL_STATES),
            )
            defs.filter { state in it.states }.associate { it.date to it.name }
        }

    private data class Def(val date: LocalDate, val name: String, val states: Set<String>)
}
