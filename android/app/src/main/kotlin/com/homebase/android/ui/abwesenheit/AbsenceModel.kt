package com.homebase.android.ui.abwesenheit

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.homebase.android.R
import com.homebase.android.data.model.AbsSettingsDto
import com.homebase.android.data.model.AbsenceDto
import com.homebase.android.data.model.AbsenceStateDto
import com.homebase.android.data.model.CustomHolidayDto
import com.homebase.android.data.model.KitaClosureDto
import com.homebase.android.data.model.PartTimeRuleDto
import com.homebase.android.ui.theme.Hb
import com.homebase.android.ui.theme.oklch
import java.util.Locale

/**
 * Absence planner data model, palette and summary math. Ported from the design
 * handoff (mirrors web core.ts). Pure — no Compose state, no I/O. The
 * mobile build is light-theme only, so the palette drops the dark variants.
 */

/**
 * Stored, user-set day types (Feiertag + Teilzeit are derived, never stored). These are stable
 * backend codes only; their user-facing labels live in string resources (`absence_type_*`, DE+EN)
 * and are resolved at the (composable) call sites — the model carries no hardcoded German (#204).
 */
object AbsTypes {
    const val URLAUB = "URLAUB"
    const val KRANK = "KRANK"
    const val KIND_KRANK = "KIND_KRANK"

    /** Maps a stored absence type to its label string-resource id (resolve via `stringResource`). */
    @StringRes
    fun labelRes(type: String): Int = when (type) {
        URLAUB -> R.string.absence_type_urlaub
        KRANK -> R.string.absence_type_krank
        KIND_KRANK -> R.string.absence_type_kind
        else -> R.string.absence_type_work
    }
}

/**
 * Light-only fill palette; urlaub/teilzeit take the person hue, the rest are fixed hues.
 *
 * TODO(#244): the Abwesenheit calendar fills are deliberately a single light palette (chips
 * designed to read on a light grid) and are NOT yet dark-mode-aware — in dark mode the grid keeps
 * these light fills. A proper dark variant needs its own design pass. Kept as plain `oklch(...)`
 * literals (not `Hb.*`) so it stays usable from non-composable code, now that the `Hb.*` colour
 * tokens are `@Composable` getters.
 */
object AbwPalette {
    fun urlaub(hue: Double): Color = oklch(0.70, 0.108, hue)
    val krank: Color = oklch(0.71, 0.13, 27.0)
    val kindKrank: Color = oklch(0.78, 0.125, 62.0)
    val feiertag: Color = oklch(0.82, 0.05, 288.0)
    fun teilzeit(hue: Double): Color = oklch(0.91, 0.034, hue)
    val weekend: Color = oklch(0.925, 0.006, 130.0)
    // Same value the light `Hb.surface` resolves to (was `Hb.surface`; inlined as a literal because
    // that token is now a @Composable getter and this object initialises outside any composition).
    val workday: Color = oklch(0.988, 0.008, 128.0)

    /** Dark ink used for chip glyphs/labels printed on the light fills. */
    val onFill: Color = oklch(0.26, 0.03, 150.0)

    /** Hairline divider drawn between the two halves of a split year-grid cell. */
    val divider: Color = oklch(0.5, 0.0, 0.0, 0.14f)
}

/** A person's resolved state for a single day. */
data class DayState(
    val hue: Double,
    val type: String?,
    val half: String?,
    val holiday: String?,
    // true only for a *half-day* custom holiday (#51): the day shows the holiday marker but is
    // still half a regular work/tracking day. Statutory + full custom holidays are full days.
    val holidayHalf: Boolean,
    val weekend: Boolean,
    val ptOff: Boolean,
)

/** Fill colour for a resolved day-state. */
fun colorFor(st: DayState?): Color {
    if (st == null) return AbwPalette.workday
    if (st.type != null) return when (st.type) {
        AbsTypes.URLAUB -> AbwPalette.urlaub(st.hue)
        AbsTypes.KRANK -> AbwPalette.krank
        AbsTypes.KIND_KRANK -> AbwPalette.kindKrank
        else -> AbwPalette.workday
    }
    if (st.holiday != null) return AbwPalette.feiertag
    if (st.ptOff) return AbwPalette.teilzeit(st.hue)
    if (st.weekend) return AbwPalette.weekend
    return AbwPalette.workday
}

