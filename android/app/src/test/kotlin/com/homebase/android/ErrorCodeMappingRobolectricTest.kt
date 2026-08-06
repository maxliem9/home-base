package com.homebase.android

import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.model.CreateTodoRequest
import com.homebase.android.data.model.UpdateTimeEntryRequest
import com.homebase.android.data.repository.AbsenceRepository
import com.homebase.android.data.repository.ApiException
import com.homebase.android.data.repository.AppError
import com.homebase.android.data.repository.ShoppingRepository
import com.homebase.android.data.repository.TimeRepository
import com.homebase.android.data.repository.TodoRepository
import com.homebase.android.data.repository.errorCodeOf
import com.homebase.android.data.websocket.AbsenceWebSocketClient
import com.homebase.android.data.websocket.ShoppingWebSocketClient
import com.homebase.android.data.websocket.TimeWebSocketClient
import com.homebase.android.data.websocket.TodoWebSocketClient
import com.homebase.android.ui.errorText
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response
import android.app.Application

/**
 * Exercises the repositories' HTTP-code → [AppError] maps under a *real* `org.json` (issue #319/#558).
 * In a plain-JVM unit test `org.json.JSONObject` is the empty `android.jar` stub, so [errorCodeOf]
 * always returns null and every mapper falls into its `else` branch — meaning the code-specific
 * branches (INVALID_TEMPLATE → NAME_REQUIRED, NOT_FOUND → …) were never asserted on Android. The
 * plain-JVM repo tests deliberately pin the fallback code; this Robolectric class is the missing layer
 * that pins the resolved per-code result — and, crucially now, the code → `strings.xml` text mapping
 * ([resolvesEveryCodeToItsGermanText]) that the UI layer owns after the #558 migration.
 *
 * The mappers are `private`, so each is driven through the public repository method that wires it
 * (`apiCatching(mapHttpError = ::x)`): we stub the api call to throw an [HttpException] carrying the
 * `{ "code": … }` body and assert the [AppError] on the failed [Result].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// Plain Application, NOT the manifest's HomeBaseApplication: the latter's onCreate builds the whole
// AppContainer graph (incl. AuthRepository), which fires a Dispatchers.IO coroutine into the Android
// Keystore — unavailable under Robolectric, so it throws uncaught and poisons the next runTest. We
// only need real org.json + resources here, no app graph. Same override as LogoutTeardownComposeTest.
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

    // --- TodoRepository.todoError (via createTodo) ---

    @Test
    fun `todoError maps every known code, unknown and missing-code to the fallback`() = runTest {
        val api = mockk<HomeBaseApi>()
        val repo = TodoRepository(api, relaxedTodoWs())
        val req = CreateTodoRequest(title = "Test")
        val cases = mapOf(
            "INVALID_TODO" to AppError.TODO_INVALID,
            "INVALID_STATUS" to AppError.TODO_INVALID_STATUS,
            "INVALID_PRIORITY" to AppError.TODO_INVALID_PRIORITY,
            "INVALID_DUE_DATE" to AppError.TODO_INVALID_DUE_DATE,
            "INVALID_RECURRENCE" to AppError.TODO_INVALID_RECURRENCE,
            "INVALID_ID" to AppError.TODO_INVALID_LIST,
            "NOT_FOUND" to AppError.TODO_NOT_FOUND,
        )
        for ((code, expected) in cases) {
            coEvery { api.createTodo(any()) } throws httpException(code)
            assertEquals(expected, repo.createTodo(req).appError())
        }
        // unknown code → fallback
        coEvery { api.createTodo(any()) } throws httpException("WAT")
        assertEquals(AppError.TODO_SAVE_FAILED, repo.createTodo(req).appError())
        // missing code key (errorCodeOf → null) → fallback
        coEvery { api.createTodo(any()) } throws rawHttpException("""{"message":"x"}""")
        assertEquals(AppError.TODO_SAVE_FAILED, repo.createTodo(req).appError())
    }

    // --- TimeRepository.timeError (via updateEntry) ---

    @Test
    fun `timeError maps every known code plus the fallback`() = runTest {
        val api = mockk<HomeBaseApi>()
        val repo = TimeRepository(api, relaxedTimeWs())
        val req = UpdateTimeEntryRequest()
        val cases = mapOf(
            "PROJECT_ARCHIVED" to AppError.TIME_PROJECT_ARCHIVED,
            "INVALID_RANGE" to AppError.TIME_INVALID_RANGE,
            "INVALID_DATE" to AppError.INVALID_DATE,
            "NOT_FOUND" to AppError.TIME_ENTRY_NOT_FOUND,
        )
        for ((code, expected) in cases) {
            coEvery { api.updateTimeEntry("e1", any()) } throws httpException(code)
            assertEquals(expected, repo.updateEntry("e1", req).appError())
        }
        coEvery { api.updateTimeEntry("e1", any()) } throws httpException("WAT")
        assertEquals(AppError.SAVE_FAILED, repo.updateEntry("e1", req).appError())
    }

    // --- TimeRepository.projectError (via updateProject) ---

    @Test
    fun `projectError maps every known code plus the fallback`() = runTest {
        val api = mockk<HomeBaseApi>()
        val repo = TimeRepository(api, relaxedTimeWs())
        val cases = mapOf(
            "INVALID_PROJECT" to AppError.NAME_REQUIRED,
            "INVALID_COLOR" to AppError.INVALID_COLOR,
            "NOT_FOUND" to AppError.PROJECT_NOT_FOUND,
        )
        for ((code, expected) in cases) {
            coEvery { api.updateProject(eq("p1"), any()) } throws httpException(code)
            assertEquals(expected, repo.updateProject("p1", "Name", "#fff").appError())
        }
        coEvery { api.updateProject(eq("p1"), any()) } throws httpException("WAT")
        assertEquals(AppError.PROJECT_SAVE_FAILED, repo.updateProject("p1", "Name", "#fff").appError())
    }

    // --- TimeRepository.splitError (via splitEntry) ---

    @Test
    fun `splitError maps every known code plus the fallback`() = runTest {
        val api = mockk<HomeBaseApi>()
        val repo = TimeRepository(api, relaxedTimeWs())
        val cases = mapOf(
            "INVALID_RANGE" to AppError.TIME_INVALID_RANGE,
            "INVALID_DATE" to AppError.INVALID_DATE,
            "NOT_FOUND" to AppError.TIME_ENTRY_NOT_FOUND,
        )
        for ((code, expected) in cases) {
            coEvery { api.splitTimeEntry("e1", any()) } throws httpException(code)
            assertEquals(expected, repo.splitEntry("e1", "2026-01-01T12:00:00Z", null).appError())
        }
        coEvery { api.splitTimeEntry("e1", any()) } throws httpException("WAT")
        assertEquals(AppError.SPLIT_FAILED, repo.splitEntry("e1", "2026-01-01T12:00:00Z", null).appError())
    }

    // --- ShoppingRepository.templateError (via createTemplate) ---

    @Test
    fun `templateError maps every known code plus the fallback`() = runTest {
        val api = mockk<HomeBaseApi>()
        val repo = ShoppingRepository(api, relaxedShoppingWs())
        val cases = mapOf(
            "INVALID_TEMPLATE" to AppError.NAME_REQUIRED,
            "NOT_FOUND" to AppError.TEMPLATE_NOT_FOUND,
        )
        for ((code, expected) in cases) {
            coEvery { api.createShoppingTemplate(any()) } throws httpException(code)
            assertEquals(expected, repo.createTemplate("Wocheneinkauf", emptyList()).appError())
        }
        coEvery { api.createShoppingTemplate(any()) } throws httpException("WAT")
        assertEquals(AppError.TEMPLATE_SAVE_FAILED, repo.createTemplate("Wocheneinkauf", emptyList()).appError())
    }

    // --- AbsenceRepository.kitaError (via addKita) ---

    @Test
    fun `kitaError maps every known code plus the fallback`() = runTest {
        val api = mockk<HomeBaseApi>()
        val repo = AbsenceRepository(api, relaxedAbsenceWs())
        val cases = mapOf(
            "DATE_CONFLICT" to AppError.DATE_CONFLICT,
            "INVALID_DATE" to AppError.INVALID_DATE,
            "RANGE_TOO_LARGE" to AppError.RANGE_TOO_LARGE,
            "NOT_FOUND" to AppError.ABSENCE_NOT_FOUND,
        )
        for ((code, expected) in cases) {
            coEvery { api.createKita(any()) } throws httpException(code)
            assertEquals(expected, repo.addKita("2026-01-01", null).appError())
        }
        coEvery { api.createKita(any()) } throws httpException("WAT")
        assertEquals(AppError.KITA_SAVE_FAILED, repo.addKita("2026-01-01", null).appError())
    }

    // --- AbsenceRepository.holidayError (via addCustomHoliday) ---

    @Test
    fun `holidayError maps every known code plus the fallback`() = runTest {
        val api = mockk<HomeBaseApi>()
        val repo = AbsenceRepository(api, relaxedAbsenceWs())
        val cases = mapOf(
            "DATE_CONFLICT" to AppError.DATE_CONFLICT,
            "INVALID_DATE" to AppError.INVALID_DATE,
            "NOT_FOUND" to AppError.ABSENCE_NOT_FOUND,
        )
        for ((code, expected) in cases) {
            coEvery { api.createCustomHoliday(any()) } throws httpException(code)
            assertEquals(expected, repo.addCustomHoliday(12, 24, true, null).appError())
        }
        coEvery { api.createCustomHoliday(any()) } throws httpException("WAT")
        assertEquals(AppError.HOLIDAY_SAVE_FAILED, repo.addCustomHoliday(12, 24, true, null).appError())
    }

    // --- UI resolution: code → strings.xml (the layer that replaced the repos' German text, #558) ---

    @Test
    fun `resolves every AppError code to its German strings-xml text`() {
        // Robolectric defaults to an English locale (→ values-en); force German so this pins the
        // default `values/` catalog (German is the app's default). "de" has no values-de → default.
        RuntimeEnvironment.setQualifiers("de")
        val ctx = RuntimeEnvironment.getApplication()
        // The full DE catalog — a wrong AppError.stringRes() wiring or a missing/renamed strings.xml
        // entry fails here. Texts are identical to the pre-#558 hardcoded repository strings.
        val de = mapOf(
            AppError.NETWORK to "Keine Verbindung – bitte später erneut versuchen.",
            AppError.GENERIC to "Serverfehler – bitte später erneut versuchen.",
            AppError.DATE_CONFLICT to "Für dieses Datum gibt es schon einen Eintrag.",
            AppError.INVALID_DATE to "Ungültiges Datum.",
            AppError.INVALID_COLOR to "Ungültige Farbe.",
            AppError.NAME_REQUIRED to "Der Name darf nicht leer sein.",
            AppError.SAVE_FAILED to "Konnte nicht gespeichert werden.",
            AppError.LOGIN_FAILED to "Login fehlgeschlagen.",
            AppError.LOGIN_THROTTLED to "Zu viele Versuche – bitte später erneut versuchen.",
            AppError.PASSWORD_WRONG to "Aktuelles Passwort ist falsch.",
            AppError.PASSWORD_SAVE_FAILED to "Passwort konnte nicht geändert werden.",
            AppError.TODO_INVALID to "Aufgabe unvollständig – Titel oder Zuständige:r/Fälligkeit angeben.",
            AppError.TODO_INVALID_STATUS to "Ungültiger Status.",
            AppError.TODO_INVALID_PRIORITY to "Ungültige Priorität.",
            AppError.TODO_INVALID_DUE_DATE to "Ungültiges Fälligkeitsdatum.",
            AppError.TODO_INVALID_RECURRENCE to "Ungültige Wiederholung – für eine Wiederholung ein Fälligkeitsdatum angeben.",
            AppError.TODO_INVALID_LIST to "Ungültige Liste.",
            AppError.TODO_NOT_FOUND to "Aufgabe nicht gefunden – bitte neu laden.",
            AppError.TODO_SAVE_FAILED to "Aufgabe konnte nicht gespeichert werden.",
            AppError.TIME_PROJECT_ARCHIVED to "Das Projekt ist archiviert.",
            AppError.TIME_INVALID_RANGE to "Das Ende muss nach dem Start liegen.",
            AppError.TIME_ENTRY_NOT_FOUND to "Eintrag nicht gefunden – bitte neu laden.",
            AppError.PROJECT_NOT_FOUND to "Projekt nicht gefunden – bitte neu laden.",
            AppError.PROJECT_SAVE_FAILED to "Projekt konnte nicht gespeichert werden.",
            AppError.SPLIT_FAILED to "Eintrag konnte nicht gesplittet werden.",
            AppError.TEMPLATE_NOT_FOUND to "Vorlage nicht gefunden – bitte neu laden.",
            AppError.TEMPLATE_SAVE_FAILED to "Vorlage konnte nicht gespeichert werden.",
            AppError.CATEGORY_PROTECTED to "Diese Kategorie kann nicht gelöscht werden.",
            AppError.CATEGORY_INVALID to "Bezeichnung und Emoji dürfen nicht leer sein.",
            AppError.CATEGORY_NOT_FOUND to "Kategorie nicht gefunden – bitte neu laden.",
            AppError.CATEGORY_SAVE_FAILED to "Kategorie konnte nicht gespeichert werden.",
            AppError.RULE_INVALID to "Der Artikelname darf nicht leer sein.",
            AppError.RULE_INVALID_CATEGORY to "Unbekannte Kategorie – bitte neu laden.",
            AppError.RULE_NOT_FOUND to "Regel nicht gefunden – bitte neu laden.",
            AppError.RULE_SAVE_FAILED to "Regel konnte nicht gespeichert werden.",
            AppError.RANGE_TOO_LARGE to "Der Zeitraum ist zu lang.",
            AppError.ABSENCE_NOT_FOUND to "Nicht gefunden – bitte neu laden.",
            AppError.KITA_SAVE_FAILED to "Kita-Schließtag konnte nicht gespeichert werden.",
            AppError.HOLIDAY_SAVE_FAILED to "Eigener Feiertag konnte nicht gespeichert werden.",
            AppError.ATTACHMENT_TOO_LARGE to "Datei ist zu groß (max. 10 MB).",
            AppError.ATTACHMENT_TYPE to "Dateityp nicht erlaubt (PDF, Text, Office …).",
            AppError.ATTACHMENT_UPLOAD_FAILED to "Upload fehlgeschlagen.",
            AppError.RECIPE_IMPORT_NO_DATA to "Auf dieser Seite wurden keine Rezeptdaten gefunden.",
            AppError.RECIPE_IMPORT_FAILED to "Import fehlgeschlagen – bitte URL prüfen.",
            AppError.HOUSEHOLD_NAME_INVALID to "Name muss 1–60 Zeichen lang sein.",
            AppError.HOUSEHOLD_NAME_SAVE_FAILED to "Name konnte nicht gespeichert werden.",
            AppError.AVATAR_COLOR_SAVE_FAILED to "Farbe konnte nicht gespeichert werden.",
            AppError.DONE_WINDOW_INVALID to "Wert muss zwischen 1 und 3650 liegen.",
            AppError.DONE_WINDOW_SAVE_FAILED to "Wert konnte nicht gespeichert werden.",
            AppError.DIGEST_TIME_INVALID to "Ungültige Uhrzeit (Format HH:MM).",
            AppError.SETTINGS_SAVE_FAILED to "Einstellungen konnten nicht gespeichert werden.",
            AppError.CALENDAR_FEED_INVALID to "Ungültige Auswahl.",
        )
        // Exhaustiveness: every enum value must be covered so a newly-added code can't slip through.
        assertEquals(AppError.entries.toSet(), de.keys)
        for ((code, text) in de) {
            assertEquals("resolve $code", text, ctx.errorText(ApiException(code, RuntimeException())))
        }
    }

    @Test
    fun `resolves codes to English under an English locale (values-en)`() {
        RuntimeEnvironment.setQualifiers("en")
        val ctx = RuntimeEnvironment.getApplication()
        // Spot-check the EN catalog the #558 migration added (full parity guarded implicitly by the
        // exhaustive DE test + the shared AppError.stringRes wiring).
        assertEquals("No connection – please try again later.", ctx.errorText(ApiException(AppError.NETWORK, RuntimeException())))
        assertEquals("Task not found – please reload.", ctx.errorText(ApiException(AppError.TODO_NOT_FOUND, RuntimeException())))
        assertEquals("The name must not be empty.", ctx.errorText(ApiException(AppError.NAME_REQUIRED, RuntimeException())))
    }

    // --- relaxed WS mocks (the repos read `wsClient.events` in their constructors) ---

    private fun relaxedTodoWs() = mockk<TodoWebSocketClient>(relaxed = true).also { every { it.events } returns emptyFlow() }
    private fun relaxedTimeWs() = mockk<TimeWebSocketClient>(relaxed = true).also { every { it.events } returns emptyFlow() }
    private fun relaxedShoppingWs() = mockk<ShoppingWebSocketClient>(relaxed = true).also { every { it.events } returns emptyFlow() }
    private fun relaxedAbsenceWs() = mockk<AbsenceWebSocketClient>(relaxed = true).also { every { it.events } returns emptyFlow() }
}
