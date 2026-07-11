package com.homebase.routes

import com.homebase.db.dbQuery
import com.homebase.db.AppSettingsTable
import com.homebase.db.UserPrefsTable
import com.homebase.digest.DigestSection
import com.homebase.model.AppConfigResponse
import com.homebase.model.CalendarFeedConfigResponse
import com.homebase.model.DigestConfigResponse
import com.homebase.model.DoneWindowConfigResponse
import com.homebase.model.ErrorResponse
import com.homebase.model.RecurringConfigResponse
import com.homebase.model.RemindersConfigResponse
import com.homebase.model.UpdateCalendarFeedRequest
import com.homebase.model.UpdateConfigRequest
import com.homebase.model.UpdateDigestRequest
import com.homebase.model.UpdateDoneWindowRequest
import com.homebase.model.UpdateRecurringRequest
import com.homebase.model.UpdateRemindersRequest
import com.homebase.reminder.ReminderLogic
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private const val HOUSEHOLD_NAME_KEY = "household_name"
private const val HOUSEHOLD_NAME_MAX = 60
private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

// "Erledigt"-history window (#356). Default mirrors the clients' historical constant; the
// bounds keep the value sane (≥ 1 day, ≤ ~10 years) so a typo can't produce an absurd window.
const val DONE_WINDOW_DEFAULT_DAYS = 14
private const val DONE_WINDOW_MIN_DAYS = 1
private const val DONE_WINDOW_MAX_DAYS = 3650

/**
 * App-level config, shared by both users (#100; not owner-restricted, like the shared
 * calendar). Values live in the generic `app_settings` key/value table and fall back
 * to the configured (conf) defaults when unset, so a fresh DB behaves exactly as before.
 * All endpoints sit under auth-jwt (see configureRouting).
 *
 * - household name — the sidebar brand ([defaultHouseholdName] fallback).
 * - digest config — the evening recap and morning briefing each expose {time, enabled, sections}
 *   plus the read-only telegramConfigured flag (#100/#182). The scheduler re-reads all of these
 *   each cycle, so a change applies from the next run. The on/off toggle + section selection are
 *   in-app; `telegramConfigured` ([telegramEnabled]) only tells the UI whether anything will
 *   actually send. Defaults: enabled on, all sections selected (back-compat with the full digest).
 * - recurring time — when the recurring-todo safety-net runs ([recurringDefaultTime] fallback).
 *   Same time shape, but always-on with no sections, so a plain {time}.
 */
