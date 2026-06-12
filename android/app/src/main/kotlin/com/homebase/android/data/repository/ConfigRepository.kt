package com.homebase.android.data.repository

import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.model.DigestConfigResponse
import com.homebase.android.data.model.UpdateConfigRequest
import com.homebase.android.data.model.UpdateDigestRequest

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

    /** Telegram-digest config — the send time + whether Telegram is configured. Falls back gracefully. */
    suspend fun getDigest(): Result<DigestConfigResponse> =
        apiCatching { api.getDigest() }

    /**
     * Set the Telegram-digest time (PUT /config/digest, #101). Returns the persisted HH:mm. A 400
     * means an invalid time (the UI pre-validates HH:mm), mapped to German text.
     */
    suspend fun updateDigestTime(time: String): Result<String> =
        apiCatching(mapHttpError = {
            if (it.code() == 400) "Ungültige Uhrzeit (Format HH:MM)." else "Uhrzeit konnte nicht gespeichert werden."
        }) { api.updateDigest(UpdateDigestRequest(time)).time }
}
