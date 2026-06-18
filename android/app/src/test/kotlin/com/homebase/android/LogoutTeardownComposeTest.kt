package com.homebase.android

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.homebase.android.data.model.DoneWindowConfigResponse
import com.homebase.android.data.repository.AuthState
import com.homebase.android.data.repository.ConfigRepository
import com.homebase.android.data.repository.TodoRepository
import com.homebase.android.ui.aufgaben.TodoViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives the *real* MainActivity logout-teardown effect ([LogoutTeardownEffect]) over a Compose
 * composition under Robolectric — the regression layer the contract-only [LogoutTeardownTest] is
 * missing (issue #192). [LogoutTeardownTest] proves `store.clear()` disconnects the socket but never
 * exercises the auth-state-driven `LaunchedEffect` edge in MainActivity. This does:
 *
 *  1. a LoggedIn → LoggedOut transition over the live composition clears the Activity
 *     [ViewModelStore] (domain ViewModel `onCleared()` → `disconnectWebSocket()`), and
 *  2. the riskiest cold-start case — a first composition *already* `LoggedIn` must NOT clear the
 *     freshly built session (no socket-clobber of a healthy login).
 *
 * The effect is hosted exactly as MainActivity hosts it (`loggedIn = authState is LoggedIn` + the
 * Activity store). Removing the `if (!loggedIn)` guard in [LogoutTeardownEffect] flips test 2 red;
 * dropping the effect entirely flips test 1 red.
 */
// Stub Application: the manifest's HomeBaseApplication builds the real AppContainer → AuthRepository,
// whose init touches the Android Keystore (absent under Robolectric) and would leak an uncaught
// coroutine exception into the Compose test scope. This test needs no container — it owns its store.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LogoutTeardownComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun relaxedTodoRepository(): TodoRepository = mockk(relaxed = true) {
        every { incomingEvents } returns MutableSharedFlow()
        coEvery { getLists() } returns Result.success(emptyList())
        coEvery { getTodos() } returns Result.success(emptyList())
    }

    // The TodoViewModel now also reads the configurable done-window (#356) on init; a relaxed
    // ConfigRepository with a valid getDoneWindow() result keeps this teardown test focused on WS.
    private fun relaxedConfigRepository(): ConfigRepository = mockk(relaxed = true) {
        coEvery { getDoneWindow() } returns Result.success(DoneWindowConfigResponse(14))
    }

    /** Materialise a real [TodoViewModel] into [store] the way the Compose `viewModel()` helper does. */
    private fun seedSession(store: ViewModelStore, repository: TodoRepository) {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                TodoViewModel(repository, relaxedConfigRepository(), "token-A") as T
        }
        ViewModelProvider(store, factory)[TodoViewModel::class.java]
    }

    @Test
    fun `LoggedIn to LoggedOut over the composition clears the store and disconnects the socket`() {
        val repository = relaxedTodoRepository()
        val store = ViewModelStore()
        seedSession(store, repository)

        // Drive the same input MainActivity feeds the effect: authState collected as Compose state.
        var authState by mutableStateOf<AuthState>(AuthState.LoggedIn("token-A"))

        composeRule.setContent {
            LogoutTeardownEffect(
                loggedIn = authState is AuthState.LoggedIn,
                viewModelStore = store,
            )
        }
        composeRule.waitForIdle()

        // While logged in the effect must not have torn anything down yet.
        verify(exactly = 0) { repository.disconnectWebSocket() }

        // Logout: the auth state flips, the effect re-runs and clears the Activity store.
        authState = AuthState.LoggedOut
        composeRule.waitForIdle()

        verify(exactly = 1) { repository.disconnectWebSocket() }
    }

    @Test
    fun `first composition already LoggedIn does not clear the freshly built session`() {
        val repository = relaxedTodoRepository()
        val store = ViewModelStore()
        seedSession(store, repository)

        // Cold start already authenticated (token restored from encrypted prefs): the effect runs
        // once on first composition but its guard must keep it from clearing a healthy session.
        val authState by mutableStateOf<AuthState>(AuthState.LoggedIn("token-A"))

        composeRule.setContent {
            LogoutTeardownEffect(
                loggedIn = authState is AuthState.LoggedIn,
                viewModelStore = store,
            )
        }
        composeRule.waitForIdle()

        verify(exactly = 0) { repository.disconnectWebSocket() }
    }
}
