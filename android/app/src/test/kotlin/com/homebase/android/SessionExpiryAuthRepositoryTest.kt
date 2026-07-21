package com.homebase.android

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.repository.AuthRepository
import com.homebase.android.data.repository.AuthState
import com.homebase.android.testutil.leakSafeScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.nio.file.Files
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the session-expiry path (#501): [AuthRepository.onUnauthorized] — the hook the OkHttp
 * [com.homebase.android.data.api.AuthInterceptor] fires on a `401` — drops the stored JWT and flips
 * the auth state to [AuthState.LoggedOut]. That is what makes MainActivity swap the main scaffold back
 * to the login screen (its top-level `when` on the state) and clears the interceptor's token holder via
 * `onTokenChange(null)`, so the user can simply sign in again instead of being stuck behind 401s.
 *
 * Constructed off-emulator exactly like [GermanLoginErrorTest]: the `init` block reads the JWT from
 * [EncryptedSharedPreferences] (Android Keystore, unavailable on plain JVM), so we mock the keystore
 * handshake to hand back a plain [SharedPreferences]. A real [leakSafeScope] (not the test scheduler)
 * runs the fire-and-forget `init` + the `logout` coroutine on real threads; the assertions await the
 * state transitions with a bounded [withTimeout], and `@After` cancels the scope before the mocks fall.
 */
class SessionExpiryAuthRepositoryTest {

    private val prefs = mockk<SharedPreferences>(relaxed = true)
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private val repoScope = leakSafeScope()

    @Before
    fun mockKeystore() {
        every { prefs.edit() } returns editor
        every { editor.remove(any()) } returns editor
        mockkConstructor(MasterKey.Builder::class)
        every { anyConstructed<MasterKey.Builder>().setKeyScheme(any()) } answers { self as MasterKey.Builder }
        every { anyConstructed<MasterKey.Builder>().build() } returns mockk(relaxed = true)
        mockkStatic(EncryptedSharedPreferences::class)
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
        repoScope.cancel()
        unmockkAll()
    }

    private fun authRepository(restoredToken: String?, onTokenChange: (String?) -> Unit): AuthRepository {
        // init restores the JWT via prefs.getString(); a non-null value → LoggedIn, null → LoggedOut.
        every { prefs.getString(any(), any()) } returns restoredToken
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        // init purges a legacy plaintext token at File(filesDir, "datastore/..."); point filesDir at a
        // real temp dir so the File ctor doesn't NPE (the legacy file won't exist → cleanup no-ops).
        every { context.filesDir } returns Files.createTempDirectory("homebase-test").toFile()
        return AuthRepository(
            context,
            api = mockk<HomeBaseApi>(relaxed = true),
            scope = repoScope,
            onTokenChange = onTokenChange,
        )
    }

    @Test
    fun `onUnauthorized on a live session clears the token and returns to LoggedOut`() = runBlocking {
        val tokenChanges = mutableListOf<String?>()
        val repo = authRepository(restoredToken = "expired-jwt", onTokenChange = { tokenChanges += it })

        // init seeds the interceptor with the restored token and publishes LoggedIn.
        withTimeout(5_000) { repo.state.first { it is AuthState.LoggedIn } }

        repo.onUnauthorized()

        val end = withTimeout(5_000) { repo.state.first { it is AuthState.LoggedOut } }
        assertTrue("a 401 must return the app to the login screen", end is AuthState.LoggedOut)
        // The interceptor's token holder is cleared (last onTokenChange is null) and the persisted JWT
        // is removed — so the next request sends no stale token and a fresh login starts clean.
        assertEquals(null, tokenChanges.last())
        verify { editor.remove(any()) }
    }

    @Test
    fun `onUnauthorized while already logged out is a no-op`() = runBlocking {
        val tokenChanges = mutableListOf<String?>()
        val repo = authRepository(restoredToken = null, onTokenChange = { tokenChanges += it })

        // No token → init publishes LoggedOut and seeds onTokenChange(null) exactly once.
        withTimeout(5_000) { repo.state.first { it is AuthState.LoggedOut } }
        val changesAfterInit = tokenChanges.size

        repo.onUnauthorized()

        // The guard returns before launching a logout, so no further token churn happens. A bounded
        // real-time wait gives an erroneously-launched logout a chance to run and be caught.
        withTimeout(1_000) {
            kotlinx.coroutines.delay(200)
        }
        assertEquals("already-logged-out 401 must not re-run logout", changesAfterInit, tokenChanges.size)
        assertTrue(repo.state.first { true } is AuthState.LoggedOut)
    }
}