fun Route.configRoutes(
    defaultHouseholdName: String,
    digestDefaultTime: String,
    telegramEnabled: Boolean,
    recurringDefaultTime: String,
    morningDigestDefaultTime: String,
) {
    get("/config") {
        call.respond(AppConfigResponse(householdName = readSetting(HOUSEHOLD_NAME_KEY) ?: defaultHouseholdName))
    }

    put("/config") {
        val name = call.receive<UpdateConfigRequest>().householdName.trim()
        if (name.isEmpty() || name.length > HOUSEHOLD_NAME_MAX) {
            return@put call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("INVALID_NAME", "householdName must be 1..$HOUSEHOLD_NAME_MAX characters"),
            )
        }
        upsertSetting(HOUSEHOLD_NAME_KEY, name)
        call.respond(AppConfigResponse(householdName = name))
    }

    // Evening recap and morning briefing share one {time, enabled, sections} contract (#100/#182),
    // differing only in their app_settings keys + default time + which sections they offer.
    digestConfigRoutes(
        path = "/config/digest",
        timeKey = AppSettingsTable.DIGEST_TIME,
        enabledKey = AppSettingsTable.DIGEST_EVENING_ENABLED,
        sectionsKey = AppSettingsTable.DIGEST_EVENING_SECTIONS,
        defaultTime = digestDefaultTime,
        allowedSections = DigestSection.evening,
        telegramConfigured = telegramEnabled,
    )
    digestConfigRoutes(
        path = "/config/morning-digest",
        timeKey = AppSettingsTable.MORNING_DIGEST_TIME,
        enabledKey = AppSettingsTable.DIGEST_MORNING_ENABLED,
        sectionsKey = AppSettingsTable.DIGEST_MORNING_SECTIONS,
        defaultTime = morningDigestDefaultTime,
        allowedSections = DigestSection.morning,
        telegramConfigured = telegramEnabled,
    )

    get("/config/recurring") {
        call.respond(
            RecurringConfigResponse(time = readSetting(AppSettingsTable.RECURRING_TIME) ?: recurringDefaultTime),
        )
    }

    put("/config/recurring") {
        val parsed = runCatching { LocalTime.parse(call.receive<UpdateRecurringRequest>().time.trim()) }.getOrNull()
            ?: return@put call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("INVALID_TIME", "time must be a valid HH:mm"),
            )
        val normalized = parsed.format(HH_MM)
        upsertSetting(AppSettingsTable.RECURRING_TIME, normalized)
        call.respond(RecurringConfigResponse(time = normalized))
    }

    // Todo reminders (#429 Phase 2a): on/off + an optional quiet-hours window, read each tick by
    // the reminder scheduler. An unset enabled key means on (like the digests).
    get("/config/reminders") {
        call.respond(currentRemindersConfig())
    }

    put("/config/reminders") {
        val req = call.receive<UpdateRemindersRequest>()
        // quiet hours: accept both bounds or neither (a single bound is meaningless). Blank clears.
        val start = req.quietStart?.trim()?.takeIf { it.isNotEmpty() }
        val end = req.quietEnd?.trim()?.takeIf { it.isNotEmpty() }
        if ((start == null) != (end == null)) {
            return@put call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("INVALID_QUIET_HOURS", "quietStart and quietEnd must be set together (or both empty)"),
            )
        }
        val normStart = start?.let { runCatching { LocalTime.parse(it) }.getOrNull()?.format(HH_MM) }
        val normEnd = end?.let { runCatching { LocalTime.parse(it) }.getOrNull()?.format(HH_MM) }
        if ((start != null && normStart == null) || (end != null && normEnd == null)) {
            return@put call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("INVALID_TIME", "quiet hours must be valid HH:mm"),
            )
        }
        // A quiet window at/above the scheduler's catch-up horizon would silently swallow a reminder
        // that came due right at its start (it'd be retired as stale before the window ends), so cap
        // the span below CATCHUP. A >12h quiet window isn't a real household use case anyway.
        if (normStart != null && normEnd != null) {
            val s = LocalTime.parse(normStart)
            val e = LocalTime.parse(normEnd)
            val spanMin = if (s.isBefore(e)) Duration.between(s, e).toMinutes()
            else Duration.ofHours(24).toMinutes() - Duration.between(e, s).toMinutes()
            if (spanMin >= ReminderLogic.CATCHUP.toMinutes()) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("INVALID_QUIET_HOURS", "quiet hours must be shorter than ${ReminderLogic.CATCHUP.toHours()} hours"),
                )
            }
        }
        upsertSetting(AppSettingsTable.REMINDERS_ENABLED, req.enabled.toString())
        upsertSetting(AppSettingsTable.REMINDER_QUIET_START, normStart ?: "")
        upsertSetting(AppSettingsTable.REMINDER_QUIET_END, normEnd ?: "")
        call.respond(currentRemindersConfig())
    }

    // "Erledigt"-history window length in days (#356). Single-value config like the recurring
    // time; the clients read it each load (falling back to the default when unset) and apply it
    // to the Erledigt tab / done-section. A malformed/out-of-range stored value falls back to the
    // default on read, so a bad row can never make the window unusable.
    get("/config/done-window") {
        call.respond(DoneWindowConfigResponse(days = readDoneWindowDays()))
    }

    put("/config/done-window") {
        val days = call.receive<UpdateDoneWindowRequest>().days
        if (days !in DONE_WINDOW_MIN_DAYS..DONE_WINDOW_MAX_DAYS) {
            return@put call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("INVALID_DAYS", "days must be between $DONE_WINDOW_MIN_DAYS and $DONE_WINDOW_MAX_DAYS"),
            )
        }
        upsertSetting(AppSettingsTable.DONE_WINDOW_DAYS, days.toString())
        call.respond(DoneWindowConfigResponse(days = days))
    }

    // Which categories the caller's iCal subscription feed includes (#427). PER USER (unlike the
    // shared app_settings config above) — the selection is keyed by the authenticated caller, so
    // each subscriber tailors their own feed. Unset = all categories (back-compat). GET returns the
    // current selection + the full available list; PUT replaces the selection (validated subset).
    get("/config/calendar-feed") {
        call.respond(currentCalendarFeedConfig(call.username()))
    }

    put("/config/calendar-feed") {
        val ids = call.receive<UpdateCalendarFeedRequest>().sections.map { it.trim() }
        val allowedIds = CalendarFeedSection.all.map { it.id }.toSet()
        if (ids.any { it !in allowedIds }) {
            return@put call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("INVALID_SECTION", "sections must be a subset of $allowedIds"),
            )
        }
        // De-dupe while keeping the canonical display order, so the stored value is stable.
        val csv = CalendarFeedSection.all.filter { it.id in ids.toSet() }.joinToString(",") { it.id }
        upsertUserPref(call.username(), UserPrefsTable.CALENDAR_FEED_SECTIONS, csv)
        call.respond(currentCalendarFeedConfig(call.username()))
    }
}

/** One user's current iCal-feed category selection (unset = all) + the full available list (#427). */
private suspend fun currentCalendarFeedConfig(username: String): CalendarFeedConfigResponse {
    val selected = CalendarFeedSection.parseSelection(
        readUserPref(username, UserPrefsTable.CALENDAR_FEED_SECTIONS),
    )
    return CalendarFeedConfigResponse(
        sections = CalendarFeedSection.all.filter { it in selected }.map { it.id },
        availableSections = CalendarFeedSection.all.map { it.id },
    )
}

