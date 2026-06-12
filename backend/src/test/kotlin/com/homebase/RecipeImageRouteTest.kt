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

    // `images` defaults to emptyList() and the JSON config omits default values, so an image-less
    // recipe has no `images` key at all — tolerate that here and treat it as an empty gallery.
    private fun imagesOf(bodyText: String): JsonArray =
        Json.parseToJsonElement(bodyText).jsonObject["images"]?.jsonArray ?: JsonArray(emptyList())

    private fun JsonElement.id() = jsonObject["id"]!!.jsonPrimitive.content

    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)

    @Test
    fun `upload returns the recipe with the embedded image`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val recipeId = createRecipe(token)

        val response = uploadImage(token, recipeId, pngBytes)

        assertEquals(HttpStatusCode.Created, response.status)
        val images = imagesOf(response.bodyAsText())
        assertEquals(1, images.size)
        val image = images[0].jsonObject
        assertEquals(recipeId, image["recipeId"]?.jsonPrimitive?.content)
        assertEquals("image/png", image["contentType"]?.jsonPrimitive?.content)
        assertEquals(pngBytes.size, image["sizeBytes"]?.jsonPrimitive?.int)
        assertEquals("pic.png", image["originalName"]?.jsonPrimitive?.content)
        assertEquals(0, image["sortOrder"]?.jsonPrimitive?.int)
    }

    @Test
    fun `uploaded image is served back with the original bytes`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val recipeId = createRecipe(token)
        val imageId = imagesOf(uploadImage(token, recipeId, pngBytes).bodyAsText())[0].id()

        val response = client.get("/api/v1/recipes/$recipeId/images/$imageId") { bearerAuth(token) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Image.PNG, response.contentType()?.withoutParameters())
        assertContentEquals(pngBytes, response.readRawBytes())
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
    }

    @Test
    fun `recipe list and detail embed uploaded images`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val recipeId = createRecipe(token)
        uploadImage(token, recipeId, pngBytes)

        val list = Json.parseToJsonElement(
            client.get("/api/v1/recipes") { bearerAuth(token) }.bodyAsText()
        ).jsonArray
        assertEquals(1, imagesOf(list[0].toString()).size)

        val detail = client.get("/api/v1/recipes/$recipeId") { bearerAuth(token) }.bodyAsText()
        assertEquals(1, imagesOf(detail).size)
    }

    @Test
    fun `set as main moves an image to the front`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val recipeId = createRecipe(token)
        val img1 = imagesOf(uploadImage(token, recipeId, pngBytes, filename = "a.png").bodyAsText()).last().id()
        val secondBody = uploadImage(token, recipeId, pngBytes, filename = "b.png").bodyAsText()
        val img2 = imagesOf(secondBody).last().id()
        // first upload is initially the main image
        assertEquals(img1, imagesOf(secondBody)[0].id())

        val response = client.put("/api/v1/recipes/$recipeId/images/$img2/main") { bearerAuth(token) }

        assertEquals(HttpStatusCode.OK, response.status)
        val images = imagesOf(response.bodyAsText())
        assertEquals(img2, images[0].id())
        assertEquals(0, images[0].jsonObject["sortOrder"]!!.jsonPrimitive.int)
        assertEquals(img1, images[1].id())
    }

    @Test
    fun `delete image removes it and renumbers the rest`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val recipeId = createRecipe(token)
        val img1 = imagesOf(uploadImage(token, recipeId, pngBytes, filename = "a.png").bodyAsText()).last().id()
        val img2 = imagesOf(uploadImage(token, recipeId, pngBytes, filename = "b.png").bodyAsText()).last().id()

        val response = client.delete("/api/v1/recipes/$recipeId/images/$img1") { bearerAuth(token) }

        assertEquals(HttpStatusCode.OK, response.status)
        val images = imagesOf(response.bodyAsText())
        assertEquals(1, images.size)
        assertEquals(img2, images[0].id())
        // the surviving image becomes the main one (dense sort_order)
        assertEquals(0, images[0].jsonObject["sortOrder"]!!.jsonPrimitive.int)
        // and the bytes of the removed image are gone
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v1/recipes/$recipeId/images/$img1") { bearerAuth(token) }.status,
        )
    }

    @Test
    fun `deleting the recipe also drops its images`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val recipeId = createRecipe(token)
        val imageId = imagesOf(uploadImage(token, recipeId, pngBytes).bodyAsText())[0].id()

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/recipes/$recipeId") { bearerAuth(token) }.status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v1/recipes/$recipeId/images/$imageId") { bearerAuth(token) }.status,
        )
    }

    @Test
    fun `both users can add and view recipe images (shared)`() = testApplication {
        configureTestApplication()
        val alice = loginAndGetToken("alice", "password123")
        val bob = loginAndGetToken("bob", "password456")
        val recipeId = createRecipe(alice)

        // bob (not the creator) may upload to a shared recipe …
        val imageId = imagesOf(uploadImage(bob, recipeId, pngBytes).bodyAsText())[0].id()
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
