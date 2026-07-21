package com.homebase.android.data.api

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches the Bearer token to every request and detects an expired session (#501).
 *
 * When a request we authenticated with a token comes back `401 Unauthorized`, the stored JWT is no
 * longer accepted (expired, or invalidated server-side). [onUnauthorized] fires so the app can drop
 * the dead token and return to the login screen — the Android analog of the web's `401 → onLogout()`.
 * Without this the token stays put, every REST call keeps failing with a generic error toast, and the
 * user is stuck on the main UI with no way to re-authenticate (the reported bug).
 *
 * Requests sent **without** a token (the login call itself, before any token exists) are excluded: a
 * wrong-password 401 there is a normal login failure, not a session expiry. It also fires only while
 * the token we *sent* is still the current one — a slow 401 from an already-replaced session (the user
 * re-logged in while the request was in flight) must not bounce the freshly authenticated session.
 * OkHttp runs application interceptors for the WebSocket handshake too, so a 401 rejecting a socket
 * upgrade is caught here as well — no per-channel handling needed. The WS handshake already carries its
 * own Authorization header, so we skip attaching a second one there (#615).
 *
 * [onUnauthorized] runs on an OkHttp dispatcher thread, so it must be thread-safe and non-blocking
 * (the wired [com.homebase.android.data.repository.AuthRepository.onUnauthorized] just hops onto a
 * coroutine scope).
 */
class AuthInterceptor(
    private val tokenProvider: () -> String?,
    private val onUnauthorized: () -> Unit = {},
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = tokenProvider()
        // Attach the session token — unless the request already carries its own Authorization header:
        // the WebSocket handshake sets one in ReconnectingWebSocketClient, and OkHttp runs this
        // interceptor for that handshake too, so appending a second identical header is pure redundancy
        // (#615). REST requests never carry one, so this branch only affects the WS upgrade.
        val request = if (token != null && original.header("Authorization") == null) {
            original.newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        } else {
            original
        }
        val response = chain.proceed(request)
        // Re-read the live token holder: fire only if it still equals the token we sent. On a normal
        // expiry it does → logout; after a re-login it is the new token (≠ ours) and after a logout it
        // is null (≠ ours) → skip, so a stale in-flight 401 can't log the new/absent session out.
        if (token != null && response.code == HTTP_UNAUTHORIZED && tokenProvider() == token) {
            onUnauthorized()
        }
        return response
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
    }
}
