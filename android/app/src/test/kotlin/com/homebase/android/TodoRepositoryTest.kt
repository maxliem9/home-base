package com.homebase.android

import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.model.CreateTodoRequest
import com.homebase.android.data.model.TodoDto
import com.homebase.android.data.model.UpdateTodoRequest
import com.homebase.android.data.repository.GENERIC_ERROR_TEXT
import com.homebase.android.data.repository.NETWORK_ERROR_TEXT
import com.homebase.android.data.repository.TodoRepository
import com.homebase.android.data.websocket.TodoWebSocketClient
import io.mockk.coEvery
import java.net.UnknownHostException
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TodoRepositoryTest {

    private lateinit var api: HomeBaseApi
    private lateinit var wsClient: TodoWebSocketClient
    private lateinit var repository: TodoRepository

    private fun todo(id: String = "1", title: String = "Test") = TodoDto(
        id = id, title = title, status = "INBOX",
        createdBy = "alice", createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z",
    )

    @Before
    fun setup() {
        api = mockk()
        wsClient = mockk(relaxed = true)
        every { wsClient.events } returns emptyFlow()
        repository = TodoRepository(api, wsClient)
    }

    @Test
    fun `getTodos returns api result on success`() = runTest {
        val todos = listOf(todo("1"), todo("2"))
        coEvery { api.getTodos() } returns todos

        val result = repository.getTodos()

        assertTrue(result.isSuccess)
        assertEquals(todos, result.getOrNull())
    }

    @Test
    fun `getTodos maps unknown errors to the German fallback text`() = runTest {
        coEvery { api.getTodos() } throws RuntimeException("Network error")

        val result = repository.getTodos()

        assertTrue(result.isFailure)
        assertEquals(GENERIC_ERROR_TEXT, result.exceptionOrNull()?.message)
    }

    @Test
    fun `getTodos maps transport errors to the German offline text`() = runTest {
        coEvery { api.getTodos() } throws UnknownHostException("Unable to resolve host api.example.com")

        val result = repository.getTodos()

        assertTrue(result.isFailure)
        assertEquals(NETWORK_ERROR_TEXT, result.exceptionOrNull()?.message)
    }

    @Test
    fun `createTodo delegates to api with correct request`() = runTest {
        val expected = todo(title = "Buy milk")
        coEvery { api.createTodo(CreateTodoRequest("Buy milk")) } returns expected

        val result = repository.createTodo(CreateTodoRequest("Buy milk"))

        assertTrue(result.isSuccess)
        assertEquals(expected, result.getOrNull())
        coVerify { api.createTodo(CreateTodoRequest("Buy milk")) }
    }

    @Test
    fun `updateTodo delegates to api`() = runTest {
        val updated = todo().copy(status = "DONE")
        val request = UpdateTodoRequest(status = "DONE")
        coEvery { api.updateTodo("1", request) } returns updated

        val result = repository.updateTodo("1", request)

        assertTrue(result.isSuccess)
        assertEquals(updated, result.getOrNull())
    }

    @Test
    fun `deleteTodo delegates to api`() = runTest {
        coEvery { api.deleteTodo("1") } returns Unit

        val result = repository.deleteTodo("1")

        assertTrue(result.isSuccess)
        coVerify { api.deleteTodo("1") }
    }

    @Test
    fun `deleteTodo returns failure on api exception`() = runTest {
        coEvery { api.deleteTodo("1") } throws RuntimeException("Not found")

        val result = repository.deleteTodo("1")

        assertTrue(result.isFailure)
    }
}
