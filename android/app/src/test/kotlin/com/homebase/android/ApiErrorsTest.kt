package com.homebase.android

import com.homebase.android.data.repository.ApiException
import com.homebase.android.data.repository.AppError
import com.homebase.android.data.repository.apiCatching
import com.homebase.android.data.repository.loginError
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * Pins the central repository error mapper (#73/#558): a failure leaving a repository carries a typed
 * [AppError] code, never user-facing text. The code→text resolution (strings.xml) is pinned separately
 * in [ErrorCodeMappingRobolectricTest].
 */
class ApiErrorsTest {

    private fun httpException(code: Int = 409): HttpException = HttpException(
        Response.error<Any>(code, """{"code":"INVALID_RANGE"}""".toResponseBody("application/json".toMediaType())),
    )

    private val Throwable?.code: AppError get() = (this as ApiException).code

    @Test
    fun `success passes through`() = runTest {
        val result = apiCatching { 42 }

        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `IOException family maps to the NETWORK code`() = runTest {
        val transportErrors = listOf<Throwable>(
            UnknownHostException("Unable to resolve host api.example.com"),
            ConnectException("Failed to connect to /10.0.2.2:8080"),
            SocketTimeoutException("timeout"),
            IOException("unexpected end of stream"),
        )
        for (e in transportErrors) {
            val mapped = apiCatching<Unit> { throw e }.exceptionOrNull()

            assertTrue("$e should map to ApiException", mapped is ApiException)
            assertEquals(AppError.NETWORK, mapped.code)
            assertSame(e, mapped?.cause)
        }
    }

    @Test
    fun `HttpException without mapper passes through unchanged`() = runTest {
        val e = httpException()

        val mapped = apiCatching<Unit> { throw e }.exceptionOrNull()

        assertSame(e, mapped)
    }

    @Test
    fun `HttpException with mapper wraps the mapped code and keeps the cause`() = runTest {
        val e = httpException()

        val mapped = apiCatching<Unit>(mapHttpError = { AppError.TIME_INVALID_RANGE }) {
            throw e
        }.exceptionOrNull()

        assertEquals(AppError.TIME_INVALID_RANGE, mapped.code)
        assertSame(e, mapped?.cause)
    }

    @Test
    fun `Moshi parse errors map to the GENERIC code`() = runTest {
        // JsonEncodingException extends IOException — a malformed response must read as
        // a server problem (GENERIC), not as NETWORK ("offline").
        val parseErrors = listOf<Throwable>(
            JsonDataException("Expected a string but was BEGIN_OBJECT at path \$.title"),
            JsonEncodingException("Use JsonReader.setLenient(true) to accept malformed JSON"),
        )
        for (e in parseErrors) {
            val mapped = apiCatching<Unit> { throw e }.exceptionOrNull()

            assertEquals(AppError.GENERIC, mapped.code)
            assertSame(e, mapped?.cause)
        }
    }

    @Test
    fun `unknown exceptions map to the GENERIC code`() = runTest {
        val e = RuntimeException("boom")

        val mapped = apiCatching<Unit> { throw e }.exceptionOrNull()

        assertTrue(mapped is ApiException)
        assertEquals(AppError.GENERIC, mapped.code)
        assertSame(e, mapped?.cause)
    }

    @Test
    fun `login 401 maps to LOGIN_FAILED via loginError`() = runTest {
        val e = httpException(401)

        val mapped = apiCatching<Unit>(mapHttpError = ::loginError) {
            throw e
        }.exceptionOrNull()

        assertTrue("should map to ApiException", mapped is ApiException)
        assertEquals(AppError.LOGIN_FAILED, mapped.code)
        assertSame(e, mapped?.cause)
    }

    @Test
    fun `login 429 maps to LOGIN_THROTTLED via loginError`() = runTest {
        val e = httpException(429)

        val mapped = apiCatching<Unit>(mapHttpError = ::loginError) {
            throw e
        }.exceptionOrNull()

        assertTrue("should map to ApiException", mapped is ApiException)
        assertEquals(AppError.LOGIN_THROTTLED, mapped.code)
        assertSame(e, mapped?.cause)
    }

    @Test
    fun `login unknown status maps to LOGIN_FAILED via loginError`() = runTest {
        val e = httpException(500)

        val mapped = apiCatching<Unit>(mapHttpError = ::loginError) {
            throw e
        }.exceptionOrNull()

        assertTrue("should map to ApiException", mapped is ApiException)
        assertEquals(AppError.LOGIN_FAILED, mapped.code)
        assertSame(e, mapped?.cause)
    }

    @Test
    fun `CancellationException is rethrown instead of captured`() = runTest {
        var rethrown: CancellationException? = null

        try {
            apiCatching<Unit> { throw CancellationException("scope cancelled") }
        } catch (e: CancellationException) {
            rethrown = e
        }

        assertNotNull(rethrown)
    }
}