/** Is this user off this weekday under a part-time rule active on [date]? */
fun partTimeOff(rules: List<PartTimeRuleDto>, userId: String, date: java.time.LocalDate, dateStr: String): Boolean {
    val wd = AbwCal.isoDow(date)
    return rules.any { r ->
        r.userId == userId && r.weekday == wd && dateStr >= r.start && (r.end == null || dateStr <= r.end)
    }
}

// A stored avatar-hue override (from the household-visible roster, Teil von #100) wins over
// the derived username-hash hue, so the calendar's person colours match the avatars everywhere.
private fun hueOf(userId: String, override: Int?): Double = Hb.userHue(userId, override)

private fun defaultSettings(userId: String, year: Int): AbsSettingsDto =
    AbsSettingsDto(userId, year, "BE", 30.0, 0.0, "$year-03-31", 15)

/**
 * Effective settings for a user in a given year. Settings are stored per year;
 * for a year without its own row we inherit the *stable* fields (Bundesland, allowance,
 * kind-krank cap) from the nearest year — preferring the closest earlier year, else the
 * closest later one — while resetting the per-year carryover ("Resturlaub") to 0. This
 * mirrors the backend's lazy-create inheritance so the displayed defaults match what a
 * first edit would persist.
 */
fun settingsFor(all: List<AbsSettingsDto>, userId: String, year: Int): AbsSettingsDto {
    val mine = all.filter { it.userId == userId }
    mine.find { it.year == year }?.let { return it }
    if (mine.isEmpty()) return defaultSettings(userId, year)
    val sorted = mine.sortedBy { it.year }
    val base = sorted.lastOrNull { it.year <= year } ?: sorted.first()
    return base.copy(year = year, carryover = 0.0, carryoverExpires = "$year-03-31")
}

/** MM-DD key for a custom holiday (recurring by month+day, year-agnostic). */
private fun mdKey(month: Int, day: Int): String = "${AbwCal.pad(month)}-${AbwCal.pad(day)}"

/** MM-DD slice of a YYYY-MM-DD date string. */
private fun mdOf(dateStr: String): String = dateStr.substring(5)

/** Lookup context built once per (snapshot, year): per-user holidays, absence map, etc. */
data class AbsCtx(
    val year: Int,
    val settings: Map<String, AbsSettingsDto>,
    val holidays: Map<String, Map<String, String>>,
    val absByUser: Map<String, Map<String, AbsenceDto>>,
    val kita: Map<String, KitaClosureDto>,
    // Household-wide custom holidays (#51), keyed by MM-DD so a fixed date matches every year.
    val customHol: Map<String, CustomHolidayDto>,
    val partTime: List<PartTimeRuleDto>,
    val hue: Map<String, Double>,
)

// `avatarHues` (Teil von #100): username → chosen avatar hue (0..359) from the shared roster;
// a present entry overrides the derived person colour. Defaults to empty (everyone automatic),
// which also keeps the existing call sites and unit tests source-compatible.
fun buildContext(
    state: AbsenceStateDto,
    year: Int,
    users: List<String>,
    avatarHues: Map<String, Int> = emptyMap(),
): AbsCtx {
    val settings = HashMap<String, AbsSettingsDto>()
    val holidays = HashMap<String, Map<String, String>>()
    val absByUser = HashMap<String, MutableMap<String, AbsenceDto>>()
    val hue = HashMap<String, Double>()
    users.forEach { uid ->
        val s = settingsFor(state.settings, uid, year)
        settings[uid] = s
        holidays[uid] = AbwCal.holidays(year, s.state)
        absByUser[uid] = HashMap()
        hue[uid] = hueOf(uid, avatarHues[uid])
    }
    state.absences.forEach { a ->
        if (a.date.take(4) != year.toString()) return@forEach
        absByUser.getOrPut(a.userId) { HashMap() }[a.date] = a
    }
    val kita = HashMap<String, KitaClosureDto>()
    state.kitaClosures.forEach { kita[it.date] = it }
    val customHol = HashMap<String, CustomHolidayDto>()
    state.customHolidays.forEach { customHol[mdKey(it.month, it.day)] = it }
    return AbsCtx(year, settings, holidays, absByUser, kita, customHol, state.partTime, hue)
}

