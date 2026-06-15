package com.homebase

import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoteImageRouteTest {

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

    private suspend fun ApplicationTestBuilder.createNote(token: String, body: String): String =
        Json.parseToJsonElement(
            client.post("/api/v1/notes") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(body)
            }.bodyAsText()
        ).jsonObject["id"]!!.jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.uploadImage(
        token: String,
        noteId: String,
        bytes: ByteArray,
        contentType: String = "image/png",
        filename: String = "pic.png",
    ) = client.post("/api/v1/notes/$noteId/images") {
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

    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)

    @Test
    fun `upload returns the note with the embedded image`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val noteId = createNote(token, """{"title":"Mit Bild"}""")

        val response = uploadImage(token, noteId, pngBytes)

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val images = body["images"]!!.jsonArray
        assertEquals(1, images.size)
        val image = images[0].jsonObject
        assertEquals(noteId, image["noteId"]?.jsonPrimitive?.content)
        assertEquals("image/png", image["contentType"]?.jsonPrimitive?.content)
        assertEquals(pngBytes.size, image["sizeBytes"]?.jsonPrimitive?.int)
        assertEquals("pic.png", image["originalName"]?.jsonPrimitive?.content)
    }

    @Test
    fun `uploaded image is served back with the original bytes`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val noteId = createNote(token, """{"title":"Mit Bild"}""")
        val imageId = Json.parseToJsonElement(uploadImage(token, noteId, pngBytes).bodyAsText())
            .jsonObject["images"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        val response = client.get("/api/v1/notes/$noteId/images/$imageId") { bearerAuth(token) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Image.PNG, response.contentType()?.withoutParameters())
        assertContentEquals(pngBytes, response.readRawBytes())
        // bytes are served with the declared (un-sniffed) content type, so the browser must
        // not be allowed to MIME-sniff a crafted file into markup
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        // the original upload name is offered for download (inline, so Coil still renders in
        // place) — otherwise the browser saves it under a generic fallback name
        val disposition = response.headers[HttpHeaders.ContentDisposition]
        assertTrue(disposition?.startsWith("inline") == true, "expected inline disposition, got $disposition")
        assertTrue(disposition?.contains("filename=pic.png") == true, "missing original filename in $disposition")
    }

    @Test
    fun `served image disposition keeps a name with umlauts and never an attachment`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val noteId = createNote(token, """{"title":"Mit Bild"}""")
        val imageId = Json.parseToJsonElement(
            uploadImage(token, noteId, pngBytes, filename = "Sommerurlaub Österreich.png").bodyAsText()
        ).jsonObject["images"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        val response = client.get("/api/v1/notes/$noteId/images/$imageId") { bearerAuth(token) }

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
    fun `notes list embeds uploaded images`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val noteId = createNote(token, """{"title":"Mit Bild"}""")
        uploadImage(token, noteId, pngBytes)

        val list = Json.parseToJsonElement(
            client.get("/api/v1/notes") { bearerAuth(token) }.bodyAsText()
        ).jsonArray
        assertEquals(1, list.size)
        assertEquals(1, list[0].jsonObject["images"]!!.jsonArray.size)
    }

    @Test
    fun `delete image removes it from the note`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val noteId = createNote(token, """{"title":"Mit Bild"}""")
        val imageId = Json.parseToJsonElement(uploadImage(token, noteId, pngBytes).bodyAsText())
            .jsonObject["images"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        val response = client.delete("/api/v1/notes/$noteId/images/$imageId") { bearerAuth(token) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(Json.parseToJsonElement(response.bodyAsText()).jsonObject["images"]!!.jsonArray.isEmpty())
        // and the bytes are gone
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v1/notes/$noteId/images/$imageId") { bearerAuth(token) }.status,
        )
    }

    @Test
    fun `deleting the note also drops its images`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val noteId = createNote(token, """{"title":"Mit Bild"}""")
        val imageId = Json.parseToJsonElement(uploadImage(token, noteId, pngBytes).bodyAsText())
            .jsonObject["images"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/notes/$noteId") { bearerAuth(token) }.status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v1/notes/$noteId/images/$imageId") { bearerAuth(token) }.status,
        )
    }

    @Test
    fun `upload of an unsupported type returns 415`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val noteId = createNote(token, """{"title":"Mit Bild"}""")

        val response = uploadImage(token, noteId, pngBytes, contentType = "application/pdf", filename = "doc.pdf")

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    // test config caps uploads at 1 MB
    private val maxBytes = 1024 * 1024

    @Test
    fun `upload exceeding the size limit returns 413`() = testApplication {
        val uploadDir = configureTestApplication()
        val token = loginAndGetToken()
        val noteId = createNote(token, """{"title":"Mit Bild"}""")

        // one byte over the cap must be rejected — the boundary is enforced exactly
        val tooBig = ByteArray(maxBytes + 1) { 0x10 }
        val response = uploadImage(token, noteId, tooBig)

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        // the over-limit body is streamed to a temp file and aborted mid-stream; nothing must be
        // left behind on disk (proves the partial upload is discarded, not buffered then dropped)
        assertTrue(
            Files.list(uploadDir).use { it.toList() }.isEmpty(),
            "rejected oversized upload must not leave any file in the upload dir",
        )
    }

    @Test
    fun `upload exactly at the size limit succeeds`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val noteId = createNote(token, """{"title":"Mit Bild"}""")

        val atLimit = ByteArray(maxBytes) { 0x10 }
        val response = uploadImage(token, noteId, atLimit)

        assertEquals(HttpStatusCode.Created, response.status)
        val image = Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["images"]!!.jsonArray[0].jsonObject
        assertEquals(maxBytes, image["sizeBytes"]?.jsonPrimitive?.int)
    }

    @Test
    fun `other user cannot fetch an image of a private note`() = testApplication {
        configureTestApplication()
        val alice = loginAndGetToken("alice", "password123")
        val bob = loginAndGetToken("bob", "password456")
        val noteId = createNote(alice, """{"title":"Geheim","visibility":"PRIVATE"}""")
        val imageId = Json.parseToJsonElement(uploadImage(alice, noteId, pngBytes).bodyAsText())
            .jsonObject["images"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v1/notes/$noteId/images/$imageId") { bearerAuth(bob) }.status,
        )
        // bob also cannot upload to it
        assertEquals(HttpStatusCode.NotFound, uploadImage(bob, noteId, pngBytes).status)
    }

    @Test
    fun `upload to a missing note returns 404`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val response = uploadImage(token, "00000000-0000-0000-0000-000000000099", pngBytes)

        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
