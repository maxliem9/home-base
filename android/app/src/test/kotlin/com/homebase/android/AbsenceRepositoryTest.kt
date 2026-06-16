package com.homebase.android

import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.repository.AbsenceRepository
import com.homebase.android.data.repository.NETWORK_ERROR_TEXT
import com.homebase.android.data.websocket.AbsenceWebSocketClient
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

/**
 * Kita-Schließtage + eigene Feiertage error mapping on the absence repository (#254):
 * an HTTP failure (incl. the backend's 409 DATE_CONFLICT when a date is already taken)
 * must leave the repository as German user-facing text, never a raw "HTTP n" message.
 *
 * Note: in JVM unit tests `org.json` is the empty android.jar stub, so `errorCodeOf`
 * can't read the body `code` and the mappers fall to their German `else` branch — same
 * as ShoppingTemplateRepositoryTest. These tests therefore pin the fallback (no raw HTTP
 * text leaks + transport text); the DATE_CONFLICT branch itself is covered by the web
 * parity + real `org.json` on device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AbsenceRepositoryTest {

    private lateinit var api: HomeBaseApi
    private lateinit var wsClient: AbsenceWebSocketClient
    private lateinit var repository: AbsenceRepository

    private fun httpException(code: Int, body: String): HttpException =
        HttpException(Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())))

    @Before
    fun setup() {
        api = mockk()
        wsClient = mockk(relaxed = true)
        every { wsClient.events } returns emptyFlow()
        repository = AbsenceRepository(api, wsClient)
    }

    @Test
    fun `updateKita maps a 409 conflict to German text (never raw HTTP 409)`() = runTest {
        coEvery { api.updateKita(any(), any()) } throws httpException(409, """{"code":"DATE_CONFLICT"}""")

        val result = repository.updateKita("k1", "2026-01-01", null)

        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()?.message
        assertEquals("Kita-Schließtag konnte nicht gespeichert werden.", msg)
        assertFalse("must not leak the raw HTTP message", msg?.contains("HTTP") == true)
    }

    @Test
    fun `addKita maps an HttpException to German text`() = runTest {
        coEvery { api.createKita(any()) } throws httpException(400, """{"code":"INVALID_DATE"}""")

        val result = repository.addKita("not-a-date", "Brückentag")

        assertTrue(result.isFailure)
        assertEquals("Kita-Schließtag konnte nicht gespeichert werden.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `updateCustomHoliday maps a 409 conflict to German text`() = runTest {
        coEvery { api.updateCustomHoliday(any(), any()) } throws httpException(409, """{"code":"DATE_CONFLICT"}""")

        val result = repository.updateCustomHoliday("h1", 12, 24, true, null)

        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()?.message
        assertEquals("Eigener Feiertag konnte nicht gespeichert werden.", msg)
        assertFalse("must not leak the raw HTTP message", msg?.contains("HTTP") == true)
    }

    @Test
    fun `addCustomHoliday maps transport errors to the German offline text`() = runTest {
        coEvery { api.createCustomHoliday(any()) } throws UnknownHostException("Unable to resolve host")

        val result = repository.addCustomHoliday(12, 24, true, "Heiligabend")

        assertTrue(result.isFailure)
        assertEquals(NETWORK_ERROR_TEXT, result.exceptionOrNull()?.message)
    }
}