/** Resolve a single person's day. */
fun personDay(ctx: AbsCtx, userId: String, dateStr: String): DayState {
    val date = AbwCal.parse(dateStr)
    // ctx.hue already has any avatar override baked in; the fallback is only for a user not in
    // the prebuilt map, where no override is known → derived hue.
    val hue = ctx.hue[userId] ?: hueOf(userId, null)
    val abs = ctx.absByUser[userId]?.get(dateStr)
    // Statutory holiday (per Bundesland, always full-day) wins; otherwise fall back to a
    // household-wide custom holiday matched by month+day (#51), which may be a half day.
    val statutory = ctx.holidays[userId]?.get(dateStr)
    val custom = if (statutory != null) null else ctx.customHol[mdOf(dateStr)]
    return DayState(
        hue = hue,
        type = abs?.type,
        half = abs?.half,
        holiday = statutory ?: custom?.label,
        holidayHalf = custom?.half ?: false,
        weekend = AbwCal.isWeekend(date),
        ptOff = partTimeOff(ctx.partTime, userId, date, dateStr),
    )
}

/** Would this be a working day absent any leave? (used for counting) A *half* custom holiday
 *  (#51) still leaves the other half a regular work day, so it does not disqualify the day here —
 *  only full holidays (statutory + whole-day custom) do. */
fun wouldWork(st: DayState): Boolean = !st.weekend && !(st.holiday != null && !st.holidayHalf) && !st.ptOff

data class AbsSummary(
    val allowance: Double,
    val carry: Double,
    val total: Double,
    val taken: Double,
    val planned: Double,
    val used: Double,
    val remaining: Double,
    val krank: Double,
    val kind: Double,
    val kindCap: Int,
    val state: String,
    val carryExpires: String?,
    val carryExpired: Boolean,
    val carryLost: Double,
)

/** Per-person yearly summary. */
fun summarize(ctx: AbsCtx, userId: String, todayStr: String): AbsSummary {
    val s = ctx.settings[userId] ?: defaultSettings(userId, ctx.year)
    var taken = 0.0
    var planned = 0.0
    var krank = 0.0
    var kind = 0.0
    AbwCal.yearDates(ctx.year).forEach { ds ->
        val st = personDay(ctx, userId, ds)
        val type = st.type ?: return@forEach
        if (!wouldWork(st)) return@forEach // leave on an already-free day doesn't count
        val amt = if (st.half != null) 0.5 else 1.0
        when (type) {
            AbsTypes.URLAUB -> if (ds <= todayStr) taken += amt else planned += amt
            AbsTypes.KRANK -> krank += amt
            AbsTypes.KIND_KRANK -> kind += amt
        }
    }
    val allowance = s.allowance
    val carry = s.carryover
    val total = allowance + carry
    val used = taken + planned
    val remaining = total - used
    val expires = s.carryoverExpires ?: "${ctx.year}-03-31"
    val carryExpired = todayStr > expires
    val carryUsed = minOf(carry, taken)
    val carryLost = if (carryExpired) maxOf(0.0, carry - carryUsed) else 0.0
    return AbsSummary(
        allowance = allowance,
        carry = carry,
        total = total,
        taken = taken,
        planned = planned,
        used = used,
        remaining = remaining,
        krank = krank,
        kind = kind,
        kindCap = s.kindKrankCap,
        state = s.state,
        carryExpires = expires,
        carryExpired = carryExpired,
        carryLost = carryLost,
    )
}

/** Pretty day count: "3", "2,5". */
fun fmtDays(n: Double): String =
    if (n == n.toLong().toDouble()) n.toLong().toString()
    else String.format(Locale.US, "%.1f", n).replace('.', ',')

/** Inclusive list of date-strings from→to (order-normalised). */
fun eachDate(from: String, to: String): List<String> {
    var a = from
    var b = to
    if (a > b) { val t = a; a = b; b = t }
    val out = ArrayList<String>()
    var d = AbwCal.parse(a)
    val end = AbwCal.parse(b)
    while (!d.isAfter(end)) {
        out.add(AbwCal.ymd(d))
        d = d.plusDays(1)
    }
    return out
}

/** Would this date be a working day for this user (not weekend / holiday / part-time-off)?
 *  A whole-day custom holiday (#51) counts as non-working; a half-day one stays workable
 *  (the other half is bookable), so range-booking still applies on it. */
fun isWorkdayFor(state: AbsenceStateDto, userId: String, ds: String): Boolean {
    val date = AbwCal.parse(ds)
    val stateCode = settingsFor(state.settings, userId, date.year).state
    if (AbwCal.isWeekend(date)) return false
    if (AbwCal.holidays(date.year, stateCode).containsKey(ds)) return false
    val custom = state.customHolidays.find { it.month == date.monthValue && it.day == date.dayOfMonth }
    if (custom != null && !custom.half) return false
    if (partTimeOff(state.partTime, userId, date, ds)) return false
    return true
}

