package com.homebase.android.data.repository

import com.homebase.android.data.api.HomeBaseApi

class ConfigRepository(private val api: HomeBaseApi) {

    /** Household display name (HOUSEHOLD_NAME, default "Mäxchen"). Falls back gracefully. */
    suspend fun getHouseholdName(): Result<String> =
        runCatching { api.getConfig().householdName }

    /** The household members' usernames (from GET /users), for assignee chips etc. Falls back gracefully. */
    suspend fun getUsers(): Result<List<String>> =
        runCatching { api.getUsers().map { it.username } }
}
