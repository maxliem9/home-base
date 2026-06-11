package com.homebase.android.data.repository

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import retrofit2.HttpException

// Central error mapper for the repositories (issue #73). ViewModels toast `e.message`
// of a failed Result, so every failure leaving a repository must carry German
// user-facing text — otherwise the raw English exception message (offline, DNS,
// Moshi parse) ends up on screen. Wording mirrors the web catalog (web/src/i18n/de.ts).

/** Transport failures (offline, DNS, timeout) — wording = web `common.networkError`. */
internal const val NETWORK_ERROR_TEXT = "Keine Verbindung – bitte später erneut versuchen."

/** Parse failures and everything unexpected — wording = web `errors.INTERNAL_ERROR`. */
internal const val GENERIC_ERROR_TEXT = "Serverfehler – bitte später erneut versuchen."

/** A failure whose [message] is German UI text; the original failure stays as [cause]. */
internal class ApiException(message: String, cause: Throwable) : Exception(message, cause)

/**
 * `runCatching` for API calls that maps failures to German messages:
 * - [HttpException] passes through unchanged, unless the caller supplies [mapHttpError] —
 *   then it is wrapped with the returned text (used by [TimeRepository]'s code→text maps,
 *   which stay exactly as before).
 * - Moshi parse errors ([JsonDataException]/[JsonEncodingException]) → [GENERIC_ERROR_TEXT].
 *   Checked before [IOException]: [JsonEncodingException] *is* an IOException, but a
 *   malformed response is not "offline".
 * - [IOException] family (offline, DNS, timeout, TLS) → [NETWORK_ERROR_TEXT].
 * - Anything else → [GENERIC_ERROR_TEXT].
 *
 * Unlike plain `runCatching`, a [CancellationException] is rethrown so coroutine
 * cancellation propagates instead of being captured (and possibly toasted).
 */
internal suspend fun <T> apiCatching(
    mapHttpError: ((HttpException) -> String)? = null,
    block: suspend () -> T,
): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(mapApiError(e, mapHttpError))
}

/** The mapping itself — separate from [apiCatching] so tests can hit it directly. */
internal fun mapApiError(e: Throwable, mapHttpError: ((HttpException) -> String)? = null): Throwable = when (e) {
    is HttpException -> if (mapHttpError != null) ApiException(mapHttpError(e), e) else e
    is JsonDataException, is JsonEncodingException -> ApiException(GENERIC_ERROR_TEXT, e)
    is IOException -> ApiException(NETWORK_ERROR_TEXT, e)
    else -> ApiException(GENERIC_ERROR_TEXT, e)
}

/** Maps a failed login HTTP response to a German user-facing message (issue #83). */
internal fun germanLoginError(e: HttpException): String = when (e.code()) {
    401 -> "Login fehlgeschlagen."
    429 -> "Zu viele Versuche – bitte später erneut versuchen."
    else -> "Login fehlgeschlagen."
}
