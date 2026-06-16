package com.homebase

import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecipeImageRouteTest {

    private suspend fun ApplicationTestBuilder.loginAndGetToken(
        username: String = "alice",
        password: String = "password123",
    ): String {
        val response = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"$password"}""")
        }
        return Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["token"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.createRecipe(token: String, title: String = "Lasagne"): String =
        Json.parseToJsonElement(
            client.post("/api/v1/recipes") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody("""{"title":"$title","category":"DINNER"}""")
            }.bodyAsText()
        ).jsonObject["id"]!!.jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.uploadImage(
        token: String,
        recipeId: String,
        bytes: ByteArray,
        contentType: String = "image/png",
        filename: String = "pic.png",
    ) = client.post("/api/v1/recipes/$recipeId/images") {
        bearerAuth(token)
        setBody(
            MultiPartFormDataContent(
                formData {
                    append(
                        "file",
                        bytes,
                        Headers.build {
                            append(HttpHeaders.ContentType, contentType)
                            append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                        },
                    )
                },
            ),
        )
    }

    // `image` is a nullable single field; the JSON config omits it when null (no cover image).
    private fun imageOf(bodyText: String): JsonObject? =
        Json.parseToJsonElement(bodyText).jsonObject["image"]?.jsonObject

    private fun JsonObject.id() = this["id"]!!.jsonPrimitive.content

    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)

    @Test
    fun `upload returns the recipe with the embedded cover image`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val recipeId = createRecipe(token)

        val response = uploadImage(token, recipeId, pngBytes)

        assertEquals(HttpStatusCode.Created, response.status)
        val image = imageOf(response.bodyAsText())!!
        assertEquals(recipeId, image["recipeId"]?.jsonPrimitive?.content)
        assertEquals("image/png", image["contentType"]?.jsonPrimitive?.content)
        assertEquals(pngBytes.size, image["sizeBytes"]?.jsonPrimitive?.int)
        assertEquals("pic.png", image["originalName"]?.jsonPrimitive?.content)
    }

    @Test
    fun `uploaded image is served back with the original bytes`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val recipeId = createRecipe(token)
        val imageId = imageOf(uploadImage(token, recipeId, pngBytes).bodyAsText())!!.id()

        val response = client.get("/api/v1/recipes/$recipeId/images/$imageId") { bearerAuth(token) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Image.PNG, response.contentType()?.withoutParameters())
        assertContentEquals(pngBytes, response.readRawBytes())
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        // the original upload name is offered for download (inline, so Coil still renders in
        // place) — otherwise the browser saves it under a generic fallback name (#272)
        val disposition = response.headers[HttpHeaders.ContentDisposition]
        assertTrue(disposition?.startsWith("inline") == true, "expected inline disposition, got $disposition")
        assertTrue(disposition?.contains("filename=pic.png") == true, "missing original filename in $disposition")
    }

    @Test
    fun `served image disposition keeps a name with umlauts and never an attachment`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val recipeId = createRecipe(token)
        val imageId = imageOf(
            uploadImage(token, recipeId, pngBytes, filename = "Sommerurlaub Österreich.png").bodyAsText()
        )!!.id()

        val response = client.get("/api/v1/recipes/$recipeId/images/$imageId") { bearerAuth(token) }

        assertEquals(HttpStatusCode.OK, response.status)
        val disposition = response.headers[HttpHeaders.ContentDisposition]
        // never attachment (would break Coil's inline rendering); Ktor encodes the umlaut per RFC 5987
        assertTrue(disposition?.startsWith("inline") == true, "expected inline disposition, got $disposition")
        assertTrue(
            disposition?.contains("Sommerurlaub") == true,
            "expected original name to survive in $disposition",
        )
    }

    @Test
    fun `recipe list and detail embed the cover image`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val recipeId = createRecipe(token)
        uploadImage(token, recipeId, pngBytes)

        val list = Json.parseToJsonElement(
            client.get("/api/v1/recipes") { bearerAuth(token) }.bodyAsText()
        ).jsonArray
        assertNotNullImage(list[0].toString())

        assertNotNullImage(client.get("/api/v1/recipes/$recipeId") { bearerAuth(token) }.bodyAsText())
    }

    private fun assertNotNullImage(bodyText: String) =
        assertTrue(imageOf(bodyText) != null, "recipe should embed its cover image")

    @Test
    fun `uploading again replaces the single cover image and drops the old bytes`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val recipeId = createRecipe(token)
        val firstId = imageOf(uploadImage(token, recipeId, pngBytes, filename = "a.png").bodyAsText())!!.id()

        val second = imageOf(uploadImage(token, recipeId, pngBytes, filename = "b.png").bodyAsText())!!
        assertNotEquals(firstId, second.id())
        assertEquals("b.png", second["originalName"]?.jsonPrimitive?.content)

        // the recipe now carries exactly the new image …
        val detailImage = imageOf(client.get("/api/v1/recipes/$recipeId") { bearerAuth(token) }.bodyAsText())!!
        assertEquals(second.id(), detailImage.id())
        // … and the replaced image's bytes are gone
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v1/recipes/$recipeId/images/$firstId") { bearerAuth(token) }.status,
        )
    }

    @Test
    fun `delete removes the cover image`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val recipeId = createRecipe(token)
        val imageId = imageOf(uploadImage(token, recipeId, pngBytes).bodyAsText())!!.id()

        val response = client.delete("/api/v1/recipes/$recipeId/images/$imageId") { bearerAuth(token) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertNull(imageOf(response.bodyAsText()), "recipe should have no cover image after delete")
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v1/recipes/$recipeId/images/$imageId") { bearerAuth(token) }.status,
        )
    }

    @Test
    fun `deleting the recipe also drops its cover image`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val recipeId = createRecipe(token)
        val imageId = imageOf(uploadImage(token, recipeId, pngBytes).bodyAsText())!!.id()

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/recipes/$recipeId") { bearerAuth(token) }.status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v1/recipes/$recipeId/images/$imageId") { bearerAuth(token) }.status,
        )
    }

    @Test
    fun `both users can set and view the cover image (shared)`() = testApplication {
        configureTestApplication()
        val alice = loginAndGetToken("alice", "password123")
        val bob = loginAndGetToken("bob", "password456")
        val recipeId = createRecipe(alice)

        // bob (not the creator) may set the cover on a shared recipe …
        val imageId = imageOf(uploadImage(bob, recipeId, pngBytes).bodyAsText())!!.id()
        // … and alice may view it
        assertEquals(
            HttpStatusCode.OK,
            client.get("/api/v1/recipes/$recipeId/images/$imageId") { bearerAuth(alice) }.status,
        )
    }

    @Test
    fun `upload of an unsupported type returns 415`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val recipeId = createRecipe(token)

        val response = uploadImage(token, recipeId, pngBytes, contentType = "application/pdf", filename = "doc.pdf")

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    @Test
    fun `upload exceeding the size limit returns 413`() = testApplication {
        val uploadDir = configureTestApplication() // test config caps uploads at 1 MB
        val token = loginAndGetToken()
        val recipeId = createRecipe(token)

        val tooBig = ByteArray(1024 * 1024 + 1) { 0x10 }
        val response = uploadImage(token, recipeId, tooBig)

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        // the aborted over-limit stream must not leave a partial file behind
        assertTrue(
            java.nio.file.Files.list(uploadDir).use { it.toList() }.isEmpty(),
            "rejected oversized upload must not leave any file in the upload dir",
        )
    }

    @Test
    fun `upload to a missing recipe returns 404`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val response = uploadImage(token, "00000000-0000-0000-0000-000000000099", pngBytes)

        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
