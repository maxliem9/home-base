package com.homebase.android.ui.util

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.homebase.android.R
import com.homebase.android.ui.components.HbTone
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Shared, locale-aware formatting helpers used across the mobile screens. Centralised so
 * date/duration/relative-time rendering is consistent everywhere and mirrors the desktop copy.
 *
 * These are Compose-free utilities (called from both composables and unit tests), so they take
 * the active [Locale] rather than a `Context`/`stringResource`. It defaults to
 * [Locale.getDefault], which AppCompat's per-app locale switch keeps in sync with the in-app
 * de/en toggle (`AppCompatDelegate.setApplicationLocales` updates both the resource Configuration
 * and the default JVM locale) — so absolute month/weekday names and the fixed vocabulary below
 * follow the chosen language. Tests pass an explicit locale to pin the expected output (#204).
 */
object Format {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** True when [loc] is English; drives the fixed-vocabulary strings (everything not a date name). */
    private fun isEn(loc: Locale): Boolean = loc.language == Locale.ENGLISH.language

    // -----------------------------------------------------------------------
    // Parsing
    // -----------------------------------------------------------------------

    /** Parse an instant from an ISO-8601 string (with or without offset). */
    fun parseInstant(iso: String?): Instant? {
        if (iso.isNullOrBlank()) return null
        return runCatching { Instant.parse(iso) }
            .recoverCatching { OffsetDateTime.parse(iso).toInstant() }
            .getOrNull()
    }

    /** Parse a due-date which may be a plain date ("2026-06-04") or a full timestamp. */
    fun parseLocalDate(value: String?): LocalDate? {
        if (value.isNullOrBlank()) return null
        return runCatching { LocalDate.parse(value) }
            .recoverCatching { parseInstant(value)!!.atZone(zone).toLocalDate() }
            .getOrNull()
    }

    // -----------------------------------------------------------------------
    // Absolute date formatting
    // -----------------------------------------------------------------------

    /** "Mittwoch, 3. Juni" / "Wednesday, 3 June" — the dashboard eyebrow for [date]. */
    fun longWeekdayDate(date: LocalDate = LocalDate.now(), locale: Locale = Locale.getDefault()): String {
        val weekday = date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        val month = date.month.getDisplayName(TextStyle.FULL, locale)
        return if (isEn(locale)) "$weekday, ${date.dayOfMonth} $month"
        else "$weekday, ${date.dayOfMonth}. $month"
    }

    /** "3. Juni" / "3 June" (current year) or with the year appended (other years). */
    fun shortDate(date: LocalDate, locale: Locale = Locale.getDefault()): String {
        val month = date.month.getDisplayName(TextStyle.FULL, locale)
        val base = if (isEn(locale)) "${date.dayOfMonth} $month" else "${date.dayOfMonth}. $month"
        return if (date.year == LocalDate.now().year) base else "$base ${date.year}"
    }

    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    /** "14:30" for an ISO instant, local time. */
    fun clockOfDay(iso: String?): String {
        val i = parseInstant(iso) ?: return "–"
        return HHMM.format(i)
    }

    // -----------------------------------------------------------------------
    // Relative time ("vor 4 Std")
    // -----------------------------------------------------------------------

    fun relativeTime(iso: String?, now: Instant = Instant.now(), locale: Locale = Locale.getDefault()): String {
        val then = parseInstant(iso) ?: return ""
        val secs = ChronoUnit.SECONDS.between(then, now)
        val en = isEn(locale)
        if (secs < 0) return if (en) "just now" else "gerade eben"
        val mins = secs / 60
        val hours = mins / 60
        val days = ChronoUnit.DAYS.between(then.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate())
        return when {
            secs < 60 -> if (en) "just now" else "gerade eben"
            mins < 60 -> if (en) "${mins}m ago" else "vor $mins Min"
            hours < 24 && days == 0L -> if (en) "${hours}h ago" else "vor $hours Std"
            days == 1L -> if (en) "yesterday" else "gestern"
            days < 7 -> if (en) "${days}d ago" else "vor $days Tagen"
            else -> shortDate(then.atZone(zone).toLocalDate(), locale)
        }
    }

    // -----------------------------------------------------------------------
    // Greeting (time-of-day aware)
    // -----------------------------------------------------------------------

    /** "Guten Morgen" / "Good morning" … based on the local hour. */
    fun greeting(now: LocalTime = LocalTime.now(), locale: Locale = Locale.getDefault()): String {
        val en = isEn(locale)
        return when (now.hour) {
            in 5..10 -> if (en) "Good morning" else "Guten Morgen"
            in 11..17 -> if (en) "Good afternoon" else "Guten Tag"
            else -> if (en) "Good evening" else "Guten Abend"
        }
    }

    // -----------------------------------------------------------------------
    // Durations
    // -----------------------------------------------------------------------

    /** "01:35:08" running-clock format. */
    fun clock(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    /** "2 Std 45 Min" / "2h 45m" … — human duration. */
    fun durationLong(totalSeconds: Long, locale: Locale = Locale.getDefault()): String {
        val s = totalSeconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val en = isEn(locale)
        return when {
            h > 0 && m > 0 -> if (en) "${h}h ${m}m" else "$h Std $m Min"
            h > 0 -> if (en) "${h}h" else "$h Std"
            m > 0 -> if (en) "${m}m" else "$m Min"
            else -> if (en) "${s}s" else "${s} Sek"
        }
    }

    /**
     * Compact "h:mm" for Wochensoll Soll/Ist figures (e.g. "38:00", "7:30") — mirrors
     * the web's hm() incl. rounding to the nearest minute; negative input is clamped.
     */
    fun hoursMinutes(totalSeconds: Long): String {
        val totalMin = (totalSeconds.coerceAtLeast(0) + 30) / 60
        return "%d:%02d".format(totalMin / 60, totalMin % 60)
    }

    /**
     * Compact forecast suffix at a running timer (#31/#55): "bis ca. 16:32", or
     * "Soll erreicht" once the projected end has passed. Null without a forecast.
     */
    fun etaShortLabel(expectedEndAt: String?, now: Instant = Instant.now(), locale: Locale = Locale.getDefault()): String? {
        val end = parseInstant(expectedEndAt) ?: return null
        val en = isEn(locale)
        return if (end.isAfter(now)) (if (en) "until ~${clockOfDay(expectedEndAt)}" else "bis ca. ${clockOfDay(expectedEndAt)}")
        else (if (en) "Target reached" else "Soll erreicht")
    }

    /** Live elapsed seconds since [startedAtIso]. */
    fun elapsedSeconds(startedAtIso: String?, now: Instant = Instant.now()): Long {
        val start = parseInstant(startedAtIso) ?: return 0L
        return ChronoUnit.SECONDS.between(start, now).coerceAtLeast(0)
    }

    /** Duration of a (possibly running) entry in seconds. */
    fun entrySeconds(startedAtIso: String?, stoppedAtIso: String?, now: Instant = Instant.now()): Long {
        val start = parseInstant(startedAtIso) ?: return 0L
        val end = parseInstant(stoppedAtIso) ?: now
        return ChronoUnit.SECONDS.between(start, end).coerceAtLeast(0)
    }

    /** Build (startIso, stopIso) UTC strings from local date/time inputs, or null if invalid. */
    fun buildEntryTimestamps(date: String, start: String, end: String): Pair<String, String>? = runCatching {
        val d = LocalDate.parse(date)
        val s = LocalTime.parse(start)
        val e = LocalTime.parse(end)
        val startInstant = d.atTime(s).atZone(zone).toInstant()
        val stopInstant = d.atTime(e).atZone(zone).toInstant()
        if (!stopInstant.isAfter(startInstant)) return null
        startInstant.toString() to stopInstant.toString()
    }.getOrNull()

    /** Day bucket label for grouping entries: "Heute" / "Today" / weekday-date. */
    fun dayGroupLabel(iso: String?, locale: Locale = Locale.getDefault()): String {
        val en = isEn(locale)
        val date = parseInstant(iso)?.atZone(zone)?.toLocalDate() ?: return if (en) "No date" else "Ohne Datum"
        return when (ChronoUnit.DAYS.between(date, LocalDate.now())) {
            0L -> if (en) "Today" else "Heute"
            1L -> if (en) "Yesterday" else "Gestern"
            else -> longWeekdayDate(date, locale)
        }
    }

    // -----------------------------------------------------------------------
    // Due dates (tasks)
    // -----------------------------------------------------------------------

    /** A task due-date bucket. [labelRes] resolves the localized header via `stringResource`. */
    enum class DueGroup(@StringRes val labelRes: Int, val order: Int) {
        UEBERFAELLIG(R.string.due_group_overdue, 0),
        HEUTE(R.string.due_group_today, 1),
        DEMNAECHST(R.string.due_group_soon, 2),
        SPAETER(R.string.due_group_later, 3),
        OHNE_DATUM(R.string.due_group_no_date, 4),
    }

    fun dueGroup(dueDate: String?, today: LocalDate = LocalDate.now()): DueGroup {
        val d = parseLocalDate(dueDate) ?: return DueGroup.OHNE_DATUM
        val diff = ChronoUnit.DAYS.between(today, d)
        return when {
            diff < 0 -> DueGroup.UEBERFAELLIG
            diff == 0L -> DueGroup.HEUTE
            diff < 7 -> DueGroup.DEMNAECHST
            else -> DueGroup.SPAETER
        }
    }

    /**
     * Sekundär-Sortierschlüssel innerhalb einer Fälligkeitsgruppe: höhere Priorität
     * zuerst, keine Priorität zuletzt. Synchron zum Web-Client halten (PRIORITY_RANK
     * in TodosView.tsx).
     */
    fun prioRank(priority: String?): Int = when (priority) {
        "HIGH" -> 0
        "MEDIUM" -> 1
        "LOW" -> 2
        else -> 3
    }

    data class DueBadge(val label: String, val tone: HbTone)

    /** A short due-date badge ("Heute"/"Today", "Morgen"/"Tomorrow", weekday, "vor 2 Tagen", date). */
    fun dueBadge(dueDate: String?, today: LocalDate = LocalDate.now(), locale: Locale = Locale.getDefault()): DueBadge? {
        val d = parseLocalDate(dueDate) ?: return null
        val diff = ChronoUnit.DAYS.between(today, d)
        val en = isEn(locale)
        return when {
            diff < -1 -> DueBadge(if (en) "${abs(diff)}d ago" else "vor ${abs(diff)} Tagen", HbTone.Over)
            diff == -1L -> DueBadge(if (en) "Yesterday" else "Gestern", HbTone.Over)
            diff == 0L -> DueBadge(if (en) "Today" else "Heute", HbTone.Today)
            diff == 1L -> DueBadge(if (en) "Tomorrow" else "Morgen", HbTone.Soon)
            diff < 7 -> DueBadge(d.dayOfWeek.getDisplayName(TextStyle.FULL, locale), HbTone.Soon)
            else -> DueBadge(shortDate(d, locale), HbTone.Far)
        }
    }

    /** "Heute · 3. Juni" / "Today · 3 June" used in the task edit sheet's due field. */
    fun dueFieldLabel(dueDate: String?, locale: Locale = Locale.getDefault()): String? {
        val d = parseLocalDate(dueDate) ?: return null
        val badge = dueBadge(dueDate, locale = locale) ?: return shortDate(d, locale)
        return "${badge.label} · ${shortDate(d, locale)}"
    }

    /** Parse a stored due time ("HH:mm" or "HH:mm:ss") to a LocalTime, or null (#429). */
    fun parseLocalTime(value: String?): LocalTime? {
        if (value.isNullOrBlank()) return null
        return runCatching { LocalTime.parse(value.trim()) }.getOrNull()
    }

    /** "HH:mm" (zero-padded, 24h) for a LocalTime — the single source of truth for due-time display. */
    fun hhmm(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

    /** "HH:mm" from a stored due time, or null when absent/malformed (#429). */
    fun dueTimeShort(dueTime: String?): String? = parseLocalTime(dueTime)?.let { hhmm(it) }

    // -----------------------------------------------------------------------
    // Numbers / amounts
    // -----------------------------------------------------------------------

    /** "1,5" style amount — drops a trailing ".0" and uses up to 2 decimals. */
    fun amount(value: Double): String {
        val rounded = (value * 100.0).roundToLong() / 100.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString()
        else rounded.toString()
    }

    // -----------------------------------------------------------------------
    // Recipes
    // -----------------------------------------------------------------------

    /** Deterministic warm hue (20–95°) per recipe, driving the striped placeholder band. */
    fun recipeHue(id: String): Double = 20.0 + (abs(id.hashCode().toLong()) % 76L).toDouble()

    /**
     * Stable backend enum → localized-label resource (#207), or null for an unknown code. The label
     * is resolved separately from the enum so the category chips/filter stay language-independent:
     * the UI filters on the enum and renders `stringResource(recipeCategoryLabelRes(cat) ?: …)`,
     * falling back to the raw code. DINNER and the legacy LUNCH alias both show as "Hauptgerichte"/
     * "Mains" so a row not yet collapsed by backend migration V17 still reads nicely.
     */
    @StringRes
    fun recipeCategoryLabelRes(category: String): Int? = when (category.uppercase()) {
        "BREAKFAST" -> R.string.recipe_cat_breakfast
        "LUNCH", "DINNER" -> R.string.recipe_cat_main
        "SNACK" -> R.string.recipe_cat_snack
        "DESSERT" -> R.string.recipe_cat_dessert
        "DRINK" -> R.string.recipe_cat_drink
        else -> null
    }

    // -----------------------------------------------------------------------
    // Colors
    // -----------------------------------------------------------------------

    /** Parse a "#rrggbb" project color, falling back to a neutral grey. */
    fun parseColor(hex: String): Color = runCatching {
        Color(android.graphics.Color.parseColor(hex))
    }.getOrDefault(Color(0xFF9A9A9A))
}
