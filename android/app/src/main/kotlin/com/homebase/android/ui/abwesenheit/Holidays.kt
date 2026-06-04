package com.homebase.android.ui.abwesenheit

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * German public-holiday computation per Bundesland + calendar date utils.
 * Ported verbatim from the design handoff (holidays.jsx / web holidays.ts) so the
 * mobile planner derives the exact same dates. Holidays are computed, never stored.
 */
object AbwCal {

    fun pad(n: Int): String = n.toString().padStart(2, '0')

    /** "YYYY-MM-DD" key for a date. */
    fun ymd(d: LocalDate): String = "${d.year}-${pad(d.monthValue)}-${pad(d.dayOfMonth)}"

    fun parse(s: String): LocalDate = LocalDate.parse(s)

    fun addDays(d: LocalDate, n: Int): LocalDate = d.plusDays(n.toLong())

    fun daysInMonth(year: Int, month0: Int): Int = YearMonth.of(year, month0 + 1).lengthOfMonth()

    /** ISO weekday: Mon = 1 … Sun = 7. */
    fun isoDow(d: LocalDate): Int = d.dayOfWeek.value

    fun isWeekend(d: LocalDate): Boolean =
        d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY

    /** Anonymous Gregorian Easter algorithm → Easter Sunday. */
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
    private fun bussBettag(year: Int): LocalDate {
        var d = LocalDate.of(year, 11, 22)
        while (d.dayOfWeek != DayOfWeek.WEDNESDAY) d = d.minusDays(1)
        return d
    }

    data class GermanState(val code: String, val name: String)

    val STATES: List<GermanState> = listOf(
        GermanState("BW", "Baden-Württemberg"),
        GermanState("BY", "Bayern"),
        GermanState("BE", "Berlin"),
        GermanState("BB", "Brandenburg"),
        GermanState("HB", "Bremen"),
        GermanState("HH", "Hamburg"),
        GermanState("HE", "Hessen"),
        GermanState("MV", "Mecklenburg-Vorpommern"),
        GermanState("NI", "Niedersachsen"),
        GermanState("NW", "Nordrhein-Westfalen"),
        GermanState("RP", "Rheinland-Pfalz"),
        GermanState("SL", "Saarland"),
        GermanState("SN", "Sachsen"),
        GermanState("ST", "Sachsen-Anhalt"),
        GermanState("SH", "Schleswig-Holstein"),
        GermanState("TH", "Thüringen"),
    )

    private val ALL: List<String> = STATES.map { it.code }

    fun stateName(code: String): String = STATES.find { it.code == code }?.name ?: code

    private val cache = HashMap<String, Map<String, String>>()

    private data class HolidayDef(val date: LocalDate, val name: String, val states: List<String>)

    /** → { "YYYY-MM-DD": "Feiertagsname", … } for a given year + Bundesland. */
    fun holidays(year: Int, state: String): Map<String, String> {
        val key = "$year:$state"
        cache[key]?.let { return it }
        val e = easter(year)
        fun off(n: Int) = e.plusDays(n.toLong())
        val defs = listOf(
            HolidayDef(LocalDate.of(year, 1, 1), "Neujahr", ALL),
            HolidayDef(LocalDate.of(year, 1, 6), "Heilige Drei Könige", listOf("BW", "BY", "ST")),
            HolidayDef(LocalDate.of(year, 3, 8), "Internationaler Frauentag", listOf("BE", "MV")),
            HolidayDef(off(-2), "Karfreitag", ALL),
            HolidayDef(off(0), "Ostersonntag", listOf("BB")),
            HolidayDef(off(1), "Ostermontag", ALL),
            HolidayDef(LocalDate.of(year, 5, 1), "Tag der Arbeit", ALL),
            HolidayDef(off(39), "Christi Himmelfahrt", ALL),
            HolidayDef(off(49), "Pfingstsonntag", listOf("BB")),
            HolidayDef(off(50), "Pfingstmontag", ALL),
            HolidayDef(off(60), "Fronleichnam", listOf("BW", "BY", "HE", "NW", "RP", "SL")),
            HolidayDef(LocalDate.of(year, 8, 15), "Mariä Himmelfahrt", listOf("SL", "BY")),
            HolidayDef(LocalDate.of(year, 9, 20), "Weltkindertag", listOf("TH")),
            HolidayDef(LocalDate.of(year, 10, 3), "Tag der Deutschen Einheit", ALL),
            HolidayDef(LocalDate.of(year, 10, 31), "Reformationstag", listOf("BB", "HB", "HH", "MV", "NI", "SN", "ST", "SH", "TH")),
            HolidayDef(LocalDate.of(year, 11, 1), "Allerheiligen", listOf("BW", "BY", "NW", "RP", "SL")),
            HolidayDef(bussBettag(year), "Buß- und Bettag", listOf("SN")),
            HolidayDef(LocalDate.of(year, 12, 25), "1. Weihnachtstag", ALL),
            HolidayDef(LocalDate.of(year, 12, 26), "2. Weihnachtstag", ALL),
        )
        val map = LinkedHashMap<String, String>()
        defs.forEach { def -> if (state in def.states) map[ymd(def.date)] = def.name }
        cache[key] = map
        return map
    }

    /** All date-strings of a year, in order. */
    fun yearDates(year: Int): List<String> {
        val out = ArrayList<String>(366)
        for (m in 0 until 12) {
            val n = daysInMonth(year, m)
            for (d in 1..n) out.add("$year-${pad(m + 1)}-${pad(d)}")
        }
        return out
    }

    val MON_FULL = listOf(
        "Januar", "Februar", "März", "April", "Mai", "Juni",
        "Juli", "August", "September", "Oktober", "November", "Dezember",
    )
    val MON_ABBR = listOf("Jan", "Feb", "Mär", "Apr", "Mai", "Jun", "Jul", "Aug", "Sep", "Okt", "Nov", "Dez")
    val WD_MIN = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So") // ISO order

    /** "Do, 11. Juni 2026" — short-weekday sheet title for a date string. */
    fun dayTitle(ds: String): String {
        val d = parse(ds)
        return "${WD_MIN[d.dayOfWeek.value - 1]}, ${d.dayOfMonth}. ${MON_FULL[d.monthValue - 1]} ${d.year}"
    }

    /** "31.3." — day.month. for carry-over expiry chips. */
    fun ddmm(ds: String?): String {
        if (ds.isNullOrBlank()) return ""
        val d = parse(ds)
        return "${d.dayOfMonth}.${d.monthValue}."
    }
}
