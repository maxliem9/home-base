package com.homebase.android

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.model.LoginRequest
import com.homebase.android.data.repository.ApiException
import com.homebase.android.data.repository.AuthRepository
import com.homebase.android.testutil.leakSafeScope
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.nio.file.Files
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * Pins `germanLoginError` (ApiErrors.kt, issue #83) end-to-end through the **public** login path
 * `AuthRepository.login()` — the only Android error-mapper still without a test driving it through
 * its caller (test-gap #338, aus Review von PR #334 / #319).
 *
 * Unlike the seven `german*Error` body-parsing mappers (covered by [ErrorCodeMappingRobolectricTest],
 * which need Robolectric because the JVM `org.json` stub makes `errorCodeOf` return null),
 * `germanLoginError` branches on `e.code()` — the HTTP **status** — so it has no `org.json`
 * dependency and is coverable by a **plain JVM** unit test (deliberately NO `@RunWith(Robolectric)`).
 *
 * The mapper is `private`-by-convention (wired via `apiCatching(mapHttpError = ::germanLoginError)`),
 * so — mirroring the #334 pattern of driving private mappers through their public repository method —
 * we stub `HomeBaseApi.login` to throw an [HttpException] of the given status and assert the German
 * message on the failed [Result] returned by `AuthRepository.login()`.
 *
 * Constructing [AuthRepository] off-emulator: its `init` block reads the JWT from
 * [EncryptedSharedPreferences], which would hit the Android Keystore (unavailable on plain JVM).
 * We mock the keystore handshake ([MasterKey.Builder] + the static `EncryptedSharedPreferences.create`)
 * to hand back a relaxed [SharedPreferences]; the failed-login path never touches `prefs` anyway
 * (the api throws before the token is persisted), so this only keeps the constructor harmless.
 */
class GermanLoginErrorTest {

    private val prefs = mockk<SharedPreferences>(relaxed = true)

    // AuthRepository.init fires a fire-and-forget `scope.launch(Dispatchers.IO){…}` (token restore)
    // that can still be running when @After unmocks the keystore (mockkStatic/mockkConstructor) below.
    // It would then hit the REAL EncryptedSharedPreferences/Keystore and throw; an unguarded scope
    // would let that uncaught exception leak into the *next* runTest as UncaughtExceptionsBeforeTest.
    // leakSafeScope() (SupervisorJob + swallowing handler) contains it; @After cancels it before the
    // mocks come down so it usually never runs at all. See leakSafeScope's docs + issue #363.
    private val repoScope = leakSafeScope()

    @Before
    fun mockKeystore() {
        // AuthRepository.init does `prefs.getString(KEY_TOKEN, null)` on Dispatchers.IO; without a
        // restored token it resolves to LoggedOut. Stubbing the keystore handshake lets the plain
        // SharedPreferences read succeed instead of blowing up on the missing Keystore.
        every { prefs.getString(any(), any()) } returns null
        mockkConstructor(MasterKey.Builder::class)
        every { anyConstructed<MasterKey.Builder>().setKeyScheme(any()) } answers { self as MasterKey.Builder }
        every { anyConstructed<MasterKey.Builder>().build() } returns mockk(relaxed = true)
        mockkStatic(EncryptedSharedPreferences::class)
        // The 5-arg create() is overloaded, so the matchers must be explicitly typed to resolve it.
        every {
            EncryptedSharedPreferences.create(
                any<Context>(),
                any<String>(),
                any<MasterKey>(),
                any<EncryptedSharedPreferences.PrefKeyEncryptionScheme>(),
                any<EncryptedSharedPreferences.PrefValueEncryptionScheme>(),
            )
        } returns prefs
    }

    @After
    fun unmock() {
        // Cancel the fire-and-forget init coroutine BEFORE removing the keystore mocks, so it cannot
        // hit the real Keystore and leak an uncaught exception into the next test.
        repoScope.cancel()
        unmockkAll()
    }

    /** A failed login response carrying the given HTTP status (body shape is irrelevant here). */
    private fun httpException(status: Int): HttpException = HttpException(
        Response.error<Any>(status, "".toResponseBody("application/json".toMediaType())),
    )

    private fun authRepository(api: HomeBaseApi): AuthRepository {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        // init purges a legacy plaintext token at `File(filesDir, "datastore/...")`; a relaxed mock
        // returns null for filesDir → NPE in the File ctor. Point it at a real temp dir (the legacy
        // file won't exist, so the cleanup is a harmless no-op).
        every { context.filesDir } returns Files.createTempDirectory("homebase-test").toFile()
        // A shared scope (not the test scheduler) with a swallowing exception handler so the
        // constructor's fire-and-forget `scope.launch(Dispatchers.IO)` stays out of the way of the
        // login assertion under runTest and can never leak past @After's unmockkAll() (see repoScope).
        return AuthRepository(context, api, scope = repoScope)
    }

    /** Drives `AuthRepository.login()` against an api that fails with [status]. */
    private suspend fun loginFailure(status: Int): Throwable? {
        val api = mockk<HomeBaseApi>()
        val cause = httpException(status)
        coEvery { api.login(any<LoginRequest>()) } throws cause
        val mapped = authRepository(api).login("user", "secret").exceptionOrNull()
        // Every failure leaving the repo carries German UI text wrapped in ApiException; the
        // original HttpException stays reachable as the cause.
        assertTrue("status $status should map to ApiException, was $mapped", mapped is ApiException)
        assertSame("status $status must keep the HttpException as cause", cause, mapped?.cause)
        return mapped
    }

    @Test
    fun `login 401 maps to the German failed-login text`() = runTest {
        assertEquals("Login fehlgeschlagen.", loginFailure(401)?.message)
    }

    @Test
    fun `login 429 maps to the German throttle text`() = runTest {
        assertEquals("Zu viele Versuche – bitte später erneut versuchen.", loginFailure(429)?.message)
    }

    @Test
    fun `login any other status falls back to the German failed-login text`() = runTest {
        // The else-branch: anything that is not 401/429 (e.g. a 500 or an unexpected 400/403).
        for (status in listOf(400, 403, 404, 500, 503)) {
            assertEquals(
                "status $status should hit the else-branch",
                "Login fehlgeschlagen.",
                loginFailure(status)?.message,
            )
        }
    }
}
