package com.homebase.android

import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.model.CreateShoppingTemplateRequest
import com.homebase.android.data.model.ShoppingTemplateDto
import com.homebase.android.data.model.ShoppingTemplateItemDto
import com.homebase.android.data.model.TemplateItemInput
import com.homebase.android.data.model.UpdateShoppingTemplateRequest
import com.homebase.android.data.repository.NETWORK_ERROR_TEXT
import com.homebase.android.data.repository.ShoppingRepository
import com.homebase.android.data.websocket.ShoppingWebSocketClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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

/** Template CRUD on the shopping repository (#215): mapping + delegation + German error texts. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShoppingTemplateRepositoryTest {

    private lateinit var api: HomeBaseApi
    private lateinit var wsClient: ShoppingWebSocketClient
    private lateinit var repository: ShoppingRepository

    private fun template(id: String = "t1", name: String = "Wocheneinkauf") = ShoppingTemplateDto(
        id = id, name = name,
        items = listOf(
            ShoppingTemplateItemDto(id = "i1", name = "Milch", sortOrder = 0),
            ShoppingTemplateItemDto(id = "i2", name = "Brot", sortOrder = 1),
        ),
        createdBy = "alice", createdAt = "2026-01-01T00:00:00Z",
    )

    private fun httpException(code: Int, body: String): HttpException =
        HttpException(Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())))

    @Before
    fun setup() {
        api = mockk()
        wsClient = mockk(relaxed = true)
        every { wsClient.events } returns emptyFlow()
        repository = ShoppingRepository(api, wsClient)
    }

    @Test
    fun `getTemplates returns the api list with embedded items`() = runTest {
        val templates = listOf(template("t1"), template("t2", "Drogerie"))
        coEvery { api.getShoppingTemplates() } returns templates

        val result = repository.getTemplates()

        assertTrue(result.isSuccess)
        assertEquals(templates, result.getOrNull())
        // The first template's items survive the round-trip (GET maps items).
        assertEquals(listOf("Milch", "Brot"), result.getOrNull()?.first()?.items?.map { it.name })
    }

    @Test
    fun `createTemplate sends trimmed name and item-name inputs, dropping blanks`() = runTest {
        val req = slot<CreateShoppingTemplateRequest>()
        coEvery { api.createShoppingTemplate(capture(req)) } returns template()

        val result = repository.createTemplate("  Wocheneinkauf  ", listOf("Milch", "  ", "Brot"))

        assertTrue(result.isSuccess)
        assertEquals("Wocheneinkauf", req.captured.name)
        assertEquals(listOf(TemplateItemInput("Milch"), TemplateItemInput("Brot")), req.captured.items)
    }

    @Test
    fun `updateTemplate sends name and replacement items`() = runTest {
        val req = slot<UpdateShoppingTemplateRequest>()
        coEvery { api.updateShoppingTemplate("t1", capture(req)) } returns template()

        val result = repository.updateTemplate("t1", "Neu", listOf("Eier"))

        assertTrue(result.isSuccess)
        assertEquals("Neu", req.captured.name)
        assertEquals(listOf(TemplateItemInput("Eier")), req.captured.items)
        coVerify { api.updateShoppingTemplate("t1", any()) }
    }

    @Test
    fun `deleteTemplate delegates to the api`() = runTest {
        coEvery { api.deleteShoppingTemplate("t1") } returns Unit

        val result = repository.deleteTemplate("t1")

        assertTrue(result.isSuccess)
        coVerify { api.deleteShoppingTemplate("t1") }
    }

    @Test
    fun `createTemplate maps an HttpException to German text (never raw HTTP n)`() = runTest {
        // org.json is the android.jar stub in unit tests, so the body code isn't parsed and
        // germanTemplateError falls to its else branch — still German, which is what we pin.
        coEvery { api.createShoppingTemplate(any()) } throws httpException(400, """{"code":"INVALID_TEMPLATE"}""")

        val result = repository.createTemplate("", emptyList())

        assertTrue(result.isFailure)
        assertEquals("Vorlage konnte nicht gespeichert werden.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `updateTemplate maps a 404 to German text`() = runTest {
        coEvery { api.updateShoppingTemplate(any(), any()) } throws httpException(404, """{"code":"NOT_FOUND"}""")

        val result = repository.updateTemplate("gone", "X", emptyList())

        assertTrue(result.isFailure)
        assertEquals("Vorlage konnte nicht gespeichert werden.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `deleteTemplate maps transport errors to the German offline text`() = runTest {
        coEvery { api.deleteShoppingTemplate(any()) } throws UnknownHostException("Unable to resolve host")

        val result = repository.deleteTemplate("t1")

        assertTrue(result.isFailure)
        assertEquals(NETWORK_ERROR_TEXT, result.exceptionOrNull()?.message)
    }
}
