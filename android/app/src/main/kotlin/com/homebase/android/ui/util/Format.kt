package com.homebase.android.ui.util

import androidx.compose.ui.graphics.Color
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
 * Shared, locale-aware (German) formatting helpers used across the mobile screens.
 * Centralised so date/duration/relative-time rendering is consistent everywhere and
 * mirrors the desktop copy described in docs/android/README.md.
 */
object Format {

    private val DE = Locale.GERMAN
    private val zone: ZoneId get() = ZoneId.systemDefault()

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

    /** "Mittwoch, 3. Juni" — the dashboard eyebrow for [date] (defaults to today). */
    fun longWeekdayDate(date: LocalDate = LocalDate.now()): String {
        val weekday = date.dayOfWeek.getDisplayName(TextStyle.FULL, DE)
        val month = date.month.getDisplayName(TextStyle.FULL, DE)
        return "$weekday, ${date.dayOfMonth}. $month"
    }

    /** "3. Juni" (current year) or "3. Juni 2025" (other years). */
    fun shortDate(date: LocalDate): String {
        val month = date.month.getDisplayName(TextStyle.FULL, DE)
        val base = "${date.dayOfMonth}. $month"
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

    fun relativeTime(iso: String?, now: Instant = Instant.now()): String {
        val then = parseInstant(iso) ?: return ""
        val secs = ChronoUnit.SECONDS.between(then, now)
        if (secs < 0) return "gerade eben"
        val mins = secs / 60
        val hours = mins / 60
        val days = ChronoUnit.DAYS.between(then.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate())
        return when {
            secs < 60 -> "gerade eben"
            mins < 60 -> "vor $mins Min"
            hours < 24 && days == 0L -> "vor $hours Std"
            days == 1L -> "gestern"
            days < 7 -> "vor $days Tagen"
            else -> shortDate(then.atZone(zone).toLocalDate())
        }
    }

    // -----------------------------------------------------------------------
    // Greeting (time-of-day aware)
    // -----------------------------------------------------------------------

    /** "Guten Morgen" / "Guten Tag" / "Guten Abend" based on the local hour. */
    fun greeting(now: LocalTime = LocalTime.now()): String = when (now.hour) {
        in 5..10 -> "Guten Morgen"
        in 11..17 -> "Guten Tag"
        else -> "Guten Abend"
    }

    // -----------------------------------------------------------------------
    // Durations
    // -----------------------------------------------------------------------

    /** "01:35:08" running-clock format. */
    fun clock(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    /** "2 Std 45 Min" / "45 Min" / "12 Sek" — human duration. */
    fun durationLong(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        return when {
            h > 0 && m > 0 -> "$h Std $m Min"
            h > 0 -> "$h Std"
            m > 0 -> "$m Min"
            else -> "${s} Sek"
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
    fun etaShortLabel(expectedEndAt: String?, now: Instant = Instant.now()): String? {
        val end = parseInstant(expectedEndAt) ?: return null
        return if (end.isAfter(now)) "bis ca. ${clockOfDay(expectedEndAt)}" else "Soll erreicht"
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

    /** Day bucket label for grouping entries: "Heute" / "Gestern" / "Mittwoch, 3. Juni". */
    fun dayGroupLabel(iso: String?): String {
        val date = parseInstant(iso)?.atZone(zone)?.toLocalDate() ?: return "Ohne Datum"
        return when (ChronoUnit.DAYS.between(date, LocalDate.now())) {
            0L -> "Heute"
            1L -> "Gestern"
            else -> longWeekdayDate(date)
        }
    }

    // -----------------------------------------------------------------------
    // Due dates (tasks)
    // -----------------------------------------------------------------------

    enum class DueGroup(val label: String, val order: Int) {
        UEBERFAELLIG("Überfällig", 0),
        HEUTE("Heute", 1),
        DEMNAECHST("Demnächst", 2),
        SPAETER("Später", 3),
        OHNE_DATUM("Ohne Datum", 4),
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

    data class DueBadge(val label: String, val tone: HbTone)

    /** A short due-date badge ("Heute", "Morgen", "Freitag", "vor 2 Tagen", "3. Juni"). */
    fun dueBadge(dueDate: String?, today: LocalDate = LocalDate.now()): DueBadge? {
        val d = parseLocalDate(dueDate) ?: return null
        val diff = ChronoUnit.DAYS.between(today, d)
        return when {
            diff < -1 -> DueBadge("vor ${abs(diff)} Tagen", HbTone.Over)
            diff == -1L -> DueBadge("Gestern", HbTone.Over)
            diff == 0L -> DueBadge("Heute", HbTone.Today)
            diff == 1L -> DueBadge("Morgen", HbTone.Soon)
            diff < 7 -> DueBadge(d.dayOfWeek.getDisplayName(TextStyle.FULL, DE), HbTone.Soon)
            else -> DueBadge(shortDate(d), HbTone.Far)
        }
    }

    /** "Heute · 3. Juni" used in the task edit sheet's due field. */
    fun dueFieldLabel(dueDate: String?): String? {
        val d = parseLocalDate(dueDate) ?: return null
        val badge = dueBadge(dueDate) ?: return shortDate(d)
        return "${badge.label} · ${shortDate(d)}"
    }

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
     * German category labels. DINNER shows as "Hauptgerichte"; LUNCH is kept only as a tolerant
     * alias so any legacy row not yet collapsed by backend migration V17 still reads nicely instead
     * of showing the raw enum — it is no longer an offered category.
     */
    fun recipeCategoryLabel(category: String): String = when (category.uppercase()) {
        "BREAKFAST" -> "Frühstück"
        "LUNCH", "DINNER" -> "Hauptgerichte"
        "SNACK" -> "Snack"
        "DESSERT" -> "Dessert"
        "DRINK" -> "Getränk"
        else -> category
    }

    // -----------------------------------------------------------------------
    // Colors
    // -----------------------------------------------------------------------

    /** Parse a "#rrggbb" project color, falling back to a neutral grey. */
    fun parseColor(hex: String): Color = runCatching {
        Color(android.graphics.Color.parseColor(hex))
    }.getOrDefault(Color(0xFF9A9A9A))
}
