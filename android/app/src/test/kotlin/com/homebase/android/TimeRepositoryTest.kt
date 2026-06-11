package com.homebase.android

import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.model.TimeEntryDto
import com.homebase.android.data.model.UpdateTimeEntryRequest
import com.homebase.android.data.repository.NETWORK_ERROR_TEXT
import com.homebase.android.data.repository.TimeRepository
import com.homebase.android.data.websocket.TimeWebSocketClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.net.UnknownHostException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/** Pins the failure texts of the time mutations after the central mapper refactor (#73). */
@OptIn(ExperimentalCoroutinesApi::class)
class TimeRepositoryTest {

    private lateinit var api: HomeBaseApi
    private lateinit var wsClient: TimeWebSocketClient
    private lateinit var repository: TimeRepository

    private fun entry(id: String = "e1") = TimeEntryDto(
        id = id, projectId = "p1", userId = "alice",
        startedAt = "2026-06-03T07:00:00Z", stoppedAt = "2026-06-03T08:00:00Z",
        createdAt = "2026-06-03T07:00:00Z", updatedAt = "2026-06-03T08:00:00Z",
    )

    private fun httpException(code: Int = 409): HttpException = HttpException(
        Response.error<Any>(code, """{"code":"INVALID_RANGE"}""".toResponseBody("application/json".toMediaType())),
    )

    @Before
    fun setup() {
        api = mockk()
        wsClient = mockk(relaxed = true)
        every { wsClient.events } returns emptyFlow()
        repository = TimeRepository(api, wsClient)
    }

    @Test
    fun `updateEntry returns api result on success`() = runTest {
        val updated = entry()
        coEvery { api.updateTimeEntry("e1", any()) } returns updated

        val result = repository.updateEntry("e1", UpdateTimeEntryRequest(stoppedAt = "2026-06-03T08:00:00Z"))

        assertTrue(result.isSuccess)
        assertEquals(updated, result.getOrNull())
    }

    @Test
    fun `updateEntry maps HttpException to German text as before`() = runTest {
        coEvery { api.updateTimeEntry("e1", any()) } throws httpException()

        val result = repository.updateEntry("e1", UpdateTimeEntryRequest(stoppedAt = "2026-06-03T06:00:00Z"))

        // In unit tests org.json is the android.jar stub, so the body's code cannot be
        // parsed and germanTimeError falls back to its else branch — still German, which
        // is exactly what this pins: HttpException never surfaces as raw "HTTP 409".
        assertTrue(result.isFailure)
        assertEquals("Konnte nicht gespeichert werden.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `updateEntry maps transport errors to the German offline text`() = runTest {
        coEvery { api.updateTimeEntry("e1", any()) } throws
            UnknownHostException("Unable to resolve host api.example.com")

        val result = repository.updateEntry("e1", UpdateTimeEntryRequest(stoppedAt = "2026-06-03T08:00:00Z"))

        assertTrue(result.isFailure)
        assertEquals(NETWORK_ERROR_TEXT, result.exceptionOrNull()?.message)
    }

    @Test
    fun `splitEntry maps HttpException to German text as before`() = runTest {
        coEvery { api.splitTimeEntry("e1", any()) } throws httpException()

        val result = repository.splitEntry("e1", "2026-06-03T07:30:00Z", null)

        assertTrue(result.isFailure)
        assertEquals("Eintrag konnte nicht gesplittet werden.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `startTimer maps transport errors to the German offline text`() = runTest {
        coEvery { api.startTimer(any()) } throws UnknownHostException("Unable to resolve host")

        val result = repository.startTimer("p1", null)

        assertTrue(result.isFailure)
        assertEquals(NETWORK_ERROR_TEXT, result.exceptionOrNull()?.message)
    }
}
