package com.homebase.android.data.repository

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import org.json.JSONObject
import retrofit2.HttpException

// Central error mapper for the repositories (issue #73/#558). A failure leaving a repository carries a
// typed [AppError] code — never user-facing text. The UI layer resolves the code to a localized
// string (ui/ErrorText.kt → strings.xml, DE + EN), so wording stays out of the data layer and an
// i18n switch reaches it. ViewModels resolve `ApiException.code` at the UI boundary before toasting.

/**
 * A repository failure carrying a typed [code]; the original failure stays as [cause]. [message] is
 * the non-localized code name (for logs/`Result` inspection only) — it is NEVER shown to the user;
 * the UI resolves [code] via `Context.errorText` instead.
 */
internal class ApiException(val code: AppError, cause: Throwable) : Exception(code.name, cause)

/**
 * `runCatching` for API calls that maps failures to a typed [AppError]:
 * - [HttpException] passes through unchanged, unless the caller supplies [mapHttpError] — then it is
 *   wrapped as [ApiException] with the returned code (used by the repositories' code maps).
 * - Moshi parse errors ([JsonDataException]/[JsonEncodingException]) → [AppError.GENERIC].
 *   Checked before [IOException]: [JsonEncodingException] *is* an IOException, but a malformed
 *   response is not "offline".
 * - [IOException] family (offline, DNS, timeout, TLS) → [AppError.NETWORK].
 * - Anything else → [AppError.GENERIC].
 *
 * Unlike plain `runCatching`, a [CancellationException] is rethrown so coroutine cancellation
 * propagates instead of being captured (and possibly toasted).
 */
internal suspend fun <T> apiCatching(
    mapHttpError: ((HttpException) -> AppError)? = null,
    block: suspend () -> T,
): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(mapApiError(e, mapHttpError))
}

/** The mapping itself — separate from [apiCatching] so tests can hit it directly. */
internal fun mapApiError(e: Throwable, mapHttpError: ((HttpException) -> AppError)? = null): Throwable = when (e) {
    is HttpException -> if (mapHttpError != null) ApiException(mapHttpError(e), e) else e
    is JsonDataException, is JsonEncodingException -> ApiException(AppError.GENERIC, e)
    is IOException -> ApiException(AppError.NETWORK, e)
    else -> ApiException(AppError.GENERIC, e)
}

/**
 * Is this failure a `401 Unauthorized` — i.e. an expired/invalid session (#501/#614)? Unwraps the same
 * way [classifyFlush] does: a repository surfaces a raw [HttpException] for un-mapped paths, or an
 * [ApiException] whose [cause] is the [HttpException] for mapped paths. A 401 is handled centrally
 * (AuthInterceptor → logout), so the UI uses this to suppress its error toast — the app slides to the
 * login screen without an error flash instead.
 */
internal fun Throwable.isUnauthorized(): Boolean {
    val http = this as? HttpException ?: (this as? ApiException)?.cause as? HttpException
    return http?.code() == 401
}

/**
 * Read the backend `ErrorResponse.code` off a failed HTTP response (the body is
 * `{ "code", "message" }` — see backend model/Models.kt), so a `mapHttpError` can branch
 * on the stable code instead of the English `message`. Returns null when the body is empty
 * or not the expected shape. Mirrors the web `errorCode` helper (web/src/api.ts).
 */
internal fun errorCodeOf(e: HttpException): String? = runCatching {
    e.response()?.errorBody()?.string()
        ?.let { JSONObject(it).optString("code").ifBlank { null } }
}.getOrNull()

/** Maps a failed login HTTP response to a typed [AppError] (issue #83). */
internal fun loginError(e: HttpException): AppError = when (e.code()) {
    429 -> AppError.LOGIN_THROTTLED
    else -> AppError.LOGIN_FAILED
}
