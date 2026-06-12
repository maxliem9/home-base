package com.homebase.android

import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.model.AppConfigResponse
import com.homebase.android.data.model.DigestConfigResponse
import com.homebase.android.data.model.UpdateConfigRequest
import com.homebase.android.data.model.UpdateDigestRequest
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

    @Test
    fun `updateDigestTime returns the persisted time`() = runTest {
        coEvery { api.updateDigest(UpdateDigestRequest("20:30")) } returns DigestConfigResponse("20:30", enabled = true)

        val result = repository.updateDigestTime("20:30")

        assertTrue(result.isSuccess)
        assertEquals("20:30", result.getOrNull())
    }

    @Test
    fun `updateDigestTime maps a 400 to the German time message`() = runTest {
        coEvery { api.updateDigest(any()) } throws HttpException(
            Response.error<Any>(400, """{"code":"INVALID_TIME"}""".toResponseBody("application/json".toMediaType())),
        )

        val result = repository.updateDigestTime("99:99")

        assertTrue(result.isFailure)
        assertEquals("Ungültige Uhrzeit (Format HH:MM).", result.exceptionOrNull()?.message)
    }
}
