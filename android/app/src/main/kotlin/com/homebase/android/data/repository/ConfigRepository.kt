package com.homebase.android.data.repository

import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.model.DigestConfigResponse
import com.homebase.android.data.model.DoneWindowConfigResponse
import com.homebase.android.data.model.RecurringConfigResponse
import com.homebase.android.data.model.SetAvatarColorRequest
import com.homebase.android.data.model.UpdateConfigRequest
import com.homebase.android.data.model.UpdateDigestRequest
import retrofit2.HttpException

class ConfigRepository(private val api: HomeBaseApi) {

    /** Household display name (set in-app, default "Mäxchen"). Falls back gracefully. */
    suspend fun getHouseholdName(): Result<String> =
        apiCatching { api.getConfig().householdName }

    /**
     * Rename the household (PUT /config, #101). Returns the persisted name. The 400 the backend
     * raises for an empty/too-long name is mapped to German text; the UI also pre-validates.
     */
    suspend fun updateHouseholdName(name: String): Result<String> =
        apiCatching(mapHttpError = {
            if (it.code() == 400) "Name muss 1–60 Zeichen lang sein." else "Name konnte nicht gespeichert werden."
        }) { api.updateConfig(UpdateConfigRequest(name)).householdName }

    /** The household members' usernames (from GET /users), for assignee chips etc. Falls back gracefully. */
    suspend fun getUsers(): Result<List<String>> =
        apiCatching { api.getUsers().map { it.username } }

    /**
     * Per-user avatar-hue overrides (Teil von #100): username → chosen hue (0..359), from the
     * household-visible roster (GET /users avatarHue). Only members who picked a colour appear;
     * everyone else stays "automatic" (derived from the username hash). The own-colour picker
     * (Konto-Einstellungen, #242) writes via [setMyAvatarColor]. Falls back gracefully.
     */
    suspend fun getAvatarHues(): Result<Map<String, Int>> =
        apiCatching {
            api.getUsers().mapNotNull { u -> u.avatarHue?.let { u.username to it } }.toMap()
        }

    /**
     * Set the signed-in user's own avatar hue (PUT /users/me/avatar-color, #242). [hue] 0..359 sets
     * the override, null clears it back to automatic. The only 400 the UI can trigger is a hue out
     * of range (the picker only ever sends palette values or null), mapped to German; the caller
     * re-reads the roster via [getAvatarHues] afterwards so the shared hue map stays in sync.
     */
    suspend fun setMyAvatarColor(hue: Int?): Result<Unit> =
        apiCatching(mapHttpError = {
            if (it.code() == 400) "Ungültige Farbe." else "Farbe konnte nicht gespeichert werden."
        }) { api.setAvatarColor(SetAvatarColorRequest(hue)) }

    /**
     * Evening-recap config — send time, in-app on/off, whether Telegram is configured, and the
     * selected + available sections (#189). Falls back gracefully.
     */
    suspend fun getDigest(): Result<DigestConfigResponse> =
        apiCatching { api.getDigest() }

    /**
     * Patch the evening-recap config (PUT /config/digest, #189): time + on/off + sections in one
     * request. Returns the persisted state. A 400 means an invalid time or section id (the UI
     * pre-validates HH:mm and only ever sends known section ids), mapped to German text.
     */
    suspend fun updateDigest(time: String, enabled: Boolean, sections: List<String>): Result<DigestConfigResponse> =
        apiCatching(mapHttpError = ::digestSaveError) {
            api.updateDigest(UpdateDigestRequest(time = time, enabled = enabled, sections = sections))
        }

    /**
     * Morning-briefing config — send time, in-app on/off, whether Telegram is configured, and the
     * selected + available sections (#189). Falls back gracefully.
     */
    suspend fun getMorningDigest(): Result<DigestConfigResponse> =
        apiCatching { api.getMorningDigest() }

    /**
     * Patch the morning-briefing config (PUT /config/morning-digest, #189): time + on/off +
     * sections in one request. Returns the persisted state. A 400 (invalid time/section) is mapped
     * to German text.
     */
    suspend fun updateMorningDigest(time: String, enabled: Boolean, sections: List<String>): Result<DigestConfigResponse> =
        apiCatching(mapHttpError = ::digestSaveError) {
            api.updateMorningDigest(UpdateDigestRequest(time = time, enabled = enabled, sections = sections))
        }

    /**
     * Recurring-todo safety-net run time (#200) — when the always-on scheduler rolls overdue
     * recurring todos forward. Always-on, so just the time (no enabled/sections). Falls back
     * gracefully.
     */
    suspend fun getRecurring(): Result<RecurringConfigResponse> =
        apiCatching { api.getRecurring() }

    /**
     * Patch the recurring-todo run time (PUT /config/recurring, #200). Returns the persisted,
     * normalised time. The only 400 is an invalid time (the UI pre-validates HH:mm), mapped to
     * German via the shared digest-time wording.
     */
    suspend fun updateRecurring(time: String): Result<RecurringConfigResponse> =
        apiCatching(mapHttpError = ::digestSaveError) {
            api.updateRecurring(RecurringConfigResponse(time = time))
        }

    /**
     * "Erledigt"-history window length in days (#356) — how many days the tasks view's Erledigt tab
     * and done-section span before they're capped (`app_settings.done_window_days`, default 14).
     * Household-shared; the per-device "Alle anzeigen" toggle (#340) still overrides it. Falls back
     * gracefully (the ViewModel keeps its default 14 on failure).
     */
    suspend fun getDoneWindow(): Result<DoneWindowConfigResponse> =
        apiCatching { api.getDoneWindow() }

    /**
     * Patch the "Erledigt"-window length (PUT /config/done-window, #356). Returns the persisted,
     * validated value. The only 400 is an out-of-range value (the UI pre-validates 1..3650), mapped
     * to German text.
     */
    suspend fun updateDoneWindow(days: Int): Result<DoneWindowConfigResponse> =
        apiCatching(mapHttpError = {
            if (it.code() == 400) "Wert muss zwischen 1 und 3650 liegen." else "Wert konnte nicht gespeichert werden."
        }) { api.updateDoneWindow(DoneWindowConfigResponse(days = days)) }
}

/**
 * Shared 400→German mapping for the digest PUTs. In practice the only 400 the app can trigger is
 * an invalid time (sections are always sent straight from the server's availableSections, so
 * INVALID_SECTION can't happen from here), hence the time-specific wording.
 */
private fun digestSaveError(e: HttpException): String =
    if (e.code() == 400) "Ungültige Uhrzeit (Format HH:MM)." else "Einstellungen konnten nicht gespeichert werden."
