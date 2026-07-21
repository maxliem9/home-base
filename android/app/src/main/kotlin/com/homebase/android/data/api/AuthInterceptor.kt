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
 * wrong-password 401 there is a normal login failure, not a session expiry. OkHttp runs application
 * interceptors for the WebSocket handshake too, so a 401 rejecting a socket upgrade is caught here as
 * well — no per-channel handling needed.
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
        val token = tokenProvider()
        val request = if (token != null) {
            chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }
        val response = chain.proceed(request)
        if (token != null && response.code == HTTP_UNAUTHORIZED) onUnauthorized()
        return response
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
    }
}
