package com.homebase.android

import com.homebase.android.data.api.AuthInterceptor
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins [AuthInterceptor] (#501): it attaches the Bearer token, and a `401` on a request we
 * authenticated with a token fires the session-expiry hook so the app can drop the dead JWT and
 * return to the login screen. A 401 on an unauthenticated request (login) must NOT fire it, and only
 * `401` triggers it — every other status passes through untouched.
 *
 * The chain is a mockk: `request()` returns the outgoing request and `proceed()` echoes a [Response]
 * of the given status back, capturing the (possibly header-augmented) request so we can assert the
 * Authorization header. No MockWebServer needed — the interceptor only reads `response.code`.
 */
class AuthInterceptorTest {

    private fun runIntercept(
        token: String?,
        status: Int,
        onUnauthorized: () -> Unit = {},
    ): Request {
        val interceptor = AuthInterceptor({ token }, onUnauthorized)
        val chain = mockk<Interceptor.Chain>()
        val outgoing = Request.Builder().url("https://hub.example/api/v1/todos").build()
        every { chain.request() } returns outgoing
        val proceeded = slot<Request>()
        every { chain.proceed(capture(proceeded)) } answers {
            Response.Builder()
                .request(proceeded.captured)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("")
                .build()
        }
        interceptor.intercept(chain)
        return proceeded.captured
    }

    @Test
    fun `attaches the Bearer token when present`() {
        val sent = runIntercept(token = "jwt-abc", status = 200)

        assertEquals("Bearer jwt-abc", sent.header("Authorization"))
    }

    @Test
    fun `sends no Authorization header when there is no token`() {
        val sent = runIntercept(token = null, status = 200)

        assertNull(sent.header("Authorization"))
    }

    @Test
    fun `401 on an authenticated request fires onUnauthorized`() {
        var fired = 0
        runIntercept(token = "jwt-abc", status = 401) { fired++ }

        assertEquals(1, fired)
    }

    @Test
    fun `401 without a token does NOT fire onUnauthorized (login failure, not session expiry)`() {
        var fired = 0
        runIntercept(token = null, status = 401) { fired++ }

        assertEquals(0, fired)
    }

    @Test
    fun `a stale 401 after the token was swapped (re-login) does NOT fire onUnauthorized`() {
        // Request goes out with the old token; while it is in flight the user re-logs in, so the live
        // token holder now returns a different value. The interceptor re-reads it and must NOT bounce
        // the freshly authenticated session on this leftover 401. (PR #613 review, finding #1.)
        var current: String? = "old-jwt"
        var fired = 0
        val interceptor = AuthInterceptor({ current }, { fired++ })
        val chain = mockk<Interceptor.Chain>()
        val outgoing = Request.Builder().url("https://hub.example/api/v1/todos").build()
        every { chain.request() } returns outgoing
        val proceeded = slot<Request>()
        every { chain.proceed(capture(proceeded)) } answers {
            current = "new-jwt" // re-login lands while the request is in flight
            Response.Builder()
                .request(proceeded.captured)
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("")
                .build()
        }

        interceptor.intercept(chain)

        assertEquals("the request went out with the old token", "Bearer old-jwt", proceeded.captured.header("Authorization"))
        assertEquals("a stale 401 must not log the re-logged-in session out", 0, fired)
    }

    @Test
    fun `a successful authenticated response does not fire onUnauthorized`() {
        var fired = 0
        runIntercept(token = "jwt-abc", status = 200) { fired++ }

        assertEquals(0, fired)
    }

    @Test
    fun `other error statuses on an authenticated request do not fire onUnauthorized`() {
        // Only 401 is a session expiry; a 403/404/409/500 is a normal domain/transport error the
        // repositories map and toast — logging the user out on those would be wrong.
        for (status in listOf(400, 403, 404, 409, 429, 500, 503)) {
            var fired = 0
            runIntercept(token = "jwt-abc", status = status) { fired++ }

            assertEquals("status $status must not trigger logout", 0, fired)
        }
    }
}
