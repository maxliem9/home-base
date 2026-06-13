package com.homebase.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.homebase.android.data.repository.TodoRepository
import com.homebase.android.data.websocket.TodoWebSocketClient
import com.homebase.android.ui.aufgaben.TodoViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Guards the logout-teardown contract (issue #180): the six domain ViewModels are Activity-scoped
 * and keyed by the JWT, so on logout MainActivity clears the Activity [ViewModelStore]. That must
 * run each ViewModel's `onCleared()` → `repository.disconnectWebSocket()`, closing its OkHttp
 * WebSocket instead of leaving it as a zombie that keeps reconnecting under the old token key.
 *
 * Rather than calling `onCleared()` by hand (it is `protected`), this drives the real framework path:
 * a ViewModel is materialised through [ViewModelProvider] into a [ViewModelStore] exactly as the
 * Compose `viewModel()` helper does, then `store.clear()` is invoked — the same call MainActivity
 * now makes when the auth state leaves `LoggedIn`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogoutTeardownTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun relaxedTodoRepository(): TodoRepository = mockk(relaxed = true) {
        every { incomingEvents } returns MutableSharedFlow()
        coEvery { getLists() } returns Result.success(emptyList())
        coEvery { getTodos() } returns Result.success(emptyList())
    }

    @Test
    fun `clearing the ViewModelStore disconnects the WebSocket`() = runTest {
        val repository = relaxedTodoRepository()
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                TodoViewModel(repository, "token-A") as T
        }

        // Materialise the ViewModel into the store the same way the Compose `viewModel()` helper does.
        ViewModelProvider(store, factory)[TodoViewModel::class.java]
        advanceUntilIdle()

        // Logout: MainActivity clears the Activity store → onCleared() → disconnectWebSocket().
        store.clear()

        verify(exactly = 1) { repository.disconnectWebSocket() }
    }

    @Test
    fun `re-login under a new token key builds a fresh ViewModel with its own socket`() = runTest {
        val repoA = relaxedTodoRepository()
        val repoB = relaxedTodoRepository()
        val store = ViewModelStore()

        fun factory(repo: TodoRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TodoViewModel(repo, "k") as T
        }

        // First session: connect, then logout clears the store (socket A closed).
        ViewModelProvider(store, factory(repoA)).get("todo-tokenA", TodoViewModel::class.java)
        advanceUntilIdle()
        store.clear()
        verify(exactly = 1) { repoA.disconnectWebSocket() }

        // Second session under a fresh token key: a brand-new ViewModel/socket, with the old one gone.
        ViewModelProvider(store, factory(repoB)).get("todo-tokenB", TodoViewModel::class.java)
        advanceUntilIdle()
        verify(exactly = 1) { repoB.connectWebSocket("k") }
        verify(exactly = 0) { repoB.disconnectWebSocket() }
    }
}