/** Reads one (user, key) pref value, or null if unset. */
private suspend fun readUserPref(username: String, prefKey: String): String? = dbQuery {
    UserPrefsTable.selectAll()
        .where { (UserPrefsTable.userId eq username) and (UserPrefsTable.key eq prefKey) }
        .singleOrNull()?.get(UserPrefsTable.value)
}

/** Upserts one (user, key) pref (update-then-insert; the composite PK guards duplicates). */
private suspend fun upsertUserPref(username: String, prefKey: String, prefValue: String) {
    dbQuery {
        val updated = UserPrefsTable.update({
            (UserPrefsTable.userId eq username) and (UserPrefsTable.key eq prefKey)
        }) { it[value] = prefValue }
        if (updated == 0) UserPrefsTable.insert {
            it[userId] = username
            it[key] = prefKey
            it[value] = prefValue
        }
    }
}

/**
 * Reads the configured "Erledigt"-window length, defaulting to [DONE_WINDOW_DEFAULT_DAYS] when
 * unset and clamping a stored value into the valid range (defensive against a hand-edited row).
 */
private suspend fun readDoneWindowDays(): Int {
    val stored = readSetting(AppSettingsTable.DONE_WINDOW_DAYS)?.toIntOrNull() ?: return DONE_WINDOW_DEFAULT_DAYS
    return stored.coerceIn(DONE_WINDOW_MIN_DAYS, DONE_WINDOW_MAX_DAYS)
}

/** Current reminders config: enabled (unset = on) + the optional quiet-hours window (#429). */
private suspend fun currentRemindersConfig(): RemindersConfigResponse {
    val enabled = readSetting(AppSettingsTable.REMINDERS_ENABLED)?.equals("false", ignoreCase = true) != true
    val start = readSetting(AppSettingsTable.REMINDER_QUIET_START)?.takeIf { it.isNotBlank() }
    val end = readSetting(AppSettingsTable.REMINDER_QUIET_END)?.takeIf { it.isNotBlank() }
    return RemindersConfigResponse(enabled = enabled, quietStart = start, quietEnd = end)
}

/**
 * GET/PUT for one digest's {time, enabled, sections} config (#182), shared by the evening recap
 * and morning briefing. GET reads each value (falling back to enabled-on + all-sections so an
 * untouched DB keeps the full digest); PUT patches only the fields present in the body (time-only
 * still works) and validates the time + section ids, leaving unset fields unchanged.
 */
private fun Route.digestConfigRoutes(
    path: String,
    timeKey: String,
    enabledKey: String,
    sectionsKey: String,
    defaultTime: String,
    allowedSections: List<DigestSection>,
    telegramConfigured: Boolean,
) {
    suspend fun currentResponse() = DigestConfigResponse(
        time = readSetting(timeKey) ?: defaultTime,
        // Unset = on (default); only an explicit "false" disables.
        enabled = readSetting(enabledKey)?.equals("false", ignoreCase = true) != true,
        telegramConfigured = telegramConfigured,
        sections = DigestSection.parseSelection(readSetting(sectionsKey), allowedSections).map { it.id },
        availableSections = allowedSections.map { it.id },
    )

    get(path) { call.respond(currentResponse()) }

    put(path) {
        val body = call.receive<UpdateDigestRequest>()

        // Validate everything before writing anything (a bad field rejects the whole PUT).
        val normalizedTime = body.time?.trim()?.let { raw ->
            runCatching { LocalTime.parse(raw) }.getOrNull()?.format(HH_MM)
                ?: return@put call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("INVALID_TIME", "time must be a valid HH:mm"),
                )
        }
        val allowedIds = allowedSections.map { it.id }.toSet()
        val sectionsCsv = body.sections?.let { ids ->
            val cleaned = ids.map { it.trim() }
            if (cleaned.any { it !in allowedIds }) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("INVALID_SECTION", "sections must be a subset of $allowedIds"),
                )
            }
            // De-dupe while keeping the canonical display order, so the stored value is stable.
            allowedSections.filter { it.id in cleaned.toSet() }.joinToString(",") { it.id }
        }

        normalizedTime?.let { upsertSetting(timeKey, it) }
        body.enabled?.let { upsertSetting(enabledKey, it.toString()) }
        sectionsCsv?.let { upsertSetting(sectionsKey, it) }

        call.respond(currentResponse())
    }
}

/** Reads one app_settings value by key, or null if unset. */
private suspend fun readSetting(settingKey: String): String? = dbQuery {
    AppSettingsTable.selectAll().where { AppSettingsTable.key eq settingKey }
        .singleOrNull()?.get(AppSettingsTable.value)
}

/** Upserts one app_settings value (update-then-insert; the PK guards against duplicates). */
private suspend fun upsertSetting(settingKey: String, settingValue: String) {
    dbQuery {
        val updated = AppSettingsTable.update({ AppSettingsTable.key eq settingKey }) { it[value] = settingValue }
        if (updated == 0) AppSettingsTable.insert {
            it[key] = settingKey
            it[value] = settingValue
        }
    }
}
