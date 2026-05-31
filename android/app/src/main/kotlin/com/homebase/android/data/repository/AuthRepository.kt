package com.homebase.android.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.model.LoginRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "auth")

class AuthRepository(
    private val context: Context,
    private val api: HomeBaseApi,
    private val onTokenChange: (String?) -> Unit = {},
) {
    private val tokenKey = stringPreferencesKey("jwt_token")

    val tokenFlow: Flow<String?> = context.dataStore.data.map { it[tokenKey] }

    suspend fun login(username: String, password: String): Result<String> {
        return runCatching {
            val response = api.login(LoginRequest(username, password))
            context.dataStore.edit { prefs -> prefs[tokenKey] = response.token }
            onTokenChange(response.token)
            response.token
        }
    }

    suspend fun logout() {
        context.dataStore.edit { prefs -> prefs.remove(tokenKey) }
        onTokenChange(null)
    }
}
