package com.homebase.android

import android.app.Application
import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.model.CreateTodoRequest
import com.homebase.android.data.model.UpdateTimeEntryRequest
import com.homebase.android.data.repository.AbsenceRepository
import com.homebase.android.data.repository.ShoppingRepository
import com.homebase.android.data.repository.TimeRepository
import com.homebase.android.data.repository.TodoRepository
import com.homebase.android.data.repository.errorCodeOf
import com.homebase.android.data.websocket.AbsenceWebSocketClient
import com.homebase.android.data.websocket.ShoppingWebSocketClient
import com.homebase.android.data.websocket.TimeWebSocketClient
import com.homebase.android.data.websocket.TodoWebSocketClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response

/**
 * Exercises the repositories' `german*Error` code→German-text maps under a *real* `org.json`
 * (issue #319). In a plain-JVM unit test `org.json.JSONObject` is the empty `android.jar` stub,
 * so [errorCodeOf] always returns null and every mapper falls into its `else` branch — meaning
 * the code-specific branches (INVALID_TEMPLATE → "Der Name darf nicht leer sein.", NOT_FOUND →
 * "… nicht gefunden – bitte neu laden.", …) were never asserted on Android. The plain-JVM repo
 * tests (e.g. ShoppingTemplateRepositoryTest, AbsenceRepositoryTest) deliberately pin that
 * fallback; this Robolectric class is the missing layer that pins the resolved per-code text.
 *
 * The mappers are `private`, so each is driven through the public repository method that wires it
 * (`apiCatching(mapHttpError = ::german*Error)`): we stub the api call to throw an [HttpException]
 * carrying the `{ "code": … }` body and assert the German message on the failed [Result].
 * Mirrors the web `api.test.ts` code→text coverage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// Plain Application, NOT the manifest's HomeBaseApplication: the latter's onCreate builds the
// whole AppContainer graph (incl. AuthRepository), which fires a Dispatchers.IO coroutine into the
// Android Keystore — unavailable under Robolectric, so it throws uncaught and poisons the next
// runTest ("uncaught exceptions before the test started"). We only need real org.json here, no app
// graph. Same override as LogoutTeardownComposeTest.
@Config(sdk = [34], application = Application::class)
class ErrorCodeMappingRobolectricTest {

    /** A failed response whose body is the backend `ErrorResponse` `{ "code", "message" }` shape. */
    private fun httpException(code: String, status: Int = 400): HttpException = HttpException(
        Response.error<Any>(
            status,
            """{"code":"$code","message":"ignored english text"}"""
                .toResponseBody("application/json".toMediaType()),
        ),
    )

    private fun rawHttpException(body: String, status: Int = 400): HttpException = HttpException(
        Response.error<Any>(status, body.toResponseBody("application/json".toMediaType())),
    )

    // --- errorCodeOf: the helper that needs real org.json (root cause of #319) ---

    @Test
    fun `errorCodeOf parses the code out of a real ErrorResponse body`() {
        assertEquals("INVALID_TEMPLATE", errorCodeOf(httpException("INVALID_TEMPLATE")))
    }

    @Test
    fun `errorCodeOf returns an unknown code verbatim (mapper else-branch handles it)`() {
        assertEquals("SOMETHING_NEW", errorCodeOf(httpException("SOMETHING_NEW")))
    }

    @Test
    fun `errorCodeOf returns null when the code key is absent`() {
        assertNull(errorCodeOf(rawHttpException("""{"message":"no code here"}""")))
    }

    @Test
    fun `errorCodeOf returns null for a blank code`() {
        assertNull(errorCodeOf(httpException("")))
    }

    @Test
    fun `errorCodeOf returns null for a malformed JSON body`() {
        assertNull(errorCodeOf(rawHttpException("not json at all")))
    }

    @Test
    fun `errorCodeOf returns null for an empty body`() {
        assertNull(errorCodeOf(rawHttpException("")))
    }

    // --- TodoRepository.germanTodoError (via createTodo) ---

    @Test
    fun `germanTodoError maps every known code, unknown and missing-code to the fallback`() = runTest {
        val api = mockk<HomeBaseApi>()
        val repo = TodoRepository(api, relaxedTodoWs())
        val req = CreateTodoRequest(title = "Test")
        val cases = mapOf(
            "INVALID_TODO" to "Aufgabe unvollständig – Titel oder Zuständige:r/Fälligkeit angeben.",
            "INVALID_STATUS" to "Ungültiger Status.",
            "INVALID_PRIORITY" to "Ungültige Priorität.",
            "INVALID_DUE_DATE" to "Ungültiges Fälligkeitsdatum.",
            "INVALID_RECURRENCE" to "Ungültige Wiederholung – für eine Wiederholung ein Fälligkeitsdatum angeben.",
            "INVALID_ID" to "Ungültige Liste.",
            "NOT_FOUND" to "Aufgabe nicht gefunden – bitte neu laden.",
        )
        for ((code, text) in cases) {
            coEvery { api.createTodo(any()) } throws httpException(code)
            assertEquals(text, repo.createTodo(req).exceptionOrNull()?.message)
        }
        // unknown code → fallback
        coEvery { api.createTodo(any()) } throws httpException("WAT")
        assertEquals("Aufgabe konnte nicht gespeichert werden.", repo.createTodo(req).exceptionOrNull()?.message)
        // missing code key (errorCodeOf → null) → fallback
        coEvery { api.createTodo(any()) } throws rawHttpException("""{"message":"x"}""")
        assertEquals("Aufgabe konnte nicht gespeichert werden.", repo.createTodo(req).exceptionOrNull()?.message)
    }

    // --- TimeRepository.germanTimeError (via updateEntry) ---

    @Test
    fun `germanTimeError maps every known code plus the fallback`() = runTest {
        val api = mockk<HomeBaseApi>()
        val repo = TimeRepository(api, relaxedTimeWs())
        val req = UpdateTimeEntryRequest()
        val cases = mapOf(
            "PROJECT_ARCHIVED" to "Das Projekt ist archiviert.",
            "INVALID_RANGE" to "Das Ende muss nach dem Start liegen.",
            "INVALID_DATE" to "Ungültiges Datum.",
            "NOT_FOUND" to "Eintrag nicht gefunden – bitte neu laden.",
        )
        for ((code, text) in cases) {
            coEvery { api.updateTimeEntry("e1", any()) } throws httpException(code)
            assertEquals(text, repo.updateEntry("e1", req).exceptionOrNull()?.message)
        }
        coEvery { api.updateTimeEntry("e1", any()) } throws httpException("WAT")
        assertEquals("Konnte nicht gespeichert werden.", repo.updateEntry("e1", req).exceptionOrNull()?.message)
    }

    // --- TimeRepository.germanProjectError (via updateProject) ---

    @Test
    fun `germanProjectError maps every known code plus the fallback`() = runTest {
        val api = mockk<HomeBaseApi>()
        val repo = TimeRepository(api, relaxedTimeWs())
        val cases = mapOf(
            "INVALID_PROJECT" to "Der Name darf nicht leer sein.",
            "INVALID_COLOR" to "Ungültige Farbe.",
            "NOT_FOUND" to "Projekt nicht gefunden – bitte neu laden.",
        )
        for ((code, text) in cases) {
            coEvery { api.updateProject(eq("p1"), any()) } throws httpException(code)
            assertEquals(text, repo.updateProject("p1", "Name", "#fff").exceptionOrNull()?.message)
        }
        coEvery { api.updateProject(eq("p1"), any()) } throws httpException("WAT")
        assertEquals("Projekt konnte nicht gespeichert werden.", repo.updateProject("p1", "Name", "#fff").exceptionOrNull()?.message)
    }

    // --- TimeRepository.germanSplitError (via splitEntry) ---

    @Test
    fun `germanSplitError maps every known code plus the fallback`() = runTest {
        val api = mockk<HomeBaseApi>()
        val repo = TimeRepository(api, relaxedTimeWs())
        val cases = mapOf(
            "ENTRY_RUNNING" to "Laufende Timer können nicht gesplittet werden — erst stoppen.",
            "INVALID_RANGE" to "Das Ende muss nach dem Start liegen.",
            "INVALID_DATE" to "Ungültiges Datum.",
            "NOT_FOUND" to "Eintrag nicht gefunden – bitte neu laden.",
        )
        for ((code, text) in cases) {
            coEvery { api.splitTimeEntry("e1", any()) } throws httpException(code)
            assertEquals(text, repo.splitEntry("e1", "2026-01-01T12:00:00Z", null).exceptionOrNull()?.message)
        }
        coEvery { api.splitTimeEntry("e1", any()) } throws httpException("WAT")
        assertEquals("Eintrag konnte nicht gesplittet werden.", repo.splitEntry("e1", "2026-01-01T12:00:00Z", null).exceptionOrNull()?.message)
    }

    // --- ShoppingRepository.germanTemplateError (via createTemplate) ---

    @Test
    fun `germanTemplateError maps every known code plus the fallback`() = runTest {
        val api = mockk<HomeBaseApi>()
        val repo = ShoppingRepository(api, relaxedShoppingWs())
        val cases = mapOf(
            "INVALID_TEMPLATE" to "Der Name darf nicht leer sein.",
            "NOT_FOUND" to "Vorlage nicht gefunden – bitte neu laden.",
        )
        for ((code, text) in cases) {
            coEvery { api.createShoppingTemplate(any()) } throws httpException(code)
            assertEquals(text, repo.createTemplate("Wocheneinkauf", emptyList()).exceptionOrNull()?.message)
        }
        coEvery { api.createShoppingTemplate(any()) } throws httpException("WAT")
        assertEquals("Vorlage konnte nicht gespeichert werden.", repo.createTemplate("Wocheneinkauf", emptyList()).exceptionOrNull()?.message)
    }

    // --- AbsenceRepository.germanKitaError (via addKita) ---

    @Test
    fun `germanKitaError maps every known code plus the fallback`() = runTest {
        val api = mockk<HomeBaseApi>()
        val repo = AbsenceRepository(api, relaxedAbsenceWs())
        val cases = mapOf(
            "DATE_CONFLICT" to "Für dieses Datum gibt es schon einen Eintrag.",
            "INVALID_DATE" to "Ungültiges Datum.",
            "RANGE_TOO_LARGE" to "Der Zeitraum ist zu lang.",
            "NOT_FOUND" to "Nicht gefunden – bitte neu laden.",
        )
        for ((code, text) in cases) {
            coEvery { api.createKita(any()) } throws httpException(code)
            assertEquals(text, repo.addKita("2026-01-01", null).exceptionOrNull()?.message)
        }
        coEvery { api.createKita(any()) } throws httpException("WAT")
        assertEquals("Kita-Schließtag konnte nicht gespeichert werden.", repo.addKita("2026-01-01", null).exceptionOrNull()?.message)
    }

    // --- AbsenceRepository.germanHolidayError (via addCustomHoliday) ---

    @Test
    fun `germanHolidayError maps every known code plus the fallback`() = runTest {
        val api = mockk<HomeBaseApi>()
        val repo = AbsenceRepository(api, relaxedAbsenceWs())
        val cases = mapOf(
            "DATE_CONFLICT" to "Für dieses Datum gibt es schon einen Eintrag.",
            "INVALID_DATE" to "Ungültiges Datum.",
            "NOT_FOUND" to "Nicht gefunden – bitte neu laden.",
        )
        for ((code, text) in cases) {
            coEvery { api.createCustomHoliday(any()) } throws httpException(code)
            assertEquals(text, repo.addCustomHoliday(12, 24, true, null).exceptionOrNull()?.message)
        }
        coEvery { api.createCustomHoliday(any()) } throws httpException("WAT")
        assertEquals("Eigener Feiertag konnte nicht gespeichert werden.", repo.addCustomHoliday(12, 24, true, null).exceptionOrNull()?.message)
    }

    // --- relaxed WS mocks (the repos read `wsClient.events` in their constructors) ---

    private fun relaxedTodoWs() = mockk<TodoWebSocketClient>(relaxed = true).also { every { it.events } returns emptyFlow() }
    private fun relaxedTimeWs() = mockk<TimeWebSocketClient>(relaxed = true).also { every { it.events } returns emptyFlow() }
    private fun relaxedShoppingWs() = mockk<ShoppingWebSocketClient>(relaxed = true).also { every { it.events } returns emptyFlow() }
    private fun relaxedAbsenceWs() = mockk<AbsenceWebSocketClient>(relaxed = true).also { every { it.events } returns emptyFlow() }
}
