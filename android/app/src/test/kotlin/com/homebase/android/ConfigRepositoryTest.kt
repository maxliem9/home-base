package com.homebase.android

import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.model.AppConfigResponse
import com.homebase.android.data.model.DigestConfigResponse
import com.homebase.android.data.model.RecurringConfigResponse
import com.homebase.android.data.model.UpdateConfigRequest
import com.homebase.android.data.model.UpdateDigestRequest
import com.homebase.android.data.model.UserDto
import com.homebase.android.data.repository.ConfigRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/** Pins the household-rename + digest-time mappings for the settings subpages (#101). */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigRepositoryTest {

    private lateinit var api: HomeBaseApi
    private lateinit var repository: ConfigRepository

    @Before
    fun setup() {
        api = mockk()
        repository = ConfigRepository(api)
    }

    @Test
    fun `updateHouseholdName returns the persisted name`() = runTest {
        coEvery { api.updateConfig(UpdateConfigRequest("Familie Stern")) } returns AppConfigResponse("Familie Stern")

        val result = repository.updateHouseholdName("Familie Stern")

        assertTrue(result.isSuccess)
        assertEquals("Familie Stern", result.getOrNull())
    }

    @Test
    fun `updateHouseholdName maps a 400 to the German length message`() = runTest {
        coEvery { api.updateConfig(any()) } throws HttpException(
            Response.error<Any>(400, """{"code":"INVALID_NAME"}""".toResponseBody("application/json".toMediaType())),
        )

        val result = repository.updateHouseholdName("")

        assertTrue(result.isFailure)
        assertEquals("Name muss 1–60 Zeichen lang sein.", result.exceptionOrNull()?.message)
    }

    // GET maps every field the digest cards drive (#189): time, the in-app on/off flag, the
    // read-only telegramConfigured hint, and the selected + available sections.
    @Test
    fun `getDigest maps all digest config fields`() = runTest {
        coEvery { api.getDigest() } returns DigestConfigResponse(
            time = "20:00",
            enabled = false,
            telegramConfigured = true,
            sections = listOf("evening_done_today", "evening_due_tomorrow"),
            availableSections = listOf(
                "evening_done_today", "evening_new_inbox", "evening_due_tomorrow",
                "evening_absent_tomorrow", "evening_kita_tomorrow",
            ),
        )

        val cfg = repository.getDigest().getOrNull()

        assertEquals("20:00", cfg?.time)
        assertEquals(false, cfg?.enabled)
        assertEquals(true, cfg?.telegramConfigured)
        assertEquals(listOf("evening_done_today", "evening_due_tomorrow"), cfg?.sections)
        assertEquals(5, cfg?.availableSections?.size)
    }

    // PUT sends the full {time, enabled, sections} patch and returns the persisted state (#189).
    @Test
    fun `updateDigest sends the full patch and returns the persisted config`() = runTest {
        val sections = listOf("evening_done_today", "evening_due_tomorrow")
        coEvery {
            api.updateDigest(UpdateDigestRequest(time = "20:30", enabled = false, sections = sections))
        } returns DigestConfigResponse(
            time = "20:30",
            enabled = false,
            telegramConfigured = true,
            sections = sections,
            availableSections = sections,
        )

        val result = repository.updateDigest("20:30", enabled = false, sections = sections)

        assertTrue(result.isSuccess)
        val cfg = result.getOrNull()
        assertEquals("20:30", cfg?.time)
        assertEquals(false, cfg?.enabled)
        assertEquals(sections, cfg?.sections)
    }

    @Test
    fun `updateDigest maps a 400 to the German time message`() = runTest {
        coEvery { api.updateDigest(any()) } throws HttpException(
            Response.error<Any>(400, """{"code":"INVALID_TIME"}""".toResponseBody("application/json".toMediaType())),
        )

        val result = repository.updateDigest("99:99", enabled = true, sections = emptyList())

        assertTrue(result.isFailure)
        assertEquals("Ungültige Uhrzeit (Format HH:MM).", result.exceptionOrNull()?.message)
    }

    @Test
    fun `updateMorningDigest sends the full patch`() = runTest {
        val sections = listOf("morning_due_today", "morning_inbox")
        coEvery {
            api.updateMorningDigest(UpdateDigestRequest(time = "07:15", enabled = true, sections = sections))
        } returns DigestConfigResponse(
            time = "07:15",
            enabled = true,
            telegramConfigured = false,
            sections = sections,
            availableSections = sections,
        )

        val result = repository.updateMorningDigest("07:15", enabled = true, sections = sections)

        assertTrue(result.isSuccess)
        assertEquals("07:15", result.getOrNull()?.time)
    }

    // Recurring-todo safety-net time (#200): GET maps the {time}-only config.
    @Test
    fun `getRecurring maps the time`() = runTest {
        coEvery { api.getRecurring() } returns RecurringConfigResponse(time = "00:30")

        val result = repository.getRecurring()

        assertTrue(result.isSuccess)
        assertEquals("00:30", result.getOrNull()?.time)
    }

    // PUT sends just {time} (request shares RecurringConfigResponse's shape) and adopts the
    // persisted, normalised time the backend echoes (#200).
    @Test
    fun `updateRecurring sends the time and returns the persisted config`() = runTest {
        coEvery {
            api.updateRecurring(RecurringConfigResponse(time = "01:15"))
        } returns RecurringConfigResponse(time = "01:15")

        val result = repository.updateRecurring("01:15")

        assertTrue(result.isSuccess)
        assertEquals("01:15", result.getOrNull()?.time)
    }

    @Test
    fun `updateRecurring maps a 400 to the German time message`() = runTest {
        coEvery { api.updateRecurring(any()) } throws HttpException(
            Response.error<Any>(400, """{"code":"INVALID_TIME"}""".toResponseBody("application/json".toMediaType())),
        )

        val result = repository.updateRecurring("99:99")

        assertTrue(result.isFailure)
        assertEquals("Ungültige Uhrzeit (Format HH:MM).", result.exceptionOrNull()?.message)
    }

    // Avatar-colour roster (Teil von #100): GET /users carries avatarHue; getAvatarHues
    // maps only the members who set one to username → hue, leaving the rest "automatic".
    @Test
    fun `getAvatarHues maps only members with a chosen hue`() = runTest {
        coEvery { api.getUsers() } returns listOf(
            UserDto("max", avatarHue = 210),
            UserDto("lea", avatarHue = null), // automatic → omitted from the map
        )

        val result = repository.getAvatarHues()

        assertTrue(result.isSuccess)
        assertEquals(mapOf("max" to 210), result.getOrNull())
    }

    @Test
    fun `getAvatarHues is empty when nobody chose a colour`() = runTest {
        coEvery { api.getUsers() } returns listOf(UserDto("max"), UserDto("lea"))

        val result = repository.getAvatarHues()

        assertTrue(result.isSuccess)
        assertEquals(emptyMap<String, Int>(), result.getOrNull())
    }
}
