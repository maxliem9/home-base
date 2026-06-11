package com.homebase.android.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.model.LoginRequest
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/** Auth gate state. `Loading` covers the brief async read of the encrypted token at cold start. */
sealed interface AuthState {
    data object Loading : AuthState
    data object LoggedOut : AuthState
    data class LoggedIn(val token: String) : AuthState
}

class AuthRepository(
    context: Context,
    private val api: HomeBaseApi,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val onTokenChange: (String?) -> Unit = {},
) {
    private val appContext = context.applicationContext

    // JWT grants full access to the hub, so it is persisted in EncryptedSharedPreferences:
    // values/keys are encrypted with a Tink keyset that is itself wrapped by a hardware-backed
    // master key in the Android Keystore. The keystore key never leaves the device, so even a
    // copy of the `auth` prefs file (ADB/cloud backup, USB) is useless elsewhere. The file is
    // additionally excluded from backups via res/xml/{backup_rules,data_extraction_rules}.xml.
    //
    // Lazy + only ever touched from Dispatchers.IO: creating it does Keystore keygen + a blocking
    // SharedPreferences load, which must not run on the main thread (ANR / StrictMode).
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // SharedPreferences has no reactive API, so we mirror the persisted token in a StateFlow.
    // It starts as Loading and is resolved once the off-main-thread read below completes; after
    // that it only changes through login()/logout(), so the mirror cannot drift.
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            // One-time migration: purge the legacy plaintext DataStore file (pre-encryption builds
            // stored the JWT there). The new encrypted store does not read it, so existing users
            // re-login once; deleting it removes the plaintext token from disk and from backups.
            val legacy = File(appContext.filesDir, "datastore/auth.preferences_pb")
            if (legacy.exists() && !legacy.delete()) {
                Log.w(TAG, "Failed to delete legacy plaintext token file")
            }

            val token = prefs.getString(KEY_TOKEN, null)
            // Seed the OkHttp interceptor's token holder before publishing LoggedIn, so the REST
            // calls the main screen fires on appearance are authenticated with the restored token.
            onTokenChange(token)
            _state.value = if (token != null) AuthState.LoggedIn(token) else AuthState.LoggedOut
        }
    }

    suspend fun login(username: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        apiCatching(mapHttpError = ::germanLoginError) {
            val response = api.login(LoginRequest(username, password))
            prefs.edit().putString(KEY_TOKEN, response.token).commit()
            onTokenChange(response.token)
            _state.value = AuthState.LoggedIn(response.token)
            response.token
        }
    }

    /** Maps a failed login HTTP response to German user-facing text. */
    private fun germanLoginError(e: HttpException): String = when (e.code()) {
        401 -> "Login fehlgeschlagen."
        429 -> "Zu viele Versuche – bitte später erneut versuchen."
        else -> "Login fehlgeschlagen."
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY_TOKEN).commit()
        onTokenChange(null)
        _state.value = AuthState.LoggedOut
    }

    private companion object {
        const val TAG = "AuthRepository"
        const val PREFS_NAME = "auth"
        const val KEY_TOKEN = "jwt_token"
    }
}
