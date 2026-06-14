package com.homebase.android.ui.abwesenheit

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

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

    // [en] is the standard, well-known English name where one exists; null keeps the German name in
    // English mode too (region-specific/obscure feast days with no clean English equivalent — #204).
    private data class HolidayDef(val date: LocalDate, val de: String, val en: String?, val states: List<String>)

    /**
     * → { "YYYY-MM-DD": "Feiertagsname", … } for a given year + Bundesland, in [locale]'s language.
     * Well-known holidays get their standard English name (Karfreitag→Good Friday, …); the rest stay
     * German in both languages (#204). The names are display-only — date lookups elsewhere key on the
     * date, not the name. Cached per year:state:lang; defaults to the active app locale.
     */
    fun holidays(year: Int, state: String, locale: Locale = Locale.getDefault()): Map<String, String> {
        val en = locale.language == Locale.ENGLISH.language
        val key = "$year:$state:${if (en) "en" else "de"}"
        cache[key]?.let { return it }
        val e = easter(year)
        fun off(n: Int) = e.plusDays(n.toLong())
        val defs = listOf(
            HolidayDef(LocalDate.of(year, 1, 1), "Neujahr", "New Year's Day", ALL),
            HolidayDef(LocalDate.of(year, 1, 6), "Heilige Drei Könige", "Epiphany", listOf("BW", "BY", "ST")),
            HolidayDef(LocalDate.of(year, 3, 8), "Internationaler Frauentag", "International Women's Day", listOf("BE", "MV")),
            HolidayDef(off(-2), "Karfreitag", "Good Friday", ALL),
            HolidayDef(off(0), "Ostersonntag", "Easter Sunday", listOf("BB")),
            HolidayDef(off(1), "Ostermontag", "Easter Monday", ALL),
            HolidayDef(LocalDate.of(year, 5, 1), "Tag der Arbeit", "Labour Day", ALL),
            HolidayDef(off(39), "Christi Himmelfahrt", "Ascension Day", ALL),
            HolidayDef(off(49), "Pfingstsonntag", "Whit Sunday", listOf("BB")),
            HolidayDef(off(50), "Pfingstmontag", "Whit Monday", ALL),
            HolidayDef(off(60), "Fronleichnam", "Corpus Christi", listOf("BW", "BY", "HE", "NW", "RP", "SL")),
            HolidayDef(LocalDate.of(year, 8, 15), "Mariä Himmelfahrt", "Assumption Day", listOf("SL", "BY")),
            // Thuringia-specific commemoration — no standard English name, keep German.
            HolidayDef(LocalDate.of(year, 9, 20), "Weltkindertag", null, listOf("TH")),
            HolidayDef(LocalDate.of(year, 10, 3), "Tag der Deutschen Einheit", "German Unity Day", ALL),
            HolidayDef(LocalDate.of(year, 10, 31), "Reformationstag", "Reformation Day", listOf("BB", "HB", "HH", "MV", "NI", "SN", "ST", "SH", "TH")),
            HolidayDef(LocalDate.of(year, 11, 1), "Allerheiligen", "All Saints' Day", listOf("BW", "BY", "NW", "RP", "SL")),
            // Saxony-specific Protestant day of repentance — no common English name, keep German.
            HolidayDef(bussBettag(year), "Buß- und Bettag", null, listOf("SN")),
            HolidayDef(LocalDate.of(year, 12, 25), "1. Weihnachtstag", "Christmas Day", ALL),
            HolidayDef(LocalDate.of(year, 12, 26), "2. Weihnachtstag", "Boxing Day", ALL),
        )
        val map = LinkedHashMap<String, String>()
        defs.forEach { def -> if (state in def.states) map[ymd(def.date)] = if (en) def.en ?: def.de else def.de }
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

    // Month/weekday names follow the active app locale (#204): German base, English when switched.
    // The grid renders these by index, so they're exposed as 0-based / ISO-ordered lists built from
    // java.time display names for the given (default) locale.

    /** Full month names, January-first (index 0 = January), in [locale]. */
    fun monFull(locale: Locale = Locale.getDefault()): List<String> =
        Month.entries.map { it.getDisplayName(TextStyle.FULL, locale) }

    /** Abbreviated month names, January-first, in [locale]. */
    fun monAbbr(locale: Locale = Locale.getDefault()): List<String> =
        Month.entries.map { it.getDisplayName(TextStyle.SHORT, locale) }

    /** Short weekday names in ISO order (index 0 = Monday), in [locale]. */
    fun wdMin(locale: Locale = Locale.getDefault()): List<String> =
        DayOfWeek.entries.map { it.getDisplayName(TextStyle.SHORT, locale) }

    /** Full weekday names in ISO order (index 0 = Monday), in [locale]. */
    fun wdLong(locale: Locale = Locale.getDefault()): List<String> =
        DayOfWeek.entries.map { it.getDisplayName(TextStyle.FULL, locale) }

    /** "Do, 11. Juni 2026" / "Thu, 11 June 2026" — short-weekday sheet title for a date string. */
    fun dayTitle(ds: String, locale: Locale = Locale.getDefault()): String {
        val d = parse(ds)
        val wd = d.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
        val mon = d.month.getDisplayName(TextStyle.FULL, locale)
        val en = locale.language == Locale.ENGLISH.language
        return if (en) "$wd, ${d.dayOfMonth} $mon ${d.year}" else "$wd, ${d.dayOfMonth}. $mon ${d.year}"
    }

    /** "31.3." — day.month. for carry-over expiry chips. */
    fun ddmm(ds: String?): String {
        if (ds.isNullOrBlank()) return ""
        val d = parse(ds)
        return "${d.dayOfMonth}.${d.monthValue}."
    }
}
